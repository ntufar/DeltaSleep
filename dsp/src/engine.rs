//! Session engine: owns all DSP state and exposes a pure-Rust API so the
//! whole pipeline is host-testable. `lib.rs` is only a thin JNI shim over
//! this module.

use crate::apnea::{
    db, AcousticEvent, BreathingMedianTracker, EventRing, EventStateMachine, Fft256, MachineInput,
    NoiseFloor, Periodicity, PeriodicityTracker, RespEnvelope, SnoreEpisodeTracker, SyllabicTracker,
};
use crate::apnea_config as cfg;
use crate::features::{self, BandPassState};
use crate::{classifier, snore};

// ── Outputs ────────────────────────────────────────────────────────────────────

/// Per-frame outputs, mirrored 1:1 by the 6-float `processFrame` FFI return.
#[derive(Clone, Copy, Debug, Default)]
pub struct FrameOutput {
    pub rms: f32,
    pub zcr: f32,
    pub band_power_ratio: f32,
    /// Adaptive noise floor, dBFS (FR-1.2).
    pub noise_floor_db: f32,
    /// Smoothed respiratory envelope in dB minus the noise floor (may be
    /// negative when no breathing sound is present).
    pub breathing_margin_db: f32,
    /// Periodicity tracker verdict (FR-1.3), refreshed once per second.
    pub breathing_present: bool,
    /// 300–3000 Hz power fraction this frame (A-4 speech evidence).
    pub speech_ratio: f32,
    /// Speech verdict: speech_ratio above threshold AND syllabic modulation
    /// index above threshold (both must hold; cached index refreshes 1 Hz).
    pub speech_present: bool,
}

/// Per-epoch outputs, mirrored 1:1 by the 10-float `computeEpoch` FFI return.
#[derive(Clone, Copy, Debug, Default)]
pub struct EpochOutput {
    pub mean_rms: f32,
    pub rms_variance: f32,
    pub mean_zcr: f32,
    pub mean_band_ratio: f32,
    /// 0 = Awake, 1 = Light, 2 = Deep (matches the Kotlin SleepPhase order).
    pub phase_ordinal: u8,
    pub snore_flag: bool,
    /// Mean breathing-to-noise margin across epoch frames, dB. The Kotlin
    /// side uses this for the FR-2.4 LOW_SIGNAL_QUALITY night flag.
    pub mean_breathing_margin_db: f32,
    /// Fraction of epoch frames with `breathing_present`, 0–1.
    pub breathing_present_fraction: f32,
    /// Fraction of epoch frames with `speech_present` (A-4 external-audio
    /// evidence); 0.0 when no speech-like frame was seen.
    pub external_audio_fraction: f32,
    /// Mean autocorrelation breath period (seconds) over frames where
    /// breathing was present; 0.0 when breathing was never present.
    /// The Kotlin side persists this per epoch (A-7) and maps NULL
    /// (≤ 0) to "no breathing detected".
    pub breath_period_s: f32,
}

// ── Epoch accumulator ──────────────────────────────────────────────────────────

#[derive(Default)]
struct EpochAccumulator {
    rms_sum: f64,
    rms_sq_sum: f64, // for variance: E[x²] − E[x]²
    zcr_sum: f64,
    band_ratio_sum: f64,
    margin_sum: f64,
    period_sum: f64,
    count: usize,
    snore_frame_count: usize,
    breathing_present_count: usize,
    period_count: usize,
    speech_frame_count: usize,
}

impl EpochAccumulator {
    fn add(&mut self, out: &FrameOutput, period_s: f32) {
        self.rms_sum += out.rms as f64;
        self.rms_sq_sum += (out.rms * out.rms) as f64;
        self.zcr_sum += out.zcr as f64;
        self.band_ratio_sum += out.band_power_ratio as f64;
        self.margin_sum += out.breathing_margin_db as f64;
        self.count += 1;
        if out.breathing_present {
            self.breathing_present_count += 1;
            self.period_sum += period_s as f64;
            self.period_count += 1;
        }
        if out.speech_present {
            self.speech_frame_count += 1;
        }
    }
}

// ── Session engine ─────────────────────────────────────────────────────────────

/// Owns every piece of DSP session state: epoch accumulator, filters, noise
/// floor, periodicity tracker, event state machine, event ring, and the
/// frame counter. All buffers are fixed-size (total well under the 4 MB
/// NFR-3 budget); `process_frame` performs no heap allocation.
pub struct SessionEngine {
    // Filters / feature state.
    bp: BandPassState,
    resp_env: RespEnvelope,
    fft: Fft256,
    /// Cached spectral features, refreshed every `FFT_FRAME_STRIDE` frames.
    flatness: f32,
    centroid_hz: f32,
    // Trackers.
    noise_floor: NoiseFloor,
    periodicity: PeriodicityTracker,
    cached_periodicity: Periodicity,
    syllabic: SyllabicTracker,
    median_tracker: BreathingMedianTracker,
    machine: EventStateMachine,
    snore_tracker: SnoreEpisodeTracker,
    ring: EventRing,
    // Counters / accumulators.
    frame_counter: u64,
    epoch: EpochAccumulator,
    /// dB offset added to the snore RMS threshold (D-3 mic sensitivity;
    /// the A-5 personal-baseline hook reuses this field). Negative =
    /// more sensitive. Clamped to ±12 dB on write.
    snore_threshold_offset_db: f32,
}

impl Default for SessionEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionEngine {
    pub fn new() -> Self {
        Self {
            bp: BandPassState::default(),
            resp_env: RespEnvelope::default(),
            fft: Fft256::new(),
            flatness: 0.0,
            centroid_hz: 0.0,
            noise_floor: NoiseFloor::default(),
            periodicity: PeriodicityTracker::default(),
            cached_periodicity: Periodicity::default(),
            syllabic: SyllabicTracker::default(),
            median_tracker: BreathingMedianTracker::default(),
            machine: EventStateMachine::default(),
            snore_tracker: SnoreEpisodeTracker::default(),
            ring: EventRing::default(),
            frame_counter: 0,
            epoch: EpochAccumulator::default(),
            snore_threshold_offset_db: 0.0,
        }
    }

    /// Full session reset: clears every tracker, the event ring, the epoch
    /// accumulator, and the frame counter. Call on tracking start/resume.
    /// The D-3 snore threshold offset survives the reset — the Kotlin side
    /// re-applies it after start anyway, but a sticky-restart ordering gap
    /// must never silently revert the user's sensitivity mid-night.
    pub fn start_session(&mut self) {
        let offset = self.snore_threshold_offset_db;
        *self = Self::new();
        self.snore_threshold_offset_db = offset;
    }

    /// Set the D-3 mic-sensitivity offset (dB) applied to the snore RMS
    /// threshold. A-5 will compose its per-user calibration into this same
    /// field. Clamped to ±12 dB.
    pub fn set_snore_threshold_offset_db(&mut self, offset_db: f32) {
        self.snore_threshold_offset_db = offset_db.clamp(-12.0, 12.0);
    }

    /// Current snore threshold offset in dB (tests / debug overlay).
    pub fn snore_threshold_offset_db(&self) -> f32 {
        self.snore_threshold_offset_db
    }

    /// Resets ONLY the epoch accumulator. Filters, noise floor, periodicity,
    /// state machine, event ring, and frame counter all persist.
    pub fn reset_epoch(&mut self) {
        self.epoch = EpochAccumulator::default();
    }

    /// Process one 10 ms frame of 16 kHz mono PCM (allocation-free).
    pub fn process_frame(&mut self, samples: &[i16]) -> FrameOutput {
        let idx = self.frame_counter;

        // 1. Legacy features (RMS / ZCR / 20–300 Hz snore-band ratio).
        let f = features::compute(samples, &mut self.bp);

        // 2. Respiratory envelope (FR-1.1) and adaptive noise floor (FR-1.2).
        let (resp_frame_rms, env) = self.resp_env.process_frame(samples);
        self.noise_floor.update(db(resp_frame_rms));
        let floor_db = self.noise_floor.floor_db();
        let env_db = db(env);
        let margin_db = env_db - floor_db;

        // 3. Breathing periodicity (FR-1.3): 20 Hz downsample, 1 Hz refresh.
        if idx.is_multiple_of(cfg::PERIODICITY_DOWNSAMPLE_FRAMES) {
            self.periodicity.push(env);
        }
        if idx.is_multiple_of(cfg::PERIODICITY_UPDATE_FRAMES) {
            self.cached_periodicity = self.periodicity.compute();
        }

        // 3b. Syllabic modulation (A-4 VAD): 20 Hz speech-envelope
        // downsample, 1 Hz index refresh. The speech verdict needs BOTH
        // speech-band energy AND syllabic-rate modulation.
        if idx.is_multiple_of(cfg::PERIODICITY_DOWNSAMPLE_FRAMES) {
            self.syllabic.push(f.speech_rms);
        }
        if idx.is_multiple_of(cfg::PERIODICITY_UPDATE_FRAMES) {
            self.syllabic.recompute();
        }
        let speech_present = f.speech_ratio > cfg::SPEECH_RATIO_MIN
            && self.syllabic.index() > cfg::SYLLABIC_INDEX_MIN;

        // 4. Trailing breathing median (decrement reference), 1 Hz refresh.
        self.median_tracker.push(env);
        if idx.is_multiple_of(cfg::BREATHING_MEDIAN_UPDATE_FRAMES) {
            self.median_tracker.recompute();
        }
        let median_lin = self.median_tracker.median();
        let median_db = db(median_lin);

        // 5. Spectral flatness / centroid (FR-1.1), amortised to 20 Hz.
        if idx.is_multiple_of(cfg::FFT_FRAME_STRIDE) {
            self.fft.compute_i16(samples);
            self.flatness = self.fft.spectral_flatness();
            self.centroid_hz = self.fft.spectral_centroid_hz(cfg::SAMPLE_RATE_HZ);
        }

        // 6. Snore episodes (FR-1.8). Band RMS = full RMS scaled by the
        //    power fraction in the 20–300 Hz snore band.
        let snore_frame = snore::detect_frame_with_offset(
            f.rms,
            f.band_power_ratio,
            self.snore_threshold_offset_db,
        );
        let band_rms = f.rms * f.band_power_ratio.max(0.0).sqrt();
        if let Some(ev) = self.snore_tracker.update(idx, snore_frame, db(band_rms) - floor_db) {
            self.ring.push(ev);
        }

        // 7. Event state machine (FR-1.4/1.5).
        let input = MachineInput {
            frame_idx: idx,
            env_lin: env,
            env_db,
            floor_db,
            median_lin,
            median_db,
            breathing_present: self.cached_periodicity.present,
            periodicity_conf: self.cached_periodicity.confidence,
            full_rms_db: db(f.rms),
            flatness: self.flatness,
            snore_frame,
        };
        self.machine.update(&input, &mut self.ring);

        // 8. Epoch accumulation and outputs.
        let out = FrameOutput {
            rms: f.rms,
            zcr: f.zcr,
            band_power_ratio: f.band_power_ratio,
            noise_floor_db: floor_db,
            breathing_margin_db: margin_db,
            breathing_present: self.cached_periodicity.present,
            speech_ratio: f.speech_ratio,
            speech_present,
        };
        self.epoch.add(&out, self.cached_periodicity.period_s);
        if snore_frame {
            self.epoch.snore_frame_count += 1;
        }
        self.frame_counter += 1;
        out
    }

    /// Summarise the epoch accumulated since the last `reset_epoch`.
    pub fn compute_epoch(&self) -> EpochOutput {
        let e = &self.epoch;
        if e.count == 0 {
            return EpochOutput::default();
        }
        let n = e.count as f64;
        let mean_rms = (e.rms_sum / n) as f32;
        let mean_sq = e.rms_sq_sum / n;
        let mean = e.rms_sum / n;
        let variance = (mean_sq - mean * mean).max(0.0) as f32;
        EpochOutput {
            mean_rms,
            rms_variance: variance,
            mean_zcr: (e.zcr_sum / n) as f32,
            mean_band_ratio: (e.band_ratio_sum / n) as f32,
            phase_ordinal: classifier::classify(mean_rms, variance),
            snore_flag: snore::detect_epoch(e.snore_frame_count, e.count),
            mean_breathing_margin_db: (e.margin_sum / n) as f32,
            breathing_present_fraction: e.breathing_present_count as f32 / e.count as f32,
            external_audio_fraction: e.speech_frame_count as f32 / e.count as f32,
            breath_period_s: if e.period_count > 0 {
                (e.period_sum / e.period_count as f64) as f32
            } else {
                0.0
            },
        }
    }

    /// Drain all completed acoustic events (FIFO). Called per epoch by the
    /// Kotlin side; allocation happens here, never in `process_frame`.
    pub fn poll_events(&mut self) -> Vec<AcousticEvent> {
        self.ring.drain()
    }

    /// Frame count since session start (start_offset_ms = frames × 10).
    pub fn frame_counter(&self) -> u64 {
        self.frame_counter
    }

    /// Cached spectral flatness of the most recent FFT frame.
    pub fn spectral_flatness(&self) -> f32 {
        self.flatness
    }

    /// Cached spectral centroid (Hz) of the most recent FFT frame.
    pub fn spectral_centroid_hz(&self) -> f32 {
        self.centroid_hz
    }

    /// Current state-machine state (debug overlay / tests).
    pub fn machine_state(&self) -> crate::apnea::MachineState {
        self.machine.state()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snore_offset_defaults_to_zero_and_clamps() {
        let mut e = SessionEngine::new();
        assert_eq!(e.snore_threshold_offset_db(), 0.0);
        e.set_snore_threshold_offset_db(99.0);
        assert_eq!(e.snore_threshold_offset_db(), 12.0);
        e.set_snore_threshold_offset_db(-99.0);
        assert_eq!(e.snore_threshold_offset_db(), -12.0);
    }

    #[test]
    fn snore_offset_survives_session_reset() {
        let mut e = SessionEngine::new();
        e.set_snore_threshold_offset_db(-6.0);
        e.start_session();
        assert_eq!(e.snore_threshold_offset_db(), -6.0);
    }
}
