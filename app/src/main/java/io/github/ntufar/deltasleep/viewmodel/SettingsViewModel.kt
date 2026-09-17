package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.settings.AppSettings
import io.github.ntufar.deltasleep.settings.AppTheme
import io.github.ntufar.deltasleep.settings.AutoTrackingWindow
import io.github.ntufar.deltasleep.settings.ClockFormat
import io.github.ntufar.deltasleep.settings.MicSensitivity
import io.github.ntufar.deltasleep.settings.Retention
import io.github.ntufar.deltasleep.settings.RetentionPolicy
import io.github.ntufar.deltasleep.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs the consolidated D-3 settings screen. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as DeltaSleepApp).database
    private val store = SettingsStore(app)

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.snapshot())

    /**
     * Settings-screen preview (C-2): finished sessions the next purge would
     * remove under the current retention choice. Null until loaded.
     */
    private val _expiringCount = MutableStateFlow<Int?>(null)
    val expiringCount: StateFlow<Int?> = _expiringCount

    init {
        viewModelScope.launch { refreshExpiring(store.snapshot()) }
    }

    private suspend fun refreshExpiring(snapshot: AppSettings) {
        _expiringCount.value = withContext(Dispatchers.IO) {
            RetentionPolicy.countExpiring(db, System.currentTimeMillis(), snapshot.retention)
        }
    }

    private fun update(edit: SettingsStore.() -> Unit) {
        store.edit()
        viewModelScope.launch { refreshExpiring(store.snapshot()) }
    }

    fun setMicSensitivity(value: MicSensitivity) = update { micSensitivity = value }
    fun setSnoreEnabled(value: Boolean) = update { snoreEnabled = value }
    fun setApneaScreeningEnabled(value: Boolean) = update { apneaScreeningEnabled = value }
    fun setTheme(value: AppTheme) = update { theme = value }
    fun setRetention(value: Retention) = update { retention = value }
    fun setAutoTrackingEnabled(value: Boolean) = update { autoTrackingEnabled = value }
    fun setAutoTrackingWindowStart(value: String) = update {
        autoTrackingWindowStart = AutoTrackingWindow.sanitize(value, autoTrackingWindowStart)
    }
    fun setAutoTrackingWindowEnd(value: String) = update {
        autoTrackingWindowEnd = AutoTrackingWindow.sanitize(value, autoTrackingWindowEnd)
    }
    fun setClockFormat(value: ClockFormat) = update { clockFormat = value }
    fun setSleepNeedHours(value: Float) = update { sleepNeedHours = value }

    /**
     * Delete ALL user data from every table and then VACUUM the database file.
     *
     * Deletion order respects foreign-key constraints (children before parents).
     * After deletion we issue a VACUUM checkpoint so SQLite truncates freed pages
     * from the file — best-effort overwrite-before-delete per FR-3.4.
     */
    fun nukeAllData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Delete child tables before parents to satisfy FK constraints
                db.acousticEventDao().deleteAll()
                db.nightSummaryDao().deleteAll()
                db.questionnaireResultDao().deleteAll()
                db.epochDao().deleteAll()
                db.sessionDao().deleteAll()

                // VACUUM: instructs SQLite to rebuild the database file, releasing freed
                // pages back to the OS. This is the closest SQLite comes to overwriting
                // deleted content without a full file-level secure-erase pass.
                db.openHelper.writableDatabase.execSQL("VACUUM")
            }
            refreshExpiring(store.snapshot())
        }
    }
}
