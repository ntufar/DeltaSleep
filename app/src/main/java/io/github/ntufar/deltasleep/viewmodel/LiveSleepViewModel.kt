package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.service.SleepTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val HISTORY_SIZE = 60  // 60 samples × 500 ms = 30 s of history

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSleepViewModel(
    app: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {

    val sessionId: Long = checkNotNull(savedState["sessionId"])
    private val db = (app as DeltaSleepApp).database

    // --- live sensor histories --------------------------------------------------

    private val _rmsHistory = kotlinx.coroutines.flow.MutableStateFlow<List<Float>>(emptyList())
    val rmsHistory: StateFlow<List<Float>> = _rmsHistory

    private val _zcrHistory = kotlinx.coroutines.flow.MutableStateFlow<List<Float>>(emptyList())
    val zcrHistory: StateFlow<List<Float>> = _zcrHistory

    private val _bandHistory = kotlinx.coroutines.flow.MutableStateFlow<List<Float>>(emptyList())
    val bandHistory: StateFlow<List<Float>> = _bandHistory

    // --- epoch-derived state ----------------------------------------------------

    private val epochs = db.epochDao().observeForSession(sessionId)

    val currentPhase: StateFlow<SleepPhase> = epochs
        .map { it.lastOrNull()?.phase ?: SleepPhase.AWAKE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepPhase.AWAKE)

    val hasRecentSnore: StateFlow<Boolean> = epochs
        .map { it.lastOrNull()?.hasSnore == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // --- elapsed time -----------------------------------------------------------

    val elapsedMs: StateFlow<Long> = db.sessionDao()
        .observeById(sessionId)
        .filterNotNull()
        .map { it.startTime }
        .flatMapLatest { startTime ->
            flow {
                while (true) {
                    emit(System.currentTimeMillis() - startTime)
                    delay(1_000)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // --- frame collection -------------------------------------------------------

    init {
        viewModelScope.launch {
            SleepTrackingService.liveFrame
                .filterNotNull()
                .collect { frame ->
                    _rmsHistory.value = (_rmsHistory.value + frame.rms).takeLast(HISTORY_SIZE)
                    _zcrHistory.value = (_zcrHistory.value + frame.zcr).takeLast(HISTORY_SIZE)
                    _bandHistory.value = (_bandHistory.value + frame.bandRatio).takeLast(HISTORY_SIZE)
                }
        }
    }

    // --- actions ----------------------------------------------------------------

    fun stopTracking() {
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
