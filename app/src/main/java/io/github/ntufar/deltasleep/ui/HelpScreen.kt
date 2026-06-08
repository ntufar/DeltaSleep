package io.github.ntufar.deltasleep.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AwakeColor = Color(0xFFE53935)
private val LightColor = Color(0xFF42A5F5)
private val DeepColor  = Color(0xFF1565C0)
private val SnoreColor = Color(0xFFFF4081)
private val MutedColor = Color(0xFF7A8FB5)
private val CardBg     = Color(0xFF12192B)
private val BorderColor = Color(0xFF1E2D4A)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(4.dp))

        Text("User Guide", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Everything you need to track, review, and export your sleep.",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedColor,
        )

        Spacer(Modifier.height(32.dp))

        // ── Getting Started ──────────────────────────────────────────────────
        SectionHeader("Getting Started")

        HelpCard {
            SubHeading("Phone placement")
            BulletItem("Place the phone face-down on the mattress near your pillow, or on a nightstand within 50–80 cm.")
            BulletItem("Do not put the phone under a pillow — muffled audio reduces accuracy.")
            BulletItem("Make sure the microphone port (usually the bottom edge) is not covered by a thick case.")
        }

        HelpCard {
            SubHeading("Permissions")
            PermRow("RECORD_AUDIO", "Analyses room sounds for sleep phases and snore detection. Raw audio is never saved.")
            PermRow("FOREGROUND_SERVICE", "Keeps tracking alive while the screen is off.")
            PermRow("POST_NOTIFICATIONS", "Shows the persistent tracking notification so you can stop from the shade.")
        }

        // ── Starting a Session ───────────────────────────────────────────────
        SectionHeader("Starting a Session")

        HelpCard {
            SubHeading("How to start")
            StepItem(1, "Open DeltaSleep and tap Start Sleep.")
            StepItem(2, "The app calibrates to your room noise for the first few seconds.")
            StepItem(3, "Tracking begins and the active screen opens automatically.")
            StepItem(4, "The screen stays on — you can watch the live charts before falling asleep.")
        }

        // ── Active Screen ────────────────────────────────────────────────────
        SectionHeader("Active Screen")

        HelpCard {
            SubHeading("Phase badge & timer")
            BodyText("The badge at the top shows your current sleep phase. The large timer counts elapsed time since you started.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhasePill("AWAKE", AwakeColor)
                PhasePill("LIGHT", LightColor)
                PhasePill("DEEP", DeepColor)
            }
        }

        HelpCard {
            SubHeading("Live signal — last 3 minutes")
            SignalLegendRow(Color(0xFF00E676), "Audio level", "Overall mic energy. Spikes on movement or speech.")
            SignalLegendRow(Color(0xFF40C4FF), "Sound texture", "Zero-crossing rate. High = high-frequency sounds like breathing or rustling.")
            SignalLegendRow(Color(0xFFFFAB40), "Snore band", "Spectral power in 20–300 Hz. Elevated when snore-like sounds are present.")
        }

        HelpCard {
            SubHeading("Phase history & snore events")
            BodyText("These two charts scroll horizontally as your night progresses. Each column is one 30-second epoch.")
            Spacer(Modifier.height(10.dp))
            PhaseLegendRow()
            Spacer(Modifier.height(6.dp))
            BodyText("A pink overlay on a phase column means snoring was detected in that epoch.")
            Spacer(Modifier.height(10.dp))
            BodyText("The Snore events chart shows filled circles for epochs with snoring and hollow circles for quiet epochs.")
        }

        HelpCard {
            SubHeading("Audio intensity")
            BodyText("A bar chart of RMS energy per epoch. Useful for identifying periods of movement or external noise that might affect phase classification.")
        }

        // ── Stopping a Session ───────────────────────────────────────────────
        SectionHeader("Stopping a Session")

        HelpCard {
            SubHeading("How to stop")
            BulletItem("Tap the red Stop Tracking button at the bottom of the active screen.")
            BulletItem("Or tap Stop in the persistent notification in your notification shade.")
            Spacer(Modifier.height(8.dp))
            InfoBox("Crash recovery: the app writes to the database every 30 seconds. A crash loses at most the current unfinished epoch.")
        }

        // ── Session Results ──────────────────────────────────────────────────
        SectionHeader("Session Results")

        HelpCard {
            SubHeading("Summary stats")
            StatInfoRow("Sleep time", "Total duration from start to stop.")
            StatInfoRow("Snore %", "Percentage of epochs where snoring was detected.")
            StatInfoRow("Deep %", "Percentage of epochs classified as deep sleep.")
        }

        HelpCard {
            SubHeading("Reading the hypnogram")
            BodyText("The hypnogram is a colour-coded bar chart of your sleep stages over time. The x-axis shows clock time; each colour block is one 30-second epoch.")
            Spacer(Modifier.height(10.dp))
            PhaseLegendRow()
            Spacer(Modifier.height(8.dp))
            BodyText("Swipe left and right to scroll through the full night.")
        }

        HelpCard {
            SubHeading("Feel rating")
            BodyText("Tap 1–5 to record how rested you felt (1 = terrible, 5 = great). This is saved with the session and included in CSV exports.")
        }

        // ── Export & Data ────────────────────────────────────────────────────
        SectionHeader("Export & Data")

        HelpCard {
            SubHeading("Export CSV")
            BulletItem("Tap Export CSV on the session screen.")
            BulletItem("Choose any folder using the system file picker.")
            BulletItem("The file is named deltasleep_<id>.csv and contains one row per 30-second epoch.")
            Spacer(Modifier.height(8.dp))
            CsvColumnRow("timestamp", "Unix milliseconds — epoch start time")
            CsvColumnRow("phase", "AWAKE · LIGHT · DEEP")
            CsvColumnRow("has_snore", "true · false")
            CsvColumnRow("rms_energy", "Normalised 0.0–1.0 audio level")
            CsvColumnRow("zcr", "Zero-crossing rate")
            CsvColumnRow("band_power", "Spectral energy in 20–300 Hz band")
        }

        HelpCard {
            SubHeading("Delete all data")
            BodyText("Tap Delete all data at the bottom of the home screen. A confirmation dialog appears.")
            Spacer(Modifier.height(8.dp))
            WarnBox("This overwrites the database with zeros before deleting. It cannot be undone. Export any sessions you want to keep first.")
        }

        // ── Tips ─────────────────────────────────────────────────────────────
        SectionHeader("Tips & Troubleshooting")

        HelpCard {
            SubHeading("Battery optimisation")
            BodyText("If tracking stops unexpectedly during the night, Android's battery optimisation may have killed the foreground service.")
            Spacer(Modifier.height(6.dp))
            BodyText("Fix: go to Settings → Apps → DeltaSleep → Battery → Unrestricted (or 'Allow background activity' on Samsung, 'No restrictions' on Xiaomi).")
        }

        HelpCard {
            SubHeading("Music & white noise")
            BodyText("DeltaSleep uses the mix-with-others audio session and does not pause other apps. Spotify, Audible, and white-noise apps all keep playing.")
            Spacer(Modifier.height(6.dp))
            BodyText("If you play audio with heavy bass in the 20–300 Hz range (e.g. binaural beats), snore detection accuracy may decrease.")
        }

        HelpCard {
            SubHeading("First epoch always shows Awake")
            BodyText("The classifier needs ~30 seconds to calibrate to your room noise baseline. The first epoch is always marked Awake — this is expected.")
        }

        HelpCard {
            SubHeading("Privacy guarantee")
            BodyText("Raw audio is processed in memory in 10 ms frames and discarded immediately. No audio files are ever written. Only epoch summary values (RMS, ZCR, band power) are stored in the local SQLite database.")
            Spacer(Modifier.height(6.dp))
            InfoBox("The INTERNET permission is absent from the manifest and verified by CI on every commit. Your data never leaves your device.")
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Section layout helpers ────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = LightColor,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun HelpCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MutedColor)
}

@Composable
private fun BulletItem(text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
        Text("•", color = MutedColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp, top = 1.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MutedColor)
    }
}

@Composable
private fun StepItem(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0x2642A5F5)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = LightColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MutedColor, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PermRow(perm: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            perm,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFEF9A9A),
            modifier = Modifier.width(130.dp),
        )
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MutedColor, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PhasePill(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun SignalLegendRow(color: Color, label: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(10.dp, 3.dp).align(Alignment.CenterVertically).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.9f), fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MutedColor)
        }
    }
}

@Composable
private fun PhaseLegendRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(AwakeColor to "Awake", LightColor to "Light", DeepColor to "Deep", SnoreColor to "Snore").forEach { (c, l) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(12.dp, 10.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Text(l, style = MaterialTheme.typography.labelSmall, color = MutedColor)
            }
        }
    }
}

@Composable
private fun StatInfoRow(label: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(80.dp))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MutedColor, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CsvColumnRow(col: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            col,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = LightColor,
            modifier = Modifier.width(100.dp),
        )
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MutedColor, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x1542A5F5))
            .padding(12.dp),
    ) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = LightColor, fontWeight = FontWeight.Bold)) { append("Note  ") }
            withStyle(SpanStyle(color = MutedColor)) { append(text) }
        }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WarnBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x15E53935))
            .padding(12.dp),
    ) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = AwakeColor, fontWeight = FontWeight.Bold)) { append("Warning  ") }
            withStyle(SpanStyle(color = MutedColor)) { append(text) }
        }, style = MaterialTheme.typography.bodySmall)
    }
}
