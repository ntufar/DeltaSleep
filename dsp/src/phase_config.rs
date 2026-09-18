//! Compile-time tunable thresholds for heuristic sleep-phase classification
//! (A-1 REM estimation).
//!
//! Mirrors the `apnea_config.rs` pattern: every detector constant lives here
//! so it can be tuned during validation without hunting through the
//! classifier code. Every constant documents its meaning, unit, and default
//! rationale.
//!
//! REM signal model: near-atonia (very low body movement) + irregular
//! breathing (high breath-period variability) + occurrence in ~90 min cycles
//! weighted toward the back half of the night. The DSP rule covers the first
//! two; cycle weighting and flicker removal are Kotlin post-processing in
//! `NightSummarizer.smoothPhases`.

// ── REM candidate rule (classifier.rs) ───────────────────────────────────────

///
/// `movement_score` is the epoch RMS variance normalised by the awake
/// variance threshold (`AWAKE_VAR` in `classifier.rs`), so 1.0 = "as restless
/// as the awake boundary". Measured on synthetic nights: calm breathing
/// modulation alone scores ≈ 0.22 (the breathing amplitude swing is itself
/// variance), so the gate must sit above that floor; 0.5 admits calm sleep
/// while excluding the restless half approaching Awake.
pub const REM_MOVEMENT_MAX: f32 = 0.5;

/// Minimum coefficient of variation (std / mean) of the breath period over
/// the epoch's autocorrelation windows to count as irregular breathing.
/// Steady sleep breathing reads near 0.0 (the tracker reports an almost
/// constant period); REM's breath-to-breath irregularity drives this up.
pub const REM_BREATH_CV_MIN: f32 = 0.25;

/// Minimum fraction of epoch frames with `breathing_present` for a REM
/// verdict. Guards against scoring REM on silent/apneic stretches where the
/// period CV is undefined noise. Strongly irregular breathing can itself
/// depress the autocorrelation confidence, so direct breath-peak evidence
/// (≥ [`REM_MIN_BREATH_INTERVALS`] detected intervals in the epoch) also
/// satisfies the breathing gate — silence yields no intervals either way.
pub const REM_BREATHING_FRACTION_MIN: f32 = 0.5;

/// Minimum detected breath intervals in an epoch to corroborate breathing
/// for the REM gate when the periodicity fraction is low (see above).
/// A normal 30 s epoch holds ~5–10 breaths; apneic silence holds none.
pub const REM_MIN_BREATH_INTERVALS: usize = 3;

/// Peak prominence for the breath-peak detector, as a multiple of the
/// trailing 30 s breathing median. Breath peaks reach ~2× the median
/// (raised-sinusoid modulation); 1.15 rejects noise wiggles while keeping
/// shallow breaths. Double-counting within one breath is prevented
/// structurally by the trough arm below, not by this threshold.
pub const BREATH_PEAK_PROMINENCE: f32 = 1.15;

/// Trough arm for the breath-peak detector, as a multiple of the trailing
/// median. After a recorded peak no further peak counts until the envelope
/// has dipped below this level — wiggles near the top of a breath can never
/// double-count, at any breathing rate.
pub const BREATH_TROUGH_ARM: f32 = 0.80;

/// Minimum peak level above the adaptive noise floor (dB) for a breath
/// peak. Floor-level wiggles during silence/apnea must not mint intervals.
pub const BREATH_PEAK_FLOOR_MARGIN_DB: f32 = 6.0;

/// Minimum separation between detected breath peaks, in frames
/// (150 × 10 ms = 1.5 s = 40 breaths/min — faster is not breathing).
pub const BREATH_PEAK_MIN_SEP_FRAMES: u64 = 150;

/// Maximum recorded breath interval, in frames (1000 × 10 ms = 10 s).
/// Longer gaps are apnea/silence, not slow breathing, and must not dilute
/// the irregularity CV.
pub const BREATH_INTERVAL_MAX_FRAMES: u64 = 1_000;
