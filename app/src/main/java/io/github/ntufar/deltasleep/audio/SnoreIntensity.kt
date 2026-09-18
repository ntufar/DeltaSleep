package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType

/**
 * Snore intensity 1–5 (A-6), derived at read time from the DSP-recorded
 * peak band power per episode ([AcousticEvent.peakDbOverFloor]).
 *
 * Buckets (dB over adaptive noise floor):
 * 1: < 6, 2: 6–12, 3: 12–18, 4: 18–24, 5: ≥ 24.
 * No schema change — intensity is never persisted.
 */
object SnoreIntensity {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 5

    /** Peak dB-over-floor → intensity 1–5. Non-finite or sub-floor values read as 1. */
    fun level(peakDbOverFloor: Float): Int {
        if (!peakDbOverFloor.isFinite() || peakDbOverFloor < 6f) return 1
        if (peakDbOverFloor < 12f) return 2
        if (peakDbOverFloor < 18f) return 3
        if (peakDbOverFloor < 24f) return 4
        return 5
    }

    /**
     * Loudest snore intensity across [events], or null when there are
     * no SNORE_EPISODE events (e.g. a quiet night, or snore detection off).
     * Non-snore event types are ignored.
     */
    fun loudest(events: List<AcousticEvent>): Int? =
        events
            .filter { it.type == AcousticEventType.SNORE_EPISODE }
            .maxOfOrNull { level(it.peakDbOverFloor) }
}
