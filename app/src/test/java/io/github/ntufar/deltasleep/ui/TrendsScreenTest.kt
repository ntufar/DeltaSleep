package io.github.ntufar.deltasleep.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.data.model.SleepSession
import io.github.ntufar.deltasleep.viewmodel.ApneaReportViewModel
import io.github.ntufar.deltasleep.viewmodel.TrendsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression test for the "tap Trends → crash" report: composing the trends
 * screen used to corrupt the composition (negative slot-table index in
 * endRoot) via the non-local returns in its loading/empty states.
 *
 * Composes the real [TrendsScreen] against the real [DeltaSleepApp] database,
 * starting from the loading state exactly like the app does on navigation:
 * once empty (fresh install) and once with a seeded night.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrendsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun app(): DeltaSleepApp =
        RuntimeEnvironment.getApplication() as DeltaSleepApp

    private fun clearSessions() {
        val db = app().database
        runBlocking { db.sessionDao().deleteAll() }
    }

    /** One 8 h night ending this morning, with mixed phases/snore/breathing. */
    private fun seedOneNight() {
        val db = app().database
        val now = System.currentTimeMillis()
        val start = now - 9L * 3600 * 1000
        val end = now - 1L * 3600 * 1000
        runBlocking {
            db.sessionDao().deleteAll()
            val id = db.sessionDao().insert(SleepSession(startTime = start, endTime = end))
            val phases = listOf(SleepPhase.LIGHT, SleepPhase.DEEP, SleepPhase.LIGHT, SleepPhase.REM)
            for (i in 0 until 32) {
                db.epochDao().insert(
                    SleepEpoch(
                        sessionId = id,
                        timestamp = start + i * 30L * 1000,
                        phase = phases[i % phases.size],
                        hasSnore = i % 4 == 0,
                        rmsEnergy = 0.1f,
                        breathPeriodS = if (i % 8 == 0) null else 4.0f,
                    ),
                )
            }
        }
    }

    /**
     * Sets content immediately (data is still null → loading spinner, the same
     * first composition the app performs on navigation), then pumps the main
     * looper until the ViewModel's load lands and the screen settles.
     */
    private fun composeAndAwaitLoad(vm: TrendsViewModel) {
        composeRule.setContent { TrendsScreen(vm = vm) }
        // viewModelScope posts on Dispatchers.Main; Room works on its own threads.
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 15_000
        while (vm.data.value == null && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(25)
        }
        assertNotNull("TrendsViewModel.data never loaded", vm.data.value)
        composeRule.waitForIdle()
    }

    private fun assertVisibleAfterScroll(text: String) {
        composeRule.onNodeWithText(text).performScrollTo()
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun trends_emptyDb_showsEmptyState() {
        clearSessions()
        val vm = TrendsViewModel(app())
        composeAndAwaitLoad(vm)

        composeRule.onNodeWithText("Trends").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Not enough data yet — track a night to see trends.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun trends_seededNight_rendersCharts() {
        seedOneNight()
        val vm = TrendsViewModel(app())
        composeAndAwaitLoad(vm)

        composeRule.onNodeWithText("Trends").assertIsDisplayed()
        composeRule.onNodeWithText("Sleep this week").assertIsDisplayed()
        assertVisibleAfterScroll("Deep sleep % · 30 days")
        assertVisibleAfterScroll("Snore by weekday · 90 days")
        assertVisibleAfterScroll("Bedtime consistency · 30 days")
    }

    /**
     * The Report tab uses the same early-return-in-Column shape: with screening
     * off (the default) it must compose without corrupting the composition.
     */
    @Test
    fun apneaReport_screeningOff_rendersWithoutCrashing() {
        clearSessions()
        val vm = ApneaReportViewModel(app())
        composeRule.setContent {
            ApneaReportScreen(onBack = {}, onQuestionnaire = {}, onSetup = {}, vm = vm)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Apnea Risk Report").assertIsDisplayed()
    }
}
