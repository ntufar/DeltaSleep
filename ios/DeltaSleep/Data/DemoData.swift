#if DEBUG
import Foundation

/// DEBUG-only fixtures for simulator testing and App Store screenshots.
/// Launch with `-seedDemoData` to replace the DB with 21 plausible nights;
/// `-autoStartTracking` starts a session on launch. Compiled out of Release.
enum DemoData {
    static func applyLaunchArguments() {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-seedDemoData") { seed(db: .shared) }
        if args.contains("-autoStartTracking") {
            Task { @MainActor in await SleepTracker.shared.start() }
        }
    }

    static func seed(db: AppDatabase, nights: Int = 21) {
        db.deleteAll()
        var rng = SeededRNG(seed: 42)
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        for n in (1...nights).reversed() {
            let day = cal.date(byAdding: .day, value: -n, to: today)!
            let bedMin = 22 * 60 + 40 + Int.random(in: -40...50, using: &rng)
            let start = Int64(day.addingTimeInterval(TimeInterval(bedMin * 60)).timeIntervalSince1970 * 1000)
            let epochCount = Int.random(in: 780...940, using: &rng)
            let id = db.insertSession(startTime: start)
            var epochs: [SleepEpoch] = []
            for i in 0..<epochCount {
                let t = Double(i) / Double(epochCount)
                // ~90-minute cycles: deep early, REM later, brief wakes.
                let cycle = sin(Double(i) / 180 * 2 * .pi)
                var phase: SleepPhase
                if i < 25 { phase = .awake }
                else if cycle < -0.45 && t < 0.6 { phase = .deep }
                else if cycle > 0.55 && t > 0.3 { phase = .rem }
                else { phase = .light }
                if Double.random(in: 0...1, using: &rng) < 0.012 { phase = .awake }
                let snore = phase != .awake && Double.random(in: 0...1, using: &rng) < (phase == .deep ? 0.35 : 0.12)
                epochs.append(SleepEpoch(
                    sessionId: id, timestamp: start + Int64(i + 1) * 30_000, phase: phase, hasSnore: snore,
                    rmsEnergy: Float(phase == .awake ? 0.05 : 0.012) * Float.random(in: 0.6...1.4, using: &rng),
                    breathingMarginDb: Float.random(in: 8...16, using: &rng),
                    breathingPresentFraction: Float.random(in: 0.6...0.95, using: &rng),
                    breathPeriodS: phase == .awake ? nil : Float.random(in: 3.6...4.4, using: &rng)))
            }
            epochs.forEach(db.insertEpoch)
            var events: [AcousticEvent] = []
            for e in epochs where e.hasSnore && Double.random(in: 0...1, using: &rng) < 0.5 {
                events.append(AcousticEvent(sessionId: id, type: .snoreEpisode, startUtc: e.timestamp - 25_000,
                                            durationMs: 20_000, confidence: 0.8,
                                            peakDbOverFloor: Float.random(in: 4...22, using: &rng),
                                            envelopeReductionPct: 0, terminatedByGasp: false,
                                            meanDbOverFloor: Float.random(in: 4...12, using: &rng)))
            }
            for _ in 0..<Int.random(in: 8...22, using: &rng) {
                let e = epochs[Int.random(in: 60..<epochs.count, using: &rng)]
                guard e.phase != .awake else { continue }
                events.append(AcousticEvent(sessionId: id, type: Bool.random(using: &rng) ? .apneaLike : .hypopneaLike,
                                            startUtc: e.timestamp - 22_000, durationMs: Int64.random(in: 10_000...24_000, using: &rng),
                                            confidence: 0.7, peakDbOverFloor: 3, envelopeReductionPct: 0.8,
                                            terminatedByGasp: false, meanDbOverFloor: 1))
            }
            db.insertEvents(events)
            var s = db.session(id: id)!
            s.endTime = epochs.last!.timestamp
            s.feelRating = Int.random(in: 3...5, using: &rng)
            db.updateSession(s)
            NightSummarizer.summarize(db: db, sessionId: id)
        }
        db.insertQuestionnaire(QuestionnaireResult(dateUtc: nowMs(), snoring: true, tiredness: true, observedApnea: false,
                                                   highPressure: false, bmiOver35: false, ageOver50: false,
                                                   neckOver40cm: true, maleGender: true, score: 4))
        SettingsStore.shared.apneaScreeningEnabled = true
        SettingsStore.shared.apneaExplainerShown = true
    }
}

/// Deterministic generator so screenshots are reproducible.
struct SeededRNG: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
#endif
