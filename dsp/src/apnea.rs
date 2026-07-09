//! Apnea-screening DSP primitives (FR-1, FR-2 partial).
//!
//! Pure-Rust, allocation-free in the per-frame path (NFR-3): every tracker
//! owns fixed-size ring buffers sized by the constants in [`crate::apnea_config`].
//! The only heap use is `EventRing::drain`, which runs once per epoch poll,
//! not per frame.

use crate::apnea_config as cfg;

// ── Small helpers ──────────────────────────────────────────────────────────────

/// Linear amplitude → dBFS. The 1e-6 bias keeps log10 finite for silence
/// (floor of −120 dBFS).
pub fn db(lin: f32) -> f32 {
    20.0 * (lin + 1e-6).log10()
}

/// dBFS → linear amplitude.
pub fn db_to_lin(db: f32) -> f32 {
    10.0_f32.powf(db / 20.0)
}

// ── Acoustic events (FR-2.1) ───────────────────────────────────────────────────

/// Event type ordinals match the Kotlin `AcousticEventType` enum and the
/// stride-8 `pollEvents` FFI encoding.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EventType {
    ApneaLike = 0,
    HypopneaLike = 1,
    Gasp = 2,
    SnoreEpisode = 3,
}

/// One completed acoustic event, as drained by `pollEvents` (FR-3.5).
#[derive(Clone, Copy, Debug)]
pub struct AcousticEvent {
    pub event_type: EventType,
    /// Milliseconds since `startSession()` (frame index × 10 ms).
    pub start_offset_ms: u64,
    pub duration_ms: u64,
    /// 0–1, per FR-2.2.
    pub confidence: f32,
    /// Peak level of the event relative to the adaptive noise floor, dB.
    /// For decrement events this is the *pre-event breathing level* over the
    /// floor (the signal that disappeared).
    pub peak_db_over_floor: f32,
    /// Fraction 0–1 of envelope reduction vs. the pre-event breathing median
    /// (0 for GASP / SNORE_EPISODE events). Matches the Kotlin contract.
    pub envelope_reduction_pct: f32,
    pub terminated_by_gasp: bool,
    /// Mean level during the event relative to the noise floor, dB.
    pub mean_db_over_floor: f32,
}

impl AcousticEvent {
    const EMPTY: AcousticEvent = AcousticEvent {
        event_type: EventType::ApneaLike,
        start_offset_ms: 0,
        duration_ms: 0,
        confidence: 0.0,
        peak_db_over_floor: 0.0,
        envelope_reduction_pct: 0.0,
        terminated_by_gasp: false,
        mean_db_over_floor: 0.0,
    };
}

// ── Event ring buffer (FR-3.5) ─────────────────────────────────────────────────

/// Fixed-capacity append-only ring drained by `pollEvents`. Oldest event is
/// dropped on overflow. Push is allocation-free; `drain` allocates the
/// returned Vec (poll-time only, not per-frame).
pub struct EventRing {
    buf: [AcousticEvent; cfg::EVENT_RING_CAPACITY],
    head: usize,
    len: usize,
}

impl Default for EventRing {
    fn default() -> Self {
        Self { buf: [AcousticEvent::EMPTY; cfg::EVENT_RING_CAPACITY], head: 0, len: 0 }
    }
}

impl EventRing {
    pub fn push(&mut self, ev: AcousticEvent) {
        let cap = self.buf.len();
        if self.len == cap {
            // Drop oldest.
            self.head = (self.head + 1) % cap;
            self.len -= 1;
        }
        let tail = (self.head + self.len) % cap;
        self.buf[tail] = ev;
        self.len += 1;
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Removes and returns all pending events in FIFO order.
    pub fn drain(&mut self) -> Vec<AcousticEvent> {
        let cap = self.buf.len();
        let mut out = Vec::with_capacity(self.len);
        for i in 0..self.len {
            out.push(self.buf[(self.head + i) % cap]);
        }
        self.head = 0;
        self.len = 0;
        out
    }
}

// ── Respiratory envelope (FR-1.1) ──────────────────────────────────────────────

/// Cascaded first-order HP (100 Hz) + LP (2 kHz) isolating the respiratory
/// band, mirroring the style of `features::BandPassState`.
#[derive(Default)]
pub struct RespBandPass {
    hp_prev_in: f32,
    hp_prev_out: f32,
    lp_prev_out: f32,
}

impl RespBandPass {
    #[inline]
    fn process(&mut self, x: f32) -> f32 {
        const DT: f32 = 1.0 / cfg::SAMPLE_RATE_HZ;
        let rc_hp = 1.0 / (2.0 * std::f32::consts::PI * cfg::RESP_HP_HZ);
        let rc_lp = 1.0 / (2.0 * std::f32::consts::PI * cfg::RESP_LP_HZ);
        let alpha_hp = rc_hp / (rc_hp + DT);
        let alpha_lp = DT / (rc_lp + DT);

        let hp_out = alpha_hp * (self.hp_prev_out + x - self.hp_prev_in);
        self.hp_prev_in = x;
        self.hp_prev_out = hp_out;

        self.lp_prev_out = alpha_lp * hp_out + (1.0 - alpha_lp) * self.lp_prev_out;
        self.lp_prev_out
    }
}

/// Band-limited respiratory envelope: per-frame RMS of the 100–2000 Hz band,
/// smoothed by a ~300 ms EMA.
#[derive(Default)]
pub struct RespEnvelope {
    bp: RespBandPass,
    smoothed: f32,
}

impl RespEnvelope {
    /// Processes one 10 ms frame. Returns `(frame_rms, smoothed_envelope)` —
    /// the unsmoothed in-band frame RMS (for the noise floor) and the EMA
    /// envelope (for periodicity / decrement tracking).
    pub fn process_frame(&mut self, samples: &[i16]) -> (f32, f32) {
        if samples.is_empty() {
            return (0.0, self.smoothed);
        }
        let mut sum_sq = 0.0_f32;
        for &s in samples {
            let x = s as f32 / 32_768.0;
            let y = self.bp.process(x);
            sum_sq += y * y;
        }
        let frame_rms = (sum_sq / samples.len() as f32).sqrt();

        // EMA over frames: alpha = 1 - exp(-frame_dt / tau).
        let frame_dt_ms = 1000.0 / cfg::FRAMES_PER_SECOND as f32;
        let alpha = 1.0 - (-frame_dt_ms / cfg::RESP_SMOOTH_MS).exp();
        self.smoothed += alpha * (frame_rms - self.smoothed);
        (frame_rms, self.smoothed)
    }

    pub fn smoothed(&self) -> f32 {
        self.smoothed
    }
}

// ── 256-point real FFT: spectral flatness + centroid (FR-1.1) ──────────────────

/// Radix-2 iterative Cooley–Tukey FFT with pre-computed twiddle and
/// bit-reversal tables. All buffers are owned — `compute_i16` performs no
/// heap allocation (NFR-3). 160-sample frames are zero-padded to 256.
pub struct Fft256 {
    re: [f32; cfg::FFT_SIZE],
    im: [f32; cfg::FFT_SIZE],
    /// Power spectrum bins 0..=N/2 (DC..Nyquist).
    power: [f32; cfg::FFT_SIZE / 2 + 1],
    bit_rev: [u16; cfg::FFT_SIZE],
    twiddle_re: [f32; cfg::FFT_SIZE / 2],
    twiddle_im: [f32; cfg::FFT_SIZE / 2],
}

impl Default for Fft256 {
    fn default() -> Self {
        Self::new()
    }
}

impl Fft256 {
    pub fn new() -> Self {
        const N: usize = cfg::FFT_SIZE;
        let bits = N.trailing_zeros();
        let mut bit_rev = [0u16; N];
        for (i, slot) in bit_rev.iter_mut().enumerate() {
            let mut x = i;
            let mut rev = 0usize;
            for _ in 0..bits {
                rev = (rev << 1) | (x & 1);
                x >>= 1;
            }
            *slot = rev as u16;
        }
        let mut twiddle_re = [0.0f32; N / 2];
        let mut twiddle_im = [0.0f32; N / 2];
        for k in 0..N / 2 {
            let angle = -2.0 * std::f32::consts::PI * k as f32 / N as f32;
            twiddle_re[k] = angle.cos();
            twiddle_im[k] = angle.sin();
        }
        Self {
            re: [0.0; N],
            im: [0.0; N],
            power: [0.0; N / 2 + 1],
            bit_rev,
            twiddle_re,
            twiddle_im,
        }
    }

    /// Zero-pads `samples` (≤ 256) to 256, runs the FFT in place, and fills
    /// the power spectrum.
    pub fn compute_i16(&mut self, samples: &[i16]) {
        const N: usize = cfg::FFT_SIZE;
        let n_in = samples.len().min(N);
        for (i, slot) in self.re.iter_mut().enumerate() {
            *slot = if i < n_in { samples[i] as f32 / 32_768.0 } else { 0.0 };
        }
        self.im.fill(0.0);

        // Bit-reversal permutation.
        for i in 0..N {
            let j = self.bit_rev[i] as usize;
            if i < j {
                self.re.swap(i, j);
                self.im.swap(i, j);
            }
        }

        // Butterflies.
        let mut len = 2usize;
        while len <= N {
            let half = len / 2;
            let step = N / len;
            let mut base = 0usize;
            while base < N {
                for k in 0..half {
                    let t_re = self.twiddle_re[k * step];
                    let t_im = self.twiddle_im[k * step];
                    let a = base + k;
                    let b = base + k + half;
                    let v_re = self.re[b] * t_re - self.im[b] * t_im;
                    let v_im = self.re[b] * t_im + self.im[b] * t_re;
                    self.re[b] = self.re[a] - v_re;
                    self.im[b] = self.im[a] - v_im;
                    self.re[a] += v_re;
                    self.im[a] += v_im;
                }
                base += len;
            }
            len *= 2;
        }

        for (i, p) in self.power.iter_mut().enumerate() {
            *p = self.re[i] * self.re[i] + self.im[i] * self.im[i];
        }
    }

    /// Spectral flatness = geometric mean / arithmetic mean of the power
    /// spectrum, DC excluded. 1.0 = perfectly flat (white), → 0 for tonal.
    pub fn spectral_flatness(&self) -> f32 {
        let bins = &self.power[1..];
        let n = bins.len() as f32;
        let mut log_sum = 0.0f32;
        let mut sum = 0.0f32;
        for &p in bins {
            let p = p.max(1e-20);
            log_sum += p.ln();
            sum += p;
        }
        let arith = sum / n;
        if arith <= 1e-20 {
            return 0.0;
        }
        ((log_sum / n).exp() / arith).clamp(0.0, 1.0)
    }

    /// Power-weighted mean frequency in Hz, DC excluded.
    pub fn spectral_centroid_hz(&self, sample_rate: f32) -> f32 {
        let mut num = 0.0f32;
        let mut den = 0.0f32;
        for (i, &p) in self.power.iter().enumerate().skip(1) {
            num += (i as f32 * sample_rate / cfg::FFT_SIZE as f32) * p;
            den += p;
        }
        if den <= 1e-20 {
            0.0
        } else {
            num / den
        }
    }
}

// ── Adaptive noise floor (FR-1.2) ──────────────────────────────────────────────

/// Percentile tracker of frame dB levels over a 60 s sliding window.
/// Frames are bucketed to 100 ms minima (600-entry ring) to bound memory;
/// the cached floor is recomputed once per bucket close (10 Hz), never in
/// the per-frame path.
pub struct NoiseFloor {
    buckets: [f32; cfg::NOISE_FLOOR_BUCKETS],
    head: usize,
    filled: usize,
    bucket_min: f32,
    frames_in_bucket: u64,
    cached_floor_db: f32,
}

impl Default for NoiseFloor {
    fn default() -> Self {
        Self {
            buckets: [0.0; cfg::NOISE_FLOOR_BUCKETS],
            head: 0,
            filled: 0,
            bucket_min: f32::INFINITY,
            frames_in_bucket: 0,
            cached_floor_db: cfg::NOISE_FLOOR_DEFAULT_DB,
        }
    }
}

impl NoiseFloor {
    /// Feed one frame level in dBFS.
    pub fn update(&mut self, frame_db: f32) {
        self.bucket_min = self.bucket_min.min(frame_db);
        self.frames_in_bucket += 1;
        if self.frames_in_bucket >= cfg::NOISE_BUCKET_FRAMES {
            self.buckets[self.head] = self.bucket_min;
            self.head = (self.head + 1) % cfg::NOISE_FLOOR_BUCKETS;
            self.filled = (self.filled + 1).min(cfg::NOISE_FLOOR_BUCKETS);
            self.bucket_min = f32::INFINITY;
            self.frames_in_bucket = 0;
            self.recompute();
        }
    }

    fn recompute(&mut self) {
        let n = self.filled;
        if n == 0 {
            self.cached_floor_db = cfg::NOISE_FLOOR_DEFAULT_DB;
            return;
        }
        let mut vals = [0.0f32; cfg::NOISE_FLOOR_BUCKETS];
        // Ring order does not matter for a percentile; slots 0..filled are
        // always the valid ones (head wraps only once the ring is full).
        vals[..n].copy_from_slice(&self.buckets[..n]);
        let slice = &mut vals[..n];
        slice.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let idx = ((n as f32 * cfg::NOISE_FLOOR_PERCENTILE) as usize).min(n - 1);
        self.cached_floor_db = slice[idx];
    }

    /// Current floor estimate in dBFS.
    pub fn floor_db(&self) -> f32 {
        self.cached_floor_db
    }
}

// ── Breathing periodicity tracker (FR-1.3) ─────────────────────────────────────

/// Result of one periodicity evaluation.
#[derive(Clone, Copy, Debug, Default)]
pub struct Periodicity {
    pub present: bool,
    pub period_s: f32,
    /// Normalised autocorrelation peak, 0–1.
    pub confidence: f32,
}

/// Normalised autocorrelation of the 20 Hz-downsampled respiratory envelope
/// over a 45 s ring, scanning lags of 2–8 s. `compute` is amortised — the
/// engine calls it once per second, not per frame.
pub struct PeriodicityTracker {
    ring: [f32; cfg::PERIODICITY_RING_SAMPLES],
    head: usize,
    filled: usize,
}

impl Default for PeriodicityTracker {
    fn default() -> Self {
        Self { ring: [0.0; cfg::PERIODICITY_RING_SAMPLES], head: 0, filled: 0 }
    }
}

impl PeriodicityTracker {
    /// Push one downsampled envelope sample (called every 50 ms).
    pub fn push(&mut self, env: f32) {
        self.ring[self.head] = env;
        self.head = (self.head + 1) % cfg::PERIODICITY_RING_SAMPLES;
        self.filled = (self.filled + 1).min(cfg::PERIODICITY_RING_SAMPLES);
    }

    /// Mean-removed normalised autocorrelation over lags 2–8 s.
    pub fn compute(&self) -> Periodicity {
        let n = self.filled;
        if n < cfg::PERIODICITY_MIN_SAMPLES {
            return Periodicity::default();
        }

        // Copy ring oldest→newest into a scratch buffer (stack, ~3.6 KB).
        let mut buf = [0.0f32; cfg::PERIODICITY_RING_SAMPLES];
        let cap = cfg::PERIODICITY_RING_SAMPLES;
        let start = (self.head + cap - n) % cap;
        for (i, slot) in buf[..n].iter_mut().enumerate() {
            *slot = self.ring[(start + i) % cap];
        }

        // Remove mean so the DC level does not fake correlation.
        let mean = buf[..n].iter().sum::<f32>() / n as f32;
        for v in buf[..n].iter_mut() {
            *v -= mean;
        }

        let r0 = buf[..n].iter().map(|x| x * x).sum::<f32>() / n as f32;
        if r0 < 1e-16 {
            return Periodicity::default();
        }

        let lag_min = (cfg::BREATH_PERIOD_MIN_S * cfg::PERIODICITY_DOWNSAMPLE_HZ) as usize;
        let lag_max = (cfg::BREATH_PERIOD_MAX_S * cfg::PERIODICITY_DOWNSAMPLE_HZ) as usize;
        let mut best_corr = f32::MIN;
        let mut best_lag = lag_min;
        for lag in lag_min..=lag_max {
            if lag + cfg::PERIODICITY_MIN_SAMPLES / 2 > n {
                break;
            }
            let m = n - lag;
            let r: f32 = buf[..m]
                .iter()
                .zip(buf[lag..lag + m].iter())
                .map(|(a, b)| a * b)
                .sum::<f32>()
                / m as f32;
            let norm = r / r0;
            if norm > best_corr {
                best_corr = norm;
                best_lag = lag;
            }
        }

        let confidence = best_corr.clamp(0.0, 1.0);
        Periodicity {
            present: confidence > cfg::PERIODICITY_CONFIDENCE_THRESHOLD,
            period_s: best_lag as f32 / cfg::PERIODICITY_DOWNSAMPLE_HZ,
            confidence,
        }
    }
}

// ── Trailing breathing median (reference level for decrements) ─────────────────

/// Median of the smoothed respiratory envelope over the trailing 30 s.
/// The sort runs in `recompute` (called once per second by the engine),
/// never in the per-frame path.
pub struct BreathingMedianTracker {
    ring: [f32; cfg::BREATHING_MEDIAN_FRAMES],
    head: usize,
    filled: usize,
    cached_median: f32,
}

impl Default for BreathingMedianTracker {
    fn default() -> Self {
        Self { ring: [0.0; cfg::BREATHING_MEDIAN_FRAMES], head: 0, filled: 0, cached_median: 0.0 }
    }
}

impl BreathingMedianTracker {
    /// Push one per-frame smoothed envelope value (linear).
    pub fn push(&mut self, env: f32) {
        self.ring[self.head] = env;
        self.head = (self.head + 1) % cfg::BREATHING_MEDIAN_FRAMES;
        self.filled = (self.filled + 1).min(cfg::BREATHING_MEDIAN_FRAMES);
    }

    /// Re-sorts the window and refreshes the cached median (~12 KB stack).
    pub fn recompute(&mut self) {
        let n = self.filled;
        if n == 0 {
            self.cached_median = 0.0;
            return;
        }
        let mut vals = [0.0f32; cfg::BREATHING_MEDIAN_FRAMES];
        vals[..n].copy_from_slice(&self.ring[..n.min(cfg::BREATHING_MEDIAN_FRAMES)]);
        let slice = &mut vals[..n];
        slice.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        self.cached_median = slice[n / 2];
    }

    /// Last cached median (linear).
    pub fn median(&self) -> f32 {
        self.cached_median
    }
}

// ── Gasp condition (FR-1.5) ────────────────────────────────────────────────────

/// True when a frame qualifies as a resumption gasp: full-band RMS at least
/// [`cfg::GASP_DB_OFFSET`] dB over the trailing 30 s median envelope, with
/// broadband character (spectral flatness above threshold).
pub fn is_gasp(full_rms_db: f32, median_env_db: f32, spectral_flatness: f32) -> bool {
    full_rms_db > median_env_db + cfg::GASP_DB_OFFSET
        && spectral_flatness > cfg::GASP_FLATNESS_THRESHOLD
}

// ── Snore episode tracker (FR-1.8) ─────────────────────────────────────────────

/// Upgrades the per-frame snore flag into contiguous SNORE_EPISODE events
/// with start, duration, and mean/peak band power over the noise floor.
/// Short gaps (< 500 ms) do not split an episode.
#[derive(Default)]
pub struct SnoreEpisodeTracker {
    active: bool,
    start_frame: u64,
    last_snore_frame: u64,
    snore_frames: u64,
    band_db_sum: f64,
    band_db_peak: f32,
}

impl SnoreEpisodeTracker {
    /// Feed one frame. `band_db_over_floor` = 20–300 Hz band RMS in dB minus
    /// the current noise floor. Returns a completed episode, if any.
    pub fn update(
        &mut self,
        frame_idx: u64,
        is_snore: bool,
        band_db_over_floor: f32,
    ) -> Option<AcousticEvent> {
        if is_snore {
            if !self.active {
                self.active = true;
                self.start_frame = frame_idx;
                self.snore_frames = 0;
                self.band_db_sum = 0.0;
                self.band_db_peak = f32::MIN;
            }
            self.last_snore_frame = frame_idx;
            self.snore_frames += 1;
            self.band_db_sum += band_db_over_floor as f64;
            self.band_db_peak = self.band_db_peak.max(band_db_over_floor);
            return None;
        }
        if self.active && frame_idx.saturating_sub(self.last_snore_frame) > cfg::SNORE_GAP_CLOSE_FRAMES
        {
            self.active = false;
            if self.snore_frames >= cfg::SNORE_MIN_EPISODE_FRAMES {
                let mean_db = (self.band_db_sum / self.snore_frames as f64) as f32;
                let frame_ms = 1000 / cfg::FRAMES_PER_SECOND;
                return Some(AcousticEvent {
                    event_type: EventType::SnoreEpisode,
                    start_offset_ms: self.start_frame * frame_ms,
                    duration_ms: (self.last_snore_frame - self.start_frame + 1) * frame_ms,
                    // Louder snoring over the floor → higher confidence.
                    confidence: (0.3 + mean_db / 30.0).clamp(0.0, 1.0),
                    peak_db_over_floor: self.band_db_peak,
                    envelope_reduction_pct: 0.0,
                    terminated_by_gasp: false,
                    mean_db_over_floor: mean_db,
                });
            }
        }
        None
    }
}

// ── Event state machine (FR-1.4) ───────────────────────────────────────────────

/// States per FR-1.4. `GaspWindow` is the post-DECREMENT interval in which a
/// resumption gasp is attributed to the just-ended event.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MachineState {
    NoSignal,
    Breathing,
    Snoring,
    Decrement,
    GaspWindow,
}

/// Per-frame feature snapshot consumed by the state machine. Plain scalars so
/// the transition table is unit-testable without audio (T-1).
#[derive(Clone, Copy, Debug)]
pub struct MachineInput {
    pub frame_idx: u64,
    /// Smoothed respiratory envelope, linear.
    pub env_lin: f32,
    /// Smoothed respiratory envelope, dBFS.
    pub env_db: f32,
    /// Adaptive noise floor, dBFS.
    pub floor_db: f32,
    /// Trailing 30 s breathing median, linear / dBFS.
    pub median_lin: f32,
    pub median_db: f32,
    /// Periodicity tracker outputs (cached, 1 Hz refresh).
    pub breathing_present: bool,
    pub periodicity_conf: f32,
    /// Full-band frame RMS in dBFS (gasp check).
    pub full_rms_db: f32,
    /// Spectral flatness of the last FFT frame (gasp check).
    pub flatness: f32,
    /// Per-frame snore flag.
    pub snore_frame: bool,
}

/// Continuous event state machine. Emits APNEA_LIKE / HYPOPNEA_LIKE events
/// for qualifying decrements bounded by confirmed respiratory sound, and
/// GASP events for resumption bursts (FR-1.4, FR-1.5, FR-2.2).
pub struct EventStateMachine {
    state: MachineState,
    /// Consecutive breathing-present frames while in NO_SIGNAL.
    breathing_streak: u64,
    // Decrement bookkeeping (valid while in Decrement / GaspWindow).
    decrement_start: u64,
    baseline_lin: f32,
    baseline_db: f32,
    baseline_floor_db: f32,
    pre_conf: f32,
    min_env_lin: f32,
    env_db_sum: f64,
    env_frames: u64,
    // Gasp-window bookkeeping.
    pending: Option<AcousticEvent>,
    gasp_deadline: u64,
    gasp_active: bool,
    gasp_start: u64,
    gasp_peak_over_floor: f32,
    gasp_db_sum: f64,
    gasp_frames: u64,
}

impl Default for EventStateMachine {
    fn default() -> Self {
        Self {
            state: MachineState::NoSignal,
            breathing_streak: 0,
            decrement_start: 0,
            baseline_lin: 0.0,
            baseline_db: 0.0,
            baseline_floor_db: 0.0,
            pre_conf: 0.0,
            min_env_lin: 0.0,
            env_db_sum: 0.0,
            env_frames: 0,
            pending: None,
            gasp_deadline: 0,
            gasp_active: false,
            gasp_start: 0,
            gasp_peak_over_floor: 0.0,
            gasp_db_sum: 0.0,
            gasp_frames: 0,
        }
    }
}

impl EventStateMachine {
    pub fn state(&self) -> MachineState {
        self.state
    }

    /// Advance one frame. Completed events are pushed into `ring`.
    pub fn update(&mut self, input: &MachineInput, ring: &mut EventRing) {
        match self.state {
            MachineState::NoSignal => self.tick_no_signal(input),
            MachineState::Breathing | MachineState::Snoring => self.tick_breathing(input),
            MachineState::Decrement => self.tick_decrement(input, ring),
            MachineState::GaspWindow => self.tick_gasp_window(input, ring),
        }
    }

    fn tick_no_signal(&mut self, input: &MachineInput) {
        if input.breathing_present {
            self.breathing_streak += 1;
        } else {
            self.breathing_streak = 0;
        }
        let confirm_frames = (cfg::BREATHING_CONFIRM_S * cfg::FRAMES_PER_SECOND as f32) as u64;
        if self.breathing_streak >= confirm_frames {
            self.state = MachineState::Breathing;
        }
    }

    fn tick_breathing(&mut self, input: &MachineInput) {
        // Snoring is a sub-mode of confirmed breathing.
        self.state = if input.snore_frame { MachineState::Snoring } else { MachineState::Breathing };

        // Decrement entry (FR-1.4): envelope below
        // max(noise floor + offset, (1 − entry_reduction) × breathing median),
        // with a meaningful breathing reference above the floor.
        if input.median_db <= input.floor_db + cfg::DECREMENT_FLOOR_OFFSET_DB {
            return; // Breathing reference indistinguishable from room noise.
        }
        let entry_thr = db_to_lin(input.floor_db + cfg::DECREMENT_FLOOR_OFFSET_DB)
            .max((1.0 - cfg::DECREMENT_ENTRY_REDUCTION) * input.median_lin);
        if input.env_lin < entry_thr {
            self.state = MachineState::Decrement;
            self.decrement_start = input.frame_idx;
            self.baseline_lin = input.median_lin;
            self.baseline_db = input.median_db;
            self.baseline_floor_db = input.floor_db;
            self.pre_conf = input.periodicity_conf;
            self.min_env_lin = input.env_lin;
            self.env_db_sum = 0.0;
            self.env_frames = 0;
        }
    }

    fn tick_decrement(&mut self, input: &MachineInput, _ring: &mut EventRing) {
        self.min_env_lin = self.min_env_lin.min(input.env_lin);
        self.env_db_sum += input.env_db as f64;
        self.env_frames += 1;
        let duration = input.frame_idx - self.decrement_start;
        let max_frames = (cfg::MAX_EVENT_DURATION_S * cfg::FRAMES_PER_SECOND as f32) as u64;

        if duration > max_frames {
            // Sanity cap: not an obstructive event — signal was lost.
            self.state = MachineState::NoSignal;
            self.breathing_streak = 0;
            return;
        }

        // Recovery: envelope back above the hysteresis fraction of the
        // frozen pre-decrement baseline.
        if input.env_lin > cfg::DECREMENT_EXIT_FRACTION * self.baseline_lin {
            let min_frames = (cfg::MIN_EVENT_DURATION_S * cfg::FRAMES_PER_SECOND as f32) as u64;
            if duration >= min_frames {
                let reduction = (1.0 - self.min_env_lin / self.baseline_lin.max(1e-9)).clamp(0.0, 1.0);
                if reduction >= cfg::HYPOPNEA_REDUCTION_LOW {
                    let event_type = if reduction > cfg::HYPOPNEA_REDUCTION_HIGH {
                        EventType::ApneaLike
                    } else {
                        EventType::HypopneaLike
                    };
                    let confidence = self.decrement_confidence(input.periodicity_conf);
                    let frame_ms = 1000 / cfg::FRAMES_PER_SECOND;
                    let mean_env_db =
                        if self.env_frames > 0 { (self.env_db_sum / self.env_frames as f64) as f32 } else { input.env_db };
                    self.pending = Some(AcousticEvent {
                        event_type,
                        start_offset_ms: self.decrement_start * frame_ms,
                        duration_ms: duration * frame_ms,
                        confidence,
                        // Pre-event breathing level over floor: the signal
                        // that disappeared during the event.
                        peak_db_over_floor: self.baseline_db - self.baseline_floor_db,
                        envelope_reduction_pct: reduction,
                        terminated_by_gasp: false,
                        mean_db_over_floor: mean_env_db - self.baseline_floor_db,
                    });
                    let window = (cfg::GASP_WINDOW_AFTER_S * cfg::FRAMES_PER_SECOND as f32) as u64;
                    self.gasp_deadline = input.frame_idx + window;
                    self.gasp_active = false;
                    self.state = MachineState::GaspWindow;
                    return;
                }
            }
            // Too short or too shallow: silently resume breathing.
            self.state = MachineState::Breathing;
        }
    }

    fn tick_gasp_window(&mut self, input: &MachineInput, ring: &mut EventRing) {
        // Reference the frozen pre-decrement breathing level, not the live
        // 30 s median: right after a long apnea the live median is depressed
        // by the silent gap, which would misread ordinary breathing
        // resumption as a "+12 dB burst".
        let reference_db = self.baseline_db.max(input.median_db);
        let gasping = is_gasp(input.full_rms_db, reference_db, input.flatness);
        if gasping {
            if !self.gasp_active {
                self.gasp_active = true;
                self.gasp_start = input.frame_idx;
                self.gasp_peak_over_floor = f32::MIN;
                self.gasp_db_sum = 0.0;
                self.gasp_frames = 0;
                if let Some(p) = self.pending.as_mut() {
                    if !p.terminated_by_gasp {
                        p.terminated_by_gasp = true;
                        p.confidence = (p.confidence + cfg::GASP_CONFIDENCE_BONUS).min(1.0);
                    }
                }
            }
            self.gasp_peak_over_floor = self.gasp_peak_over_floor.max(input.full_rms_db - input.floor_db);
            self.gasp_db_sum += (input.full_rms_db - input.floor_db) as f64;
            self.gasp_frames += 1;
        }

        let gasp_run_ended = self.gasp_active && !gasping;
        let expired = input.frame_idx >= self.gasp_deadline;
        if gasp_run_ended || expired {
            if let Some(p) = self.pending.take() {
                ring.push(p);
            }
            if self.gasp_active {
                let frame_ms = 1000 / cfg::FRAMES_PER_SECOND;
                let frames = self.gasp_frames.max(1);
                ring.push(AcousticEvent {
                    event_type: EventType::Gasp,
                    start_offset_ms: self.gasp_start * frame_ms,
                    duration_ms: frames * frame_ms,
                    // Broadband character dominates gasp confidence.
                    confidence: (0.4 + input.flatness).clamp(0.0, 1.0),
                    peak_db_over_floor: self.gasp_peak_over_floor,
                    envelope_reduction_pct: 0.0,
                    terminated_by_gasp: false,
                    mean_db_over_floor: (self.gasp_db_sum / frames as f64) as f32,
                });
                self.gasp_active = false;
            }
            self.state = MachineState::Breathing;
        }
    }

    /// Confidence per FR-2.2: periodicity before/after, decrement depth, and
    /// ambient-noise margin. A terminal gasp adds a bonus separately.
    fn decrement_confidence(&self, post_conf: f32) -> f32 {
        let depth_db = self.baseline_db - db(self.min_env_lin);
        let depth_factor = (depth_db / cfg::DEPTH_CONFIDENCE_SPAN_DB).clamp(0.0, 1.0);
        let margin_db = self.baseline_db - self.baseline_floor_db;
        let margin_factor =
            ((margin_db - cfg::LOW_MARGIN_DB) / cfg::MARGIN_CONFIDENCE_SPAN_DB).clamp(0.0, 1.0);
        (0.35 * self.pre_conf.clamp(0.0, 1.0)
            + 0.25 * post_conf.clamp(0.0, 1.0)
            + 0.20 * depth_factor
            + 0.20 * margin_factor)
            .clamp(0.0, 1.0)
    }
}
