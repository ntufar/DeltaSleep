/// Maps epoch-level features to a sleep phase ordinal:
///   0 = Awake, 1 = Light, 2 = Deep  (matches SleepPhase enum order in Kotlin)
///
/// Thresholds are heuristic; all inputs are normalised to [-1.0, 1.0] audio range.
pub fn classify(mean_rms: f32, rms_variance: f32) -> u8 {
    const AWAKE_RMS: f32 = 0.04;
    const AWAKE_VAR: f32 = 0.0010;
    const DEEP_RMS: f32  = 0.005;

    if mean_rms > AWAKE_RMS || rms_variance > AWAKE_VAR {
        0 // Awake
    } else if mean_rms < DEEP_RMS {
        2 // Deep
    } else {
        1 // Light
    }
}
