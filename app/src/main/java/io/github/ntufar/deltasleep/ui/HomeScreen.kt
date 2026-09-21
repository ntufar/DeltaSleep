package io.github.ntufar.deltasleep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.ntufar.deltasleep.data.model.SleepSession
import io.github.ntufar.deltasleep.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(
    onSessionTap: (Long) -> Unit,
    onActiveSession: (Long) -> Unit,
    onHelp: () -> Unit = {},
    onApnea: () -> Unit = {},
    onApneaSetup: () -> Unit = {},
    onSettings: () -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    val sessions by vm.sessions.collectAsState()
    val isTracking by vm.isTracking.collectAsState()
    val activeSessionId by vm.activeSessionId.collectAsState()

    // Auto-navigate to the active screen whenever tracking starts
    LaunchedEffect(activeSessionId) {
        if (activeSessionId >= 0L) onActiveSession(activeSessionId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "DeltaSleep",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Your sleep data stays in your bed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = onHelp) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help")
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (isTracking && activeSessionId >= 0L) onActiveSession(activeSessionId)
                else vm.startTracking()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTracking) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.primary,
            ),
            shape = CircleShape,
        ) {
            Icon(
                Icons.Filled.Bedtime,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                if (isTracking) "View Active Session" else "Start Sleep",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Apnea screening card (between start button and previous sessions)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (vm.shouldShowApneaSetup()) onApneaSetup() else onApnea()
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Apnea Screening",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Risk indication from breathing sounds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Previous Sessions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        PreviousSessionsCalendar(sessions = sessions, onSessionTap = onSessionTap)
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                fmt.format(Date(session.startTime)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${hours}h ${minutes}m",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Month calendar view of past sleep sessions. Days with a recorded session are
 * marked with a dot and are tappable; tapping navigates straight to that session,
 * or to a picker if more than one session started on the same calendar day.
 */
@Composable
private fun PreviousSessionsCalendar(
    sessions: List<SleepSession>,
    onSessionTap: (Long) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val sessionsByDay = remember(sessions, zone) {
        sessions.groupBy {
            Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate()
        }
    }
    var displayedMonth by remember { mutableStateOf(YearMonth.now(zone)) }
    var dayPickerSessions by remember { mutableStateOf<List<SleepSession>?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { displayedMonth = displayedMonth.minusMonths(1) }) {
                Text("‹", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "${displayedMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${displayedMonth.year}",
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = { displayedMonth = displayedMonth.plusMonths(1) }) {
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            for (dow in 0 until 7) {
                val label = DayOfWeek.of(if (dow == 0) 7 else dow)
                    .getDisplayName(TextStyle.NARROW, Locale.getDefault())
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val firstOfMonth = displayedMonth.atDay(1)
        // Sunday-first offset: DayOfWeek.SUNDAY.value == 7, so map to 0
        val leadingBlanks = firstOfMonth.dayOfWeek.value % 7
        val daysInMonth = displayedMonth.lengthOfMonth()
        val totalCells = leadingBlanks + daysInMonth
        val rows = (totalCells + 6) / 7
        val today = LocalDate.now(zone)

        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNumber = row * 7 + col - leadingBlanks + 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dayNumber in 1..daysInMonth) {
                            val date = displayedMonth.atDay(dayNumber)
                            val daySessions = sessionsByDay[date]
                            val hasSessions = !daySessions.isNullOrEmpty()
                            val isToday = date == today

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        color = when {
                                            hasSessions -> MaterialTheme.colorScheme.primary
                                            isToday -> MaterialTheme.colorScheme.surfaceVariant
                                            else -> Color.Transparent
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable(enabled = hasSessions) {
                                        val daySessionList = daySessions.orEmpty()
                                        if (daySessionList.size == 1) {
                                            onSessionTap(daySessionList.first().id)
                                        } else if (daySessionList.size > 1) {
                                            dayPickerSessions = daySessionList
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    dayNumber.toString(),
                                    color = if (hasSessions) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    dayPickerSessions?.let { daySessionList ->
        AlertDialog(
            onDismissRequest = { dayPickerSessions = null },
            title = { Text("Multiple sessions this day") },
            text = {
                Column {
                    daySessionList.forEach { session ->
                        SessionRow(
                            session,
                            onClick = {
                                dayPickerSessions = null
                                onSessionTap(session.id)
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dayPickerSessions = null }) { Text("Cancel") }
            },
        )
    }
}
