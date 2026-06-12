/// Per-frame snore check. Returns true when the frame has snore-like characteristics:
/// audible volume and energy concentrated in the 20–300 Hz band.
pub fn detect_frame(rms: f32, band_ratio: f32) -> bool {
    const MIN_RMS: f32        = 0.010;
    const MIN_BAND_RATIO: f32 = 0.25;
    rms > MIN_RMS && band_ratio > MIN_BAND_RATIO
}

/// Epoch-level snore verdict based on per-frame counts.
/// Flags the epoch as snoring if ≥5% of frames passed detect_frame
/// (~1.5 s of snoring within a 30 s epoch).
pub fn detect_epoch(snore_frames: usize, total_frames: usize) -> bool {
    if total_frames == 0 { return false; }
    snore_frames as f32 / total_frames as f32 >= 0.05
}
