package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.apnea.ApneaPrefs
import io.github.ntufar.deltasleep.apnea.RiskModel
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.QuestionnaireResult
import io.github.ntufar.deltasleep.data.model.SignalQuality
import io.github.ntufar.deltasleep.export.PhysicianReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val MAX_TREND_NIGHTS = 30

data class ApneaReportState(
    val screeningEnabled: Boolean = false,
    val recentSummaries: List<NightSummary> = emptyList(),
    val latestQuestionnaire: QuestionnaireResult? = null,
    val riskResult: RiskModel.RiskResult? = null,
    val latestNightEvents: List<AcousticEvent> = emptyList(),
)

/**
 * ViewModel for the apnea risk report screen (FR-5.3/5.4, R1.1.2).
 */
class ApneaReportViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as DeltaSleepApp).database
    private val apneaPrefs = ApneaPrefs(app)

    private val _screeningEnabled = MutableStateFlow(apneaPrefs.screeningEnabled)

    /** All recent summaries (up to 30) for the trend chart — includes LOW quality (shown dimmed). */
    private val recentSummaries = db.nightSummaryDao()
        .observeRecent(MAX_TREND_NIGHTS)

    private val questionnaire = db.questionnaireResultDao()
        .observeAll()

    val state: StateFlow<ApneaReportState> = combine(
        _screeningEnabled,
        recentSummaries,
        questionnaire,
    ) { enabled, summaries, questionnaires ->
        val latestQ = questionnaires.firstOrNull()

        // Risk band needs ≥5 GOOD/FAIR nights
        val goodFair = summaries.filter { it.signalQuality != SignalQuality.LOW }
        val riskResult = RiskModel.computeRiskBand(goodFair, latestQ)

        // Latest night events: use the most recent summary's sessionId
        val latestSessionId = summaries.firstOrNull()?.sessionId
        val events = if (latestSessionId != null) {
            db.acousticEventDao().getForSession(latestSessionId)
        } else {
            emptyList()
        }

        ApneaReportState(
            screeningEnabled = enabled,
            recentSummaries = summaries,
            latestQuestionnaire = latestQ,
            riskResult = riskResult,
            latestNightEvents = events,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApneaReportState())

    fun exportPhysicianReport(uri: Uri) {
        viewModelScope.launch {
            PhysicianReport.export(
                context = getApplication(),
                uri = uri,
                riskResult = state.value.riskResult,
            )
        }
    }
}
