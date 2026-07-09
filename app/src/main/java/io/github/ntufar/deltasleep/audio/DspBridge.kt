package io.github.ntufar.deltasleep.audio

/**
 * JNI bridge to the Rust DSP library (libdeltasleep_dsp.so).
 *
 * All methods operate on global Rust-side state protected by a Mutex, so only
 * one DspBridge instance should be active at a time (the one owned by SleepTrackingService).
 *
 * Rust FFI contract (fixed — implemented by the dsp/ agent):
 *
 * 1. [processFrame] — 6-float return: [rms, zcr, band_power_ratio, noise_floor_db,
 *    breathing_margin_db, breathing_present(0/1)]
 * 2. [computeEpoch] — 8-float return: [mean_rms, rms_variance, mean_zcr,
 *    mean_band_ratio, phase_ordinal, snore_flag, mean_breathing_margin_db,
 *    breathing_present_fraction]
 * 3. [resetEpoch] — clears epoch accumulator only
 * 4. [startSession] — full DSP session reset (call on tracking start/resume)
 * 5. [pollEvents] — flattened stride-8 array of acoustic events emitted since
 *    last poll. Each event: [type, start_offset_ms, duration_ms, confidence,
 *    peak_db_over_floor, envelope_reduction_pct, terminated_by_gasp(0/1),
 *    mean_db_over_floor]. type ordinals: 0=APNEA_LIKE, 1=HYPOPNEA_LIKE,
 *    2=GASP, 3=SNORE_EPISODE. start_offset_ms is ms since startSession().
 */
class DspBridge {
    /**
     * Feed one 10 ms audio frame (160 samples at 16 kHz) into the DSP.
     *
     * Returns a 6-element array:
     * [0] rms               — full-band root-mean-square energy
     * [1] zcr               — zero-crossing rate
     * [2] band_power_ratio  — band-limited (20–300 Hz) power ratio
     * [3] noise_floor_db    — current adaptive noise floor (dB)
     * [4] breathing_margin_db — breathing level minus noise floor (dB)
     * [5] breathing_present — 1.0 if periodic respiratory sound detected, 0.0 otherwise
     */
    external fun processFrame(samples: ShortArray): FloatArray

    /**
     * Summarise the epoch accumulated since the last [resetEpoch] call.
     *
     * Returns an 8-element array:
     * [0] mean_rms
     * [1] rms_variance
     * [2] mean_zcr
     * [3] mean_band_ratio
     * [4] phase_ordinal         — maps to SleepPhase.entries index
     * [5] snore_flag            — 1.0 if snore detected in epoch, 0.0 otherwise
     * [6] mean_breathing_margin_db — mean breathing-to-noise margin across epoch frames
     * [7] breathing_present_fraction — fraction of frames with breathing detected (0–1)
     */
    external fun computeEpoch(): FloatArray

    /** Discard accumulated epoch data and start fresh (epoch accumulator only). */
    external fun resetEpoch()

    /**
     * Full DSP session reset — clears noise-floor tracker, event state machine,
     * event ring buffer, and epoch accumulator.
     *
     * MUST be called when tracking starts (ACTION_START) and on sticky-restart resume.
     * The Kotlin side records [System.currentTimeMillis] immediately after this call
     * to convert DSP-relative event offsets to wall-clock times.
     */
    external fun startSession()

    /**
     * Poll and drain the Rust-side acoustic event ring buffer.
     *
     * Returns a flattened FloatArray with stride 8 per event:
     * [n*8+0] type                  — 0=APNEA_LIKE, 1=HYPOPNEA_LIKE, 2=GASP, 3=SNORE_EPISODE
     * [n*8+1] start_offset_ms       — ms since [startSession] was called
     * [n*8+2] duration_ms
     * [n*8+3] confidence            — 0–1
     * [n*8+4] peak_db_over_floor
     * [n*8+5] envelope_reduction_pct — 0–1
     * [n*8+6] terminated_by_gasp    — 1.0 = true, 0.0 = false
     * [n*8+7] mean_db_over_floor
     *
     * Returns an empty array if no events are pending.
     */
    external fun pollEvents(): FloatArray

    companion object {
        init {
            System.loadLibrary("deltasleep_dsp")
        }
    }
}
