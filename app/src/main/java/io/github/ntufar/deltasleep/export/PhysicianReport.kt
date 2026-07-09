package io.github.ntufar.deltasleep.export

import android.content.Context
import android.net.Uri
import io.github.ntufar.deltasleep.data.db.AppDatabase
import io.github.ntufar.deltasleep.data.model.AcousticBand
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.QuestionnaireResult
import io.github.ntufar.deltasleep.data.model.RiskBand
import io.github.ntufar.deltasleep.data.model.SignalQuality
import io.github.ntufar.deltasleep.apnea.RiskModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_NIGHTS_IN_REPORT = 30
private const val DISCLAIMER =
    "This is not a medical device and does not diagnose any condition. " +
    "Only a sleep study interpreted by a clinician can diagnose sleep apnea. " +
    "If your results suggest elevated risk, discuss them with a doctor."

/**
 * Generates a self-contained, printable physician-report HTML string on-device.
 *
 * The output uses only inline CSS and no external references. No network access
 * is required or used. The file contains no patient identity fields.
 *
 * Write via [export] using the system content-resolver (same pattern as CsvExporter).
 */
object PhysicianReport {

    /**
     * Write the physician report HTML to [uri] (obtained via system file picker).
     *
     * @param context Android context for DB and content-resolver access.
     * @param uri     Write-only URI from ACTION_CREATE_DOCUMENT.
     * @param riskResult Pre-computed risk result (pass from the view-model to avoid re-querying).
     */
    suspend fun export(
        context: Context,
        uri: Uri,
        riskResult: RiskModel.RiskResult?,
    ) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val recentSummaries = db.nightSummaryDao()
            .getRecentGoodFairNights(MAX_NIGHTS_IN_REPORT, SignalQuality.LOW)
        val latestQuestionnaire = db.questionnaireResultDao().getLatest()

        val html = buildHtml(recentSummaries, latestQuestionnaire, riskResult)

        context.contentResolver.openOutputStream(uri)?.use { stream ->
            PrintWriter(stream, false, Charsets.UTF_8).use { writer ->
                writer.print(html)
            }
        }
    }

    /**
     * Build the full HTML string. Pure function — unit-testable without Android framework.
     */
    fun buildHtml(
        summaries: List<NightSummary>,
        questionnaire: QuestionnaireResult?,
        riskResult: RiskModel.RiskResult?,
    ): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sb = StringBuilder()

        sb.append(
            """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sleep Apnea Risk Screening Report</title>
<style>
  body { font-family: Arial, sans-serif; max-width: 900px; margin: 0 auto; padding: 24px; color: #222; }
  h1 { font-size: 1.5em; border-bottom: 2px solid #555; padding-bottom: 8px; }
  h2 { font-size: 1.15em; margin-top: 2em; border-bottom: 1px solid #ccc; }
  table { border-collapse: collapse; width: 100%; margin-top: 1em; font-size: 0.9em; }
  th, td { border: 1px solid #bbb; padding: 6px 10px; text-align: left; }
  th { background: #f0f0f0; }
  .disclaimer { background: #fff8dc; border: 1px solid #ccc; padding: 12px 16px;
                border-radius: 4px; font-size: 0.9em; margin: 1.5em 0; }
  .risk-low      { color: #2e7d32; font-weight: bold; }
  .risk-elevated { color: #e65100; font-weight: bold; }
  .risk-high     { color: #b71c1c; font-weight: bold; }
  .quality-low   { color: #b71c1c; }
  svg { display: block; margin-top: 1em; overflow: visible; }
</style>
</head>
<body>
"""
        )

        sb.append("<h1>Sleep Apnea Risk Screening Report</h1>\n")
        sb.append("<p><strong>Generated:</strong> ${dateFmt.format(Date())}</p>\n")
        sb.append("<p><em>Identity-free — no patient name or contact details are included.</em></p>\n")

        // Disclaimer (R1.1.2)
        sb.append("<div class=\"disclaimer\">$DISCLAIMER</div>\n")

        // Methodology
        sb.append("<h2>How This Report Is Generated</h2>\n")
        sb.append(
            """<p>DeltaSleep records audio from your phone's microphone during sleep and analyses it
entirely on-device. The app detects acoustic events acoustically associated with sleep-disordered
breathing: periods of silence or strong sound-level reduction lasting ≥&nbsp;10&nbsp;seconds
between confirmed respiratory sounds (<em>apnea-like events</em>), partial reductions of 30&ndash;50&nbsp;%
(<em>hypopnea-like events</em>), and post-event gasps. It also tracks continuous snoring episodes.</p>
<p>The <strong>Respiratory Event Index &ndash; acoustic (REI-a)</strong> counts apnea-like events per hour of
non-AWAKE sleep time. It is an acoustic proxy for the clinical Apnea&ndash;Hypopnea Index (AHI) measured
in a polysomnography sleep study and is <em>not</em> the same measurement.</p>
<p><strong>Known limitations:</strong> Accuracy depends on phone placement (nightstand or mattress edge,
0.5&ndash;1.5&nbsp;m from head, microphone unobstructed). Results may be unreliable if a bed partner or
pet sleeps within ~1&nbsp;m of the phone, if ambient noise (fan, air conditioning) is high relative to
breathing sounds, or if the user breathes primarily through their mouth. Nights where the
breathing-to-noise margin is below 6&nbsp;dB for more than 20&nbsp;% of sleep time are flagged
<em>LOW signal quality</em> and excluded from risk trending.</p>
"""
        )

        // STOP-BANG
        sb.append("<h2>STOP-BANG Questionnaire</h2>\n")
        if (questionnaire != null) {
            val score = questionnaire.score
            val band = RiskModel.stopBangBand(score)
            sb.append("<p><strong>Score:</strong> $score / 8 &nbsp; ")
            sb.append("<strong>Band:</strong> ${band.name.lowercase().replaceFirstChar { it.uppercase() }}")
            sb.append(" &nbsp; <strong>Date:</strong> ${dateFmt.format(Date(questionnaire.dateUtc))}</p>\n")
            sb.append("<p>Completed answers: ")
            sb.append(buildStopBangAnswerList(questionnaire))
            sb.append("</p>\n")
        } else {
            sb.append("<p><em>No questionnaire completed yet.</em></p>\n")
        }

        // Risk band
        sb.append("<h2>Headline Risk Indication</h2>\n")
        when (riskResult) {
            is RiskModel.RiskResult.NotEnoughData -> {
                sb.append(
                    "<p>Not enough data yet — ${riskResult.nightsSoFar} of 5 required " +
                    "GOOD/FAIR-quality nights recorded.</p>\n"
                )
            }
            is RiskModel.RiskResult.Result -> {
                val cssClass = when (riskResult.riskBand) {
                    RiskBand.LOW -> "risk-low"
                    RiskBand.ELEVATED -> "risk-elevated"
                    RiskBand.HIGH -> "risk-high"
                }
                sb.append(
                    "<p>Combined risk indication: <span class=\"$cssClass\">" +
                    "${riskResult.riskBand.name}</span></p>\n"
                )
                sb.append("<p>Median REI-a: ${String.format(Locale.US, "%.1f", riskResult.medianReiA)} events/h &nbsp; ")
                sb.append("Acoustic band: ${acousticBandLabel(riskResult.acousticBand)}</p>\n")
                if (riskResult.riskBand == RiskBand.ELEVATED || riskResult.riskBand == RiskBand.HIGH) {
                    sb.append(
                        "<p><strong>What to do next:</strong> Consider discussing these results " +
                        "with your doctor. They may recommend a home sleep apnea test (HSAT) " +
                        "or full polysomnography. Bring this exported report to your appointment.</p>\n"
                    )
                }
                sb.append(matrixExplanation())
            }
            null -> {
                sb.append("<p><em>Risk band not available.</em></p>\n")
            }
        }

        // Nightly REI-a chart (inline SVG bar chart)
        if (summaries.isNotEmpty()) {
            sb.append("<h2>Nightly REI-a Trend (last $MAX_NIGHTS_IN_REPORT nights)</h2>\n")
            sb.append(buildReiAChart(summaries, dateFmt))
        }

        // Nightly summary table
        sb.append("<h2>Night-by-Night Summary</h2>\n")
        sb.append(
            "<table>\n<tr><th>Date</th><th>TST (min)</th><th>REI-a</th>" +
            "<th>Apnea-like</th><th>Hypopnea-like</th><th>Longest (s)</th>" +
            "<th>Snore %</th><th>Signal quality</th></tr>\n"
        )
        for (s in summaries) {
            val qualClass = if (s.signalQuality == SignalQuality.LOW) " class=\"quality-low\"" else ""
            sb.append(
                "<tr$qualClass><td>${dateFmt.format(Date(s.sessionId))}</td>" +
                "<td>${s.totalSleepTimeMin}</td>" +
                "<td>${String.format(Locale.US, "%.1f", s.reiA)}</td>" +
                "<td>${s.apneaLikeCount}</td>" +
                "<td>${s.hypopneaLikeCount}</td>" +
                "<td>${String.format(Locale.US, "%.0f", s.longestEventS)}</td>" +
                "<td>${String.format(Locale.US, "%.0f", s.snorePctOfSleep)}</td>" +
                "<td>${s.signalQuality.name}</td></tr>\n"
            )
        }
        sb.append("</table>\n")

        sb.append("<div class=\"disclaimer\">$DISCLAIMER</div>\n")
        sb.append("</body>\n</html>")
        return sb.toString()
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun buildStopBangAnswerList(q: QuestionnaireResult): String {
        val items = listOf(
            "Snoring" to q.snoring,
            "Tiredness" to q.tiredness,
            "Observed apnea" to q.observedApnea,
            "High pressure" to q.highPressure,
            "BMI &gt; 35" to q.bmiOver35,
            "Age &gt; 50" to q.ageOver50,
            "Neck &gt; 40 cm" to q.neckOver40cm,
            "Male gender" to q.maleGender,
        )
        return items.joinToString(", ") { (label, value) ->
            "$label: <strong>${if (value) "Yes" else "No"}</strong>"
        }
    }

    private fun acousticBandLabel(band: AcousticBand): String = when (band) {
        AcousticBand.NONE -> "None (&lt; 5 events/h)"
        AcousticBand.MILD -> "Mild range (5&ndash;14 events/h)"
        AcousticBand.MODERATE -> "Moderate range (15&ndash;29 events/h)"
        AcousticBand.SEVERE -> "Severe range (&ge; 30 events/h)"
    }

    private fun matrixExplanation(): String = """
<details><summary>How the risk indication is calculated</summary>
<p>The headline risk indication combines the acoustic REI-a band with the STOP-BANG questionnaire
band using the matrix below. At least 5 GOOD/FAIR-quality nights are required; the median REI-a
(not a single night) is used so one outlier night cannot produce a HIGH result.</p>
<table>
<tr><th>Acoustic band</th><th>STOP-BANG: Low</th><th>STOP-BANG: Intermediate</th><th>STOP-BANG: High</th></tr>
<tr><td>None</td><td>LOW</td><td>LOW</td><td>ELEVATED</td></tr>
<tr><td>Mild</td><td>LOW</td><td>ELEVATED</td><td>ELEVATED</td></tr>
<tr><td>Moderate</td><td>ELEVATED</td><td>HIGH</td><td>HIGH</td></tr>
<tr><td>Severe</td><td>HIGH</td><td>HIGH</td><td>HIGH</td></tr>
</table>
<p>Missing questionnaire is treated as Intermediate column. LOWquality nights are excluded.</p>
</details>
"""

    /**
     * Build a simple inline SVG bar chart of nightly REI-a values.
     * No external dependencies — pure SVG, renders in any browser or print preview.
     */
    private fun buildReiAChart(summaries: List<NightSummary>, dateFmt: SimpleDateFormat): String {
        val padL = 50; val padR = 20; val padT = 20; val padB = 60
        val width = 800; val height = 220
        val chartW = width - padL - padR
        val chartH = height - padT - padB

        val maxRei = (summaries.maxOfOrNull { it.reiA } ?: 30f).coerceAtLeast(30f)
        val barW = (chartW.toFloat() / summaries.size).coerceAtMost(40f)

        val sb = StringBuilder()
        sb.append("<svg width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\" ")
        sb.append("aria-label=\"Bar chart of nightly REI-a\">\n")

        // Axes
        sb.append("<line x1=\"$padL\" y1=\"$padT\" x2=\"$padL\" y2=\"${padT + chartH}\" stroke=\"#999\" stroke-width=\"1\"/>")
        sb.append("<line x1=\"$padL\" y1=\"${padT + chartH}\" x2=\"${padL + chartW}\" y2=\"${padT + chartH}\" stroke=\"#999\" stroke-width=\"1\"/>")

        // Y-axis label
        sb.append("<text x=\"12\" y=\"${padT + chartH / 2}\" font-size=\"11\" fill=\"#555\" transform=\"rotate(-90 12 ${padT + chartH / 2})\">REI-a</text>")

        // Guideline at REI-a = 5
        val y5 = padT + chartH - (5f / maxRei * chartH).toInt()
        sb.append("<line x1=\"$padL\" y1=\"$y5\" x2=\"${padL + chartW}\" y2=\"$y5\" stroke=\"#aaa\" stroke-dasharray=\"4,4\" stroke-width=\"1\"/>")
        sb.append("<text x=\"${padL + 2}\" y=\"${y5 - 2}\" font-size=\"9\" fill=\"#999\">5</text>")

        // Guideline at REI-a = 15
        val y15 = padT + chartH - (15f / maxRei * chartH).toInt()
        sb.append("<line x1=\"$padL\" y1=\"$y15\" x2=\"${padL + chartW}\" y2=\"$y15\" stroke=\"#fa0\" stroke-dasharray=\"4,4\" stroke-width=\"1\"/>")
        sb.append("<text x=\"${padL + 2}\" y=\"${y15 - 2}\" font-size=\"9\" fill=\"#999\">15</text>")

        // Guideline at REI-a = 30
        val y30 = padT + chartH - (30f / maxRei * chartH).toInt()
        sb.append("<line x1=\"$padL\" y1=\"$y30\" x2=\"${padL + chartW}\" y2=\"$y30\" stroke=\"#f44\" stroke-dasharray=\"4,4\" stroke-width=\"1\"/>")
        sb.append("<text x=\"${padL + 2}\" y=\"${y30 - 2}\" font-size=\"9\" fill=\"#999\">30</text>")

        // Bars (oldest first = leftmost)
        val ordered = summaries.reversed()
        ordered.forEachIndexed { i, s ->
            val barH = ((s.reiA / maxRei) * chartH).toInt().coerceAtLeast(1)
            val x = padL + i * (chartW.toFloat() / ordered.size)
            val y = padT + chartH - barH
            val color = when (s.acousticBand) {
                AcousticBand.NONE -> "#4caf50"
                AcousticBand.MILD -> "#ff9800"
                AcousticBand.MODERATE -> "#f44336"
                AcousticBand.SEVERE -> "#7b1fa2"
            }
            sb.append("<rect x=\"${x + 2}\" y=\"$y\" width=\"${barW - 4}\" height=\"$barH\" fill=\"$color\" rx=\"2\"/>")

            // X-axis date labels (every 5th night to avoid clutter)
            if (i % 5 == 0) {
                val dateStr = dateFmt.format(Date(s.sessionId)).substring(5) // MM-DD
                sb.append(
                    "<text x=\"${x + barW / 2}\" y=\"${padT + chartH + 14}\" " +
                    "font-size=\"9\" fill=\"#555\" text-anchor=\"middle\">$dateStr</text>"
                )
            }
        }

        sb.append("\n</svg>\n")
        return sb.toString()
    }
}
