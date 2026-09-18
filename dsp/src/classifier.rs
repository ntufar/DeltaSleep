/// Maps epoch-level features to a sleep phase ordinal:
///   0 = Awake, 1 = Light, 2 = Deep, 3 = REM  (matches SleepPhase enum order in Kotlin)
///
/// Thresholds are heuristic; all inputs are normalised to [-1.0, 1.0] audio range.
///
/// REM (A-1) is a candidate rule, not a validated stage: near-atonia
/// (`movement_score` below [`crate::phase_config::REM_MOVEMENT_MAX`]) AND
/// breathing present AND irregular breathing
/// (`breath_period_cv` above [`crate::phase_config::REM_BREATH_CV_MIN`]) AND
/// no dominant external audio (podcast/TV time is suspect per A-4 and must
/// not mint sleep stages).
/// Movement dominates: anything restless is Awake regardless of breathing.
/// The Kotlin layer labels REM "estimated" until validation (A-2).
pub fn classify(
    mean_rms: f32,
    rms_variance: f32,
    breathing_present: bool,
    breath_period_cv: f32,
    external_audio_fraction: f32,
) -> u8 {
    use crate::phase_config as pc;

    const AWAKE_RMS: f32 = 0.04;
    const AWAKE_VAR: f32 = 0.0010;
    const DEEP_RMS: f32 = 0.005;
    /// Epochs at/above this speech-like fraction read as external audio
    /// (mirrors the Kotlin `ExternalAudio` dominant verdict, A-4).
    const EXTERNAL_AUDIO_MAX: f32 = 0.5;

    if mean_rms > AWAKE_RMS || rms_variance > AWAKE_VAR {
        return 0; // Awake
    }
    // movement_score 1.0 = "as restless as the awake boundary".
    let movement_score = rms_variance / AWAKE_VAR;
    if breathing_present
        && movement_score < pc::REM_MOVEMENT_MAX
        && breath_period_cv > pc::REM_BREATH_CV_MIN
        && external_audio_fraction < EXTERNAL_AUDIO_MAX
    {
        return 3; // REM (estimated)
    }
    if mean_rms < DEEP_RMS {
        2 // Deep
    } else {
        1 // Light
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn calm_regular_breathing_is_not_rem() {
        // Quiet sleep, steady 4 s breathing: low movement, CV ~ 0.
        assert_eq!(classify(0.003, 1e-5, true, 0.02, 0.0), 2); // Deep
        assert_eq!(classify(0.010, 1e-5, true, 0.02, 0.0), 1); // Light
    }

    #[test]
    fn still_irregular_breathing_is_rem() {
        // Near-atonia (variance well under half the awake threshold),
        // breathing present, irregular period, clean audio.
        assert_eq!(classify(0.010, 5e-5, true, 0.35, 0.0), 3);
        assert_eq!(classify(0.003, 5e-5, true, 0.40, 0.1), 3);
    }

    #[test]
    fn rem_gates_each_fail_closed() {
        // No breathing (e.g. apneic stretch): never REM.
        assert_ne!(classify(0.010, 5e-5, false, 0.90, 0.0), 3);
        // Regular breathing: never REM.
        assert_ne!(classify(0.010, 5e-5, true, 0.10, 0.0), 3);
        // Too much movement: never REM (Awake when over the boundary…).
        assert_eq!(classify(0.050, 0.002, true, 0.90, 0.0), 0);
        // …and restless-but-sub-awake is Light, not REM.
        assert_eq!(classify(0.010, 5e-4, true, 0.90, 0.0), 1);
        // Dominant external audio (podcast/TV): never REM.
        assert_eq!(classify(0.010, 5e-5, true, 0.90, 0.7), 1);
    }
}
