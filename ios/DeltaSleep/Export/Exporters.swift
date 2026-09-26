import SwiftUI
import UniformTypeIdentifiers

/// A text payload handed to `.fileExporter`, so the user picks the folder
/// with the system file picker (nothing is written to shared storage
/// without an explicit export).
struct TextExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText, .html, .plainText] }
    var text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        text = configuration.file.regularFileContents.flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

/// CSV layout identical to the Android CsvExporter: epoch rows, then
/// `# acoustic_events`, then `# night_summary` sections.
enum CsvExporter {
    static func build(session: SleepSession, epochs: [SleepEpoch], events: [AcousticEvent], summary: NightSummary?) -> String {
        var out = "session_id,start_time_ms,end_time_ms,epoch_timestamp_ms,phase,has_snore,rms_energy," +
            "breathing_margin_db,breathing_present_fraction,breath_period_s,external_audio_fraction,playback_active\n"
        let end = session.endTime.map(String.init) ?? ""
        for e in epochs {
            out += "\(session.id),\(session.startTime),\(end),\(e.timestamp),\(e.phase.name),\(e.hasSnore)," +
                "\(e.rmsEnergy),\(e.breathingMarginDb),\(e.breathingPresentFraction)," +
                "\(e.breathPeriodS.map { "\($0)" } ?? ""),\(e.externalAudioFraction),\(e.playbackActive)\n"
        }
        out += "\n# acoustic_events\n"
        out += "id,session_id,type,start_utc_ms,duration_ms,confidence,peak_db_over_floor," +
            "envelope_reduction_pct,terminated_by_gasp,mean_db_over_floor\n"
        for e in events {
            out += "\(e.id),\(e.sessionId),\(e.type.name),\(e.startUtc),\(e.durationMs),\(e.confidence)," +
                "\(e.peakDbOverFloor),\(e.envelopeReductionPct),\(e.terminatedByGasp),\(e.meanDbOverFloor)\n"
        }
        out += "\n# night_summary\n"
        out += "session_id,total_sleep_time_min,rei_a,apnea_like_count,hypopnea_like_count,longest_event_s," +
            "snore_pct_of_sleep,mean_snore_db_over_floor,signal_quality,acoustic_band\n"
        if let s = summary {
            out += "\(s.sessionId),\(s.totalSleepTimeMin),\(s.reiA),\(s.apneaLikeCount),\(s.hypopneaLikeCount)," +
                "\(s.longestEventS),\(s.snorePctOfSleep),\(s.meanSnoreDbOverFloor),\(s.signalQuality.name),\(s.acousticBand.name)\n"
        }
        return out
    }

    static func export(db: AppDatabase, sessionId: Int64) -> String? {
        guard let session = db.session(id: sessionId) else { return nil }
        return build(session: session, epochs: db.epochs(sessionId: sessionId),
                     events: db.events(sessionId: sessionId), summary: db.summary(sessionId: sessionId))
    }
}

/// Self-contained, identity-free physician report (inline CSS + SVG, no
/// external references). Content mirrors PhysicianReport.kt.
enum PhysicianReport {
    static let maxNights = 30
    static let disclaimer = "This is not a medical device and does not diagnose any condition. " +
        "Only a sleep study interpreted by a clinician can diagnose sleep apnea. " +
        "If your results suggest elevated risk, discuss them with a doctor."

    static func export(db: AppDatabase, risk: RiskModel.RiskResult?) -> String {
        let summaries = db.recentSummaries(limit: maxNights, excludeLowQuality: true)
        // Night dates come from the session start, not the row id.
        var starts: [Int64: Int64] = [:]
        for s in summaries { starts[s.sessionId] = db.session(id: s.sessionId)?.startTime }
        return buildHtml(summaries: summaries, nightStart: { starts[$0] ?? 0 },
                         questionnaire: db.latestQuestionnaire(), risk: risk)
    }

    static func buildHtml(summaries: [NightSummary], nightStart: (Int64) -> Int64,
                          questionnaire q: QuestionnaireResult?, risk: RiskModel.RiskResult?) -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        func date(_ ms: Int64) -> String { fmt.string(from: Date(ms: ms)) }
        func f1(_ v: Float) -> String { String(format: "%.1f", v) }
        func f0(_ v: Float) -> String { String(format: "%.0f", v) }

        var h = """
        <!DOCTYPE html>
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
          .disclaimer { background: #fff8dc; border: 1px solid #ccc; padding: 12px 16px; border-radius: 4px; font-size: 0.9em; margin: 1.5em 0; }
          .risk-low { color: #2e7d32; font-weight: bold; }
          .risk-elevated { color: #e65100; font-weight: bold; }
          .risk-high { color: #b71c1c; font-weight: bold; }
          svg { display: block; margin-top: 1em; overflow: visible; max-width: 100%; height: auto; }
        </style>
        </head>
        <body>
        <h1>Sleep Apnea Risk Screening Report</h1>
        <p><strong>Generated:</strong> \(date(nowMs()))</p>
        <p><em>Identity-free — no patient name or contact details are included.</em></p>
        <div class="disclaimer">\(disclaimer)</div>
        <h2>How This Report Is Generated</h2>
        <p>DeltaSleep records audio from the phone's microphone during sleep and analyses it entirely on-device.
        The app detects acoustic events associated with sleep-disordered breathing: periods of silence or strong
        sound-level reduction lasting ≥&nbsp;10&nbsp;seconds between confirmed respiratory sounds (<em>apnea-like events</em>),
        partial reductions of 30&ndash;50&nbsp;% (<em>hypopnea-like events</em>), and post-event gasps. It also tracks
        continuous snoring episodes.</p>
        <p>The <strong>Respiratory Event Index &ndash; acoustic (REI-a)</strong> counts apnea-like events per hour of
        non-AWAKE sleep time. It is an acoustic proxy for the clinical Apnea&ndash;Hypopnea Index (AHI) measured in a
        polysomnography sleep study and is <em>not</em> the same measurement.</p>
        <p><strong>Known limitations:</strong> Accuracy depends on phone placement (nightstand or mattress edge,
        0.5&ndash;1.5&nbsp;m from head, microphone unobstructed). Results may be unreliable if a bed partner or pet sleeps
        within ~1&nbsp;m of the phone, if ambient noise is high relative to breathing sounds, or if the user breathes
        primarily through their mouth. Nights where the breathing-to-noise margin is below 6&nbsp;dB for more than
        20&nbsp;% of sleep time are flagged <em>LOW signal quality</em> and excluded from risk trending.</p>
        <h2>STOP-BANG Questionnaire</h2>

        """
        if let q {
            let band = RiskModel.stopBangBand(q.score)
            let answers: [(String, Bool)] = [
                ("Snoring", q.snoring), ("Tiredness", q.tiredness), ("Observed apnea", q.observedApnea),
                ("High pressure", q.highPressure), ("BMI &gt; 35", q.bmiOver35), ("Age &gt; 50", q.ageOver50),
                ("Neck &gt; 40 cm", q.neckOver40cm), ("Male gender", q.maleGender),
            ]
            h += "<p><strong>Score:</strong> \(q.score) / 8 &nbsp; <strong>Band:</strong> \(band.label.capitalized)"
            h += " &nbsp; <strong>Date:</strong> \(date(q.dateUtc))</p>\n"
            h += "<p>Completed answers: " + answers.map { "\($0.0): <strong>\($0.1 ? "Yes" : "No")</strong>" }
                .joined(separator: ", ") + "</p>\n"
        } else {
            h += "<p><em>No questionnaire completed yet.</em></p>\n"
        }

        h += "<h2>Headline Risk Indication</h2>\n"
        switch risk {
        case .notEnoughData(let n):
            h += "<p>Not enough data yet — \(n) of 5 required GOOD/FAIR-quality nights recorded.</p>\n"
        case .result(let band, let med, let acoustic, _):
            let css = band == .low ? "risk-low" : (band == .elevated ? "risk-elevated" : "risk-high")
            h += "<p>Combined risk indication: <span class=\"\(css)\">\(band.name)</span></p>\n"
            h += "<p>Median REI-a: \(f1(med)) events/h &nbsp; Acoustic band: \(bandLabel(acoustic))</p>\n"
            if band != .low {
                h += "<p><strong>What to do next:</strong> Consider discussing these results with your doctor. " +
                    "They may recommend a home sleep apnea test (HSAT) or full polysomnography. " +
                    "Bring this exported report to your appointment.</p>\n"
            }
            h += matrixExplanation
        case nil:
            h += "<p><em>Risk band not available.</em></p>\n"
        }

        if !summaries.isEmpty {
            h += "<h2>Nightly REI-a Trend (last \(maxNights) nights)</h2>\n"
            h += reiChart(summaries, label: { String(date(nightStart($0.sessionId)).dropFirst(5)) })
        }

        h += "<h2>Night-by-Night Summary</h2>\n<table>\n<tr><th>Date</th><th>TST (min)</th><th>REI-a</th>" +
            "<th>Apnea-like</th><th>Hypopnea-like</th><th>Longest (s)</th><th>Snore %</th><th>Signal quality</th></tr>\n"
        for s in summaries {
            h += "<tr><td>\(date(nightStart(s.sessionId)))</td><td>\(s.totalSleepTimeMin)</td><td>\(f1(s.reiA))</td>" +
                "<td>\(s.apneaLikeCount)</td><td>\(s.hypopneaLikeCount)</td><td>\(f0(s.longestEventS))</td>" +
                "<td>\(f0(s.snorePctOfSleep))</td><td>\(s.signalQuality.name)</td></tr>\n"
        }
        h += "</table>\n<div class=\"disclaimer\">\(disclaimer)</div>\n</body>\n</html>"
        return h
    }

    private static func bandLabel(_ b: AcousticBand) -> String {
        switch b {
        case .none: "None (&lt; 5 events/h)"
        case .mild: "Mild range (5&ndash;14 events/h)"
        case .moderate: "Moderate range (15&ndash;29 events/h)"
        case .severe: "Severe range (&ge; 30 events/h)"
        }
    }

    private static let matrixExplanation = """
    <details><summary>How the risk indication is calculated</summary>
    <p>The headline risk indication combines the acoustic REI-a band with the STOP-BANG questionnaire band using the
    matrix below. At least 5 GOOD/FAIR-quality nights are required; the median REI-a (not a single night) is used so
    one outlier night cannot produce a HIGH result.</p>
    <table>
    <tr><th>Acoustic band</th><th>STOP-BANG: Low</th><th>STOP-BANG: Intermediate</th><th>STOP-BANG: High</th></tr>
    <tr><td>None</td><td>LOW</td><td>LOW</td><td>ELEVATED</td></tr>
    <tr><td>Mild</td><td>LOW</td><td>ELEVATED</td><td>ELEVATED</td></tr>
    <tr><td>Moderate</td><td>ELEVATED</td><td>HIGH</td><td>HIGH</td></tr>
    <tr><td>Severe</td><td>HIGH</td><td>HIGH</td><td>HIGH</td></tr>
    </table>
    <p>A missing questionnaire is treated as the Intermediate column. LOW-quality nights are excluded.</p>
    </details>

    """

    /// Inline SVG bar chart of nightly REI-a, oldest night on the left.
    private static func reiChart(_ summaries: [NightSummary], label: (NightSummary) -> String) -> String {
        let padL = 50.0, padT = 20.0, width = 800.0, height = 220.0
        let chartW = width - padL - 20, chartH = height - padT - 60
        let maxRei = Double(max(summaries.map(\.reiA).max() ?? 30, 30))
        let slot = chartW / Double(summaries.count)
        let barW = min(slot, 40)
        func y(_ v: Double) -> Double { padT + chartH - v / maxRei * chartH }

        var s = "<svg width=\"\(Int(width))\" height=\"\(Int(height))\" viewBox=\"0 0 \(Int(width)) \(Int(height))\" aria-label=\"Bar chart of nightly REI-a\">\n"
        s += "<line x1=\"\(padL)\" y1=\"\(padT)\" x2=\"\(padL)\" y2=\"\(padT + chartH)\" stroke=\"#999\"/>"
        s += "<line x1=\"\(padL)\" y1=\"\(padT + chartH)\" x2=\"\(padL + chartW)\" y2=\"\(padT + chartH)\" stroke=\"#999\"/>"
        s += "<text x=\"12\" y=\"\(padT + chartH / 2)\" font-size=\"11\" fill=\"#555\" transform=\"rotate(-90 12 \(padT + chartH / 2))\">REI-a</text>"
        for (v, color) in [(5.0, "#aaa"), (15.0, "#fa0"), (30.0, "#f44")] {
            s += "<line x1=\"\(padL)\" y1=\"\(y(v))\" x2=\"\(padL + chartW)\" y2=\"\(y(v))\" stroke=\"\(color)\" stroke-dasharray=\"4,4\"/>"
            s += "<text x=\"\(padL + 2)\" y=\"\(y(v) - 2)\" font-size=\"9\" fill=\"#999\">\(Int(v))</text>"
        }
        for (i, n) in summaries.reversed().enumerated() {
            let barH = max(Double(n.reiA) / maxRei * chartH, 1)
            let x = padL + Double(i) * slot
            let color = switch n.acousticBand {
            case .none: "#4caf50"
            case .mild: "#ff9800"
            case .moderate: "#f44336"
            case .severe: "#7b1fa2"
            }
            s += "<rect x=\"\(x + 2)\" y=\"\(padT + chartH - barH)\" width=\"\(max(barW - 4, 1))\" height=\"\(barH)\" fill=\"\(color)\" rx=\"2\"/>"
            if i % 5 == 0 {
                s += "<text x=\"\(x + barW / 2)\" y=\"\(padT + chartH + 14)\" font-size=\"9\" fill=\"#555\" text-anchor=\"middle\">\(label(n))</text>"
            }
        }
        return s + "\n</svg>\n"
    }
}
