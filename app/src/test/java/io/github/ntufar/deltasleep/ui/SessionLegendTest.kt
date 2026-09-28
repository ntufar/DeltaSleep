package io.github.ntufar.deltasleep.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for issue #6 ("Sleep results screen is looking ugly"):
 * with apnea screening on, the hypnogram legend holds seven chips
 * (four phases + snore + two apnea markers). On a phone they overflowed
 * the viewport — the trailing chips collapsed into a sliver where
 * "Apnea-like" rendered as a vertical letter stack, ballooning the
 * section height and pushing the rest of the screen down.
 *
 * The legend must wrap (a [FlowRow]), keeping every chip laid out and
 * inside the viewport. Robolectric has no real fonts, so text measures
 * ~1 px per character and a phone-width window would never overflow;
 * the test therefore uses a 100 dp window, which reproduces the same
 * content-wider-than-viewport ratio and collapses the trailing chips
 * of a non-wrapping [Row] to zero width at the window edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionLegendTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun legend_allChips_laidOutInsideViewport() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(100.dp)) {
                SessionLegendRow(screeningEnabled = true, hasApneaEvents = true)
            }
        }
        composeRule.waitForIdle()

        val maxRight = with(composeRule.density) { 100.dp.toPx() } + 1f
        listOf("Awake", "Light", "Deep", "REM (est.)", "Snore", "Apnea-like", "Hypopnea-like")
            .forEach { label ->
                val node = composeRule.onNodeWithText(label).fetchSemanticsNode()
                val right = node.positionInRoot.x + node.size.width
                assertTrue(
                    "\"$label\" is not laid out inside the viewport " +
                        "(x=${node.positionInRoot.x}, width=${node.size.width}, max=$maxRight)",
                    node.size.width > 0 && right <= maxRight,
                )
            }
    }

    @Test fun legend_apneaChips_gatedOnScreeningOrEvents() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(100.dp)) {
                SessionLegendRow(screeningEnabled = false, hasApneaEvents = false)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Snore").fetchSemanticsNode()
        composeRule.onNodeWithText("Apnea-like").assertDoesNotExist()
        composeRule.onNodeWithText("Hypopnea-like").assertDoesNotExist()
    }
}
