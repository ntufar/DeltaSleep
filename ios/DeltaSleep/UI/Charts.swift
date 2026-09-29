import SwiftUI

private let axisColor = Color(hex: 0x8A94A8)

/// Hypnogram: X = time, Y = Awake / Light / REM / Deep.
///
/// Readability design (mirrors Android `HypnogramChart`):
/// - The data area scrolls horizontally with at least `minEpochWidth` per
///   30 s epoch, so a full night is inspected by swiping instead of reading
///   sub-pixel slivers. Y-axis labels stay fixed on the left.
/// - The top `stripH` is reserved for event markers: apnea/hypopnea bars in
///   the top lane, snore bars (height = intensity) in a lane below. Apnea
///   uses dark maroon `Warn.apnea` — never the Awake-phase red — with a
///   light outline, so Awake / apnea / snore are distinguishable.
struct HypnogramChart: View {
    let epochs: [SleepEpoch]
    let startMs: Int64
    let endMs: Int64
    let events: [AcousticEvent]
    var minEpochWidth: CGFloat = 4

    private let totalH: CGFloat = 240
    private let labelW: CGFloat = 64
    private let stripH: CGFloat = 20

    var body: some View {
        GeometryReader { geo in
            let axisH: CGFloat = endMs > startMs ? 22 : 0
            let chartH = totalH - axisH
            let phaseH = chartH - stripH
            let rowH = phaseH / 4
            let viewportW = max(geo.size.width - labelW, 48)
            let contentW = max(viewportW, minEpochWidth * CGFloat(max(epochs.count, 1)))
            HStack(spacing: 0) {
                // Fixed Y-axis labels.
                VStack(spacing: 0) {
                    Spacer().frame(height: stripH)
                    ForEach(SleepPhase.hypnogramOrder, id: \.self) { phase in
                        Text(phase.label).font(.system(size: 11)).foregroundStyle(axisColor)
                            .frame(width: labelW - 6, height: rowH, alignment: .trailing)
                    }
                    Spacer().frame(height: axisH)
                }
                .frame(width: labelW)
                // Scrollable data area.
                ScrollView(.horizontal, showsIndicators: true) {
                    Canvas { ctx, size in
                        guard !epochs.isEmpty, size.width > 0, rowH > 0 else { return }
                        let chartHPx = chartH
                        let epochW = size.width / CGFloat(epochs.count)

                        // Faint snore-epoch wash, phase area only.
                        for (k, e) in epochs.enumerated() where e.hasSnore {
                            ctx.fill(Path(CGRect(x: CGFloat(k) * epochW, y: stripH, width: epochW, height: chartHPx - stripH)),
                                     with: .color(Warn.snore.opacity(0.08)))
                        }

                        // Contiguous runs of a phase merge into one rounded block.
                        var i = 0
                        while i < epochs.count {
                            let phase = epochs[i].phase
                            var j = i + 1
                            while j < epochs.count && epochs[j].phase == phase { j += 1 }
                            let rect = CGRect(x: CGFloat(i) * epochW + 0.5, y: stripH + CGFloat(phase.hypnogramRow) * rowH + 0.5,
                                              width: CGFloat(j - i) * epochW - 1, height: rowH - 1)
                            ctx.fill(Path(roundedRect: rect, cornerRadius: 3), with: .color(phase.color))
                            i = j
                        }
                        for r in 1..<4 {
                            ctx.stroke(hLine(y: stripH + CGFloat(r) * rowH, from: 0, to: size.width), with: .color(.gray.opacity(0.15)), lineWidth: 1)
                        }

                        guard endMs > startMs else { return }
                        let dur = CGFloat(endMs - startMs)
                        for e in events {
                            let x0 = min(max(CGFloat(e.startUtc - startMs) / dur, 0), 1) * size.width
                            let x1 = min(max(CGFloat(e.startUtc + e.durationMs - startMs) / dur, 0), 1) * size.width
                            let w = max(x1 - x0, 3)
                            switch e.type {
                            case .apneaLike:
                                let r = CGRect(x: x0, y: 0, width: w, height: 6)
                                ctx.fill(Path(r), with: .color(Warn.apnea))
                                ctx.stroke(Path(r), with: .color(Warn.apneaOutline), lineWidth: 1)
                            case .hypopneaLike:
                                ctx.fill(Path(CGRect(x: x0, y: 0, width: w, height: 6)), with: .color(Warn.fair))
                            case .snoreEpisode:
                                let h = CGFloat(3 + 2 * SnoreIntensity.level(e.peakDbOverFloor))
                                ctx.fill(Path(CGRect(x: x0, y: 8, width: w, height: h)), with: .color(Warn.snore))
                            case .gasp: break
                            }
                        }

                        // Hour ticks.
                        let cal = Calendar.current
                        var tick = cal.nextDate(after: Date(ms: startMs), matching: DateComponents(minute: 0, second: 0),
                                                matchingPolicy: .nextTime)!
                        let end = Date(ms: endMs)
                        while tick <= end {
                            let x = CGFloat(tick.timeIntervalSince1970 * 1000 - Double(startMs)) / dur * size.width
                            ctx.stroke(Path { $0.move(to: CGPoint(x: x, y: 0)); $0.addLine(to: CGPoint(x: x, y: chartHPx)) },
                                       with: .color(.gray.opacity(0.27)), lineWidth: 1)
                            ctx.draw(Text(tick, format: .dateTime.hour().minute()).font(.system(size: 10)).foregroundStyle(axisColor),
                                     at: CGPoint(x: x, y: totalH - 8))
                            tick = cal.date(byAdding: .hour, value: 1, to: tick)!
                        }
                    }
                    .frame(width: contentW, height: totalH)
                }
            }
        }
        .frame(height: totalH)
        .accessibilityElement()
        .accessibilityLabel(accessibilitySummary)
    }

    private var accessibilitySummary: String {
        let counts = SleepPhase.allCases.map { p in "\(p.label) \(epochs.filter { $0.phase == p }.count / 2) minutes" }
        return "Hypnogram. " + counts.joined(separator: ", ")
    }
}

/// Breathing rate per epoch (A-7); epochs without a measured period break
/// the line instead of plotting fake zeros.
struct BreathingChart: View {
    let epochs: [SleepEpoch]
    private let minBpm: CGFloat = 5, maxBpm: CGFloat = 35

    var body: some View {
        Canvas { ctx, size in
            guard !epochs.isEmpty else { return }
            let labelW: CGFloat = 36
            let chartW = size.width - labelW
            guard chartW > 0 else { return }
            let w = chartW / CGFloat(epochs.count)
            func y(_ bpm: CGFloat) -> CGFloat { size.height - (min(max(bpm, minBpm), maxBpm) - minBpm) / (maxBpm - minBpm) * size.height }
            for g in [10, 20, 30] {
                ctx.stroke(hLine(y: y(CGFloat(g)), from: labelW, to: size.width), with: .color(.gray.opacity(0.27)), lineWidth: 1)
                ctx.draw(Text("\(g)").font(.system(size: 10)).foregroundStyle(axisColor), at: CGPoint(x: labelW - 6, y: y(CGFloat(g))), anchor: .trailing)
            }
            let line = Color(hex: 0x26A69A)
            var path = Path()
            var prev: CGPoint?
            for (i, e) in epochs.enumerated() {
                guard let bpm = BreathingRate.bpm(e.breathPeriodS) else { prev = nil; continue }
                let pt = CGPoint(x: labelW + (CGFloat(i) + 0.5) * w, y: y(CGFloat(bpm)))
                if let prev { path.move(to: prev); path.addLine(to: pt) }
                ctx.fill(Path(ellipseIn: CGRect(x: pt.x - 2.5, y: pt.y - 2.5, width: 5, height: 5)), with: .color(line))
                prev = pt
            }
            ctx.stroke(path, with: .color(line), lineWidth: 2)
        }
        .frame(height: 160)
        .accessibilityElement()
        .accessibilityLabel(BreathingRate.medianBpm(epochs).map { String(format: "Breathing rate chart, median %.0f breaths per minute", $0) } ?? "Breathing rate chart, no data")
    }
}

func hLine(y: CGFloat, from x0: CGFloat, to x1: CGFloat) -> Path {
    Path { $0.move(to: CGPoint(x: x0, y: y)); $0.addLine(to: CGPoint(x: x1, y: y)) }
}
