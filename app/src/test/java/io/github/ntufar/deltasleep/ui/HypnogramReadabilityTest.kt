package io.github.ntufar.deltasleep.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/**
 * The night-summary hypnogram squeezed a full night into one screen width
 * (sub-pixel epochs) and painted Awake phases, apnea markers and snore
 * markers in near-identical reds. Guards the readability fix:
 * - apnea markers must stay visually distinct from Awake red and snore magenta
 * - a full night (~960 epochs + events) must compose inside a phone-width
 *   window (the chart scrolls internally instead of over-squeezing)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HypnogramReadabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun rgbDistance(a: Color, b: Color): Float {
        val dr = (a.red - b.red) * 255f
        val dg = (a.green - b.green) * 255f
        val db = (a.blue - b.blue) * 255f
        return sqrt(dr * dr + dg * dg + db * db)
    }

    @Test fun apneaMarker_distinctFromAwakeAndSnore() {
        val awake = SleepPhase.AWAKE.color
        val snore = Color(0xFFFF4081)
        assertTrue(
            "apnea marker too close to Awake red (dist=${rgbDistance(ApneaMarkerColor, awake)})",
            rgbDistance(ApneaMarkerColor, awake) > 50f,
        )
        assertTrue(
            "apnea marker too close to snore magenta (dist=${rgbDistance(ApneaMarkerColor, snore)})",
            rgbDistance(ApneaMarkerColor, snore) > 50f,
        )
    }

    @Test fun fullNight_composesAtPhoneWidth() {
        val phases = listOf(SleepPhase.DEEP, SleepPhase.LIGHT, SleepPhase.AWAKE, SleepPhase.REM)
        val epochs = List(960) { i ->
            SleepEpoch(
                sessionId = 1L,
                timestamp = i * 30_000L,
                phase = phases[i % phases.size],
                hasSnore = i % 3 == 0,
                rmsEnergy = 0.1f,
                breathPeriodS = 4f,
            )
        }
        val startMs = 1_700_000_000_000L
        val events = listOf(
            AcousticEvent(
                sessionId = 1L,
                type = AcousticEventType.APNEA_LIKE,
                startUtc = startMs + 3_600_000L,
                durationMs = 20_000L,
                confidence = 0.9f,
                peakDbOverFloor = 10f,
                envelopeReductionPct = 0.9f,
                terminatedByGasp = false,
                meanDbOverFloor = 8f,
            ),
            AcousticEvent(
                sessionId = 1L,
                type = AcousticEventType.SNORE_EPISODE,
                startUtc = startMs + 7_200_000L,
                durationMs = 5_000L,
                confidence = 0.9f,
                peakDbOverFloor = 12f,
                envelopeReductionPct = 0.5f,
                terminatedByGasp = false,
                meanDbOverFloor = 9f,
            ),
        )
        composeRule.setContent {
            Box(Modifier.requiredWidth(360.dp)) {
                HypnogramChart(
                    epochs = epochs,
                    startMs = startMs,
                    endMs = startMs + 8 * 3_600_000L,
                    events = events,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
