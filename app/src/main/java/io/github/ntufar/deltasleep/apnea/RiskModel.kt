package io.github.ntufar.deltasleep.apnea

import io.github.ntufar.deltasleep.data.model.AcousticBand
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.QuestionnaireResult
import io.github.ntufar.deltasleep.data.model.RiskBand
import io.github.ntufar.deltasleep.data.model.SignalQuality

private const val MIN_NIGHTS_FOR_TRENDING = 5
private const val SNORE_PCT_PREFILL_THRESHOLD = 10f
private const val REI_A_PREFILL_THRESHOLD = 5f

/**
 * Pure, unit-testable risk-model computations for the sleep-apnea screening feature.
 *
 * All functions are stateless and take plain data — no Room, no coroutines.
 *
 * ## STOP-BANG scoring (FR-4.3)
 * Source: Chung et al., Anesthesiology 2008; validated thresholds:
 *   0–2 → LOW risk, 3–4 → INTERMEDIATE risk, 5–8 → HIGH risk.
 *
 * ## Headline risk-band matrix (FR-5.2)
 * Rows = acoustic band (NONE/MILD/MODERATE/SEVERE),
 * Columns = STOP-BANG band (LOW / INTERMEDIATE / HIGH).
 * Missing questionnaire → treated as INTERMEDIATE column.
 *
 *          | LOW        | INTERMEDIATE | HIGH      |
 * ---------+------------+--------------+-----------|
 * NONE     | LOW        | LOW          | ELEVATED  |
 * MILD     | LOW        | ELEVATED     | ELEVATED  |
 * MODERATE | ELEVATED   | HIGH         | HIGH      |
 * SEVERE   | HIGH       | HIGH         | HIGH      |
 */
object RiskModel {

    // ─── STOP-BANG ───────────────────────────────────────────────────────────

    /** Three-level STOP-BANG band derived from a raw 0–8 score. */
    enum class StopBangBand { LOW, INTERMEDIATE, HIGH }

    /**
     * Compute the 0–8 STOP-BANG score from a [QuestionnaireResult].
     * Each YES answer counts as 1 point.
     */
    fun stopBangScore(q: QuestionnaireResult): Int {
        var score = 0
        if (q.snoring) score++
        if (q.tiredness) score++
        if (q.observedApnea) score++
        if (q.highPressure) score++
        if (q.bmiOver35) score++
        if (q.ageOver50) score++
        if (q.neckOver40cm) score++
        if (q.maleGender) score++
        return score
    }

    /**
     * Map a raw 0–8 STOP-BANG score to a [StopBangBand] per FR-4.3.
     *
     * 0–2 → LOW, 3–4 → INTERMEDIATE, 5–8 → HIGH
     */
    fun stopBangBand(score: Int): StopBangBand = when {
        score <= 2 -> StopBangBand.LOW
        score <= 4 -> StopBangBand.INTERMEDIATE
        else -> StopBangBand.HIGH
    }

    // ─── Headline risk band ──────────────────────────────────────────────────

    /** Result of the multi-night risk model computation. */
    sealed class RiskResult {
        /**
         * Not enough GOOD/FAIR nights to compute a reliable risk band.
         * @param nightsSoFar number of GOOD/FAIR nights seen so far.
         */
        data class NotEnoughData(val nightsSoFar: Int) : RiskResult()

        /**
         * Risk computation succeeded.
         * @param riskBand          Headline risk band for the user.
         * @param medianReiA        Median REI-a across qualifying nights.
         * @param acousticBand      Acoustic band derived from medianReiA.
         * @param questionnaireBand STOP-BANG band if a questionnaire is available, else null.
         */
        data class Result(
            val riskBand: RiskBand,
            val medianReiA: Float,
            val acousticBand: AcousticBand,
            val questionnaireBand: StopBangBand?,
        ) : RiskResult()
    }

    /**
     * Compute the headline risk band from recent night summaries and an optional
     * questionnaire result (FR-5.2).
     *
     * Only GOOD or FAIR nights are counted (LOW-quality nights excluded per FR-2.4).
     * Requires ≥ [MIN_NIGHTS_FOR_TRENDING] such nights; returns [RiskResult.NotEnoughData] otherwise.
     * Uses the median REI-a so a single outlier night can never produce HIGH (FR-5.2 guarantee).
     *
     * @param allNightSummaries All night summaries for the user (any order — sorted internally).
     * @param latestQuestionnaire The most recent questionnaire result, or null if not filled in.
     */
    fun computeRiskBand(
        allNightSummaries: List<NightSummary>,
        latestQuestionnaire: QuestionnaireResult?,
    ): RiskResult {
        val qualifyingNights = allNightSummaries
            .filter { it.signalQuality != SignalQuality.LOW }

        if (qualifyingNights.size < MIN_NIGHTS_FOR_TRENDING) {
            return RiskResult.NotEnoughData(nightsSoFar = qualifyingNights.size)
        }

        val medianReiA = median(qualifyingNights.map { it.reiA })
        val acousticBand = NightSummarizer.reiAToAcousticBand(medianReiA)

        val questionnaireBand = latestQuestionnaire?.let { q ->
            stopBangBand(stopBangScore(q))
        }
        // Missing questionnaire → treat as INTERMEDIATE per spec
        val effectiveBand = questionnaireBand ?: StopBangBand.INTERMEDIATE

        val riskBand = riskMatrix(acousticBand, effectiveBand)

        return RiskResult.Result(
            riskBand = riskBand,
            medianReiA = medianReiA,
            acousticBand = acousticBand,
            questionnaireBand = questionnaireBand,
        )
    }

    /**
     * Risk matrix (FR-5.2).
     *
     * Acoustic band (rows) × STOP-BANG band (cols) → RiskBand
     *
     *          | LOW      | INTERMEDIATE | HIGH     |
     * ---------+----------+--------------+----------|
     * NONE     | LOW      | LOW          | ELEVATED |
     * MILD     | LOW      | ELEVATED     | ELEVATED |
     * MODERATE | ELEVATED | HIGH         | HIGH     |
     * SEVERE   | HIGH     | HIGH         | HIGH     |
     */
    fun riskMatrix(acousticBand: AcousticBand, questionnaireBand: StopBangBand): RiskBand =
        when (acousticBand) {
            AcousticBand.NONE -> when (questionnaireBand) {
                StopBangBand.LOW, StopBangBand.INTERMEDIATE -> RiskBand.LOW
                StopBangBand.HIGH -> RiskBand.ELEVATED
            }
            AcousticBand.MILD -> when (questionnaireBand) {
                StopBangBand.LOW -> RiskBand.LOW
                StopBangBand.INTERMEDIATE, StopBangBand.HIGH -> RiskBand.ELEVATED
            }
            AcousticBand.MODERATE -> when (questionnaireBand) {
                StopBangBand.LOW -> RiskBand.ELEVATED
                StopBangBand.INTERMEDIATE, StopBangBand.HIGH -> RiskBand.HIGH
            }
            AcousticBand.SEVERE -> RiskBand.HIGH
        }

    // ─── STOP-BANG pre-fill helper (FR-4.2) ─────────────────────────────────

    /**
     * Pre-fill suggestions for the "Snoring" and "Observed apnea" STOP-BANG items
     * derived from ≥ [MIN_NIGHTS_FOR_TRENDING] nights of acoustic data (FR-4.2).
     *
     * @return null if fewer than [MIN_NIGHTS_FOR_TRENDING] qualifying nights exist.
     *         Otherwise a Pair of (suggestSnoring, suggestObservedApnea) — shown as
     *         pre-filled but user-overridable.
     */
    fun stopBangPrefill(
        allNightSummaries: List<NightSummary>,
    ): Pair<Boolean, Boolean>? {
        val qualifyingNights = allNightSummaries
            .filter { it.signalQuality != SignalQuality.LOW }

        if (qualifyingNights.size < MIN_NIGHTS_FOR_TRENDING) return null

        val medianSnorePct = median(qualifyingNights.map { it.snorePctOfSleep })
        val medianReiA = median(qualifyingNights.map { it.reiA })

        val suggestSnoring = medianSnorePct > SNORE_PCT_PREFILL_THRESHOLD
        val suggestObservedApnea = medianReiA >= REI_A_PREFILL_THRESHOLD

        return Pair(suggestSnoring, suggestObservedApnea)
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    /** Compute the median of a non-empty list of floats. */
    fun median(values: List<Float>): Float {
        require(values.isNotEmpty()) { "Cannot compute median of empty list" }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }
}
