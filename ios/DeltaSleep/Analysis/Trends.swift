import Foundation

// Trends dashboard math (D-1), ported from trends/TrendMath.kt and
// TrendsRepository.kt. The "sleep day" rolls over at 18:00 so evening
// bedtimes and next-morning wakes belong to the same night.

let dayBoundaryHour = 18
let regularityWindowMin = 30

struct NightStat: Identifiable {
    var id: Int64 { sessionId }
    let sessionId: Int64
    let startMs: Int64
    let endMs: Int64
    let durationMin: Float
    let deepPct: Float?
    let snorePct: Float?
    let medianBpm: Float?
}

struct DayTotal: Identifiable {
    var id: Date { date }
    /// Start of the calendar day this sleep-day is named after.
    let date: Date
    let minutes: Float
}

struct TrendsData {
    let nights: [NightStat]
    let weekBars: [DayTotal]
}

enum TrendMath {
    static func sleepDay(of ms: Int64, calendar: Calendar = .current) -> Date {
        let date = Date(ms: ms)
        let day = calendar.startOfDay(for: date)
        return calendar.component(.hour, from: date) < dayBoundaryHour
            ? calendar.date(byAdding: .day, value: -1, to: day)!
            : day
    }

    /// Minutes since 18:00: 23:30 → 330, 00:30 → 390, 07:00 → 780.
    static func minutesSinceDayBoundary(_ ms: Int64, calendar: Calendar = .current) -> Int {
        let c = calendar.dateComponents([.hour, .minute], from: Date(ms: ms))
        let mins = (c.hour ?? 0) * 60 + (c.minute ?? 0) - dayBoundaryHour * 60
        return ((mins % 1440) + 1440) % 1440
    }

    /// Fraction of bedtimes within ±30 min of the median bedtime.
    static func regularityScore(_ bedtimes: [Int]) -> Float? {
        guard !bedtimes.isEmpty else { return nil }
        let s = bedtimes.sorted()
        let med = s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
        return Float(bedtimes.filter { abs($0 - med) <= regularityWindowMin }.count) / Float(bedtimes.count)
    }

    static func dailyTotals(_ nights: [NightStat], days: Int, now: Int64, calendar: Calendar = .current) -> [DayTotal] {
        let today = sleepDay(of: now, calendar: calendar)
        var totals: [(Date, Float)] = (0..<days).reversed().map {
            (calendar.date(byAdding: .day, value: -$0, to: today)!, 0)
        }
        for n in nights {
            let d = sleepDay(of: n.startMs, calendar: calendar)
            if let i = totals.firstIndex(where: { $0.0 == d }) { totals[i].1 += n.durationMin }
        }
        return totals.map { DayTotal(date: $0.0, minutes: $0.1) }
    }

    /// Mean snore % per weekday (1 = Sunday … 7 = Saturday, Calendar numbering).
    static func weekdaySnoreAvg(_ nights: [NightStat], calendar: Calendar = .current) -> [Int: Float] {
        var byDay: [Int: [Float]] = [:]
        for n in nights {
            guard let s = n.snorePct else { continue }
            byDay[calendar.component(.weekday, from: Date(ms: n.startMs)), default: []].append(s)
        }
        return byDay.mapValues { $0.reduce(0, +) / Float($0.count) }
    }

    static func load(db: AppDatabase, windowDays: Int, now: Int64 = nowMs()) -> TrendsData {
        let sessions = db.completedSessions(since: now - Int64(windowDays) * 86_400_000)
        guard !sessions.isEmpty else { return TrendsData(nights: [], weekBars: []) }
        let ids = sessions.map(\.id)
        let aggs = db.epochAggregates(sessionIds: ids)
        let periods = db.breathPeriods(sessionIds: ids)
        let nights = sessions.map { s -> NightStat in
            let end = s.endTime ?? s.startTime
            let agg = aggs[s.id].flatMap { $0.epochs > 0 ? $0 : nil }
            return NightStat(
                sessionId: s.id, startMs: s.startTime, endMs: end,
                durationMin: Float(end - s.startTime) / 60000,
                deepPct: agg.map { Float($0.deep) * 100 / Float($0.epochs) },
                snorePct: agg.map { Float($0.snore) * 100 / Float($0.epochs) },
                medianBpm: periods[s.id].flatMap { BreathingRate.medianBpm(periods: $0) })
        }
        return TrendsData(nights: nights, weekBars: dailyTotals(nights, days: 7, now: now))
    }
}
