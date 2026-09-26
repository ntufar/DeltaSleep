import SwiftUI

private let bgColor = Color(hex: 0x0A0E14)
private let cardColor = Color(hex: 0x141A23)
private let dimText = Color.white.opacity(0.4)
private let rmsColor = Color(hex: 0x00E676)
private let zcrColor = Color(hex: 0x40C4FF)
private let bandColor = Color(hex: 0xFFAB40)

private let graphSlots: CGFloat = 90   // 90 × 2 s = 3 min
private let epochWidth: CGFloat = 20   // pt per epoch in scrollable charts
private let labelEveryN = 10           // x-axis label every 5 min

/// Night-time tracking screen. Always dark regardless of theme; keeps the
/// display awake while visible (the user can still lock the phone —
/// tracking continues in the background).
struct ActiveSleepView: View {
    @EnvironmentObject private var tracker: SleepTracker
    @State private var epochs: [SleepEpoch] = []
    @State private var startMs: Int64 = nowMs()
    @State private var now = Date()
    @State private var confirmStop = false

    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 16) {
            PhaseBadge(phase: epochs.last?.phase ?? .awake)
            Text(elapsed)
                .font(.system(size: 56, weight: .regular, design: .monospaced))
                .foregroundStyle(.white)
                .accessibilityLabel("Elapsed time \(elapsed)")

            ScrollView {
                VStack(spacing: 12) {
                    ChartCard(title: "Live signal — last 3 min") {
                        MiniSignalGraph(label: "Audio level", data: tracker.rmsHistory, color: rmsColor)
                        MiniSignalGraph(label: "Sound texture", data: tracker.zcrHistory, color: zcrColor)
                        MiniSignalGraph(label: "Snore band", data: tracker.bandHistory, color: bandColor)
                        HStack {
                            ForEach(["3 min ago", "2 min ago", "1 min ago", "now"], id: \.self) { l in
                                Text(l).font(.caption2).foregroundStyle(dimText)
                                if l != "now" { Spacer() }
                            }
                        }
                    }
                    ChartCard(title: "Phase history") {
                        HStack(spacing: 10) {
                            ForEach(SleepPhase.allCases) { LegendDot(color: $0.color, label: $0.label) }
                            LegendDot(color: Warn.snore.opacity(0.5), label: "Snore")
                        }
                        EpochStrip(epochs: epochs, height: 56, draw: drawPhases)
                    }
                    ChartCard(title: "Snore events") {
                        let n = epochs.filter(\.hasSnore).count
                        Text(n == 0 ? "No snoring detected yet" : "\(n) event\(n > 1 ? "s" : "") detected")
                            .font(.caption).foregroundStyle(n > 0 ? Warn.snore : dimText)
                        EpochStrip(epochs: epochs, height: 36, draw: drawSnore)
                    }
                    ChartCard(title: "Audio intensity") {
                        EpochStrip(epochs: epochs, height: 60, draw: drawIntensity)
                    }
                }
            }

            let snoring = epochs.last?.hasSnore == true
            Text(snoring ? "Snore detected" : "No snoring")
                .font(.callout)
                .foregroundStyle(snoring ? Color(hex: 0xFF5252) : dimText)
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(snoring ? Color(hex: 0xE53935, alpha: 0.27) : Color.white.opacity(0.07),
                            in: RoundedRectangle(cornerRadius: 8))
                .animation(.easeInOut(duration: 0.4), value: snoring)

            Button { confirmStop = true } label: {
                Text("Stop Tracking").font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .foregroundStyle(.white)
                    .background(Color(hex: 0xE53935), in: RoundedRectangle(cornerRadius: 28))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 24)
        .background(bgColor.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .confirmationDialog("Stop tracking and save this night?", isPresented: $confirmStop, titleVisibility: .visible) {
            Button("Stop Tracking", role: .destructive) { tracker.stop() }
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            reload()
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .onReceive(AppDatabase.shared.didChange) { reload() }
        .onReceive(ticker) { now = $0 }
    }

    private var elapsed: String {
        let s = max(0, Int(now.timeIntervalSince(Date(ms: startMs))))
        return String(format: "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    private func reload() {
        guard let id = tracker.activeSessionId else { return }
        if let s = AppDatabase.shared.session(id: id) { startMs = s.startTime }
        epochs = AppDatabase.shared.epochs(sessionId: id)
    }

    // MARK: - Epoch chart drawing

    private func drawPhases(_ ctx: GraphicsContext, _ size: CGSize, _ epochs: [SleepEpoch]) {
        let w = size.width / CGFloat(epochs.count)
        let rowH = size.height / 4
        for (i, e) in epochs.enumerated() {
            let x = CGFloat(i) * w
            ctx.fill(Path(CGRect(x: x, y: CGFloat(e.phase.hypnogramRow) * rowH, width: w, height: rowH)), with: .color(e.phase.color))
            if e.hasSnore {
                ctx.fill(Path(CGRect(x: x, y: 0, width: w, height: size.height)), with: .color(Warn.snore.opacity(0.33)))
            }
        }
        for r in 1...3 {
            ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: CGFloat(r) * rowH)); $0.addLine(to: CGPoint(x: size.width, y: CGFloat(r) * rowH)) },
                       with: .color(.white.opacity(0.13)), lineWidth: 1)
        }
    }

    private func drawSnore(_ ctx: GraphicsContext, _ size: CGSize, _ epochs: [SleepEpoch]) {
        let w = size.width / CGFloat(epochs.count)
        let mid = size.height / 2
        ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: mid)); $0.addLine(to: CGPoint(x: size.width, y: mid)) },
                   with: .color(.white.opacity(0.13)), lineWidth: 1.5)
        for (i, e) in epochs.enumerated() {
            let r: CGFloat = e.hasSnore ? 7 : 2.8
            let c = CGPoint(x: (CGFloat(i) + 0.5) * w, y: mid)
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)),
                     with: .color(e.hasSnore ? Warn.snore : .white.opacity(0.2)))
        }
    }

    private func drawIntensity(_ ctx: GraphicsContext, _ size: CGSize, _ epochs: [SleepEpoch]) {
        let w = size.width / CGFloat(epochs.count)
        let maxE = CGFloat(max(epochs.map(\.rmsEnergy).max() ?? 0, 1e-6))
        let barW = max(w * 0.65, 2)
        for (i, e) in epochs.enumerated() {
            let h = CGFloat(e.rmsEnergy) / maxE * size.height
            ctx.fill(Path(CGRect(x: CGFloat(i) * w + (w - barW) / 2, y: size.height - h, width: barW, height: h)),
                     with: .color(rmsColor.opacity(0.75)))
        }
    }
}

private struct PhaseBadge: View {
    let phase: SleepPhase
    var body: some View {
        Text(phase.label.uppercased())
            .font(.subheadline.weight(.semibold)).kerning(2)
            .foregroundStyle(phase.color)
            .padding(.horizontal, 20).padding(.vertical, 6)
            .background(phase.color.opacity(0.25), in: Capsule())
            .accessibilityLabel("Current phase \(phase.label)")
    }
}

private struct ChartCard<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.caption).foregroundStyle(.white.opacity(0.6))
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(cardColor, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct LegendDot: View {
    let color: Color
    let label: String
    var body: some View {
        HStack(spacing: 4) {
            Rectangle().fill(color).frame(width: 10, height: 8)
            Text(label).font(.caption2).foregroundStyle(dimText)
        }
    }
}

private struct MiniSignalGraph: View {
    let label: String
    let data: [Float]
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Rectangle().fill(color).frame(width: 10, height: 3)
                Text(label).font(.caption2).foregroundStyle(color.opacity(0.8))
            }
            Canvas { ctx, size in
                ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: size.height / 2)); $0.addLine(to: CGPoint(x: size.width, y: size.height / 2)) },
                           with: .color(.white.opacity(0.13)), lineWidth: 1)
                let s = smooth(data)
                guard s.count >= 2 else { return }
                let sorted = s.sorted()
                let maxV = CGFloat(max(sorted[min(Int(Float(sorted.count) * 0.95), sorted.count - 1)], 1e-6))
                let step = size.width / (graphSlots - 1)
                let offset = graphSlots - CGFloat(s.count)
                var path = Path()
                for (i, v) in s.enumerated() {
                    let pt = CGPoint(x: (offset + CGFloat(i)) * step,
                                     y: size.height * (1 - min(max(CGFloat(v) / maxV, 0), 1)))
                    i == 0 ? path.move(to: pt) : path.addLine(to: pt)
                }
                ctx.stroke(path, with: .color(color), lineWidth: 2)
            }
            .frame(height: 55)
            .accessibilityHidden(true)
        }
    }

    private func smooth(_ d: [Float], window: Int = 5) -> [Float] {
        guard d.count >= 2 else { return d }
        return d.indices.map { i in
            let slice = d[max(0, i - window + 1)...i]
            return slice.reduce(0, +) / Float(slice.count)
        }
    }
}

/// Horizontally scrolling epoch chart that follows the newest epoch, with
/// clock labels every 5 minutes.
private struct EpochStrip: View {
    let epochs: [SleepEpoch]
    let height: CGFloat
    let draw: (GraphicsContext, CGSize, [SleepEpoch]) -> Void

    var body: some View {
        GeometryReader { geo in
            let width = max(epochWidth * CGFloat(epochs.count), geo.size.width)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    VStack(spacing: 2) {
                        Canvas { ctx, size in
                            guard !epochs.isEmpty else { return }
                            draw(ctx, size, epochs)
                        }
                        .frame(width: width, height: height)
                        Canvas { ctx, size in
                            guard !epochs.isEmpty else { return }
                            let w = size.width / CGFloat(epochs.count)
                            for (i, e) in epochs.enumerated() where i % labelEveryN == 0 {
                                ctx.draw(Text(Date(ms: e.timestamp), format: .dateTime.hour().minute())
                                            .font(.system(size: 10)).foregroundStyle(.white.opacity(0.53)),
                                         at: CGPoint(x: (CGFloat(i) + 0.5) * w, y: 8))
                            }
                        }
                        .frame(width: width, height: 18)
                        Color.clear.frame(width: 1, height: 1).id("end")
                    }
                }
                .onChange(of: epochs.count) { _, _ in
                    withAnimation { proxy.scrollTo("end", anchor: .trailing) }
                }
                .onAppear { proxy.scrollTo("end", anchor: .trailing) }
            }
        }
        .frame(height: height + 20)
        .accessibilityElement()
        .accessibilityLabel("\(epochs.count) epochs recorded")
    }
}
