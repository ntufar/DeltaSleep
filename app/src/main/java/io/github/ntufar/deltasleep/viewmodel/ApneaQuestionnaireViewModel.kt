package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.apnea.RiskModel
import io.github.ntufar.deltasleep.data.model.QuestionnaireResult
import io.github.ntufar.deltasleep.data.model.SignalQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the STOP-BANG questionnaire screen (FR-4).
 *
 * Loads the latest saved result to pre-fill the form and applies measurement-based
 * suggestions for snoring/observedApnea when ≥ 5 qualifying nights exist (FR-4.2).
 */
class ApneaQuestionnaireViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as DeltaSleepApp).database

    data class FormState(
        val snoring: Boolean = false,
        val tiredness: Boolean = false,
        val observedApnea: Boolean = false,
        val highPressure: Boolean = false,
        val bmiOver35: Boolean = false,
        val ageOver50: Boolean = false,
        val neckOver40cm: Boolean = false,
        val maleGender: Boolean = false,
        /** True when snoring/observedApnea values came from measurements and have not been overridden. */
        val prefillApplied: Boolean = false,
        val isSaved: Boolean = false,
        val savedScore: Int = 0,
    )

    private val _form = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val latest = db.questionnaireResultDao().getLatest()
        val summaries = db.nightSummaryDao()
            .getRecentGoodFairNights(limit = 30, excludedQuality = SignalQuality.LOW)

        // Prefill suggestions from measurements (FR-4.2)
        val prefill = RiskModel.stopBangPrefill(summaries)

        _form.update { current ->
            if (latest != null) {
                // Load from previous answer; apply measurement prefill only for snoring/observed
                // if no previous answer exists — here a previous answer takes precedence.
                current.copy(
                    snoring = latest.snoring,
                    tiredness = latest.tiredness,
                    observedApnea = latest.observedApnea,
                    highPressure = latest.highPressure,
                    bmiOver35 = latest.bmiOver35,
                    ageOver50 = latest.ageOver50,
                    neckOver40cm = latest.neckOver40cm,
                    maleGender = latest.maleGender,
                    prefillApplied = false,
                )
            } else if (prefill != null) {
                // No prior answer — suggest from measurements
                current.copy(
                    snoring = prefill.first,
                    observedApnea = prefill.second,
                    prefillApplied = true,
                )
            } else {
                current
            }
        }
    }

    fun setSnoring(v: Boolean) = _form.update { it.copy(snoring = v) }
    fun setTiredness(v: Boolean) = _form.update { it.copy(tiredness = v) }
    fun setObservedApnea(v: Boolean) = _form.update { it.copy(observedApnea = v) }
    fun setHighPressure(v: Boolean) = _form.update { it.copy(highPressure = v) }
    fun setBmiOver35(v: Boolean) = _form.update { it.copy(bmiOver35 = v) }
    fun setAgeOver50(v: Boolean) = _form.update { it.copy(ageOver50 = v) }
    fun setNeckOver40cm(v: Boolean) = _form.update { it.copy(neckOver40cm = v) }
    fun setMaleGender(v: Boolean) = _form.update { it.copy(maleGender = v) }

    fun save() {
        val f = _form.value
        val result = QuestionnaireResult(
            dateUtc = System.currentTimeMillis(),
            snoring = f.snoring,
            tiredness = f.tiredness,
            observedApnea = f.observedApnea,
            highPressure = f.highPressure,
            bmiOver35 = f.bmiOver35,
            ageOver50 = f.ageOver50,
            neckOver40cm = f.neckOver40cm,
            maleGender = f.maleGender,
            score = listOf(
                f.snoring, f.tiredness, f.observedApnea, f.highPressure,
                f.bmiOver35, f.ageOver50, f.neckOver40cm, f.maleGender,
            ).count { it },
        )
        viewModelScope.launch {
            db.questionnaireResultDao().insert(result)
            _form.update { it.copy(isSaved = true, savedScore = result.score) }
        }
    }
}
