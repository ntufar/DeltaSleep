package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.data.model.SleepSession
import io.github.ntufar.deltasleep.service.SleepTrackingService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as DeltaSleepApp).database

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

    fun nukeAllData() {
        viewModelScope.launch {
            db.epochDao().deleteAll()
            db.sessionDao().deleteAll()
        }
    }
}
