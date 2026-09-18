//! Synthetic-night generator (T-2) — generator CODE only, no audio files.
//!
//! Composes breathing cycles (amplitude-modulated band-limited noise),
//! snore bursts (low-frequency-weighted noise), apnea gaps of configurable
//! duration with optional terminal gasps, and white ambient noise at a
//! configurable level. Fully deterministic via a seeded xorshift PRNG.

#![allow(dead_code)] // Each integration-test binary uses a subset.

pub const SAMPLE_RATE: f32 = 16_000.0;
pub const FRAME_LEN: usize = 160;

// ── Deterministic PRNG ─────────────────────────────────────────────────────────

/// Hand-rolled xorshift32 — deterministic across platforms, no dependencies.
pub struct Xorshift32(u32);

impl Xorshift32 {
    pub fn new(seed: u32) -> Self {
        Self(seed.max(1))
    }

    pub fn next_u32(&mut self) -> u32 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 17;
        self.0 ^= self.0 << 5;
        self.0
    }

    /// Uniform in [0, 1).
    pub fn next_f32(&mut self) -> f32 {
        (self.next_u32() >> 8) as f32 / (1u32 << 24) as f32
    }

    /// Standard normal via Box–Muller.
    pub fn next_gaussian(&mut self) -> f32 {
        let u1 = self.next_f32().max(1e-9);
        let u2 = self.next_f32();
        (-2.0 * u1.ln()).sqrt() * (2.0 * std::f32::consts::PI * u2).cos()
    }
}

// ── Night scenario description ─────────────────────────────────────────────────

/// One ground-truth apnea gap: breathing fully stops for `duration_s`,
/// optionally terminated by a broadband gasp burst.
#[derive(Clone, Copy, Debug)]
pub struct ApneaGap {
    pub start_s: f32,
    pub duration_s: f32,
    pub with_gasp: bool,
}

/// Full synthetic-night configuration.
#[derive(Clone, Debug)]
pub struct NightConfig {
    /// Breathing cycle period in seconds (e.g. 4.0 = 15 breaths/min).
    pub breathing_period_s: f32,
    /// Peak amplitude of the breathing noise component (linear, 0–1).
    pub breathing_amp: f32,
    /// RMS amplitude of the white ambient-noise component (linear).
    pub noise_amp: f32,
    /// Amplitude of gasp bursts (linear); gasps are broadband white noise.
    pub gasp_amp: f32,
    /// Ground-truth apnea gaps.
    pub apnea_gaps: Vec<ApneaGap>,
    /// Snoring intervals as (start_s, end_s); snores pulse with breathing.
    pub snore_intervals: Vec<(f32, f32)>,
    /// Amplitude of the snore component (linear).
    pub snore_amp: f32,
    /// Speech intervals as (start_s, end_s): band-limited (300–3000 Hz)
    /// noise with bursty syllabic-rate gating — a synthetic podcast (A-4).
    pub speech_intervals: Vec<(f32, f32)>,
    /// REM intervals as (start_s, end_s): breathing continues at the same
    /// amplitude but each breath cycle draws a fresh period ~ U(2.0, 7.0) s
    /// (irregular spacing, no movement bursts) — synthetic REM (A-1). The
    /// endpoints span rapid-shallow to slow breathing, both seen in REM;
    /// ground-truth interval CV ≈ 0.32 vs ≈ 0.0 for steady breathing.
    pub rem_intervals: Vec<(f32, f32)>,
    /// Peak amplitude of the speech component (linear).
    pub speech_amp: f32,
    /// Total night length in seconds.
    pub total_s: f32,
    /// PRNG seed.
    pub seed: u32,
}

impl NightConfig {
    /// Quiet-room baseline: 4 s breathing period, high SNR, no events.
    pub fn baseline(seed: u32) -> Self {
        Self {
            breathing_period_s: 4.0,
            breathing_amp: 0.05,
            noise_amp: 0.002,
            gasp_amp: 0.3,
            apnea_gaps: Vec::new(),
            snore_intervals: Vec::new(),
            snore_amp: 0.15,
            speech_intervals: Vec::new(),
            rem_intervals: Vec::new(),
            speech_amp: 0.12,
            total_s: 300.0,
            seed,
        }
    }

    pub fn total_frames(&self) -> u64 {
        (self.total_s * 100.0) as u64
    }
}

// ── Sample-level generator ─────────────────────────────────────────────────────

/// Duration of a terminal gasp burst, seconds.
const GASP_BURST_S: f32 = 0.4;

/// Streaming generator: call `next_frame` with consecutive frame indices.
/// Filter state persists across frames so the audio is continuous.
pub struct NightGenerator {
    cfg: NightConfig,
    rng: Xorshift32,
    // Breathing band-limiter: band ≈ 250–1200 Hz (one-pole HP + one-pole LP)
    // so breathing energy sits inside the 100–2000 Hz respiratory band but
    // mostly OUTSIDE the 20–300 Hz snore band.
    breath_lp_slow: f32, // one-pole LP @ 250 Hz (subtracted → HP)
    breath_lp_fast: f32, // one-pole LP @ 1200 Hz
    // Snore band-limiter: heavy one-pole LP @ 150 Hz (in the snore band).
    snore_lp: f32,
    // Speech band-limiter: band ≈ 300–3000 Hz (one-pole HP + one-pole LP).
    speech_lp_slow: f32, // one-pole LP @ 300 Hz (subtracted → HP)
    speech_lp_fast: f32, // one-pole LP @ 3000 Hz
    // Breathing phase accumulator (cycles): integrates DT / period so each
    // cycle can use its own period inside REM intervals (A-1). Identical to
    // absolute-time phase outside REM (starts at 0, constant period).
    breath_phase: f32,
    current_period_s: f32,
}

impl NightGenerator {
    pub fn new(cfg: NightConfig) -> Self {
        let seed = cfg.seed;
        let period = cfg.breathing_period_s;
        Self {
            cfg,
            rng: Xorshift32::new(seed),
            breath_lp_slow: 0.0,
            breath_lp_fast: 0.0,
            snore_lp: 0.0,
            speech_lp_slow: 0.0,
            speech_lp_fast: 0.0,
            breath_phase: 0.0,
            current_period_s: period,
        }
    }

    fn in_gap(&self, t: f32) -> bool {
        self.cfg
            .apnea_gaps
            .iter()
            .any(|g| t >= g.start_s && t < g.start_s + g.duration_s)
    }

    fn in_gasp(&self, t: f32) -> bool {
        self.cfg
            .apnea_gaps
            .iter()
            .any(|g| g.with_gasp && t >= g.start_s + g.duration_s && t < g.start_s + g.duration_s + GASP_BURST_S)
    }

    fn in_snore(&self, t: f32) -> bool {
        self.cfg.snore_intervals.iter().any(|&(s, e)| t >= s && t < e)
    }

    fn in_speech(&self, t: f32) -> bool {
        self.cfg.speech_intervals.iter().any(|&(s, e)| t >= s && t < e)
    }

    fn in_rem(&self, t: f32) -> bool {
        self.cfg.rem_intervals.iter().any(|&(s, e)| t >= s && t < e)
    }

    /// Generate one 10 ms frame (160 samples) for the given frame index.
    pub fn next_frame(&mut self, frame_idx: u64) -> [i16; FRAME_LEN] {
        const DT: f32 = 1.0 / SAMPLE_RATE;
        let alpha_slow = one_pole_alpha(250.0);
        let alpha_fast = one_pole_alpha(1200.0);
        let alpha_snore = one_pole_alpha(150.0);
        let alpha_speech_hp = one_pole_alpha(300.0);
        let alpha_speech_lp = one_pole_alpha(3000.0);
        // Variance loss of a one-pole LP on white noise ≈ α/(2−α);
        // compensate so component amplitudes are roughly what's configured.
        let breath_gain = ((2.0 - alpha_fast) / alpha_fast).sqrt();
        let snore_gain = ((2.0 - alpha_snore) / alpha_snore).sqrt();
        let speech_gain = ((2.0 - alpha_speech_lp) / alpha_speech_lp).sqrt();

        let mut out = [0i16; FRAME_LEN];
        for (k, slot) in out.iter_mut().enumerate() {
            let t = frame_idx as f32 * 0.010 + k as f32 * DT;

            // Breathing amplitude modulation: raised sinusoid of the phase
            // accumulator, zero in gaps. At each cycle boundary inside a REM
            // interval a fresh period ~ U(2.5, 6.5) s is drawn (irregular
            // spacing, A-1); elsewhere the configured period holds, which
            // reproduces the old absolute-time phase exactly. Phase freezes
            // during apnea gaps so post-gap alignment is preserved.
            let in_gap = self.in_gap(t);
            if !in_gap {
                if self.breath_phase >= 1.0 {
                    self.breath_phase -= 1.0;
                    self.current_period_s = if self.in_rem(t) {
                        2.0 + 5.0 * self.rng.next_f32()
                    } else {
                        self.cfg.breathing_period_s
                    };
                }
                self.breath_phase += DT / self.current_period_s;
            }
            let breath_env = if in_gap {
                0.0
            } else {
                0.5 * (1.0 + (2.0 * std::f32::consts::PI * self.breath_phase).sin())
            };

            // Band-limited breathing noise (≈ 250–1200 Hz).
            let white = self.rng.next_gaussian();
            self.breath_lp_slow += alpha_slow * (white - self.breath_lp_slow);
            let hp = white - self.breath_lp_slow;
            self.breath_lp_fast += alpha_fast * (hp - self.breath_lp_fast);
            let mut s = self.cfg.breathing_amp * breath_env * self.breath_lp_fast * breath_gain;

            // Snore component: low-frequency-weighted, pulsing with breathing.
            if self.in_snore(t) && breath_env > 0.5 {
                let w = self.rng.next_gaussian();
                self.snore_lp += alpha_snore * (w - self.snore_lp);
                s += self.cfg.snore_amp * breath_env * self.snore_lp * snore_gain;
            }

            // Speech component (A-4): 300–3000 Hz noise with bursty
            // syllabic-rate gating (3.3 Hz × 5.1 Hz product) — synthetic
            // podcast speech. Independent of the breathing envelope.
            if self.in_speech(t) {
                const PI: f32 = std::f32::consts::PI;
                let g1 = 0.5 + 0.5 * (2.0 * PI * 3.3 * t).sin();
                let g2 = 0.5 + 0.5 * (2.0 * PI * 5.1 * t + 1.0).sin();
                let gate = g1 * g2;
                let w = self.rng.next_gaussian();
                self.speech_lp_slow += alpha_speech_hp * (w - self.speech_lp_slow);
                let hp = w - self.speech_lp_slow;
                self.speech_lp_fast += alpha_speech_lp * (hp - self.speech_lp_fast);
                s += self.cfg.speech_amp * gate * self.speech_lp_fast * speech_gain;
            }

            // Terminal gasp: loud broadband (white) burst.
            if self.in_gasp(t) {
                s += self.cfg.gasp_amp * self.rng.next_gaussian();
            }

            // Ambient room noise: white.
            s += self.cfg.noise_amp * self.rng.next_gaussian();

            *slot = (s * 32_767.0).clamp(-32_767.0, 32_767.0) as i16;
        }
        out
    }
}

fn one_pole_alpha(cutoff_hz: f32) -> f32 {
    1.0 - (-2.0 * std::f32::consts::PI * cutoff_hz / SAMPLE_RATE).exp()
}

// ── Scoring helper ─────────────────────────────────────────────────────────────

/// Greedy match of detected event starts (ms) against ground truth (s),
/// within ± `tol_s`. Returns (true_positives, false_positives, false_negatives).
pub fn score_events(detected_start_ms: &[u64], truth_start_s: &[f32], tol_s: f32) -> (usize, usize, usize) {
    let mut matched = vec![false; truth_start_s.len()];
    let mut tp = 0usize;
    let mut fp = 0usize;
    for &d in detected_start_ms {
        let d_s = d as f32 / 1000.0;
        let hit = truth_start_s
            .iter()
            .enumerate()
            .find(|(i, &t)| !matched[*i] && (d_s - t).abs() <= tol_s);
        match hit {
            Some((i, _)) => {
                matched[i] = true;
                tp += 1;
            }
            None => fp += 1,
        }
    }
    let fn_count = matched.iter().filter(|&&m| !m).count();
    (tp, fp, fn_count)
}
