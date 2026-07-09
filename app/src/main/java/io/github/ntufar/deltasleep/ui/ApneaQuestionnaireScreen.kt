package io.github.ntufar.deltasleep.ui

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.R
import io.github.ntufar.deltasleep.apnea.RiskModel
import io.github.ntufar.deltasleep.viewmodel.ApneaQuestionnaireViewModel

private val CardBgQ = Color(0xFF12192B)
private val MutedQ = Color(0xFF7A8FB5)
private val AccentQ = Color(0xFF42A5F5)
private val WarnQ = Color(0xFFE65100)

@Composable
fun ApneaQuestionnaireScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit = {},
    vm: ApneaQuestionnaireViewModel = viewModel(),
) {
    val form by vm.form.collectAsState()

    // Navigate when saved
    if (form.isSaved) {
        // Render the saved confirmation inline; caller may pop back or stay
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
            stringResource(R.string.apnea_questionnaire_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.apnea_questionnaire_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MutedQ,
        )

        // Prefill notice (FR-4.2)
        if (form.prefillApplied) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.apnea_questionnaire_prefill_caption),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = AccentQ,
            )
        }

        Spacer(Modifier.height(16.dp))

        // 8 STOP-BANG items
        QuestionItem(
            label = stringResource(R.string.apnea_q_snoring),
            checked = form.snoring,
            onCheckedChange = { vm.setSnoring(it) },
            hasPrefill = form.prefillApplied,
        )
        QuestionItem(
            label = stringResource(R.string.apnea_q_tiredness),
            checked = form.tiredness,
            onCheckedChange = { vm.setTiredness(it) },
        )
        QuestionItem(
            label = stringResource(R.string.apnea_q_observed),
            checked = form.observedApnea,
            onCheckedChange = { vm.setObservedApnea(it) },
            hasPrefill = form.prefillApplied,
        )
        QuestionItem(
            label = stringResource(R.string.apnea_q_pressure),
            checked = form.highPressure,
            onCheckedChange = { vm.setHighPressure(it) },
        )
        QuestionItem(
            label = stringResource(R.string.apnea_q_bmi),
            checked = form.bmiOver35,
            onCheckedChange = { vm.setBmiOver35(it) },
        )
        QuestionItem(
            label = stringResource(R.string.apnea_q_age),
            checked = form.ageOver50,
            onCheckedChange = { vm.setAgeOver50(it) },
        )
        QuestionItem(
            label = stringResource(R.string.apnea_q_neck),
            checked = form.neckOver40cm,
            onCheckedChange = { vm.setNeckOver40cm(it) },
        )
        QuestionItem(
            label = stringResource(R.string.apnea_q_gender),
            checked = form.maleGender,
            onCheckedChange = { vm.setMaleGender(it) },
        )

        Spacer(Modifier.height(16.dp))

        // Show score + band after save
        if (form.isSaved) {
            val band = RiskModel.stopBangBand(form.savedScore)
            val bandLabel = when (band) {
                RiskModel.StopBangBand.LOW -> "low"
                RiskModel.StopBangBand.INTERMEDIATE -> "intermediate"
                RiskModel.StopBangBand.HIGH -> "high"
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBgQ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.apnea_questionnaire_score_label, form.savedScore, bandLabel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = bandColor(band),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.apnea_questionnaire_citation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedQ,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSaved,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("View report")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.apnea_questionnaire_skip))
                }
                Button(
                    onClick = { vm.save() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.apnea_questionnaire_save))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QuestionItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hasPrefill: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBgQ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE0E8FF),
                )
                if (hasPrefill) {
                    Text(
                        "suggested",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentQ,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
        }
    }
}

private fun bandColor(band: RiskModel.StopBangBand): Color = when (band) {
    RiskModel.StopBangBand.LOW -> Color(0xFF4CAF50)
    RiskModel.StopBangBand.INTERMEDIATE -> Color(0xFFFF9800)
    RiskModel.StopBangBand.HIGH -> Color(0xFFE53935)
}
