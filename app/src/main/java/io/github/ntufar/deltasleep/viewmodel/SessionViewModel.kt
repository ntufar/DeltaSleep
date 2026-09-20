package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.apnea.ApneaPrefs
import io.github.ntufar.deltasleep.apnea.NightSummarizer
import io.github.ntufar.deltasleep.audio.ExternalAudio
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.NightSummary
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
    val acousticEvents: List<AcousticEvent> = emptyList(),
    val nightSummary: NightSummary? = null,
    val screeningEnabled: Boolean = false,
    /** Whole minutes of dominant-external-audio time (A-4), 30 s per epoch. */
    val externalAudioMin: Int = 0,
)

class SessionViewModel(
    app: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    // Missing/invalid navigation argument degrades to the not-found UI
    // instead of crashing in checkNotNull.
    private val sessionId: Long = savedState["sessionId"] ?: -1L
    private val db = (app as DeltaSleepApp).database
    private val apneaPrefs = ApneaPrefs(app)

    private val _summary = MutableStateFlow<SessionSummary?>(null)
    val summary: StateFlow<SessionSummary?> = _summary

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed

    private val _exportError = MutableStateFlow(false)
    val exportError: StateFlow<Boolean> = _exportError

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val session = if (sessionId < 0) null else db.sessionDao().getById(sessionId)
        if (session == null) {
            _loadFailed.value = true
            return
        }
        // A-1: display the smoothed phases (median filter, first-60-min REM
        // suppression, short-run merge); stored rows keep raw DSP verdicts.
        val rawEpochs = db.epochDao().getForSession(sessionId)
        val smoothed = NightSummarizer.smoothPhases(rawEpochs.map { it.phase })
        val epochs = rawEpochs.mapIndexed { i, e -> e.copy(phase = smoothed[i]) }
        val totalSleepMs = (session.endTime ?: System.currentTimeMillis()) - session.startTime
        val snoreCount = epochs.count { it.hasSnore }
        val deepCount = epochs.count { it.phase == SleepPhase.DEEP }
        val screeningEnabled = apneaPrefs.screeningEnabled
        // A-6: snore episodes load regardless of screening so intensity is
        // visible whenever snore detection is on; apnea types stay gated.
        val acousticEvents = if (screeningEnabled) {
            db.acousticEventDao().getForSession(sessionId)
        } else {
            db.acousticEventDao().getByTypeForSession(
                sessionId,
                AcousticEventType.SNORE_EPISODE,
            )
        }
        val nightSummary = if (screeningEnabled) {
            db.nightSummaryDao().getBySession(sessionId)
        } else {
            null
        }
        _summary.update {
            SessionSummary(
                session = session,
                epochs = epochs,
                totalSleepMs = totalSleepMs,
                snorePercent = if (epochs.isEmpty()) 0f else snoreCount * 100f / epochs.size,
                deepPercent = if (epochs.isEmpty()) 0f else deepCount * 100f / epochs.size,
                acousticEvents = acousticEvents,
                nightSummary = nightSummary,
                screeningEnabled = screeningEnabled,
                externalAudioMin = epochs.count { ExternalAudio.isExternal(it) } / 2,
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
            _exportError.value = false
            _exportError.value = try {
                !CsvExporter.export(getApplication(), uri, sessionId)
            } catch (_: Exception) {
                true
            }
        }
    }
}
