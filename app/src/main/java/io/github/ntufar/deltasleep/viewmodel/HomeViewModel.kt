package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.apnea.ApneaPrefs
import io.github.ntufar.deltasleep.data.model.SleepSession
import io.github.ntufar.deltasleep.service.SleepTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as DeltaSleepApp).database
    private val apneaPrefs = ApneaPrefs(app)

    /** Whether to navigate to setup (explainer not shown or screening disabled) vs. the report hub. */
    fun shouldShowApneaSetup(): Boolean = !apneaPrefs.explainerShown || !apneaPrefs.screeningEnabled

    val sessions: StateFlow<List<SleepSession>> = db.sessionDao()
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isTracking: StateFlow<Boolean> = SleepTrackingService.isTracking
    val activeSessionId: StateFlow<Long> = SleepTrackingService.activeSessionId

    fun startTracking() {
        viewModelScope.launch {
            val sessionId = db.sessionDao().insert(
                SleepSession(startTime = System.currentTimeMillis())
            )
            val intent = Intent(getApplication(), SleepTrackingService::class.java).apply {
                action = SleepTrackingService.ACTION_START
                putExtra(SleepTrackingService.EXTRA_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(getApplication(), intent)
        }
    }

    fun stopTracking(sessionId: Long) {
        viewModelScope.launch {
            db.sessionDao().getById(sessionId)?.let { session ->
                db.sessionDao().update(session.copy(endTime = System.currentTimeMillis()))
            }
            getApplication<Application>().startService(
                Intent(getApplication(), SleepTrackingService::class.java).apply {
                    action = SleepTrackingService.ACTION_STOP
                }
            )
        }
    }

    /**
     * Delete ALL user data from every table and then VACUUM the database file.
     *
     * Deletion order respects foreign-key constraints (children before parents).
     * After deletion we issue a VACUUM checkpoint so SQLite truncates freed pages
     * from the file — best-effort overwrite-before-delete per FR-3.4.
     * (SQLite WAL mode may defer the actual page reclaim; for a true cryptographic
     * wipe the caller should also delete and re-create the DB file, or use SQLCipher.)
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
        }
    }
}
