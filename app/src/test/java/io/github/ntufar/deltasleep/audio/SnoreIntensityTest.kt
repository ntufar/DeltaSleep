package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnoreIntensityTest {

    @Test fun level_bucketBoundaries() {
        assertEquals(1, SnoreIntensity.level(0f))
        assertEquals(1, SnoreIntensity.level(5.9f))
        assertEquals(2, SnoreIntensity.level(6f))
        assertEquals(2, SnoreIntensity.level(11.9f))
        assertEquals(3, SnoreIntensity.level(12f))
        assertEquals(3, SnoreIntensity.level(17.9f))
        assertEquals(4, SnoreIntensity.level(18f))
        assertEquals(4, SnoreIntensity.level(23.9f))
        assertEquals(5, SnoreIntensity.level(24f))
        assertEquals(5, SnoreIntensity.level(40f))
    }

    @Test fun level_degenerateInput_readsAsQuietest() {
        assertEquals(1, SnoreIntensity.level(-3f))
        assertEquals(1, SnoreIntensity.level(Float.NaN))
        assertEquals(1, SnoreIntensity.level(Float.NEGATIVE_INFINITY))
    }

    @Test fun loudest_picksMaxOverSnoreEpisodesOnly() {
        val events = listOf(
            makeEvent(AcousticEventType.APNEA_LIKE, 99f),
            makeEvent(AcousticEventType.SNORE_EPISODE, 5f),
            makeEvent(AcousticEventType.SNORE_EPISODE, 13f),
            makeEvent(AcousticEventType.SNORE_EPISODE, 7f),
        )
        assertEquals(3, SnoreIntensity.loudest(events))
    }

    @Test fun loudest_noSnoreEvents_isNull() {
        assertNull(SnoreIntensity.loudest(emptyList()))
        assertNull(
            SnoreIntensity.loudest(
                listOf(makeEvent(AcousticEventType.APNEA_LIKE, 30f))
            )
        )
    }

    private fun makeEvent(type: AcousticEventType, peakDb: Float) = AcousticEvent(
        sessionId = 1L,
        type = type,
        startUtc = 0L,
        durationMs = 1_000L,
        confidence = 1f,
        peakDbOverFloor = peakDb,
        envelopeReductionPct = 0f,
        terminatedByGasp = false,
        meanDbOverFloor = peakDb,
    )
}
