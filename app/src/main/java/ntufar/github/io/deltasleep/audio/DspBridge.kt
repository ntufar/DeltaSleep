package ntufar.github.io.deltasleep.audio

/**
 * JNI bridge to the Rust DSP library (libdeltasleep_dsp.so).
 *
 * All methods operate on global Rust-side state protected by a Mutex, so only
 * one DspBridge instance should be active at a time (the one owned by SleepTrackingService).
 */
class DspBridge {
    /**
     * Feed one 10 ms audio frame (160 samples at 16 kHz) into the DSP.
     * Returns [rms, zcr, band_power_ratio] for this frame.
     */
    external fun processFrame(samples: ShortArray): FloatArray

    /**
     * Summarise the epoch accumulated since the last resetEpoch() call.
     * Returns [mean_rms, rms_variance, mean_zcr, mean_band_ratio, phase_ordinal, snore_flag].
     */
    external fun computeEpoch(): FloatArray

    /** Discard accumulated epoch data and start fresh. */
    external fun resetEpoch()

    companion object {
        init {
            System.loadLibrary("deltasleep_dsp")
        }
    }
}
