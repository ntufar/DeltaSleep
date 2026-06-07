package ntufar.github.io.deltasleep.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ntufar.github.io.deltasleep.viewmodel.SessionViewModel
import java.util.concurrent.TimeUnit

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

        // Summary stat cards
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Sleep time", "${totalHours}h ${totalMinutes}m", Modifier.weight(1f))
            StatCard("Snore", "${s.snorePercent.toInt()}%", Modifier.weight(1f))
            StatCard("Deep", "${s.deepPercent.toInt()}%", Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))

        Text("Sleep stages", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        HypnogramChart(epochs = s.epochs)

        // Phase legend
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ntufar.github.io.deltasleep.data.model.SleepPhase.entries.forEach { phase ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .padding(2.dp)
                            .then(Modifier.height(12.dp).fillMaxWidth(0f))
                    ) { drawRect(phase.color) }
                    Text(phase.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Feel rating
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
