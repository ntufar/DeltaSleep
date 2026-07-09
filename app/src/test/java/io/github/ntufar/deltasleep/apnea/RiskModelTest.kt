package io.github.ntufar.deltasleep.apnea

import io.github.ntufar.deltasleep.data.model.AcousticBand
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.QuestionnaireResult
import io.github.ntufar.deltasleep.data.model.RiskBand
import io.github.ntufar.deltasleep.data.model.SignalQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskModelTest {

    // ─── STOP-BANG scoring ────────────────────────────────────────────────────

    private fun makeQuestionnaire(
        snoring: Boolean = false,
        tiredness: Boolean = false,
        observedApnea: Boolean = false,
        highPressure: Boolean = false,
        bmiOver35: Boolean = false,
        ageOver50: Boolean = false,
        neckOver40cm: Boolean = false,
        maleGender: Boolean = false,
    ) = QuestionnaireResult(
        dateUtc = 0L,
        snoring = snoring,
        tiredness = tiredness,
        observedApnea = observedApnea,
        highPressure = highPressure,
        bmiOver35 = bmiOver35,
        ageOver50 = ageOver50,
        neckOver40cm = neckOver40cm,
        maleGender = maleGender,
        score = 0, // will be computed separately
    )

    @Test fun stopBangScore_allFalse_isZero() {
        assertEquals(0, RiskModel.stopBangScore(makeQuestionnaire()))
    }

    @Test fun stopBangScore_allTrue_isEight() {
        assertEquals(
            8, RiskModel.stopBangScore(
                makeQuestionnaire(
                    snoring = true, tiredness = true, observedApnea = true,
                    highPressure = true, bmiOver35 = true, ageOver50 = true,
                    neckOver40cm = true, maleGender = true,
                )
            )
        )
    }

    @Test fun stopBangScore_threeItems_isThree() {
        assertEquals(
            3, RiskModel.stopBangScore(
                makeQuestionnaire(snoring = true, tiredness = true, bmiOver35 = true)
            )
        )
    }

    @Test fun stopBangBand_0_isLow() = assertEquals(RiskModel.StopBangBand.LOW, RiskModel.stopBangBand(0))
    @Test fun stopBangBand_2_isLow() = assertEquals(RiskModel.StopBangBand.LOW, RiskModel.stopBangBand(2))
    @Test fun stopBangBand_3_isIntermediate() = assertEquals(RiskModel.StopBangBand.INTERMEDIATE, RiskModel.stopBangBand(3))
    @Test fun stopBangBand_4_isIntermediate() = assertEquals(RiskModel.StopBangBand.INTERMEDIATE, RiskModel.stopBangBand(4))
    @Test fun stopBangBand_5_isHigh() = assertEquals(RiskModel.StopBangBand.HIGH, RiskModel.stopBangBand(5))
    @Test fun stopBangBand_8_isHigh() = assertEquals(RiskModel.StopBangBand.HIGH, RiskModel.stopBangBand(8))

    // ─── Risk matrix full coverage ────────────────────────────────────────────

    @Test fun matrix_none_low_isLow() =
        assertEquals(RiskBand.LOW, RiskModel.riskMatrix(AcousticBand.NONE, RiskModel.StopBangBand.LOW))

    @Test fun matrix_none_intermediate_isLow() =
        assertEquals(RiskBand.LOW, RiskModel.riskMatrix(AcousticBand.NONE, RiskModel.StopBangBand.INTERMEDIATE))

    @Test fun matrix_none_high_isElevated() =
        assertEquals(RiskBand.ELEVATED, RiskModel.riskMatrix(AcousticBand.NONE, RiskModel.StopBangBand.HIGH))

    @Test fun matrix_mild_low_isLow() =
        assertEquals(RiskBand.LOW, RiskModel.riskMatrix(AcousticBand.MILD, RiskModel.StopBangBand.LOW))

    @Test fun matrix_mild_intermediate_isElevated() =
        assertEquals(RiskBand.ELEVATED, RiskModel.riskMatrix(AcousticBand.MILD, RiskModel.StopBangBand.INTERMEDIATE))

    @Test fun matrix_mild_high_isElevated() =
        assertEquals(RiskBand.ELEVATED, RiskModel.riskMatrix(AcousticBand.MILD, RiskModel.StopBangBand.HIGH))

    @Test fun matrix_moderate_low_isElevated() =
        assertEquals(RiskBand.ELEVATED, RiskModel.riskMatrix(AcousticBand.MODERATE, RiskModel.StopBangBand.LOW))

    @Test fun matrix_moderate_intermediate_isHigh() =
        assertEquals(RiskBand.HIGH, RiskModel.riskMatrix(AcousticBand.MODERATE, RiskModel.StopBangBand.INTERMEDIATE))

    @Test fun matrix_moderate_high_isHigh() =
        assertEquals(RiskBand.HIGH, RiskModel.riskMatrix(AcousticBand.MODERATE, RiskModel.StopBangBand.HIGH))

    @Test fun matrix_severe_low_isHigh() =
        assertEquals(RiskBand.HIGH, RiskModel.riskMatrix(AcousticBand.SEVERE, RiskModel.StopBangBand.LOW))

    @Test fun matrix_severe_intermediate_isHigh() =
        assertEquals(RiskBand.HIGH, RiskModel.riskMatrix(AcousticBand.SEVERE, RiskModel.StopBangBand.INTERMEDIATE))

    @Test fun matrix_severe_high_isHigh() =
        assertEquals(RiskBand.HIGH, RiskModel.riskMatrix(AcousticBand.SEVERE, RiskModel.StopBangBand.HIGH))

    // ─── Not enough data ──────────────────────────────────────────────────────

    @Test fun computeRiskBand_fewerThan5Nights_returnsNotEnoughData() {
        val nights = List(4) { makeSummary(reiA = 10f, quality = SignalQuality.GOOD) }
        val result = RiskModel.computeRiskBand(nights, latestQuestionnaire = null)
        assertTrue(result is RiskModel.RiskResult.NotEnoughData)
        assertEquals(4, (result as RiskModel.RiskResult.NotEnoughData).nightsSoFar)
    }

    @Test fun computeRiskBand_lowQualityNightsExcluded() {
        // 3 GOOD + 3 LOW — only 3 qualify → NotEnoughData
        val goodNights = List(3) { makeSummary(reiA = 20f, quality = SignalQuality.GOOD) }
        val lowNights = List(3) { makeSummary(reiA = 20f, quality = SignalQuality.LOW) }
        val result = RiskModel.computeRiskBand(goodNights + lowNights, latestQuestionnaire = null)
        assertTrue(result is RiskModel.RiskResult.NotEnoughData)
    }

    @Test fun computeRiskBand_exactly5GoodNights_succeeds() {
        val nights = List(5) { makeSummary(reiA = 3f, quality = SignalQuality.GOOD) }
        val result = RiskModel.computeRiskBand(nights, latestQuestionnaire = null)
        assertTrue(result is RiskModel.RiskResult.Result)
    }

    // ─── Median logic ─────────────────────────────────────────────────────────

    @Test fun median_oddCount() {
        assertEquals(3f, RiskModel.median(listOf(1f, 2f, 3f, 4f, 5f)), 0.001f)
    }

    @Test fun median_evenCount() {
        assertEquals(2.5f, RiskModel.median(listOf(1f, 2f, 3f, 4f)), 0.001f)
    }

    @Test fun median_singleValue() {
        assertEquals(7f, RiskModel.median(listOf(7f)), 0.001f)
    }

    @Test fun median_unsortedInput() {
        assertEquals(3f, RiskModel.median(listOf(5f, 1f, 3f)), 0.001f)
    }

    // ─── Single bad night must NOT produce HIGH ───────────────────────────────

    @Test fun singleBadNight_cannotYieldHigh() {
        // One very high REI-a night among 4 normal ones: median is 5 → MILD → max ELEVATED with HIGH STOP-BANG
        val nights = listOf(
            makeSummary(reiA = 3f), makeSummary(reiA = 3f), makeSummary(reiA = 5f),
            makeSummary(reiA = 3f), makeSummary(reiA = 80f), // outlier
        )
        val result = RiskModel.computeRiskBand(nights, QuestionnaireResult(
            dateUtc=0, snoring=true, tiredness=true, observedApnea=true,
            highPressure=true, bmiOver35=true, ageOver50=false, neckOver40cm=false,
            maleGender=false, score=5
        ))
        val r = result as RiskModel.RiskResult.Result
        // Median of [3,3,3,5,80] = 3 → NONE → at most ELEVATED (with HIGH STOP-BANG)
        assertTrue(r.riskBand != RiskBand.HIGH || r.acousticBand == AcousticBand.MODERATE || r.acousticBand == AcousticBand.SEVERE)
        // Specifically: median=3 → NONE, STOP-BANG=HIGH → ELEVATED per matrix
        assertEquals(AcousticBand.NONE, r.acousticBand)
        assertEquals(RiskBand.ELEVATED, r.riskBand)
    }

    // ─── Missing questionnaire → INTERMEDIATE column ──────────────────────────

    @Test fun missingQuestionnaire_usesIntermediate() {
        // MODERATE acoustic + INTERMEDIATE STOP-BANG = HIGH per matrix
        val nights = List(5) { makeSummary(reiA = 20f, quality = SignalQuality.GOOD) }
        val result = RiskModel.computeRiskBand(nights, latestQuestionnaire = null) as RiskModel.RiskResult.Result
        assertNull(result.questionnaireBand)
        assertEquals(RiskBand.HIGH, result.riskBand) // MODERATE + INTERMEDIATE → HIGH
    }

    // ─── STOP-BANG prefill ────────────────────────────────────────────────────

    @Test fun prefill_fewerThan5Nights_returnsNull() {
        val nights = List(4) { makeSummary(reiA = 10f, snorePct = 20f) }
        assertNull(RiskModel.stopBangPrefill(nights))
    }

    @Test fun prefill_highSnorePct_suggestsSnoring() {
        val nights = List(5) { makeSummary(reiA = 2f, snorePct = 30f) }
        val (snoring, apnea) = RiskModel.stopBangPrefill(nights)!!
        assertTrue(snoring)
        assertTrue(!apnea)
    }

    @Test fun prefill_highReiA_suggestsObservedApnea() {
        val nights = List(5) { makeSummary(reiA = 8f, snorePct = 5f) }
        val (snoring, apnea) = RiskModel.stopBangPrefill(nights)!!
        assertTrue(!snoring)
        assertTrue(apnea)
    }

    @Test fun prefill_lowSnorePctAndLowReiA_suggestsNeither() {
        val nights = List(5) { makeSummary(reiA = 2f, snorePct = 5f) }
        val (snoring, apnea) = RiskModel.stopBangPrefill(nights)!!
        assertTrue(!snoring)
        assertTrue(!apnea)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun makeSummary(
        reiA: Float,
        quality: SignalQuality = SignalQuality.GOOD,
        snorePct: Float = 0f,
        sessionId: Long = System.currentTimeMillis(),
    ) = NightSummary(
        sessionId = sessionId,
        totalSleepTimeMin = 480,
        reiA = reiA,
        apneaLikeCount = (reiA * 8).toInt(),
        hypopneaLikeCount = 0,
        longestEventS = 20f,
        snorePctOfSleep = snorePct,
        meanSnoreDbOverFloor = 5f,
        signalQuality = quality,
        acousticBand = NightSummarizer.reiAToAcousticBand(reiA),
    )
}
