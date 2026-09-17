package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BreathingRateTest {

    @Test fun bpm_fourSecondPeriod_isFifteen() {
        assertEquals(15f, BreathingRate.bpm(4f)!!, 0.001f)
    }

    @Test fun bpm_nullPeriod_isNull() {
        assertNull(BreathingRate.bpm(null))
    }

    @Test fun bpm_zeroOrNegativePeriod_isNull() {
        assertNull(BreathingRate.bpm(0f))
        assertNull(BreathingRate.bpm(-2f))
    }

    @Test fun medianBpm_oddCount_isMiddleValue() {
        // Periods 3/4/5 s → 20/15/12 bpm; median is 15.
        val epochs = listOf(3f, 4f, 5f).map { makeEpoch(it) }
        assertEquals(15f, BreathingRate.medianBpm(epochs)!!, 0.001f)
    }

    @Test fun medianBpm_evenCount_averagesMiddleTwo() {
        // Periods 3/4/5/6 s → 20/15/12/10 bpm; median is (15+12)/2.
        val epochs = listOf(3f, 4f, 5f, 6f).map { makeEpoch(it) }
        assertEquals(13.5f, BreathingRate.medianBpm(epochs)!!, 0.001f)
    }

    @Test fun medianBpm_ignoresEpochsWithoutPeriod() {
        val epochs = listOf(makeEpoch(4f), makeEpoch(null), makeEpoch(4f))
        assertEquals(15f, BreathingRate.medianBpm(epochs)!!, 0.001f)
    }

    @Test fun medianBpm_noMeasuredPeriod_isNull() {
        assertNull(BreathingRate.medianBpm(emptyList()))
        assertNull(BreathingRate.medianBpm(listOf(makeEpoch(null), makeEpoch(null))))
    }

    private fun makeEpoch(periodS: Float?) = SleepEpoch(
        sessionId = 1L,
        timestamp = 0L,
        phase = SleepPhase.LIGHT,
        hasSnore = false,
        rmsEnergy = 0.01f,
        breathPeriodS = periodS,
    )
}
