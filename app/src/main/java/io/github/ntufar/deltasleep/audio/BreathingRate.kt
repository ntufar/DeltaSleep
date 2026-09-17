package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.SleepEpoch

/**
 * Breathing-rate presentation logic (A-7).
 *
 * The DSP persists a mean autocorrelation breath period per epoch
 * ([SleepEpoch.breathPeriodS], seconds, NULL when breathing was never
 * present). This object converts it to breaths/min for display and
 * computes the median nightly rate — the per-night value the D-1 trends
 * screen will plot on its 30-day respiratory-rate chart.
 */
object BreathingRate {
    /** Breath period (s) → breaths/min, or null when unknown/non-positive. */
    fun bpm(periodS: Float?): Float? =
        periodS?.takeIf { it > 0f }?.let { 60f / it }

    /**
     * Median breathing rate over epochs with a measured period.
     * Null when no epoch has one (e.g. nights recorded before DB v3).
     */
    fun medianBpm(epochs: List<SleepEpoch>): Float? =
        medianBpmFromPeriods(epochs.mapNotNull { it.breathPeriodS })

    /**
     * Median breathing rate over raw breath periods (seconds). Used by the
     * trends repository, which loads periods without full epoch rows.
     */
    fun medianBpmFromPeriods(periods: List<Float>): Float? {
        val bpms = periods.mapNotNull { bpm(it) }.sorted()
        if (bpms.isEmpty()) return null
        val mid = bpms.size / 2
        return if (bpms.size % 2 == 1) bpms[mid]
        else (bpms[mid - 1] + bpms[mid]) / 2f
    }
}
