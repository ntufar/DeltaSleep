package io.github.ntufar.deltasleep.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.settings.AppTheme
import io.github.ntufar.deltasleep.ui.theme.DeltaSleepTheme
import io.github.ntufar.deltasleep.viewmodel.ApneaQuestionnaireViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Structural regression test for the "STOP-BANG questionnaire is empty"
 * report: the real [ApneaQuestionnaireScreen] must render its title, all 8
 * STOP-BANG items, and its actions in the light theme.
 *
 * Note: the actual shipped bug was a hardcoded near-white label color that
 * made the items invisible on light cards. Pixel-contrast assertion of that
 * would need [androidx.compose.ui.test.captureToImage], which cannot force a
 * redraw under Robolectric (software renderer) in either graphics mode, so
 * this harness cannot guard contrast directly — verify label contrast on a
 * device when touching these rows. This test guards the symptom class (an
 * empty-looking questionnaire) at the structure level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestionnaireScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun questionnaire_rendersAllItemsInLightTheme() {
        val app = RuntimeEnvironment.getApplication() as DeltaSleepApp
        val vm = ApneaQuestionnaireViewModel(app)
        composeRule.setContent {
            DeltaSleepTheme(theme = AppTheme.LIGHT) {
                ApneaQuestionnaireScreen(onBack = {}, vm = vm)
            }
        }
        // viewModelScope posts on Dispatchers.Main; pump the main looper the
        // same way TrendsScreenTest does until the items compose, otherwise
        // the pending load blocks waitForIdle.
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 15_000
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            looper.idle()
            found = try {
                composeRule.onNodeWithText("STOP-BANG Questionnaire").assertExists()
                true
            } catch (_: AssertionError) {
                Thread.sleep(25)
                false
            }
        }
        assertTrue("questionnaire title never composed", found)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("STOP-BANG Questionnaire").assertIsDisplayed()
        listOf(
            "snore loudly",
            "tired",
            "stop breathing",
            "blood pressure",
            "BMI",
            "older than 50",
            "neck circumference",
            "male",
        ).forEach { fragment ->
            composeRule.onNodeWithText(fragment, substring = true).assertExists()
        }
        composeRule.onNodeWithText("Skip").assertExists()
        composeRule.onNodeWithText("Save questionnaire").assertExists()
    }
}
