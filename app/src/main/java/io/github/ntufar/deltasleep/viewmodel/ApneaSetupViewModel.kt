package io.github.ntufar.deltasleep.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ntufar.deltasleep.apnea.ApneaPrefs
import io.github.ntufar.deltasleep.audio.AudioCapture
import io.github.ntufar.deltasleep.audio.DspBridge
import io.github.ntufar.deltasleep.service.SleepTrackingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel for the apnea setup / explainer screen.
 *
 * Manages the 10-second "test breathing sound level" meter (FR-8.2).
 * The meter is only available when sleep tracking is NOT running.
 */
class ApneaSetupViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = ApneaPrefs(app)

    // ── Preferences state ──────────────────────────────────────────────────────

    private val _screeningEnabled = MutableStateFlow(prefs.screeningEnabled)
    val screeningEnabled: StateFlow<Boolean> = _screeningEnabled

    // ── Level-test state ──────────────────────────────────────────────────────

    enum class TestState { IDLE, RUNNING, DONE }

    private val _testState = MutableStateFlow(TestState.IDLE)
    val testState: StateFlow<TestState> = _testState

    /** Latest breathingMarginDb from processFrame index 4 during the test. */
    private val _breathingMarginDb = MutableStateFlow(0f)
    val breathingMarginDb: StateFlow<Float> = _breathingMarginDb

    private var testJob: Job? = null

    /** Whether sleep tracking is currently active (guards the test button). */
    val isTracking: StateFlow<Boolean> = SleepTrackingService.isTracking

    // ── Actions ───────────────────────────────────────────────────────────────

    fun setScreeningEnabled(enabled: Boolean) {
        prefs.screeningEnabled = enabled
        _screeningEnabled.value = enabled
    }

    fun markExplainerShown() {
        prefs.explainerShown = true
    }

    /**
     * Run a ~10 s sound-level test using AudioCapture + DspBridge.
     *
     * Safe only because we guard against isTracking (DSP Rust state is global).
     * 10 s = 1000 frames of 10 ms each.
     */
    fun startLevelTest() {
        if (SleepTrackingService.isTracking.value) return
        if (_testState.value == TestState.RUNNING) return

        testJob?.cancel()
        testJob = viewModelScope.launch {
            _testState.value = TestState.RUNNING
            _breathingMarginDb.value = 0f

            val dsp = DspBridge()
            val capture = AudioCapture()

            try {
                dsp.startSession()
                // Collect 1000 frames (10 s at 10 ms/frame), updating the margin live.
                // withTimeoutOrNull caps the test if audio stalls.
                withTimeoutOrNull(12_000L) {
                    capture.frames()
                        .take(1000)
                        .collect { frame ->
                            val result = dsp.processFrame(frame)
                            if (result.size > 4) {
                                _breathingMarginDb.value = result[4]
                            }
                        }
                }
            } catch (_: CancellationException) {
                // ViewModel scope cancelled — no action needed.
                throw CancellationException()
            } catch (_: Exception) {
                // Microphone error or similar — show whatever we got.
            } finally {
                _testState.value = TestState.DONE
            }
        }
    }

    fun cancelLevelTest() {
        testJob?.cancel()
        _testState.value = TestState.IDLE
        _breathingMarginDb.value = 0f
    }

    override fun onCleared() {
        super.onCleared()
        cancelLevelTest()
    }
}
