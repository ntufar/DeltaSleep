import SwiftUI

struct SessionSummary {
    let session: SleepSession
    /// Smoothed phases for display (A-1); stored rows keep raw DSP verdicts.
    let epochs: [SleepEpoch]
    let totalSleepMs: Int64
    let snorePercent: Float
    let deepPercent: Float
    let events: [AcousticEvent]
    let nightSummary: NightSummary?
    let screeningEnabled: Bool
    let externalAudioMin: Int

    static func load(sessionId: Int64, screening: Bool, db: AppDatabase = .shared) -> SessionSummary? {
        guard let session = db.session(id: sessionId) else { return nil }
        let raw = db.epochs(sessionId: sessionId)
        let smoothed = NightSummarizer.smoothPhases(raw.map(\.phase))
        let epochs = raw.enumerated().map { i, e in var e = e; e.phase = smoothed[i]; return e }
        let n = Float(max(epochs.count, 1))
        return SessionSummary(
            session: session,
            epochs: epochs,
            totalSleepMs: (session.endTime ?? nowMs()) - session.startTime,
            snorePercent: epochs.isEmpty ? 0 : Float(epochs.filter(\.hasSnore).count) * 100 / n,
            deepPercent: epochs.isEmpty ? 0 : Float(epochs.filter { $0.phase == .deep }.count) * 100 / n,
            // A-6: snore episodes regardless of screening; apnea types gated.
            events: screening ? db.events(sessionId: sessionId) : db.events(sessionId: sessionId, type: .snoreEpisode),
            nightSummary: screening ? db.summary(sessionId: sessionId) : nil,
            screeningEnabled: screening,
            externalAudioMin: epochs.filter { ExternalAudio.isExternal($0) }.count / 2)
    }
}

struct SessionView: View {
    let sessionId: Int64
    @Environment(\.palette) private var p
    @EnvironmentObject private var settings: SettingsStore
    @State private var summary: SessionSummary?
    @State private var loaded = false
    @State private var rating = 0
    @State private var exportDoc: TextExportDocument?
    @State private var exportError = false

    var body: some View {
        ScrollView {
            if let s = summary {
                content(s).screen(p)
            } else if loaded {
                Text("Session not found — it may have been deleted or purged.")
                    .foregroundStyle(p.onSurfaceVariant).screen(p)
            } else {
                ProgressView().padding(40)
            }
        }
        .background(p.background)
        .navigationTitle("Last Night")
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            summary = SessionSummary.load(sessionId: sessionId, screening: settings.apneaScreeningEnabled)
            rating = summary?.session.feelRating ?? 0
            loaded = true
        }
        .fileExporter(isPresented: Binding(get: { exportDoc != nil }, set: { if !$0 { exportDoc = nil } }),
                      document: exportDoc, contentType: .commaSeparatedText,
                      defaultFilename: exportFilename) { result in
            if case .failure = result { exportError = true }
        }
    }

    private var exportFilename: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return "deltasleep_\(f.string(from: Date(ms: summary?.session.startTime ?? 0))).csv"
    }

    @ViewBuilder
    private func content(_ s: SessionSummary) -> some View {
        let mins = Int(s.totalSleepMs / 60000)
        VStack(alignment: .leading, spacing: 12) {
            Text("Movement + breathing staging, all on-device").font(.footnote).foregroundStyle(p.onSurfaceVariant)
            Text(Date(ms: s.session.startTime).formatted(date: .complete, time: .shortened))
                .font(.footnote).foregroundStyle(p.onSurfaceVariant)

            HStack(spacing: 12) {
                StatCard(label: "Sleep time", value: mins >= 60 ? "\(mins / 60)h \(mins % 60)m" : "\(mins)m")
                StatCard(label: "Snore", value: "\(Int(s.snorePercent))%")
                StatCard(label: "Deep", value: "\(Int(s.deepPercent))%")
            }

            Text("Sleep stages").font(.headline).padding(.top, 12)
            HypnogramChart(epochs: s.epochs, startMs: s.session.startTime,
                           endMs: s.session.endTime ?? nowMs(), events: s.events)

            let hasApnea = s.events.contains { $0.type == .apneaLike || $0.type == .hypopneaLike }
            FlowRow {
                ForEach(SleepPhase.allCases) { LegendChip(color: $0.color, label: $0.label) }
                LegendChip(color: Warn.snore, label: "Snore")
                if s.screeningEnabled || hasApnea {
                    LegendChip(color: Warn.bad, label: "Apnea-like")
                    LegendChip(color: Warn.fair, label: "Hypopnea-like")
                }
            }

            if s.epochs.contains(where: { $0.phase == .rem }) {
                note("REM stages are estimated from movement and breathing patterns — not validated sleep-lab staging.")
            }
            if s.externalAudioMin > 0 {
                note("External audio filtered: \(s.externalAudioMin) min — podcast, music, or TV time is excluded from snore and apnea stats.")
            }

            let snoreEvents = s.events.filter { $0.type == .snoreEpisode }
            if let loudest = SnoreIntensity.loudest(snoreEvents) {
                Text("Snore intensity").font(.headline).padding(.top, 12)
                note("Taller magenta bars in the chart above are louder snores.")
                HStack(spacing: 12) {
                    StatCard(label: "Loudest snore", value: "\(loudest)/5")
                    StatCard(label: "Snore episodes", value: "\(snoreEvents.count)")
                }
            }

            if s.screeningEnabled, let ns = s.nightSummary {
                Text("Apnea screening").font(.headline).padding(.top, 12)
                HStack(spacing: 12) {
                    StatCard(label: "REI-a", value: String(format: "%.1f/h", ns.reiA))
                    StatCard(label: "Signal", value: ns.signalQuality.name)
                }
            }

            if s.epochs.contains(where: { $0.breathPeriodS != nil }) {
                Text("Breathing rate").font(.headline).padding(.top, 12)
                note(BreathingRate.medianBpm(s.epochs).map { String(format: "Median %.0f breaths/min", $0) } ?? "No breathing detected")
                BreathingChart(epochs: s.epochs)
            }

            Text("How did you feel?").font(.headline).padding(.top, 12)
            HStack(spacing: 12) {
                ForEach(1...5, id: \.self) { n in
                    Button {
                        rating = n
                        var updated = s.session
                        updated.feelRating = n
                        AppDatabase.shared.updateSession(updated)
                    } label: {
                        Text("\(n)").font(.headline)
                            .frame(width: 48, height: 48)
                            .foregroundStyle(rating == n ? p.onPrimary : p.onSurfaceVariant)
                            .background(Circle().fill(rating == n ? p.primary : p.surfaceVariant))
                            .overlay(Circle().stroke(rating == n ? p.primary : p.outline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Feel rating \(n) of 5")
                    .accessibilityAddTraits(rating == n ? .isSelected : [])
                }
            }

            Button {
                exportError = false
                if let csv = CsvExporter.export(db: .shared, sessionId: sessionId) {
                    exportDoc = TextExportDocument(text: csv)
                } else {
                    exportError = true
                }
            } label: {
                Label("Export CSV", systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 20)
            if exportError {
                Text("Export failed — could not write the file. Try again.").font(.footnote).foregroundStyle(p.error)
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(p.onSurfaceVariant)
    }
}

/// Simple wrapping row for legend chips.
struct FlowRow: Layout {
    var spacing: CGFloat = 12

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews)
        return CGSize(width: proposal.width ?? rows.width, height: rows.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = arrange(width: bounds.width, subviews)
        for (i, pt) in rows.points.enumerated() {
            subviews[i].place(at: CGPoint(x: bounds.minX + pt.x, y: bounds.minY + pt.y), proposal: .unspecified)
        }
    }

    private func arrange(width: CGFloat, _ subviews: Subviews) -> (points: [CGPoint], width: CGFloat, height: CGFloat) {
        var points: [CGPoint] = []
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxW: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > width {
                x = 0
                y += rowH + 6
                rowH = 0
            }
            points.append(CGPoint(x: x, y: y))
            x += s.width + spacing
            rowH = max(rowH, s.height)
            maxW = max(maxW, x)
        }
        return (points, maxW, y + rowH)
    }
}
