//! Compile-time tunable thresholds for the apnea-screening DSP (FR-1.7).
//!
//! All detector constants live here so they can be tuned during validation
//! (milestone M5) without hunting through the detector code. Every constant
//! documents its meaning, unit, and default rationale.

// ── Sampling geometry ──────────────────────────────────────────────────────────

/// Audio sample rate in Hz. Fixed by the capture pipeline (`AudioRecord` @ 16 kHz).
pub const SAMPLE_RATE_HZ: f32 = 16_000.0;

/// Frames per second. One frame = 10 ms of audio (160 samples at 16 kHz).
pub const FRAMES_PER_SECOND: u64 = 100;

/// FFT size for spectral flatness / centroid. 160-sample frames are
/// zero-padded to the next power of two so a radix-2 FFT applies.
pub const FFT_SIZE: usize = 256;

/// Compute the spectral FFT only every Nth frame (20 Hz update rate).
/// Spectral flatness/centroid are used for gasp/snore character checks that
/// do not need a 100 Hz refresh; skipping 4 of 5 frames cuts CPU (NFR-2).
pub const FFT_FRAME_STRIDE: u64 = 5;

// ── Respiratory envelope (FR-1.1) ──────────────────────────────────────────────

/// High-pass cutoff of the respiratory band in Hz. Breathing turbulence noise
/// lives roughly in 100–2000 Hz; the HP edge rejects low-frequency rumble
/// (traffic, HVAC, handling noise) below 100 Hz.
pub const RESP_HP_HZ: f32 = 100.0;

/// Low-pass cutoff of the respiratory band in Hz. Energy above ~2 kHz is
/// dominated by transient environmental sounds rather than breath sounds.
pub const RESP_LP_HZ: f32 = 2_000.0;

/// Time constant of the exponential moving average applied to the per-frame
/// respiratory-band RMS, in milliseconds. ~300 ms smooths within-breath
/// texture while still tracking the inhale/exhale amplitude modulation.
pub const RESP_SMOOTH_MS: f32 = 300.0;

// ── Adaptive noise floor (FR-1.2) ──────────────────────────────────────────────

/// Number of frames aggregated (by minimum) into one noise-floor bucket.
/// 10 frames = 100 ms; the per-bucket minimum makes the tracker robust to
/// brief loud transients inside a bucket.
pub const NOISE_BUCKET_FRAMES: u64 = 10;

/// Number of 100 ms buckets in the sliding window: 600 × 100 ms = 60 s,
/// per FR-1.2 ("60 s sliding window"). Memory: 600 × 4 B = 2.4 KB.
pub const NOISE_FLOOR_BUCKETS: usize = 600;

/// Percentile of the bucket-minimum distribution reported as the floor.
/// The 10th percentile sits below breathing sounds but above outright
/// numerical silence, tracking the quiet gaps between breaths.
pub const NOISE_FLOOR_PERCENTILE: f32 = 0.10;

/// Floor value in dBFS reported before any audio has been observed.
/// −80 dBFS is far below any realistic room recording.
pub const NOISE_FLOOR_DEFAULT_DB: f32 = -80.0;

// ── Breathing periodicity tracker (FR-1.3) ─────────────────────────────────────

/// Downsample rate of the smoothed respiratory envelope fed to the
/// autocorrelation, in Hz. 20 Hz (one sample per 50 ms) is ample for
/// breath periods of 2–8 s and keeps the ring small.
pub const PERIODICITY_DOWNSAMPLE_HZ: f32 = 20.0;

/// Take one downsampled envelope sample every N frames (5 × 10 ms = 50 ms).
pub const PERIODICITY_DOWNSAMPLE_FRAMES: u64 = 5;

/// Length of the downsampled-envelope ring: 900 samples = 45 s at 20 Hz,
/// inside the 30–60 s window required by FR-1.3. Memory: 3.6 KB.
pub const PERIODICITY_RING_SAMPLES: usize = 900;

/// Minimum accepted breathing period in seconds (30 breaths/min).
pub const BREATH_PERIOD_MIN_S: f32 = 2.0;

/// Maximum accepted breathing period in seconds (7.5 breaths/min).
pub const BREATH_PERIOD_MAX_S: f32 = 8.0;

/// Normalised (mean-removed) autocorrelation peak required to declare
/// `breathing_present = true`. White noise over a 45 s window peaks well
/// below 0.2; real AM breathing typically exceeds 0.4.
pub const PERIODICITY_CONFIDENCE_THRESHOLD: f32 = 0.30;

/// Recompute the autocorrelation once per this many frames (100 = 1 s).
/// Amortises the O(window × lags) scan instead of running it every frame.
pub const PERIODICITY_UPDATE_FRAMES: u64 = 100;

/// Minimum number of downsampled samples before periodicity is evaluated
/// (200 × 50 ms = 10 s ≈ multiple breath cycles).
pub const PERIODICITY_MIN_SAMPLES: usize = 200;

// ── Trailing breathing level (reference for decrements, FR-1.4) ────────────────

/// Length of the trailing per-frame respiratory-envelope ring used for the
/// breathing median: 3000 frames = 30 s. Memory: 12 KB.
pub const BREATHING_MEDIAN_FRAMES: usize = 3_000;

/// Recompute the (sorted) median once per this many frames (100 = 1 s);
/// cached in between. Avoids a 3000-element sort in the per-frame path.
pub const BREATHING_MEDIAN_UPDATE_FRAMES: u64 = 100;

// ── Decrement / event state machine (FR-1.4) ───────────────────────────────────

/// dB offset above the noise floor that the decrement-entry threshold can
/// never fall below. Prevents chasing "decrements" into the room noise when
/// breathing is barely above the floor.
pub const DECREMENT_FLOOR_OFFSET_DB: f32 = 3.0;

/// Envelope-reduction fraction that opens a DECREMENT: the envelope must
/// fall below (1 − 0.30) = 70 % of the trailing breathing median. 0.30 is
/// the lower bound of the hypopnea range (spec §2), so both hypopnea- and
/// apnea-depth drops enter the state.
pub const DECREMENT_ENTRY_REDUCTION: f32 = 0.30;

/// Recovery threshold as a fraction of the frozen pre-decrement baseline.
/// The DECREMENT closes when the envelope climbs back above 80 % of the
/// baseline (hysteresis above the 70 % entry level to avoid chatter).
pub const DECREMENT_EXIT_FRACTION: f32 = 0.80;

/// Minimum decrement duration in seconds to emit an event (clinical apnea /
/// hypopnea definition: ≥ 10 s).
pub const MIN_EVENT_DURATION_S: f32 = 10.0;

/// Sanity cap on decrement duration in seconds. A "decrement" longer than
/// 120 s is treated as loss of signal (person left, mic covered), not an
/// obstructive event; no event is emitted and the machine drops to NO_SIGNAL.
pub const MAX_EVENT_DURATION_S: f32 = 120.0;

/// Lower bound of the partial-reduction band classified HYPOPNEA_LIKE
/// (envelope fell by 30–50 %, spec §2).
pub const HYPOPNEA_REDUCTION_LOW: f32 = 0.30;

/// Upper bound of the hypopnea band; reductions deeper than 50 % are
/// classified APNEA_LIKE.
pub const HYPOPNEA_REDUCTION_HIGH: f32 = 0.50;

/// Consecutive seconds of `breathing_present` required to move from
/// NO_SIGNAL to BREATHING (debounce so a single autocorrelation blip does
/// not confirm breathing).
pub const BREATHING_CONFIRM_S: f32 = 2.0;

// ── Gasp detector (FR-1.5) ─────────────────────────────────────────────────────

/// Full-band RMS must exceed the trailing 30 s median envelope by at least
/// this many dB to qualify as a gasp (FR-1.5 fixes 12 dB).
pub const GASP_DB_OFFSET: f32 = 12.0;

/// Window after a DECREMENT ends, in seconds, during which a loud broadband
/// burst is attributed to the event as a resumption gasp (FR-1.5: 5 s).
pub const GASP_WINDOW_AFTER_S: f32 = 5.0;

/// Minimum spectral flatness for a burst to count as a gasp. A gasp/snort is
/// broadband (flatness well above tonal sounds); 0.15 rejects narrowband
/// interference such as beeps or hum while accepting noisy bursts.
pub const GASP_FLATNESS_THRESHOLD: f32 = 0.15;

// ── Snore episodes (FR-1.8) ────────────────────────────────────────────────────

/// Minimum episode length in frames for a SNORE_EPISODE event
/// (30 × 10 ms = 300 ms — shorter blips are ignored).
pub const SNORE_MIN_EPISODE_FRAMES: u64 = 30;

/// A snore episode is closed after this many consecutive non-snore frames
/// (50 × 10 ms = 500 ms), so short intra-snore dips do not split episodes.
pub const SNORE_GAP_CLOSE_FRAMES: u64 = 50;

// ── Confidence & signal quality (FR-2) ─────────────────────────────────────────

/// Breathing-to-noise margin in dB below which the signal is considered too
/// weak for reliable screening (FR-2.4 fixes 6 dB; the Kotlin layer flags
/// LOW_SIGNAL_QUALITY from the per-epoch margin output).
pub const LOW_MARGIN_DB: f32 = 6.0;

/// Margin (dB above `LOW_MARGIN_DB`) at which the ambient-margin factor of
/// the event confidence saturates at 1.0.
pub const MARGIN_CONFIDENCE_SPAN_DB: f32 = 10.0;

/// Decrement depth (dB of the pre-event breathing level over the event
/// minimum) at which the depth factor of the confidence saturates at 1.0.
pub const DEPTH_CONFIDENCE_SPAN_DB: f32 = 20.0;

/// Additive confidence bonus when the decrement is terminated by a gasp.
pub const GASP_CONFIDENCE_BONUS: f32 = 0.15;

// ── Event ring buffer (FR-3.5) ─────────────────────────────────────────────────

/// Capacity of the append-only acoustic-event ring drained by `pollEvents`.
/// 256 events comfortably covers one 30 s epoch even in pathological input;
/// on overflow the oldest event is dropped. Memory: ≈ 10 KB.
pub const EVENT_RING_CAPACITY: usize = 256;
