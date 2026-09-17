/// Per-frame snore check. Returns true when the frame has snore-like characteristics:
/// audible volume and energy concentrated in the 20–300 Hz band.
pub fn detect_frame(rms: f32, band_ratio: f32) -> bool {
    detect_frame_with_offset(rms, band_ratio, 0.0)
}

/// Offset-aware snore check (D-3 mic sensitivity / A-5 personal baseline).
/// `threshold_offset_db` is added to the RMS threshold in dB: a negative
/// offset lowers the bar (higher sensitivity, quieter snores count), a
/// positive offset raises it. Zero reproduces the legacy behaviour exactly.
pub fn detect_frame_with_offset(rms: f32, band_ratio: f32, threshold_offset_db: f32) -> bool {
    const BASE_MIN_RMS: f32   = 0.010;
    const MIN_BAND_RATIO: f32 = 0.25;
    let min_rms = BASE_MIN_RMS * 10f32.powf(threshold_offset_db / 20.0);
    rms > min_rms && band_ratio > MIN_BAND_RATIO
}

/// Epoch-level snore verdict based on per-frame counts.
/// Flags the epoch as snoring if ≥5% of frames passed detect_frame
/// (~1.5 s of snoring within a 30 s epoch).
pub fn detect_epoch(snore_frames: usize, total_frames: usize) -> bool {
    if total_frames == 0 { return false; }
    snore_frames as f32 / total_frames as f32 >= 0.05
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_offset_matches_legacy_thresholds() {
        assert!(detect_frame_with_offset(0.011, 0.30, 0.0));
        assert!(!detect_frame_with_offset(0.009, 0.30, 0.0));
        assert!(!detect_frame_with_offset(0.011, 0.20, 0.0));
        assert_eq!(
            detect_frame_with_offset(0.011, 0.30, 0.0),
            detect_frame(0.011, 0.30)
        );
    }

    #[test]
    fn negative_offset_catches_quieter_frames() {
        // −6 dB halves the RMS threshold: 0.005 now passes.
        assert!(!detect_frame_with_offset(0.006, 0.30, 0.0));
        assert!(detect_frame_with_offset(0.006, 0.30, -6.0));
    }

    #[test]
    fn positive_offset_rejects_quieter_frames() {
        // +6 dB doubles the RMS threshold: 0.011 no longer passes.
        assert!(detect_frame_with_offset(0.011, 0.30, 0.0));
        assert!(!detect_frame_with_offset(0.011, 0.30, 6.0));
        // Loud frames still pass at +6 dB.
        assert!(detect_frame_with_offset(0.025, 0.30, 6.0));
    }

    #[test]
    fn band_ratio_gate_is_offset_independent() {
        assert!(!detect_frame_with_offset(0.10, 0.20, -12.0));
    }
}
