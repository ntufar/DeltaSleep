package io.github.ntufar.deltasleep.apnea

import android.content.Context

/**
 * Thin SharedPreferences wrapper for apnea-screening feature flags.
 *
 * All keys default to conservative/off values so the feature is opt-in (R1.1.3).
 * File name: "apnea_prefs" — isolated from the main service prefs.
 */
class ApneaPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether acoustic sleep-apnea screening is enabled.
     * Default: FALSE (opt-in per R1.1.3).
     */
    var screeningEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREENING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SCREENING_ENABLED, value).apply()

    /**
     * Whether the first-run explainer screen (methodology + disclaimer) has been shown.
     * The UI must show it before allowing screeningEnabled to be set to true.
     */
    var explainerShown: Boolean
        get() = prefs.getBoolean(KEY_EXPLAINER_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPLAINER_SHOWN, value).apply()

    /**
     * Whether the bed-partner caveat has been surfaced to the user (FR-8.3).
     * Must be shown once before or during the first screened night.
     */
    var bedPartnerCaveatShown: Boolean
        get() = prefs.getBoolean(KEY_BED_PARTNER_CAVEAT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_BED_PARTNER_CAVEAT_SHOWN, value).apply()

    companion object {
        const val FILE_NAME = "apnea_prefs"
        private const val KEY_SCREENING_ENABLED = "screening_enabled"
        private const val KEY_EXPLAINER_SHOWN = "explainer_shown"
        private const val KEY_BED_PARTNER_CAVEAT_SHOWN = "bed_partner_caveat_shown"
    }
}
