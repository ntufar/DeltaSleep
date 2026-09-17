package io.github.ntufar.deltasleep.settings

/**
 * Consolidated settings model (D-3): every user-visible setting in one
 * immutable snapshot with documented defaults and pure mapping helpers.
 *
 * Persistence lives in [SettingsStore]; this file holds no Android state so
 * the mappings stay JVM-unit-testable.
 */

/** Mic sensitivity → dB offset added to the DSP snore threshold (A-5 hook). */
enum class MicSensitivity(val id: String) {
    LOW("low"),
    NORMAL("normal"),
    HIGH("high");

    /**
     * Offset in dB applied to the snore RMS threshold. High sensitivity
     * lowers the bar (negative offset catches quieter snores); low raises
     * it. Magnitude is clamped to ±6 dB, mirroring the A-5 per-user offset
     * clamp so the two compose without exceeding it.
     */
    fun toThresholdOffsetDb(): Float = when (this) {
        LOW -> 6f
        NORMAL -> 0f
        HIGH -> -6f
    }

    companion object {
        fun fromId(id: String?): MicSensitivity =
            entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

enum class AppTheme(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    AMOLED_BLACK("amoled_black");

    companion object {
        fun fromId(id: String?): AppTheme =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** Data-retention window (C-2). NEVER disables auto-delete. */
enum class Retention(val id: String) {
    DAYS_30("30"),
    DAYS_90("90"),
    DAYS_365("365"),
    NEVER("never");

    /** Retention window in days, or null for [NEVER]. */
    fun toDays(): Long? = when (this) {
        DAYS_30 -> 30L
        DAYS_90 -> 90L
        DAYS_365 -> 365L
        NEVER -> null
    }

    companion object {
        fun fromId(id: String?): Retention =
            entries.firstOrNull { it.id == id } ?: DAYS_365
    }
}

enum class ClockFormat(val id: String) {
    SYSTEM("system"),
    HOUR_12("12h"),
    HOUR_24("24h");

    companion object {
        fun fromId(id: String?): ClockFormat =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

object SleepNeed {
    const val DEFAULT_HOURS = 8f
    const val MIN_HOURS = 3f
    const val MAX_HOURS = 12f

    /** Clamp free-form input (sliders, restored backups) into range. */
    fun clamp(hours: Float): Float = hours.coerceIn(MIN_HOURS, MAX_HOURS)
}

/** Auto-tracking window (B-4 home): "HH:mm" strings; overnight wrap allowed. */
object AutoTrackingWindow {
    const val DEFAULT_START = "21:00"
    const val DEFAULT_END = "03:00"

    private val HH_MM = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

    fun sanitize(value: String?, fallback: String): String =
        if (value != null && HH_MM.matches(value)) value else fallback
}

/**
 * One immutable snapshot of all D-3 settings. [SettingsStore.snapshot]
 * builds this from storage; Compose screens render from it.
 */
data class AppSettings(
    val micSensitivity: MicSensitivity = MicSensitivity.NORMAL,
    val snoreEnabled: Boolean = true,
    val apneaScreeningEnabled: Boolean = false,
    val apneaExplainerShown: Boolean = false,
    val apneaBedPartnerCaveatShown: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
    val retention: Retention = Retention.DAYS_365,
    val autoTrackingEnabled: Boolean = false,
    val autoTrackingWindowStart: String = AutoTrackingWindow.DEFAULT_START,
    val autoTrackingWindowEnd: String = AutoTrackingWindow.DEFAULT_END,
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,
    val sleepNeedHours: Float = SleepNeed.DEFAULT_HOURS,
)
