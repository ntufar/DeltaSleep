package io.github.ntufar.deltasleep.apnea

import android.content.Context
import io.github.ntufar.deltasleep.settings.SettingsStore

/**
 * Thin facade over [SettingsStore] for the apnea-screening feature flags.
 *
 * D-3 moved these flags into the consolidated settings store; this class
 * keeps its pre-D-3 API so existing callers are untouched. The legacy
 * "apnea_prefs" file is migrated by [SettingsStore] on first access.
 */
class ApneaPrefs(context: Context) {
    private val store = SettingsStore(context)

    /**
     * Whether acoustic sleep-apnea screening is enabled.
     * Default: FALSE (opt-in per R1.1.3).
     */
    var screeningEnabled: Boolean
        get() = store.apneaScreeningEnabled
        set(value) { store.apneaScreeningEnabled = value }

    /**
     * Whether the first-run explainer screen (methodology + disclaimer) has been shown.
     * The UI must show it before allowing screeningEnabled to be set to true.
     */
    var explainerShown: Boolean
        get() = store.apneaExplainerShown
        set(value) { store.apneaExplainerShown = value }

    /**
     * Whether the bed-partner caveat has been surfaced to the user (FR-8.3).
     * Must be shown once before or during the first screened night.
     */
    var bedPartnerCaveatShown: Boolean
        get() = store.apneaBedPartnerCaveatShown
        set(value) { store.apneaBedPartnerCaveatShown = value }

    companion object {
        const val FILE_NAME = "apnea_prefs"
    }
}
