package io.github.ntufar.deltasleep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.R
import io.github.ntufar.deltasleep.viewmodel.ApneaSetupViewModel

private val CardBg = Color(0xFF12192B)
private val MutedColor = Color(0xFF7A8FB5)
private val AccentColor = Color(0xFF42A5F5)
private val WarnColor = Color(0xFFE65100)
private val ErrorColor = Color(0xFFE53935)

@Composable
fun ApneaSetupScreen(
    onBack: () -> Unit,
    vm: ApneaSetupViewModel = viewModel(),
) {
    val screeningEnabled by vm.screeningEnabled.collectAsState()
    val isTracking by vm.isTracking.collectAsState()
    val testState by vm.testState.collectAsState()
    val breathingMarginDb by vm.breathingMarginDb.collectAsState()

    // Mark explainer as shown when composable enters composition
    DisposableEffect(Unit) {
        vm.markExplainerShown()
        onDispose { vm.cancelLevelTest() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.apnea_setup_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))

        // Disclaimer (R1.1.2, R1.1.3)
        DisclaimerBox(stringResource(R.string.apnea_disclaimer))
        Spacer(Modifier.height(16.dp))

        // What is measured
        SetupCard {
            SubHeading(stringResource(R.string.apnea_setup_what_is_measured))
            BodyText(stringResource(R.string.apnea_setup_what_desc))
        }

        // Bed-partner caveat (FR-8.3)
        SetupCard {
            SubHeading(stringResource(R.string.apnea_setup_bed_partner_title))
            BodyText(stringResource(R.string.apnea_setup_bed_partner_desc))
        }

        // Placement hints (FR-8.2)
        SetupCard {
            SubHeading(stringResource(R.string.apnea_setup_placement_title))
            BodyText(stringResource(R.string.apnea_setup_placement_desc))
        }

        // Level test (FR-8.2 SHOULD)
        SetupCard {
            SubHeading(stringResource(R.string.apnea_setup_level_test_title))
            BodyText(stringResource(R.string.apnea_setup_level_test_desc))
            Spacer(Modifier.height(12.dp))

            if (isTracking) {
                BodyText(stringResource(R.string.apnea_setup_tracking_active))
            } else {
                when (testState) {
                    ApneaSetupViewModel.TestState.IDLE -> {
                        Button(
                            onClick = { vm.startLevelTest() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.apnea_setup_level_test_start))
                        }
                    }
                    ApneaSetupViewModel.TestState.RUNNING -> {
                        Text(
                            stringResource(R.string.apnea_setup_level_test_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentColor,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (breathingMarginDb / 20f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
                            color = marginColor(breathingMarginDb),
                            trackColor = Color(0xFF1E2D4A),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "%.1f dB".format(breathingMarginDb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedColor,
                        )
                    }
                    ApneaSetupViewModel.TestState.DONE -> {
                        LinearProgressIndicator(
                            progress = { (breathingMarginDb / 20f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
                            color = marginColor(breathingMarginDb),
                            trackColor = Color(0xFF1E2D4A),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "%.1f dB — %s".format(
                                breathingMarginDb,
                                marginLabel(breathingMarginDb),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = marginColor(breathingMarginDb),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.cancelLevelTest() }) {
                            Text("Test again", color = AccentColor)
                        }
                    }
                }
            }
        }

        // Enable / disable switch
        SetupCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.apnea_setup_enable_label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.apnea_setup_enable_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedColor,
                    )
                }
                Switch(
                    checked = screeningEnabled,
                    onCheckedChange = { vm.setScreeningEnabled(it) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.apnea_setup_save))
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun SetupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MutedColor)
}

@Composable
private fun DisclaimerBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22E65100))
            .padding(12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = WarnColor,
        )
    }
}

// ── Margin helpers ────────────────────────────────────────────────────────────

private fun marginColor(db: Float): Color = when {
    db >= 10f -> Color(0xFF4CAF50)
    db >= 6f -> Color(0xFFFF9800)
    else -> Color(0xFFE53935)
}

@Composable
private fun marginLabel(db: Float): String = when {
    db >= 10f -> stringResource(R.string.apnea_setup_level_margin_good)
    db >= 6f -> stringResource(R.string.apnea_setup_level_margin_fair)
    else -> stringResource(R.string.apnea_setup_level_margin_poor)
}
