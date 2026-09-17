package io.github.ntufar.deltasleep.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single persistent home for all D-3 settings.
 *
 * Backed by one SharedPreferences file ([SettingsKeys.FILE_NAME]) so the
 * whole settings surface is a single unit for the C-3 backup. Exposes
 * synchronous getters (safe on the audio thread — SharedPreferences reads
 * are in-memory after first load) plus a [settings] flow for Compose.
 *
 * Uses SharedPreferences rather than DataStore deliberately: zero new
 * dependencies (offline build, minimal footprint), and the value types here
 * are trivially serializable. Key names are frozen per [SettingsKeys].
 *
 * First access migrates the legacy "apnea_prefs" file (screening flags)
 * into this store; the legacy file is cleared afterwards.
 */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(SettingsKeys.FILE_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(snapshot())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Cross-instance sync: several stores exist at once (activity theme,
     * settings screen, service). A write through any of them refreshes all
     * live instances. Held in a field — SharedPreferences keeps listeners
     * weakly, so an anonymous registration would go silent.
     */
    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init {
        migrateLegacyApneaPrefs()
        _settings.value = snapshot()
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
    }

    /** Re-read storage into an immutable snapshot. */
    fun snapshot(): AppSettings = AppSettings(
        micSensitivity = MicSensitivity.fromId(prefs.getString(SettingsKeys.MIC_SENSITIVITY, null)),
        snoreEnabled = prefs.getBoolean(SettingsKeys.SNORE_ENABLED, true),
        apneaScreeningEnabled = prefs.getBoolean(SettingsKeys.APNEA_SCREENING_ENABLED, false),
        apneaExplainerShown = prefs.getBoolean(SettingsKeys.APNEA_EXPLAINER_SHOWN, false),
        apneaBedPartnerCaveatShown =
            prefs.getBoolean(SettingsKeys.APNEA_BED_PARTNER_CAVEAT_SHOWN, false),
        theme = AppTheme.fromId(prefs.getString(SettingsKeys.THEME, null)),
        retention = Retention.fromId(prefs.getString(SettingsKeys.RETENTION, null)),
        autoTrackingEnabled = prefs.getBoolean(SettingsKeys.AUTO_TRACKING_ENABLED, false),
        autoTrackingWindowStart = AutoTrackingWindow.sanitize(
            prefs.getString(SettingsKeys.AUTO_TRACKING_WINDOW_START, null),
            AutoTrackingWindow.DEFAULT_START,
        ),
        autoTrackingWindowEnd = AutoTrackingWindow.sanitize(
            prefs.getString(SettingsKeys.AUTO_TRACKING_WINDOW_END, null),
            AutoTrackingWindow.DEFAULT_END,
        ),
        clockFormat = ClockFormat.fromId(prefs.getString(SettingsKeys.CLOCK_FORMAT, null)),
        sleepNeedHours = SleepNeed.clamp(
            prefs.getFloat(SettingsKeys.SLEEP_NEED_HOURS, SleepNeed.DEFAULT_HOURS),
        ),
    )

    private fun refresh() {
        _settings.value = snapshot()
    }

    var micSensitivity: MicSensitivity
        get() = MicSensitivity.fromId(prefs.getString(SettingsKeys.MIC_SENSITIVITY, null))
        set(value) {
            prefs.edit().putString(SettingsKeys.MIC_SENSITIVITY, value.id).apply()
            refresh()
        }

    var snoreEnabled: Boolean
        get() = prefs.getBoolean(SettingsKeys.SNORE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(SettingsKeys.SNORE_ENABLED, value).apply()
            refresh()
        }

    var apneaScreeningEnabled: Boolean
        get() = prefs.getBoolean(SettingsKeys.APNEA_SCREENING_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(SettingsKeys.APNEA_SCREENING_ENABLED, value).apply()
            refresh()
        }

    var apneaExplainerShown: Boolean
        get() = prefs.getBoolean(SettingsKeys.APNEA_EXPLAINER_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(SettingsKeys.APNEA_EXPLAINER_SHOWN, value).apply()
            refresh()
        }

    var apneaBedPartnerCaveatShown: Boolean
        get() = prefs.getBoolean(SettingsKeys.APNEA_BED_PARTNER_CAVEAT_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(SettingsKeys.APNEA_BED_PARTNER_CAVEAT_SHOWN, value).apply()
            refresh()
        }

    var theme: AppTheme
        get() = AppTheme.fromId(prefs.getString(SettingsKeys.THEME, null))
        set(value) {
            prefs.edit().putString(SettingsKeys.THEME, value.id).apply()
            refresh()
        }

    var retention: Retention
        get() = Retention.fromId(prefs.getString(SettingsKeys.RETENTION, null))
        set(value) {
            prefs.edit().putString(SettingsKeys.RETENTION, value.id).apply()
            refresh()
        }

    var autoTrackingEnabled: Boolean
        get() = prefs.getBoolean(SettingsKeys.AUTO_TRACKING_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(SettingsKeys.AUTO_TRACKING_ENABLED, value).apply()
            refresh()
        }

    var autoTrackingWindowStart: String
        get() = AutoTrackingWindow.sanitize(
            prefs.getString(SettingsKeys.AUTO_TRACKING_WINDOW_START, null),
            AutoTrackingWindow.DEFAULT_START,
        )
        set(value) {
            prefs.edit().putString(
                SettingsKeys.AUTO_TRACKING_WINDOW_START,
                AutoTrackingWindow.sanitize(value, autoTrackingWindowStart),
            ).apply()
            refresh()
        }

    var autoTrackingWindowEnd: String
        get() = AutoTrackingWindow.sanitize(
            prefs.getString(SettingsKeys.AUTO_TRACKING_WINDOW_END, null),
            AutoTrackingWindow.DEFAULT_END,
        )
        set(value) {
            prefs.edit().putString(
                SettingsKeys.AUTO_TRACKING_WINDOW_END,
                AutoTrackingWindow.sanitize(value, autoTrackingWindowEnd),
            ).apply()
            refresh()
        }

    var clockFormat: ClockFormat
        get() = ClockFormat.fromId(prefs.getString(SettingsKeys.CLOCK_FORMAT, null))
        set(value) {
            prefs.edit().putString(SettingsKeys.CLOCK_FORMAT, value.id).apply()
            refresh()
        }

    var sleepNeedHours: Float
        get() = SleepNeed.clamp(prefs.getFloat(SettingsKeys.SLEEP_NEED_HOURS, SleepNeed.DEFAULT_HOURS))
        set(value) {
            prefs.edit().putFloat(SettingsKeys.SLEEP_NEED_HOURS, SleepNeed.clamp(value)).apply()
            refresh()
        }

    /**
     * One-time migration from the pre-D-3 "apnea_prefs" file. Only copies
     * keys that are still at their legacy values when this store has no
     * value yet, then clears the legacy file so it cannot drift.
     */
    private fun migrateLegacyApneaPrefs() {
        val legacy = appContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        if (!legacy.all.isNullOrEmpty()) {
            val edit = prefs.edit()
            if (!prefs.contains(SettingsKeys.APNEA_SCREENING_ENABLED) &&
                legacy.contains(LEGACY_SCREENING)
            ) {
                edit.putBoolean(
                    SettingsKeys.APNEA_SCREENING_ENABLED,
                    legacy.getBoolean(LEGACY_SCREENING, false),
                )
            }
            if (!prefs.contains(SettingsKeys.APNEA_EXPLAINER_SHOWN) &&
                legacy.contains(LEGACY_EXPLAINER)
            ) {
                edit.putBoolean(
                    SettingsKeys.APNEA_EXPLAINER_SHOWN,
                    legacy.getBoolean(LEGACY_EXPLAINER, false),
                )
            }
            if (!prefs.contains(SettingsKeys.APNEA_BED_PARTNER_CAVEAT_SHOWN) &&
                legacy.contains(LEGACY_CAVEAT)
            ) {
                edit.putBoolean(
                    SettingsKeys.APNEA_BED_PARTNER_CAVEAT_SHOWN,
                    legacy.getBoolean(LEGACY_CAVEAT, false),
                )
            }
            edit.apply()
            legacy.edit().clear().apply()
        }
    }

    companion object {
        private const val LEGACY_FILE = "apnea_prefs"
        private const val LEGACY_SCREENING = "screening_enabled"
        private const val LEGACY_EXPLAINER = "explainer_shown"
        private const val LEGACY_CAVEAT = "bed_partner_caveat_shown"
    }
}
