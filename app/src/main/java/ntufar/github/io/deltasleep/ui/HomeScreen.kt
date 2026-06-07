package ntufar.github.io.deltasleep.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ntufar.github.io.deltasleep.data.model.SleepSession
import ntufar.github.io.deltasleep.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(
    onSessionTap: (Long) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val sessions by vm.sessions.collectAsState()
    val isTracking by vm.isTracking.collectAsState()
    var showNukeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("DeltaSleep", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = {
                    if (isTracking) vm.stopTracking().let { id ->
                        if (id >= 0) onSessionTap(id)
                    }
                    else vm.startTracking()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTracking) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(if (isTracking) "Stop Sleep" else "Start Sleep",
                    style = MaterialTheme.typography.titleLarge)
            }
        }

        Spacer(Modifier.height(32.dp))

        Text("Previous Sessions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions) { session ->
                SessionRow(session, onClick = { onSessionTap(session.id) })
            }
            if (sessions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { showNukeDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete all data", color = Color(0xFFE53935))
                    }
                }
            }
        }
    }

    if (showNukeDialog) {
        AlertDialog(
            onDismissRequest = { showNukeDialog = false },
            title = { Text("Delete all sleep data?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.nukeAllData(); showNukeDialog = false }) {
                    Text("Delete", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNukeDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionRow(session: SleepSession, onClick: () -> Unit) {
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val durationMs = (session.endTime ?: System.currentTimeMillis()) - session.startTime
    val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(fmt.format(Date(session.startTime)))
            Text("${hours}h ${minutes}m")
        }
    }
}
