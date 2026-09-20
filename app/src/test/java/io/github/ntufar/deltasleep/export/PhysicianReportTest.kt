package io.github.ntufar.deltasleep.export

import io.github.ntufar.deltasleep.apnea.NightSummarizer
import io.github.ntufar.deltasleep.apnea.RiskModel
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.QuestionnaireResult
import io.github.ntufar.deltasleep.data.model.SignalQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicianReportTest {

    private fun summary(sessionId: Long, reiA: Float) = NightSummary(
        sessionId = sessionId,
        totalSleepTimeMin = 480,
        reiA = reiA,
        apneaLikeCount = (reiA * 8).toInt(),
        hypopneaLikeCount = 0,
        longestEventS = 20f,
        snorePctOfSleep = 5f,
        meanSnoreDbOverFloor = 5f,
        signalQuality = SignalQuality.GOOD,
        acousticBand = NightSummarizer.reiAToAcousticBand(reiA),
    )

    private fun questionnaire() = QuestionnaireResult(
        dateUtc = 1_000L,
        snoring = true,
        tiredness = false,
        observedApnea = false,
        highPressure = false,
        bmiOver35 = false,
        ageOver50 = false,
        neckOver40cm = false,
        maleGender = false,
        score = 1,
    )

    @Test fun buildHtml_emptyState_rendersShellWithoutNightRows() {
        val html = PhysicianReport.buildHtml(emptyList(), null, null)
        assertTrue(html.contains("<h1>Sleep Apnea Risk Screening Report</h1>"))
        assertTrue(html.contains("No questionnaire completed yet."))
        assertTrue(html.contains("Risk band not available."))
        // Night rows render only with data (dates derive from sessionId).
        assertFalse(html.contains("<td>1970"))
    }

    @Test fun buildHtml_withResult_containsBandAndNights() {
        val nights = List(5) { summary(sessionId = it.toLong(), reiA = 3f) }
        val risk = RiskModel.computeRiskBand(nights, questionnaire())
        val html = PhysicianReport.buildHtml(nights, questionnaire(), risk)
        assertTrue(risk is RiskModel.RiskResult.Result)
        assertTrue(html.contains((risk as RiskModel.RiskResult.Result).riskBand.name))
        assertTrue(html.contains("Night-by-Night Summary"))
        assertTrue(html.contains("<td>1970"))
    }
}
