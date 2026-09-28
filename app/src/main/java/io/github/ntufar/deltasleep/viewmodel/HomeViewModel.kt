package io.github.ntufar.deltasleep.viewmodel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.apnea.ApneaPrefs
import io.github.ntufar.deltasleep.data.model.SleepSession
import io.github.ntufar.deltasleep.service.SleepTrackingService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /**
     * One-shot event: the mic permission is missing, so tracking was NOT started
     * (no session row, no service start). The UI should request RECORD_AUDIO and
     * retry [startTracking] on grant. Starting the foreground service without the
     * permission ends in stopSelf() without ever promoting, which the system
     * reports as ForegroundServiceDidNotStartInTimeException.
     *
     * A [Channel] (not a replay-less SharedFlow, whose tryEmit silently drops
     * the event when no collector is active yet) so an emission that races UI
     * collection is still delivered.
     */
    private val _micPermissionNeeded = Channel<Unit>(Channel.BUFFERED)
    val micPermissionNeeded: Flow<Unit> = _micPermissionNeeded.receiveAsFlow()

    fun startTracking() {
        if (ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _micPermissionNeeded.trySend(Unit)
            return
        }
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

}
