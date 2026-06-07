/// Returns true when epoch-level features suggest snoring.
///
/// Snore signal characteristics: audible volume (rms above floor) and
/// significant energy concentrated in the 20–300 Hz band.
pub fn detect(mean_rms: f32, mean_band_ratio: f32) -> bool {
    const MIN_RMS: f32        = 0.012;
    const MIN_BAND_RATIO: f32 = 0.30;

    mean_rms > MIN_RMS && mean_band_ratio > MIN_BAND_RATIO
}
