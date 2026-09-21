package io.github.ntufar.deltasleep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.settings.AppTheme
import io.github.ntufar.deltasleep.settings.ClockFormat
import io.github.ntufar.deltasleep.settings.MicSensitivity
import io.github.ntufar.deltasleep.settings.Retention
import io.github.ntufar.deltasleep.settings.SleepNeed
import io.github.ntufar.deltasleep.viewmodel.SettingsViewModel

/**
 * Consolidated settings screen (D-3): audio, screening, display, sleep,
 * auto-tracking home (B-4), and data controls in one place, backed by
 * SettingsStore with stable keys (SettingsKeys) covered by the C-3 backup.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onApneaSetup: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsState()
    val expiringCount by vm.expiringCount.collectAsState()
    var showNukeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))

        SectionTitle("Audio")
        OptionRow(
            title = "Mic sensitivity",
            summary = when (settings.micSensitivity) {
                MicSensitivity.LOW -> "Low — only loud, clear snores (+6 dB bar)"
                MicSensitivity.NORMAL -> "Normal — default threshold"
                MicSensitivity.HIGH -> "High — catches quiet snores (−6 dB bar)"
            },
        )
        MicSensitivity.entries.forEach { level ->
            RadioRow(
                label = level.id.replaceFirstChar { it.uppercase() },
                selected = settings.micSensitivity == level,
                onSelect = { vm.setMicSensitivity(level) },
            )
        }
        SwitchRow(
            title = "Snore detection",
            summary = "Record snore flags and episodes (PRD 2.4)",
            checked = settings.snoreEnabled,
            onChecked = vm::setSnoreEnabled,
        )

        SectionTitle("Screening")
        SwitchRow(
            title = "Apnea screening",
            summary = "Acoustic risk indication from breathing sounds",
            checked = settings.apneaScreeningEnabled,
            onChecked = {
                if (it && !settings.apneaExplainerShown) onApneaSetup()
                else vm.setApneaScreeningEnabled(it)
            },
        )
        TextButton(onClick = onApneaSetup, modifier = Modifier.fillMaxWidth()) {
            Text("Review setup and methodology")
        }

        SectionTitle("Display")
        OptionRow(title = "Theme", summary = "AMOLED black is pure black for night use")
        AppTheme.entries.forEach { theme ->
            RadioRow(
                label = when (theme) {
                    AppTheme.SYSTEM -> "System"
                    AppTheme.LIGHT -> "Light"
                    AppTheme.DARK -> "Dark"
                    AppTheme.AMOLED_BLACK -> "AMOLED black"
                },
                selected = settings.theme == theme,
                onSelect = { vm.setTheme(theme) },
            )
        }
        OptionRow(title = "Clock format", summary = null)
        ClockFormat.entries.forEach { format ->
            RadioRow(
                label = when (format) {
                    ClockFormat.SYSTEM -> "System"
                    ClockFormat.HOUR_12 -> "12-hour"
                    ClockFormat.HOUR_24 -> "24-hour"
                },
                selected = settings.clockFormat == format,
                onSelect = { vm.setClockFormat(format) },
            )
        }

        SectionTitle("Sleep")
        OptionRow(
            title = "Sleep need",
            summary = "Your nightly target — feeds the sleep score (D-2)",
        )
        Text(
            "%.1f h".format(settings.sleepNeedHours),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = settings.sleepNeedHours,
            onValueChange = vm::setSleepNeedHours,
            valueRange = SleepNeed.MIN_HOURS..SleepNeed.MAX_HOURS,
            steps = 17,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Auto tracking")
        SwitchRow(
            title = "Suggest start/stop",
            summary = "Prompt when charging and flat during the night window (opt-in)",
            checked = settings.autoTrackingEnabled,
            onChecked = vm::setAutoTrackingEnabled,
        )
        WindowTimeRow(
            label = "Window start",
            value = settings.autoTrackingWindowStart,
            onCommit = vm::setAutoTrackingWindowStart,
        )
        WindowTimeRow(
            label = "Window end",
            value = settings.autoTrackingWindowEnd,
            onCommit = vm::setAutoTrackingWindowEnd,
        )

        SectionTitle("Data")
        OptionRow(
            title = "Keep history",
            summary = expiringCount?.let {
                if (it == 0) "Nothing older than the window — nothing to purge"
                else "$it finished session(s) older than the window would be removed"
            },
        )
        Retention.entries.forEach { retention ->
            RadioRow(
                label = when (retention) {
                    Retention.DAYS_30 -> "30 days"
                    Retention.DAYS_90 -> "90 days"
                    Retention.DAYS_365 -> "365 days"
                    Retention.NEVER -> "Never delete"
                },
                selected = settings.retention == retention,
                onSelect = { vm.setRetention(retention) },
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Encrypted backup moves with you as a single file " +
                    "(planned, C-3) — nothing leaves the phone automatically.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { showNukeDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete all data", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showNukeDialog) {
        AlertDialog(
            onDismissRequest = { showNukeDialog = false },
            title = { Text("Delete all sleep data?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.nukeAllData(); showNukeDialog = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNukeDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun OptionRow(title: String, summary: String?) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WindowTimeRow(label: String, value: String, onCommit: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            placeholder = { Text("HH:mm") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(draft) }),
            modifier = Modifier.width(120.dp),
        )
    }
}
