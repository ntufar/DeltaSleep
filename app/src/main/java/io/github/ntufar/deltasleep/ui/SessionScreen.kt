package io.github.ntufar.deltasleep.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.viewmodel.SessionViewModel
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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Sleep time", "${totalHours}h ${totalMinutes}m", Modifier.weight(1f))
            StatCard("Snore", "${s.snorePercent.toInt()}%", Modifier.weight(1f))
            StatCard("Deep", "${s.deepPercent.toInt()}%", Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))

        Text("Sleep stages", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        HypnogramChart(
            epochs = s.epochs,
            startMs = s.session.startTime,
            endMs = s.session.endTime ?: System.currentTimeMillis(),
        )

        // Legend
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SleepPhase.entries.forEach { phase ->
                LegendChip(color = phase.color, label = phase.label)
            }
            LegendChip(color = SnoreLegendColor, label = "Snore")
        }

        Spacer(Modifier.height(24.dp))

        var rating by remember { mutableIntStateOf(0) }
        Text("How did you feel?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { n ->
                TextButton(
                    onClick = { rating = n; vm.saveFeelRating(n) },
                ) {
                    Text(
                        text = n.toString(),
                        style = if (rating == n) MaterialTheme.typography.titleLarge
                                else MaterialTheme.typography.bodyLarge,
                        color = if (rating == n) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { csvLauncher.launch("deltasleep_${s.session.id}.csv") },
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
