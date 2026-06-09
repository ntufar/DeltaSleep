package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.data.model.SleepSession
import io.github.ntufar.deltasleep.export.CsvExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionSummary(
    val session: SleepSession,
    val epochs: List<SleepEpoch>,
    val totalSleepMs: Long,
    val snorePercent: Float,
    val deepPercent: Float,
)

class SessionViewModel(
    app: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    private val sessionId: Long = checkNotNull(savedState["sessionId"])
    private val db = (app as DeltaSleepApp).database

    private val _summary = MutableStateFlow<SessionSummary?>(null)
    val summary: StateFlow<SessionSummary?> = _summary

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val session = db.sessionDao().getById(sessionId) ?: return
        val epochs = db.epochDao().getForSession(sessionId)
        val totalSleepMs = (session.endTime ?: System.currentTimeMillis()) - session.startTime
        val snoreCount = epochs.count { it.hasSnore }
        val deepCount = epochs.count { it.phase == SleepPhase.DEEP }
        _summary.update {
            SessionSummary(
                session = session,
                epochs = epochs,
                totalSleepMs = totalSleepMs,
                snorePercent = if (epochs.isEmpty()) 0f else snoreCount * 100f / epochs.size,
                deepPercent = if (epochs.isEmpty()) 0f else deepCount * 100f / epochs.size,
            )
        }
    }

    fun saveFeelRating(rating: Int) {
        viewModelScope.launch {
            val s = db.sessionDao().getById(sessionId) ?: return@launch
            db.sessionDao().update(s.copy(feelRating = rating))
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            CsvExporter.export(getApplication(), uri, sessionId)
        }
    }
}
