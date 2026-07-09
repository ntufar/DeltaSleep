package io.github.ntufar.deltasleep.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.SignalQuality
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.viewmodel.SessionViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Magenta used for snore overlay in the hypnogram
private val SnoreLegendColor = Color(0xFFFF4081)

@Composable
fun SessionScreen(
    onBack: () -> Unit,
    vm: SessionViewModel = viewModel(),
) {
    val summary by vm.summary.collectAsState()
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? -> uri?.let { vm.exportCsv(it) } }

    if (summary == null) {
        CircularProgressIndicator(modifier = Modifier.fillMaxSize())
        return
    }

    val s = summary!!
    val totalHours = TimeUnit.MILLISECONDS.toHours(s.totalSleepMs)
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(s.totalSleepMs) % 60
    val sleepLabel = if (totalHours > 0) "${totalHours}h ${totalMinutes}m" else "${totalMinutes}m"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(8.dp))

        Text("Last Night", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatCard("Sleep time", sleepLabel, Modifier.weight(1f).height(88.dp))
            StatCard("Snore", "${s.snorePercent.toInt()}%", Modifier.weight(1f).height(88.dp))
            StatCard("Deep", "${s.deepPercent.toInt()}%", Modifier.weight(1f).height(88.dp))
        }

        Spacer(Modifier.height(24.dp))

        Text("Sleep stages", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        HypnogramChart(
            epochs = s.epochs,
            startMs = s.session.startTime,
            endMs = s.session.endTime ?: System.currentTimeMillis(),
            events = s.acousticEvents,
        )

        // Legend
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SleepPhase.entries.forEach { phase ->
                LegendChip(color = phase.color, label = phase.label)
            }
            LegendChip(color = SnoreLegendColor, label = "Snore")
            // Apnea markers legend — shown only when screening is on or events exist
            val hasApneaEvents = s.acousticEvents.any {
                it.type == AcousticEventType.APNEA_LIKE || it.type == AcousticEventType.HYPOPNEA_LIKE
            }
            if (s.screeningEnabled || hasApneaEvents) {
                LegendChip(color = Color(0xFFE53935), label = "Apnea-like")
                LegendChip(color = Color(0xFFFF9800), label = "Hypopnea-like")
            }
        }

        // Apnea stats for this session (shown if screening enabled and summary available)
        if (s.screeningEnabled && s.nightSummary != null) {
            val ns = s.nightSummary
            Spacer(Modifier.height(16.dp))
            Text("Apnea screening", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                StatCard(
                    "REI-a",
                    "%.1f/h".format(ns.reiA),
                    Modifier.weight(1f).height(88.dp),
                )
                StatCard(
                    "Signal",
                    ns.signalQuality.name,
                    Modifier.weight(1f).height(88.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        var rating by remember { mutableIntStateOf(0) }
        Text("How did you feel?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            (1..5).forEach { n ->
                val selected = rating == n
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { rating = n; vm.saveFeelRating(n) },
                ) {
                    Text(
                        text = n.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        val tsFormat = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }
        val exportTs = tsFormat.format(Date(s.session.startTime))
        Button(
            onClick = { csvLauncher.launch("deltasleep_${exportTs}.csv") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export CSV")
        }
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}
