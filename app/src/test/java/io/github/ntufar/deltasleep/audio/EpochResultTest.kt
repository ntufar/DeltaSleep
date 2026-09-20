package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.SleepPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochResultTest {

    private fun fullResult() = floatArrayOf(
        0.5f, // mean_rms
        0.1f, // rms_variance
        0.2f, // mean_zcr
        0.3f, // mean_band_ratio
        2f,   // phase_ordinal = DEEP
        1f,   // snore_flag
        3f,   // breathing_margin_db
        0.8f, // breathing_present_fraction
        4f,   // breath_period_s
        0.1f, // external_audio_fraction
        0.2f, // breath_period_cv
    )

    @Test fun shortArray_returnsNullInsteadOfThrowing() {
        assertNull(epochFromResult(floatArrayOf(), snoreDetectionEnabled = true))
        assertNull(epochFromResult(floatArrayOf(0.5f, 0.1f, 0.2f, 0.3f, 2f), snoreDetectionEnabled = true))
    }

    @Test fun sixElements_mapsCoreFields() {
        val epoch = epochFromResult(
            floatArrayOf(0.5f, 0.1f, 0.2f, 0.3f, 1f, 1f),
            snoreDetectionEnabled = true,
        )!!
        assertEquals(SleepPhase.LIGHT, epoch.phase)
        assertTrue(epoch.hasSnore)
        assertEquals(0.5f, epoch.rmsEnergy, 0.0001f)
        // Trailing indices absent on old native builds → safe defaults.
        assertEquals(0f, epoch.breathingMarginDb, 0.0001f)
        assertEquals(0f, epoch.breathingPresentFraction, 0.0001f)
        assertNull(epoch.breathPeriodS)
        assertEquals(0f, epoch.externalAudioFraction, 0.0001f)
    }

    @Test fun zeroRms_isAwakeRegardlessOfPhase() {
        val epoch = epochFromResult(fullResult().also { it[0] = 0f }, snoreDetectionEnabled = true)!!
        assertEquals(SleepPhase.AWAKE, epoch.phase)
    }

    @Test fun phaseOrdinal_isClampedToKnownPhases() {
        val high = epochFromResult(fullResult().also { it[4] = 99f }, snoreDetectionEnabled = true)!!
        assertEquals(SleepPhase.entries.last(), high.phase)
        val low = epochFromResult(fullResult().also { it[4] = -3f }, snoreDetectionEnabled = true)!!
        assertEquals(SleepPhase.AWAKE, low.phase)
    }

    @Test fun snoreToggleOff_dropsSnoreFlag() {
        val epoch = epochFromResult(fullResult(), snoreDetectionEnabled = false)!!
        assertFalse(epoch.hasSnore)
    }

    @Test fun fullArray_mapsTrailingFields() {
        val epoch = epochFromResult(fullResult(), snoreDetectionEnabled = true)!!
        assertEquals(3f, epoch.breathingMarginDb, 0.0001f)
        assertEquals(0.8f, epoch.breathingPresentFraction, 0.0001f)
        assertEquals(4f, epoch.breathPeriodS!!, 0.0001f)
        assertEquals(0.1f, epoch.externalAudioFraction, 0.0001f)
    }
}
