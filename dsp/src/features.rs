/// Audio features extracted from a single 10 ms frame (160 samples at 16 kHz).
#[derive(Copy, Clone)]
pub struct FrameFeatures {
    pub rms: f32,
    /// Normalised zero-crossing rate [0, 1].
    pub zcr: f32,
    /// Fraction of frame power in the 20–300 Hz snore band.
    pub band_power_ratio: f32,
    /// Fraction of frame power in the 300–3000 Hz speech band (A-4).
    /// High for speech AND breathing (100–2000 Hz respiratory band
    /// overlaps), low for snore and room rumble — so this never stands
    /// alone; the engine pairs it with syllabic-rate modulation.
    pub speech_ratio: f32,
    /// Linear RMS of the 300–3000 Hz band (feeds the syllabic tracker).
    pub speech_rms: f32,
}

/// Simple first-order IIR state for the band-pass chain.
#[derive(Default)]
pub struct BandPassState {
    lp_prev_out: f32,
    hp_prev_in: f32,
    hp_prev_out: f32,
    // Second chain for the 300–3000 Hz speech band (A-4).
    speech_lp_prev_out: f32,
    speech_hp_prev_in: f32,
    speech_hp_prev_out: f32,
}


/// Computes RMS, ZCR, snore band power ratio, and speech band features
/// for one audio frame.
///
/// Snore band-pass approximation:
///   Low-pass  at 300 Hz: α_lp = dt / (RC_lp + dt), RC_lp = 1 / (2π·300)
///   High-pass at  20 Hz: α_hp = RC_hp / (RC_hp + dt), RC_hp = 1 / (2π·20)
///
/// Speech band-pass approximation (same topology):
///   Low-pass  at 3000 Hz, high-pass at 300 Hz.
pub fn compute(samples: &[i16], bp: &mut BandPassState) -> FrameFeatures {
    const FS: f32 = 16_000.0;
    const DT: f32 = 1.0 / FS;
    const RC_LP: f32 = 1.0 / (2.0 * std::f32::consts::PI * 300.0);
    const RC_HP: f32 = 1.0 / (2.0 * std::f32::consts::PI * 20.0);
    const ALPHA_LP: f32 = DT / (RC_LP + DT);
    const ALPHA_HP: f32 = RC_HP / (RC_HP + DT);
    const RC_SPEECH_LP: f32 = 1.0 / (2.0 * std::f32::consts::PI * 3000.0);
    const RC_SPEECH_HP: f32 = 1.0 / (2.0 * std::f32::consts::PI * 300.0);
    const ALPHA_SPEECH_LP: f32 = DT / (RC_SPEECH_LP + DT);
    const ALPHA_SPEECH_HP: f32 = RC_SPEECH_HP / (RC_SPEECH_HP + DT);

    let n = samples.len() as f32;
    let mut sum_sq = 0.0_f32;
    let mut zero_crossings = 0usize;
    let mut band_sq = 0.0_f32;
    let mut speech_sq = 0.0_f32;

    let mut prev_sign = samples.first().map(|&s| s >= 0).unwrap_or(true);

    for &s in samples {
        let x = s as f32 / 32_768.0;
        sum_sq += x * x;

        let sign = x >= 0.0;
        if sign != prev_sign { zero_crossings += 1; }
        prev_sign = sign;

        // Low-pass at 300 Hz
        bp.lp_prev_out = ALPHA_LP * x + (1.0 - ALPHA_LP) * bp.lp_prev_out;
        // High-pass at 20 Hz (derived from the low-passed signal)
        let hp_out = ALPHA_HP * (bp.hp_prev_out + bp.lp_prev_out - bp.hp_prev_in);
        bp.hp_prev_in = bp.lp_prev_out;
        bp.hp_prev_out = hp_out;

        band_sq += hp_out * hp_out;

        // Speech chain: low-pass at 3000 Hz, then high-pass at 300 Hz.
        bp.speech_lp_prev_out =
            ALPHA_SPEECH_LP * x + (1.0 - ALPHA_SPEECH_LP) * bp.speech_lp_prev_out;
        let speech_hp = ALPHA_SPEECH_HP
            * (bp.speech_hp_prev_out + bp.speech_lp_prev_out - bp.speech_hp_prev_in);
        bp.speech_hp_prev_in = bp.speech_lp_prev_out;
        bp.speech_hp_prev_out = speech_hp;

        speech_sq += speech_hp * speech_hp;
    }

    let total_power = sum_sq / n;
    let band_power = band_sq / n;
    let band_power_ratio = if total_power > 1e-10 { band_power / total_power } else { 0.0 };
    let speech_power = speech_sq / n;
    let speech_ratio = if total_power > 1e-10 { speech_power / total_power } else { 0.0 };

    FrameFeatures {
        rms: total_power.sqrt(),
        zcr: zero_crossings as f32 / n,
        band_power_ratio,
        speech_ratio,
        speech_rms: speech_power.sqrt(),
    }
}
