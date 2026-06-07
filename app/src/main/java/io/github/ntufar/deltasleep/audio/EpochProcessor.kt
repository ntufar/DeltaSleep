package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase

private const val EPOCH_DURATION_MS = 30_000L
private const val FRAME_DURATION_MS = 10L

/**
 * Accumulates audio frames for one 30-second epoch.
 * When the epoch is full, flushEpoch() converts it to a SleepEpoch for storage
 * and resets the DSP accumulator.
 *
 * Thread-safety: call from a single coroutine (the audio capture loop in SleepTrackingService).
 */
class EpochProcessor(private val dsp: DspBridge) {
    private var frameCount = 0
    private val framesPerEpoch = (EPOCH_DURATION_MS / FRAME_DURATION_MS).toInt()

    /** Most recent per-frame metrics: [rms, zcr, band_power_ratio]. Updated on every frame. */
    var lastFrameMetrics: FloatArray = FloatArray(3)
        private set

    fun onFrame(samples: ShortArray): SleepEpoch? {
        lastFrameMetrics = dsp.processFrame(samples)
        frameCount++
        return if (frameCount >= framesPerEpoch) flush() else null
    }

    private fun flush(): SleepEpoch {
        // [mean_rms, rms_variance, mean_zcr, mean_band_ratio, phase_ordinal, snore_flag]
        val result = dsp.computeEpoch()
        dsp.resetEpoch()
        frameCount = 0

        val phase = SleepPhase.entries[result[4].toInt().coerceIn(0, SleepPhase.entries.lastIndex)]
        val hasSnore = result[5] != 0f

        return SleepEpoch(
            sessionId = 0,  // caller must set this before inserting
            timestamp = System.currentTimeMillis(),
            phase = phase,
            hasSnore = hasSnore,
            rmsEnergy = result[0],
        )
    }

    fun reset() {
        dsp.resetEpoch()
        frameCount = 0
    }
}
