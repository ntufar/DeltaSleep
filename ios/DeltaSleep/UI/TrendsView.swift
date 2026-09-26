import Charts
import SwiftUI

/// Trends dashboard (D-1): weekly duration bars, 30-day deep-%, snore by
/// weekday, breathing-rate trend, and bedtime consistency.
struct TrendsView: View {
    @Environment(\.palette) private var p
    @State private var data: TrendsData?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if let d = data {
                    if d.nights.isEmpty {
                        Text("Not enough data yet — track a night to see trends.")
                            .foregroundStyle(p.onSurfaceVariant)
                    } else {
                        TrendsContent(nights: d.nights, weekBars: d.weekBars)
                    }
                } else {
                    ProgressView()
                }
            }
            .screen(p)
        }
        .background(p.background)
        .navigationTitle("Trends")
        .onAppear { data = TrendMath.load(db: .shared, windowDays: 90) }
        .onReceive(AppDatabase.shared.didChange) { data = TrendMath.load(db: .shared, windowDays: 90) }
    }
}

private struct TrendsContent: View {
    @Environment(\.palette) private var p
    let nights: [NightStat]
    let weekBars: [DayTotal]

    private var month: [NightStat] {
        let cutoff = nowMs() - 30 * 86_400_000
        return nights.filter { $0.startMs >= cutoff }
    }

    var body: some View {
        let month = month
        Text("Based on \(nights.count) nights (last 90 days)").font(.footnote).foregroundStyle(p.onSurfaceVariant)

        section("Sleep this week")
        Chart(weekBars) { d in
            BarMark(x: .value("Day", d.date, unit: .day), y: .value("Hours", d.minutes / 60))
                .foregroundStyle(Color(hex: 0x42A5F5))
        }
        .chartXAxis { AxisMarks(values: .stride(by: .day)) { AxisValueLabel(format: .dateTime.weekday(.narrow)) } }
        .chartYAxis { AxisMarks { v in AxisGridLine(); AxisValueLabel { Text("\(v.as(Double.self).map { Int($0) } ?? 0)h") } } }
        .chartYScale(domain: 0...max(8, Double(weekBars.map(\.minutes).max() ?? 0) / 60))
        .frame(height: 180)

        section("Deep sleep % · 30 days")
        lineChart(month.enumerated().map { ($0.offset, $0.element.deepPct) }, domain: 0...100, label: "%")

        section("Snore by weekday · 90 days")
        WeekdayHeatmap(avgs: TrendMath.weekdaySnoreAvg(nights))

        let rr = month.enumerated().map { ($0.offset, $0.element.medianBpm) }
        if rr.contains(where: { $0.1 != nil }) {
            section("Breathing rate · 30 days")
            Text("Median breaths/min per night").font(.footnote).foregroundStyle(p.onSurfaceVariant)
            lineChart(rr, domain: 5...35, label: "")
        }

        section("Bedtime consistency · 30 days")
        let beds = month.map { TrendMath.minutesSinceDayBoundary($0.startMs) }
        let wakes = month.map { TrendMath.minutesSinceDayBoundary($0.endMs) }
        Chart {
            if let med = median(beds.map(Float.init)) {
                RectangleMark(yStart: .value("", Double(med) - 30), yEnd: .value("", Double(med) + 30))
                    .foregroundStyle(Color(hex: 0x42A5F5, alpha: 0.13))
            }
            ForEach(Array(beds.enumerated()), id: \.offset) { i, m in
                PointMark(x: .value("Night", i), y: .value("Time", m)).foregroundStyle(by: .value("Kind", "Bedtime"))
            }
            ForEach(Array(wakes.enumerated()), id: \.offset) { i, m in
                PointMark(x: .value("Night", i), y: .value("Time", m)).foregroundStyle(by: .value("Kind", "Wake"))
            }
        }
        .chartForegroundStyleScale(["Bedtime": Color(hex: 0x42A5F5), "Wake": Color(hex: 0xFF9800)])
        .chartYScale(domain: 0...1080)
        .chartYAxis {
            AxisMarks(values: [0, 360, 720, 1080]) { v in
                AxisGridLine()
                AxisValueLabel { Text(["18:00", "0:00", "6:00", "12:00"][(v.as(Int.self) ?? 0) / 360]) }
            }
        }
        .chartXAxis(.hidden)
        .frame(height: 200)
        Text(TrendMath.regularityScore(beds).map { String(format: "Regularity %.0f%% — bedtimes within ±30 min of median", $0 * 100) }
             ?? "Regularity unavailable")
            .font(.footnote).foregroundStyle(p.onSurfaceVariant)
    }

    private func section(_ title: String) -> some View {
        Text(title).font(.headline).padding(.top, 16)
    }

    /// Per-night line; nil values break the line instead of plotting zero.
    private func lineChart(_ points: [(Int, Float?)], domain: ClosedRange<Double>, label: String) -> some View {
        var segment = 0
        var rows: [(x: Int, y: Float, seg: Int)] = []
        for (x, y) in points {
            if let y { rows.append((x, y, segment)) } else { segment += 1 }
        }
        return Chart(rows, id: \.x) { r in
            LineMark(x: .value("Night", r.x), y: .value("Value", r.y), series: .value("Segment", r.seg))
                .foregroundStyle(Color(hex: 0x26A69A))
            PointMark(x: .value("Night", r.x), y: .value("Value", r.y))
                .foregroundStyle(Color(hex: 0x26A69A)).symbolSize(20)
        }
        .chartYScale(domain: domain)
        .chartYAxis { AxisMarks { v in AxisGridLine(); AxisValueLabel { Text("\(v.as(Double.self).map { Int($0) } ?? 0)\(label)") } } }
        .chartXAxis(.hidden)
        .frame(height: 180)
    }
}

private struct WeekdayHeatmap: View {
    let avgs: [Int: Float]

    var body: some View {
        let cal = Calendar.current
        let order = (0..<7).map { (cal.firstWeekday - 1 + $0) % 7 + 1 }
        HStack(spacing: 4) {
            ForEach(order, id: \.self) { wd in
                let v = avgs[wd]
                VStack(spacing: 6) {
                    Text(cal.veryShortWeekdaySymbols[wd - 1]).font(.caption)
                    Text(v.map { String(format: "%.0f%%", $0) } ?? "–").font(.caption.weight(.semibold))
                }
                .frame(maxWidth: .infinity, minHeight: 64)
                .background(Warn.snore.opacity(v.map { Double(min(max($0 / 50, 0.12), 1)) } ?? 0), in: RoundedRectangle(cornerRadius: 4))
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(cal.weekdaySymbols[wd - 1]): " + (v.map { String(format: "%.0f percent snoring", $0) } ?? "no data"))
            }
        }
    }
}
