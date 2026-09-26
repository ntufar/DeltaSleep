import SwiftUI

/// Apnea risk report (FR-5.3/5.4). Screening copy always says "risk
/// indication" and never "diagnosis" (R1.1.1); the disclaimer is always shown.
struct ApneaReportView: View {
    @Environment(\.palette) private var p
    @EnvironmentObject private var settings: SettingsStore
    @State private var summaries: [NightSummary] = []
    @State private var risk: RiskModel.RiskResult?
    @State private var exportDoc: TextExportDocument?
    @State private var exportError = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if !settings.apneaScreeningEnabled {
                    Card {
                        Text("Apnea screening is off.").foregroundStyle(p.onSurfaceVariant)
                        NavigationLink { ApneaSetupView() } label: {
                            Text("Go to setup").frame(maxWidth: .infinity, minHeight: 40)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    DisclaimerBox(text: disclaimerText)
                } else {
                    content
                }
            }
            .screen(p)
        }
        .background(p.background)
        .navigationTitle("Apnea Risk Report")
        .onAppear(perform: reload)
        .onReceive(AppDatabase.shared.didChange) { reload() }
        .fileExporter(isPresented: Binding(get: { exportDoc != nil }, set: { if !$0 { exportDoc = nil } }),
                      document: exportDoc, contentType: .html,
                      defaultFilename: "deltasleep_apnea_report.html") { result in
            if case .failure = result { exportError = true }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch risk {
        case .notEnoughData(let n):
            Card {
                Text("\(n) of 5 nights collected").font(.headline).foregroundStyle(p.primary)
                Text("At least 5 nights of good-quality data are needed to compute a risk indication. Keep tracking!")
                    .font(.footnote).foregroundStyle(p.onSurfaceVariant)
            }
        case .result(let band, let med, _, _):
            riskCard(band: band, medianReiA: med)
            if band != .low {
                Card {
                    Text("What to do next").font(.subheadline.bold()).foregroundStyle(Warn.orange)
                    Text("Consider discussing these results with your doctor. They may recommend a home sleep apnea test (HSAT) or full polysomnography. Bring the exported physician report to your appointment.")
                        .font(.footnote).foregroundStyle(p.onSurfaceVariant)
                }
            }
        case nil:
            EmptyView()
        }

        if !summaries.isEmpty {
            Card {
                Text("REI-a trend (last 30 nights)").font(.subheadline.bold())
                ReiTrendChart(summaries: summaries)
                FlowRow(spacing: 10) {
                    LegendChip(color: Warn.good, label: "None")
                    LegendChip(color: Warn.fair, label: "Mild")
                    LegendChip(color: Color(hex: 0xF44336), label: "Moderate")
                    LegendChip(color: Color(hex: 0x7B1FA2), label: "Severe")
                    LegendChip(color: Warn.good.opacity(0.4), label: "Low quality (excluded)")
                }
            }
        }

        if let latest = summaries.first {
            Card {
                Text("Latest night").font(.subheadline.bold())
                HStack {
                    Text(String(format: "Longest event: %.0f s", latest.longestEventS))
                    Spacer()
                    Text(String(format: "Snore: %.0f%% of sleep", latest.snorePctOfSleep))
                }
                .font(.footnote)
                Text("Signal quality: \(latest.signalQuality.name)").font(.footnote).foregroundStyle(qualityColor(latest.signalQuality))
            }
        }

        Button {
            exportError = false
            exportDoc = TextExportDocument(text: PhysicianReport.export(db: .shared, risk: risk))
        } label: {
            Label("Physician report (HTML)", systemImage: "doc.text").frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        if exportError {
            Text("Export failed — could not write the file. Try again.").font(.footnote).foregroundStyle(p.error)
        }
        NavigationLink { QuestionnaireView() } label: {
            Text("STOP-BANG Questionnaire").frame(maxWidth: .infinity, minHeight: 36)
        }
        NavigationLink { ApneaSetupView() } label: {
            Text("Screening setup").frame(maxWidth: .infinity, minHeight: 36)
        }
        DisclaimerBox(text: disclaimerText).padding(.top, 8)
    }

    private func riskCard(band: RiskBand, medianReiA: Float) -> some View {
        let color: Color
        let explanation: String
        switch band {
        case .low:
            color = Warn.good
            explanation = String(format: "A median REI-a of %.1f events/h is in the range associated with normal/minimal sleep-disordered breathing.", medianReiA)
        case .elevated:
            color = Warn.fair
            explanation = String(format: "A median REI-a of %.1f events/h is in the range associated with mild OSA. Combined with your questionnaire answers, this suggests an elevated risk.", medianReiA)
        case .high:
            color = Warn.bad
            explanation = String(format: "A median REI-a of %.1f events/h is in the range associated with moderate-to-severe OSA. Combined with your questionnaire answers, this suggests a high risk.", medianReiA)
        }
        return Card {
            Text(band.name).font(.headline.weight(.heavy)).foregroundStyle(color)
                .padding(.horizontal, 14).padding(.vertical, 6)
                .background(color.opacity(0.2), in: Capsule())
            Text(explanation).font(.footnote).foregroundStyle(p.onSurfaceVariant)
            Text(String(format: "Median REI-a: %.1f events/h", medianReiA)).font(.footnote).foregroundStyle(p.primary)
        }
    }

    private func qualityColor(_ q: SignalQuality) -> Color {
        switch q {
        case .good: Warn.good
        case .fair: Warn.fair
        case .low: Warn.bad
        }
    }

    private func reload() {
        let db = AppDatabase.shared
        summaries = db.recentSummaries(limit: 30)
        risk = RiskModel.computeRiskBand(summaries.filter { $0.signalQuality != .low }, latest: db.latestQuestionnaire())
    }
}

private struct ReiTrendChart: View {
    let summaries: [NightSummary]

    var body: some View {
        Canvas { ctx, size in
            let padL: CGFloat = 24, padT: CGFloat = 8, padB: CGFloat = 8
            let w = size.width - padL - 8, h = size.height - padT - padB
            guard w > 0, !summaries.isEmpty else { return }
            let maxRei = CGFloat(max(summaries.map(\.reiA).max() ?? 30, 30))
            func y(_ v: CGFloat) -> CGFloat { padT + h - v / maxRei * h }
            for (v, c) in [(5.0, Warn.good), (15.0, Warn.fair), (30.0, Color(hex: 0xF44336))] {
                ctx.stroke(hLine(y: y(v), from: padL, to: padL + w), with: .color(c.opacity(0.4)),
                           style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
            }
            let slot = w / CGFloat(summaries.count)
            let barW = min(slot, 24)
            for (i, s) in summaries.reversed().enumerated() {
                let barH = max(CGFloat(s.reiA) / maxRei * h, 1)
                let color = bandColor(s.reiA).opacity(s.signalQuality == .low ? 0.3 : 1)
                ctx.fill(Path(CGRect(x: padL + CGFloat(i) * slot + 2, y: padT + h - barH, width: max(barW - 4, 1), height: barH)),
                         with: .color(color))
            }
        }
        .frame(height: 120)
        .accessibilityElement()
        .accessibilityLabel("REI-a trend over \(summaries.count) nights")
    }

    private func bandColor(_ rei: Float) -> Color {
        rei < 5 ? Warn.good : (rei < 15 ? Warn.fair : (rei < 30 ? Color(hex: 0xF44336) : Color(hex: 0x7B1FA2)))
    }
}
