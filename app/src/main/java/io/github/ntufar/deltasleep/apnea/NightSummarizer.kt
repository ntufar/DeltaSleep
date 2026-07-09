package io.github.ntufar.deltasleep.apnea

import io.github.ntufar.deltasleep.data.model.AcousticBand
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.SignalQuality
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase

private const val EPOCH_DURATION_S = 30
private const val SECONDS_PER_MINUTE = 60
private const val BREATHING_MARGIN_THRESHOLD_DB = 6f
private const val SIGNAL_QUALITY_LOW_THRESHOLD = 0.20f
private const val SIGNAL_QUALITY_FAIR_THRESHOLD = 0.10f

/**
 * Pure computation of per-night sleep-apnea screening metrics.
 *
 * The [compute] function is fully pure (no I/O, no coroutines, no Room calls) so it
 * can be unit-tested without any Android framework. The [summarize] suspend function
 * wraps it with DB reads/writes for use inside the service.
 */
object NightSummarizer {

    // ─── Pure computation ───────────────────────────────────────────────────────

    /**
     * Compute all nightly apnea-screening metrics from in-memory lists.
     *
     * This is the unit-testable core:
     * - Discards events whose midpoint falls within an AWAKE epoch (FR-2.3)
     * - Computes total sleep time, REI-a, acoustic band, signal quality
     *
     * @param sessionId    Session identifier; written verbatim into the result.
     * @param epochs       All 30-second epochs for the session, in chronological order.
     * @param events       All acoustic events for the session (pre-DB, may include awake-phase ones).
     * @return Pair of (NightSummary, list of event IDs to delete as awake-phase discards).
     *         The caller is responsible for deleting those IDs from the DB.
     */
    fun compute(
        sessionId: Long,
        epochs: List<SleepEpoch>,
        events: List<AcousticEvent>,
    ): Pair<NightSummary, List<Long>> {
        // Build a set of AWAKE epoch time windows [startMs, endMs)
        val awakeWindows: List<LongRange> = epochs
            .filter { it.phase == SleepPhase.AWAKE }
            .map { epoch ->
                val start = epoch.timestamp - EPOCH_DURATION_S * 1000L
                val end = epoch.timestamp
                start until end
            }

        // Identify events to discard (FR-2.3): midpoint falls in an AWAKE window
        val eventIdsToDiscard = mutableListOf<Long>()
        val keptEvents = mutableListOf<AcousticEvent>()
        for (event in events) {
            val midpoint = event.startUtc + event.durationMs / 2
            if (awakeWindows.any { midpoint in it }) {
                if (event.id != 0L) eventIdsToDiscard.add(event.id)
            } else {
                keptEvents.add(event)
            }
        }

        // Non-AWAKE epochs (sleep epochs)
        val sleepEpochs = epochs.filter { it.phase != SleepPhase.AWAKE }
        val sleepEpochCount = sleepEpochs.size

        // Total sleep time
        val totalSleepTimeMin = (sleepEpochCount * EPOCH_DURATION_S) / SECONDS_PER_MINUTE

        // REI-a: APNEA_LIKE events per hour of sleep (hypopnea counted separately)
        val apneaLikeEvents = keptEvents.filter { it.type == AcousticEventType.APNEA_LIKE }
        val hypopneaLikeEvents = keptEvents.filter { it.type == AcousticEventType.HYPOPNEA_LIKE }
        val apneaLikeCount = apneaLikeEvents.size
        val hypopneaLikeCount = hypopneaLikeEvents.size

        val totalSleepHours = totalSleepTimeMin / 60f
        val reiA = if (totalSleepHours > 0f) apneaLikeCount / totalSleepHours else 0f

        // Longest APNEA_LIKE or HYPOPNEA_LIKE event in seconds
        val longestEventS = (apneaLikeEvents + hypopneaLikeEvents)
            .maxOfOrNull { it.durationMs / 1000f } ?: 0f

        // Snore % of sleep: fraction of sleep epochs with hasSnore == true (× 100)
        val snoreEpochCount = sleepEpochs.count { it.hasSnore }
        val snorePctOfSleep = if (sleepEpochCount > 0) {
            snoreEpochCount.toFloat() / sleepEpochCount * 100f
        } else 0f

        // Mean dB-over-floor for SNORE_EPISODE events
        val snoreEvents = keptEvents.filter { it.type == AcousticEventType.SNORE_EPISODE }
        val meanSnoreDbOverFloor = if (snoreEvents.isNotEmpty()) {
            snoreEvents.map { it.meanDbOverFloor }.average().toFloat()
        } else 0f

        // Signal quality (FR-2.4): fraction of sleep epochs with breathingMarginDb < 6 dB
        val lowMarginEpochCount = sleepEpochs.count { it.breathingMarginDb < BREATHING_MARGIN_THRESHOLD_DB }
        val lowMarginFraction = if (sleepEpochCount > 0) {
            lowMarginEpochCount.toFloat() / sleepEpochCount
        } else 0f
        val signalQuality = when {
            lowMarginFraction > SIGNAL_QUALITY_LOW_THRESHOLD -> SignalQuality.LOW
            lowMarginFraction > SIGNAL_QUALITY_FAIR_THRESHOLD -> SignalQuality.FAIR
            else -> SignalQuality.GOOD
        }

        // Acoustic band from REI-a (FR-5.1)
        val acousticBand = reiAToAcousticBand(reiA)

        val summary = NightSummary(
            sessionId = sessionId,
            totalSleepTimeMin = totalSleepTimeMin,
            reiA = reiA,
            apneaLikeCount = apneaLikeCount,
            hypopneaLikeCount = hypopneaLikeCount,
            longestEventS = longestEventS,
            snorePctOfSleep = snorePctOfSleep,
            meanSnoreDbOverFloor = meanSnoreDbOverFloor,
            signalQuality = signalQuality,
            acousticBand = acousticBand,
        )

        return Pair(summary, eventIdsToDiscard)
    }

    /**
     * Map a REI-a value to an acoustic band per FR-5.1.
     *
     * Thresholds: < 5 → NONE, 5–14 → MILD, 15–29 → MODERATE, ≥ 30 → SEVERE
     */
    fun reiAToAcousticBand(reiA: Float): AcousticBand = when {
        reiA < 5f -> AcousticBand.NONE
        reiA < 15f -> AcousticBand.MILD
        reiA < 30f -> AcousticBand.MODERATE
        else -> AcousticBand.SEVERE
    }
}
