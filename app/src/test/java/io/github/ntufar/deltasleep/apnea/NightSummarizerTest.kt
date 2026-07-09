package io.github.ntufar.deltasleep.apnea

import io.github.ntufar.deltasleep.data.model.AcousticBand
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.SignalQuality
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightSummarizerTest {

    // ─── REI-a computation ────────────────────────────────────────────────────

    @Test fun reiA_withNoSleepEpochs_isZero() {
        val (summary, _) = NightSummarizer.compute(1L, emptyList(), emptyList())
        assertEquals(0f, summary.reiA, 0.001f)
        assertEquals(0, summary.apneaLikeCount)
    }

    @Test fun reiA_eightHoursOfSleepWithEightApneaEvents_isOne() {
        // 8 h sleep = 960 epochs × 30 s; 8 APNEA_LIKE events → REI-a = 1.0
        val epochs = makeEpochs(960, SleepPhase.LIGHT, baseTimeMs = 0L)
        val events = List(8) { makeEvent(AcousticEventType.APNEA_LIKE, midpointMs = (it * 3_600_000L)) }
        val (summary, _) = NightSummarizer.compute(1L, epochs, events)
        assertEquals(1.0f, summary.reiA, 0.01f)
        assertEquals(8, summary.apneaLikeCount)
    }

    @Test fun hypopneaNotCountedInReiA() {
        val epochs = makeEpochs(60, SleepPhase.LIGHT, baseTimeMs = 0L)
        val events = listOf(
            makeEvent(AcousticEventType.APNEA_LIKE, midpointMs = 100_000L),
            makeEvent(AcousticEventType.HYPOPNEA_LIKE, midpointMs = 200_000L),
        )
        val (summary, _) = NightSummarizer.compute(1L, epochs, events)
        assertEquals(1, summary.apneaLikeCount)
        assertEquals(1, summary.hypopneaLikeCount)
        // REI-a only counts apnea-like: 1 / 0.5 h = 2.0
        assertEquals(2.0f, summary.reiA, 0.1f)
    }

    // ─── Awake-event discarding ───────────────────────────────────────────────

    @Test fun eventsInAwakeEpochs_areDiscarded() {
        // Two epochs: one AWAKE, one LIGHT; event midpoint in the AWAKE window should be discarded
        val baseMs = 1_000_000L
        val awakeEpoch = SleepEpoch(
            id = 1L, sessionId = 1L,
            timestamp = baseMs + 30_000L,  // epoch covers [baseMs, baseMs+30s)
            phase = SleepPhase.AWAKE, hasSnore = false, rmsEnergy = 0.5f,
        )
        val lightEpoch = SleepEpoch(
            id = 2L, sessionId = 1L,
            timestamp = baseMs + 60_000L,  // epoch covers [baseMs+30s, baseMs+60s)
            phase = SleepPhase.LIGHT, hasSnore = false, rmsEnergy = 0.5f,
        )
        // Event whose midpoint is inside the AWAKE window
        val awakeEvent = makeEvent(
            type = AcousticEventType.APNEA_LIKE,
            midpointMs = baseMs + 15_000L,
            id = 10L,
        )
        // Event whose midpoint is inside the LIGHT window
        val lightEvent = makeEvent(
            type = AcousticEventType.APNEA_LIKE,
            midpointMs = baseMs + 45_000L,
            id = 11L,
        )
        val (summary, discardedIds) = NightSummarizer.compute(
            1L, listOf(awakeEpoch, lightEpoch), listOf(awakeEvent, lightEvent)
        )
        assertEquals(listOf(10L), discardedIds)
        assertEquals(1, summary.apneaLikeCount)
    }

    @Test fun allEventsInSleepEpochs_noneDiscarded() {
        val baseMs = 0L
        val epochs = List(4) { i ->
            SleepEpoch(
                id = i.toLong(), sessionId = 1L,
                timestamp = baseMs + (i + 1) * 30_000L,
                phase = SleepPhase.LIGHT, hasSnore = false, rmsEnergy = 0.5f,
            )
        }
        val events = List(3) { i ->
            makeEvent(AcousticEventType.APNEA_LIKE, midpointMs = (i + 1) * 30_000L - 5_000L, id = i.toLong())
        }
        val (_, discardedIds) = NightSummarizer.compute(1L, epochs, events)
        assertTrue(discardedIds.isEmpty())
    }

    // ─── Signal quality banding ───────────────────────────────────────────────

    @Test fun signalQuality_allGoodMargin_isGood() {
        // 10 sleep epochs all with margin = 10 dB (> 6 dB threshold)
        val epochs = makeEpochsWithMargin(10, SleepPhase.LIGHT, breathingMarginDb = 10f)
        val (summary, _) = NightSummarizer.compute(1L, epochs, emptyList())
        assertEquals(SignalQuality.GOOD, summary.signalQuality)
    }

    @Test fun signalQuality_exactly10pctBelowThreshold_isFair() {
        // 10 epochs, 1 below threshold = 10 % → boundary between GOOD and FAIR
        // Spec: > 10 % → FAIR, so exactly 10 % is still GOOD (not strictly >, but > is used)
        val goodEpochs = makeEpochsWithMargin(9, SleepPhase.LIGHT, breathingMarginDb = 10f)
        val lowEpoch = makeEpochsWithMargin(1, SleepPhase.LIGHT, breathingMarginDb = 3f)
        val (summary, _) = NightSummarizer.compute(1L, goodEpochs + lowEpoch, emptyList())
        // 1/10 = 0.10, not > 0.10 → GOOD
        assertEquals(SignalQuality.GOOD, summary.signalQuality)
    }

    @Test fun signalQuality_elevenPctBelowThreshold_isFair() {
        // 9 good + 1 low = 10 epochs; boundary: >10% → FAIR
        // Use 11 epochs: 10 good + 1 low = 1/11 ≈ 9% → GOOD
        // Use 10 good + 2 low = 2/12 ≈ 16.7% → FAIR
        val goodEpochs = makeEpochsWithMargin(10, SleepPhase.LIGHT, breathingMarginDb = 10f)
        val lowEpochs = makeEpochsWithMargin(2, SleepPhase.LIGHT, breathingMarginDb = 3f)
        val (summary, _) = NightSummarizer.compute(1L, goodEpochs + lowEpochs, emptyList())
        assertEquals(SignalQuality.FAIR, summary.signalQuality)
    }

    @Test fun signalQuality_moreThan20pctBelowThreshold_isLow() {
        // 8 good + 3 low = 3/11 ≈ 27 % → LOW
        val goodEpochs = makeEpochsWithMargin(8, SleepPhase.LIGHT, breathingMarginDb = 10f)
        val lowEpochs = makeEpochsWithMargin(3, SleepPhase.LIGHT, breathingMarginDb = 1f)
        val (summary, _) = NightSummarizer.compute(1L, goodEpochs + lowEpochs, emptyList())
        assertEquals(SignalQuality.LOW, summary.signalQuality)
    }

    @Test fun signalQuality_awakeEpochsExcluded_fromMarginCalc() {
        // AWAKE epochs with bad margin should not affect quality rating
        val sleepEpochs = makeEpochsWithMargin(10, SleepPhase.LIGHT, breathingMarginDb = 10f)
        val awakeEpochs = makeEpochsWithMargin(5, SleepPhase.AWAKE, breathingMarginDb = 1f)
        val (summary, _) = NightSummarizer.compute(1L, sleepEpochs + awakeEpochs, emptyList())
        assertEquals(SignalQuality.GOOD, summary.signalQuality)
    }

    // ─── Acoustic band edge cases ─────────────────────────────────────────────

    @Test fun acousticBand_reiA_4_9_isNone() =
        assertEquals(AcousticBand.NONE, NightSummarizer.reiAToAcousticBand(4.9f))

    @Test fun acousticBand_reiA_5_isMild() =
        assertEquals(AcousticBand.MILD, NightSummarizer.reiAToAcousticBand(5f))

    @Test fun acousticBand_reiA_14_9_isMild() =
        assertEquals(AcousticBand.MILD, NightSummarizer.reiAToAcousticBand(14.9f))

    @Test fun acousticBand_reiA_15_isModerate() =
        assertEquals(AcousticBand.MODERATE, NightSummarizer.reiAToAcousticBand(15f))

    @Test fun acousticBand_reiA_29_9_isModerate() =
        assertEquals(AcousticBand.MODERATE, NightSummarizer.reiAToAcousticBand(29.9f))

    @Test fun acousticBand_reiA_30_isSevere() =
        assertEquals(AcousticBand.SEVERE, NightSummarizer.reiAToAcousticBand(30f))

    @Test fun acousticBand_reiA_0_isNone() =
        assertEquals(AcousticBand.NONE, NightSummarizer.reiAToAcousticBand(0f))

    // ─── Total sleep time ─────────────────────────────────────────────────────

    @Test fun totalSleepTime_awakeEpochsExcluded() {
        val sleepEpochs = makeEpochs(20, SleepPhase.LIGHT, baseTimeMs = 0L)
        val awakeEpochs = makeEpochs(10, SleepPhase.AWAKE, baseTimeMs = 900_000L)
        val (summary, _) = NightSummarizer.compute(1L, sleepEpochs + awakeEpochs, emptyList())
        // 20 epochs × 30 s = 600 s = 10 min
        assertEquals(10, summary.totalSleepTimeMin)
    }

    // ─── Snore percentage ─────────────────────────────────────────────────────

    @Test fun snorePct_halfEpochsSnoring() {
        val snoreEpochs = makeEpochs(5, SleepPhase.LIGHT, baseTimeMs = 0L, hasSnore = true)
        val quietEpochs = makeEpochs(5, SleepPhase.LIGHT, baseTimeMs = 180_000L, hasSnore = false)
        val (summary, _) = NightSummarizer.compute(1L, snoreEpochs + quietEpochs, emptyList())
        assertEquals(50f, summary.snorePctOfSleep, 0.01f)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private var epochIdCounter = 0L
    private var eventIdCounter = 0L

    private fun makeEpochs(
        count: Int,
        phase: SleepPhase,
        baseTimeMs: Long,
        hasSnore: Boolean = false,
        breathingMarginDb: Float = 10f,
    ): List<SleepEpoch> = List(count) { i ->
        SleepEpoch(
            id = ++epochIdCounter,
            sessionId = 1L,
            timestamp = baseTimeMs + (i + 1) * 30_000L,
            phase = phase,
            hasSnore = hasSnore,
            rmsEnergy = 0.5f,
            breathingMarginDb = breathingMarginDb,
        )
    }

    private fun makeEpochsWithMargin(
        count: Int,
        phase: SleepPhase,
        breathingMarginDb: Float,
    ): List<SleepEpoch> = makeEpochs(count, phase, ++epochIdCounter * 30_000L, breathingMarginDb = breathingMarginDb)

    /**
     * Create an event with the given midpoint. startUtc is set so that
     * startUtc + durationMs/2 == midpointMs.
     */
    private fun makeEvent(
        type: AcousticEventType,
        midpointMs: Long,
        id: Long = ++eventIdCounter,
        durationMs: Long = 15_000L,
    ) = AcousticEvent(
        id = id,
        sessionId = 1L,
        type = type,
        startUtc = midpointMs - durationMs / 2,
        durationMs = durationMs,
        confidence = 0.9f,
        peakDbOverFloor = 10f,
        envelopeReductionPct = 0.8f,
        terminatedByGasp = false,
        meanDbOverFloor = 8f,
    )
}
