package io.github.ntufar.deltasleep.settings

/**
 * Stable preference keys for the consolidated settings store (D-3).
 *
 * This file is the single documented home for every setting key. Key names
 * are frozen: renaming a key silently drops the user's stored value, and the
 * encrypted backup (C-3) includes exactly the entries under [FILE_NAME], so
 * any new setting must add its key here to be backup-covered.
 *
 * Backing file: SharedPreferences [FILE_NAME] (single file — one unit to
 * back up; a DataStore migration must preserve these key names verbatim).
 * Legacy file "apnea_prefs" is migrated into this store on first access
 * (see [SettingsStore]) and then left unused.
 */
object SettingsKeys {
    const val FILE_NAME = "deltasleep_settings"

    /** Mic sensitivity level id (see [MicSensitivity]). Default: normal. */
    const val MIC_SENSITIVITY = "mic_sensitivity"

    /** Snore detection on/off (PRD 2.4). Default: true. */
    const val SNORE_ENABLED = "snore_enabled"

    /** Acoustic apnea screening on/off; migrated from "apnea_prefs". Default: false. */
    const val APNEA_SCREENING_ENABLED = "apnea_screening_enabled"

    /** First-run explainer shown; migrated from "apnea_prefs". Default: false. */
    const val APNEA_EXPLAINER_SHOWN = "apnea_explainer_shown"

    /** Bed-partner caveat surfaced; migrated from "apnea_prefs". Default: false. */
    const val APNEA_BED_PARTNER_CAVEAT_SHOWN = "apnea_bed_partner_caveat_shown"

    /** App theme id (see [AppTheme]). Default: system. */
    const val THEME = "theme"

    /** Data retention id (see [Retention]). Default: 365 days. */
    const val RETENTION = "retention"

    /** Auto start/stop prompt on charge + flat phone (B-4 home). Default: false. */
    const val AUTO_TRACKING_ENABLED = "auto_tracking_enabled"

    /** Auto-tracking evening window start, "HH:mm". Default: "21:00". */
    const val AUTO_TRACKING_WINDOW_START = "auto_tracking_window_start"

    /** Auto-tracking window end, "HH:mm". Default: "03:00". */
    const val AUTO_TRACKING_WINDOW_END = "auto_tracking_window_end"

    /** Clock format id (see [ClockFormat]). Default: system. */
    const val CLOCK_FORMAT = "clock_format"

    /** User-set sleep need in hours; feeds the D-2 sleep score. Default: 8.0. */
    const val SLEEP_NEED_HOURS = "sleep_need_hours"

    /** All keys, in a fixed order for backup serialization (C-3). */
    val ALL: List<String> = listOf(
        MIC_SENSITIVITY,
        SNORE_ENABLED,
        APNEA_SCREENING_ENABLED,
        APNEA_EXPLAINER_SHOWN,
        APNEA_BED_PARTNER_CAVEAT_SHOWN,
        THEME,
        RETENTION,
        AUTO_TRACKING_ENABLED,
        AUTO_TRACKING_WINDOW_START,
        AUTO_TRACKING_WINDOW_END,
        CLOCK_FORMAT,
        SLEEP_NEED_HOURS,
    )
}
