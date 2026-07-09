package io.github.ntufar.deltasleep.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.R
import io.github.ntufar.deltasleep.apnea.RiskModel
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.RiskBand
import io.github.ntufar.deltasleep.data.model.SignalQuality
import io.github.ntufar.deltasleep.viewmodel.ApneaReportViewModel

private val CardBgR = Color(0xFF12192B)
private val MutedR = Color(0xFF7A8FB5)
private val AccentR = Color(0xFF42A5F5)
private val WarnR = Color(0xFFE65100)

@Composable
fun ApneaReportScreen(
    onBack: () -> Unit,
    onQuestionnaire: () -> Unit,
    onSetup: () -> Unit,
    vm: ApneaReportViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    val htmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri: Uri? -> uri?.let { vm.exportPhysicianReport(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.apnea_report_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))

        // Screening off state
        if (!state.screeningEnabled) {
            ReportCard {
                Text(
                    stringResource(R.string.apnea_report_screening_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedR,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.apnea_report_screening_off_button))
                }
            }
            DisclaimerCard()
            return@Column
        }

        // Risk band headline
        when (val rr = state.riskResult) {
            is RiskModel.RiskResult.NotEnoughData -> {
                ReportCard {
                    Text(
                        stringResource(R.string.apnea_report_not_enough_data, rr.nightsSoFar),
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentR,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.apnea_report_not_enough_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedR,
                    )
                }
            }
            is RiskModel.RiskResult.Result -> {
                RiskBandCard(rr)

                // What to do next (FR-5.4)
                if (rr.riskBand == RiskBand.ELEVATED || rr.riskBand == RiskBand.HIGH) {
                    Spacer(Modifier.height(8.dp))
                    WhatToDoNextCard()
                }
            }
            null -> {
                ReportCard {
                    Text(
                        stringResource(R.string.apnea_report_not_enough_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedR,
                    )
                }
            }
        }

        // REI-a trend chart
        if (state.recentSummaries.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ReportCard {
                Text(
                    stringResource(R.string.apnea_report_trend_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                ReiATrendChart(summaries = state.recentSummaries)
                Spacer(Modifier.height(8.dp))
                // Legend
                Row(verticalAlignment = Alignment.CenterVertically) {
                    legendDot(Color(0xFF4CAF50)); Text(" None  ", style = MaterialTheme.typography.labelSmall, color = MutedR)
                    legendDot(Color(0xFFFF9800)); Text(" Mild  ", style = MaterialTheme.typography.labelSmall, color = MutedR)
                    legendDot(Color(0xFFF44336)); Text(" Moderate  ", style = MaterialTheme.typography.labelSmall, color = MutedR)
                    legendDot(Color(0xFF7B1FA2)); Text(" Severe  ", style = MaterialTheme.typography.labelSmall, color = MutedR)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    legendDot(Color(0x664CAF50)); Text(" Low quality (excluded)", style = MaterialTheme.typography.labelSmall, color = MutedR)
                }
            }
        }

        // Latest night stats (FR-5.3)
        val latestSummary = state.recentSummaries.firstOrNull()
        if (latestSummary != null) {
            Spacer(Modifier.height(16.dp))
            ReportCard {
                Text(
                    stringResource(R.string.apnea_report_latest_night),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    SmallStatCard(
                        label = stringResource(R.string.apnea_report_longest_event, latestSummary.longestEventS),
                        modifier = Modifier.weight(1f),
                    )
                    SmallStatCard(
                        label = stringResource(R.string.apnea_report_snore_pct, latestSummary.snorePctOfSleep),
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.apnea_report_signal_quality, latestSummary.signalQuality.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = signalQualityColor(latestSummary.signalQuality),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Action buttons
        Button(
            onClick = { htmlLauncher.launch("deltasleep_apnea_report.html") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.apnea_report_export_html))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onQuestionnaire,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.apnea_report_questionnaire))
        }
        TextButton(
            onClick = onSetup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.apnea_report_setup))
        }

        // Disclaimer always at the bottom (R1.1.2)
        Spacer(Modifier.height(16.dp))
        DisclaimerCard()
        Spacer(Modifier.height(24.dp))
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun RiskBandCard(rr: RiskModel.RiskResult.Result) {
    val (color, label, explanation) = when (rr.riskBand) {
        RiskBand.LOW -> Triple(
            Color(0xFF4CAF50),
            stringResource(R.string.apnea_report_low),
            stringResource(R.string.apnea_report_low_explanation, rr.medianReiA),
        )
        RiskBand.ELEVATED -> Triple(
            Color(0xFFFF9800),
            stringResource(R.string.apnea_report_elevated),
            stringResource(R.string.apnea_report_elevated_explanation, rr.medianReiA),
        )
        RiskBand.HIGH -> Triple(
            Color(0xFFE53935),
            stringResource(R.string.apnea_report_high),
            stringResource(R.string.apnea_report_high_explanation, rr.medianReiA),
        )
    }

    ReportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.2f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MutedR)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.apnea_report_median_rei, rr.medianReiA),
            style = MaterialTheme.typography.bodySmall,
            color = AccentR,
        )
    }
}

@Composable
private fun WhatToDoNextCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1200)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.apnea_report_what_next_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = WarnR,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.apnea_report_what_next_body),
                style = MaterialTheme.typography.bodySmall,
                color = MutedR,
            )
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22E65100))
            .padding(12.dp),
    ) {
        Text(
            stringResource(R.string.apnea_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = WarnR,
        )
    }
}

@Composable
private fun ReportCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBgR),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SmallStatCard(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0E1A))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MutedR)
    }
}

@Composable
private fun legendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

// ── REI-a Trend chart (Canvas) ────────────────────────────────────────────────

@Composable
private fun ReiATrendChart(summaries: List<NightSummary>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        if (summaries.isEmpty()) return@Canvas

        val padL = 24.dp.toPx()
        val padR = 8.dp.toPx()
        val padT = 8.dp.toPx()
        val padB = 16.dp.toPx()
        val chartW = size.width - padL - padR
        val chartH = size.height - padT - padB

        val maxRei = (summaries.maxOfOrNull { it.reiA } ?: 30f).coerceAtLeast(30f)
        val barW = (chartW / summaries.size.toFloat()).coerceAtMost(24.dp.toPx())
        val gap = 2.dp.toPx()

        // Guideline at 5 and 15
        drawThresholdLine(padL, padT, padL + chartW, padT + chartH, 5f, maxRei, chartH, Color(0x446CAF50))
        drawThresholdLine(padL, padT, padL + chartW, padT + chartH, 15f, maxRei, chartH, Color(0x44FF9800))
        drawThresholdLine(padL, padT, padL + chartW, padT + chartH, 30f, maxRei, chartH, Color(0x44F44336))

        // Bars — oldest first (summaries are DESC, so we reverse)
        val ordered = summaries.reversed()
        ordered.forEachIndexed { i, s ->
            val barH = ((s.reiA / maxRei) * chartH).coerceAtLeast(1.dp.toPx())
            val x = padL + i * (chartW / ordered.size.toFloat())
            val y = padT + chartH - barH
            val isLow = s.signalQuality == SignalQuality.LOW
            val barColor = if (isLow) {
                acousticBandColor(s).copy(alpha = 0.3f)
            } else {
                acousticBandColor(s)
            }
            drawRect(
                color = barColor,
                topLeft = Offset(x + gap, y),
                size = Size(barW - gap * 2, barH),
            )
            // Hatch pattern for low-quality nights
            if (isLow) {
                val step = 4.dp.toPx()
                var hatchX = x + gap
                while (hatchX < x + barW - gap) {
                    drawLine(
                        color = Color(0x44FFFFFF),
                        start = Offset(hatchX, y),
                        end = Offset(hatchX, y + barH),
                        strokeWidth = 1.dp.toPx(),
                    )
                    hatchX += step
                }
            }
        }
    }
}

private fun DrawScope.drawThresholdLine(
    padL: Float, padT: Float, endX: Float, baseY: Float,
    value: Float, maxRei: Float, chartH: Float, color: Color,
) {
    val y = padT + chartH - (value / maxRei * chartH)
    drawLine(
        color = color,
        start = Offset(padL, y),
        end = Offset(endX, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
    )
}

private fun acousticBandColor(s: NightSummary): Color {
    val reiA = s.reiA
    return when {
        reiA < 5f -> Color(0xFF4CAF50)
        reiA < 15f -> Color(0xFFFF9800)
        reiA < 30f -> Color(0xFFF44336)
        else -> Color(0xFF7B1FA2)
    }
}

private fun signalQualityColor(q: SignalQuality): Color = when (q) {
    SignalQuality.GOOD -> Color(0xFF4CAF50)
    SignalQuality.FAIR -> Color(0xFFFF9800)
    SignalQuality.LOW -> Color(0xFFE53935)
}
