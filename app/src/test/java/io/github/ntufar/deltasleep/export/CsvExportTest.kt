package io.github.ntufar.deltasleep.export

import io.github.ntufar.deltasleep.apnea.NightSummarizer
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.SignalQuality
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.data.model.SleepSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExportTest {

    private val session = SleepSession(id = 7L, startTime = 1_000L, endTime = 2_000L)
    private val epoch = SleepEpoch(
        sessionId = 7L,
        timestamp = 1_500L,
        phase = SleepPhase.DEEP,
        hasSnore = true,
        rmsEnergy = 0.5f,
        breathPeriodS = 4f,
    )
    private val event = AcousticEvent(
        sessionId = 7L,
        type = AcousticEventType.SNORE_EPISODE,
        startUtc = 1_600L,
        durationMs = 2_000L,
        confidence = 0.9f,
        peakDbOverFloor = 10f,
        envelopeReductionPct = 0.5f,
        terminatedByGasp = false,
        meanDbOverFloor = 6f,
    )
    private val summary = NightSummary(
        sessionId = 7L,
        totalSleepTimeMin = 480,
        reiA = 3f,
        apneaLikeCount = 24,
        hypopneaLikeCount = 0,
        longestEventS = 20f,
        snorePctOfSleep = 10f,
        meanSnoreDbOverFloor = 5f,
        signalQuality = SignalQuality.GOOD,
        acousticBand = NightSummarizer.reiAToAcousticBand(3f),
    )

    @Test fun buildCsv_containsAllSectionsAndRows() {
        val csv = CsvExporter.buildCsv(session, listOf(epoch), listOf(event), summary)
        assertTrue(csv.contains("session_id,start_time_ms"))
        assertTrue(csv.contains("# acoustic_events"))
        assertTrue(csv.contains("# night_summary"))
        assertTrue(csv.contains("7,1000,2000,1500,DEEP,true"))
        assertTrue(csv.contains("SNORE_EPISODE"))
        assertTrue(csv.contains("7,480,3.0,24"))
    }

    @Test fun buildCsv_withoutSummary_omitsSummaryRow() {
        val csv = CsvExporter.buildCsv(session, emptyList(), emptyList(), null)
        assertTrue(csv.contains("# night_summary"))
        assertFalse(csv.contains("7,480,3.0,24"))
    }
}
