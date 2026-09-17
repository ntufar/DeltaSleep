package io.github.ntufar.deltasleep.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-3 contract: defaults, stable ids, mic-sensitivity mapping, retention
 * windows, and input sanitizing. These are the values the C-3 backup and
 * the A-5 DSP hook depend on — a silent change here breaks both.
 */
class AppSettingsTest {

    @Test fun defaults_matchSpec() {
        val s = AppSettings()
        assertEquals(MicSensitivity.NORMAL, s.micSensitivity)
        assertEquals(true, s.snoreEnabled)
        assertEquals(false, s.apneaScreeningEnabled)
        assertEquals(AppTheme.SYSTEM, s.theme)
        assertEquals(Retention.DAYS_365, s.retention)
        assertEquals(false, s.autoTrackingEnabled)
        assertEquals("21:00", s.autoTrackingWindowStart)
        assertEquals("03:00", s.autoTrackingWindowEnd)
        assertEquals(ClockFormat.SYSTEM, s.clockFormat)
        assertEquals(8f, s.sleepNeedHours, 0.0001f)
    }

    @Test fun micSensitivity_mapsToDbOffset() {
        // High sensitivity lowers the bar (negative offset catches quieter
        // snores); magnitude stays within the ±6 dB A-5 clamp.
        assertEquals(-6f, MicSensitivity.HIGH.toThresholdOffsetDb(), 0.0001f)
        assertEquals(0f, MicSensitivity.NORMAL.toThresholdOffsetDb(), 0.0001f)
        assertEquals(6f, MicSensitivity.LOW.toThresholdOffsetDb(), 0.0001f)
    }

    @Test fun fromId_fallsBackToDefaults() {
        assertEquals(MicSensitivity.NORMAL, MicSensitivity.fromId("bogus"))
        assertEquals(MicSensitivity.NORMAL, MicSensitivity.fromId(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromId("bogus"))
        assertEquals(Retention.DAYS_365, Retention.fromId("bogus"))
        assertEquals(Retention.DAYS_365, Retention.fromId(null))
        assertEquals(ClockFormat.SYSTEM, ClockFormat.fromId("bogus"))
    }

    @Test fun retention_toDays() {
        assertEquals(30L, Retention.DAYS_30.toDays())
        assertEquals(90L, Retention.DAYS_90.toDays())
        assertEquals(365L, Retention.DAYS_365.toDays())
        assertNull(Retention.NEVER.toDays())
    }

    @Test fun retentionPolicy_cutoff() {
        val now = 1_700_000_000_000L
        assertEquals(now - 30L * 86_400_000L, RetentionPolicy.cutoffMs(now, Retention.DAYS_30))
        assertEquals(now - 365L * 86_400_000L, RetentionPolicy.cutoffMs(now, Retention.DAYS_365))
        assertNull(RetentionPolicy.cutoffMs(now, Retention.NEVER))
    }

    @Test fun sleepNeed_clampsToRange() {
        assertEquals(3f, SleepNeed.clamp(0f), 0.0001f)
        assertEquals(12f, SleepNeed.clamp(99f), 0.0001f)
        assertEquals(7.5f, SleepNeed.clamp(7.5f), 0.0001f)
    }

    @Test fun autoTrackingWindow_sanitizes() {
        assertEquals("22:30", AutoTrackingWindow.sanitize("22:30", "21:00"))
        assertEquals("21:00", AutoTrackingWindow.sanitize("25:00", "21:00"))
        assertEquals("21:00", AutoTrackingWindow.sanitize(null, "21:00"))
        assertEquals("21:00", AutoTrackingWindow.sanitize("9pm", "21:00"))
        // Overnight wrap is legal (end < start).
        assertEquals("03:00", AutoTrackingWindow.sanitize("03:00", "21:00"))
    }

    @Test fun keys_areStableUniqueAndCovered() {
        // Frozen key names: the C-3 backup includes exactly these entries.
        assertTrue(SettingsKeys.ALL.contains(SettingsKeys.MIC_SENSITIVITY))
        assertTrue(SettingsKeys.ALL.contains(SettingsKeys.SNORE_ENABLED))
        assertTrue(SettingsKeys.ALL.contains(SettingsKeys.APNEA_SCREENING_ENABLED))
        assertTrue(SettingsKeys.ALL.contains(SettingsKeys.THEME))
        assertTrue(SettingsKeys.ALL.contains(SettingsKeys.RETENTION))
        assertTrue(SettingsKeys.ALL.contains(SettingsKeys.AUTO_TRACKING_ENABLED))
        assertTrue(SettingsKeys.ALL.contains(SettingsKeys.CLOCK_FORMAT))
        assertTrue(SettingsKeys.ALL.contains(SettingsKeys.SLEEP_NEED_HOURS))
        assertEquals(SettingsKeys.ALL.size, SettingsKeys.ALL.toSet().size)
    }
}
