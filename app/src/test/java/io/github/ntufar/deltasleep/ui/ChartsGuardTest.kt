package io.github.ntufar.deltasleep.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.trends.DayTotal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Charts subtract label padding from the canvas width, so absurdly narrow
 * windows must skip drawing instead of feeding negative sizes into draw
 * calls. Composes every label-padded chart at 10 dp wide: no crash means
 * the guards hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartsGuardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val epochs = List(4) { i ->
        SleepEpoch(
            sessionId = 1L,
            timestamp = i * 30_000L,
            phase = SleepPhase.LIGHT,
            hasSnore = i % 2 == 0,
            rmsEnergy = 0.1f,
            breathPeriodS = 4f,
        )
    }
    private val days = List(7) { i ->
        DayTotal(LocalDate.now().minusDays(i.toLong()), 120f)
    }

    @Test fun narrowCharts_doNotCrash() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(10.dp)) {
                Column {
                    WeekBars(days = days)
                    TrendLine(
                        points = listOf(10f, null, 20f),
                        yMin = 0f,
                        yMax = 100f,
                        gridLines = listOf(0f, 50f, 100f),
                        yLabel = { "%.0f".format(it) },
                        description = "guard probe",
                    )
                    WeekdayHeatmap(avgs = emptyMap<DayOfWeek, Float?>())
                    BedtimeScatter(
                        bedtimesMin = listOf(300),
                        wakesMin = listOf(700),
                        medianBedMin = 300,
                    )
                    BreathingChart(epochs = epochs)
                    HypnogramChart(epochs = epochs)
                }
            }
        }
        composeRule.waitForIdle()
    }
}
