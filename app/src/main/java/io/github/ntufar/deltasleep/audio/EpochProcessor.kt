package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase

private const val EPOCH_DURATION_MS = 30_000L
private const val FRAME_DURATION_MS = 10L
private const val DSP_EVENT_STRIDE = 8

/**
 * An acoustic event parsed out of the raw stride-8 pollEvents() array.
 *
 * [startOffsetMs] is relative to the DSP session start (ms since DspBridge.startSession()).
 * The service converts it to wall-clock time using the recorded dspStartWallMs.
 */
data class ParsedEvent(
    val typeOrdinal: Int,
    val startOffsetMs: Long,
    val durationMs: Long,
    val confidence: Float,
    val peakDbOverFloor: Float,
    val envelopeReductionPct: Float,
    val terminatedByGasp: Boolean,
    val meanDbOverFloor: Float,
) {
    val type: AcousticEventType
        get() = AcousticEventType.entries[typeOrdinal.coerceIn(0, AcousticEventType.entries.lastIndex)]
}

/**
 * Combined result from one epoch flush: the epoch record plus any acoustic events
 * the DSP emitted during that epoch window.
 */
data class EpochResult(
    val epoch: SleepEpoch,
    val events: List<ParsedEvent>,
)

/**
 * Accumulates audio frames for one 30-second epoch.
 * When the epoch is full, [onFrame] converts it to an [EpochResult] for storage
 * and resets the DSP accumulator.
 *
 * Thread-safety: call from a single coroutine (the audio capture loop in SleepTrackingService).
 */
class EpochProcessor(private val dsp: DspBridge) {
    private var frameCount = 0
    private val framesPerEpoch = (EPOCH_DURATION_MS / FRAME_DURATION_MS).toInt()

    /**
     * Most recent per-frame metrics from processFrame() (6 elements):
     * [0] rms, [1] zcr, [2] band_power_ratio, [3] noise_floor_db,
     * [4] breathing_margin_db, [5] breathing_present
     */
    var lastFrameMetrics: FloatArray = FloatArray(6)
        private set

    /**
     * Process one 10 ms audio frame.
     * Returns an [EpochResult] when a 30-second epoch is complete, null otherwise.
     */
    fun onFrame(samples: ShortArray): EpochResult? {
        lastFrameMetrics = dsp.processFrame(samples)
        frameCount++
        return if (frameCount >= framesPerEpoch) flush() else null
    }

    private fun flush(): EpochResult {
        // [mean_rms, rms_variance, mean_zcr, mean_band_ratio, phase_ordinal, snore_flag,
        //  mean_breathing_margin_db, breathing_present_fraction]
        val result = dsp.computeEpoch()
        // Drain pending events before resetting so we capture all events in this epoch window
        val rawEvents = dsp.pollEvents()
        dsp.resetEpoch()
        frameCount = 0

        // mean_rms == 0 means the mic returned all-zero samples (access revoked); don't
        // let the DSP classifier call that DEEP sleep.
        val phase = if (result[0] == 0f) SleepPhase.AWAKE
                    else SleepPhase.entries[result[4].toInt().coerceIn(0, SleepPhase.entries.lastIndex)]
        val hasSnore = result[5] != 0f
        val breathingMarginDb = if (result.size > 6) result[6] else 0f
        val breathingPresentFraction = if (result.size > 7) result[7] else 0f

        val epoch = SleepEpoch(
            sessionId = 0,  // caller must set this before inserting
            timestamp = System.currentTimeMillis(),
            phase = phase,
            hasSnore = hasSnore,
            rmsEnergy = result[0],
            breathingMarginDb = breathingMarginDb,
            breathingPresentFraction = breathingPresentFraction,
        )

        val events = parseEvents(rawEvents)
        return EpochResult(epoch = epoch, events = events)
    }

    private fun parseEvents(raw: FloatArray): List<ParsedEvent> {
        if (raw.isEmpty()) return emptyList()
        val count = raw.size / DSP_EVENT_STRIDE
        return List(count) { i ->
            val base = i * DSP_EVENT_STRIDE
            ParsedEvent(
                typeOrdinal = raw[base + 0].toInt(),
                startOffsetMs = raw[base + 1].toLong(),
                durationMs = raw[base + 2].toLong(),
                confidence = raw[base + 3],
                peakDbOverFloor = raw[base + 4],
                envelopeReductionPct = raw[base + 5],
                terminatedByGasp = raw[base + 6] != 0f,
                meanDbOverFloor = raw[base + 7],
            )
        }
    }

    fun reset() {
        dsp.resetEpoch()
        frameCount = 0
    }
}
