import Foundation

// Pure analysis logic, ported 1:1 from the Android app (apnea/, audio/).
// Thresholds and rules must stay in lock-step with the Kotlin sources.

/// Breathing-rate presentation (A-7): breath period (s) → breaths/min.
enum BreathingRate {
    static func bpm(_ periodS: Float?) -> Float? {
        guard let p = periodS, p > 0 else { return nil }
        return 60 / p
    }

    static func medianBpm(_ epochs: [SleepEpoch]) -> Float? {
        medianBpm(periods: epochs.compactMap(\.breathPeriodS))
    }

    static func medianBpm(periods: [Float]) -> Float? {
        median(periods.compactMap { bpm($0) })
    }
}

/// External-audio verdict (A-4): podcasts/TV while tracking.
enum ExternalAudio {
    static let thresholdSpeech: Float = 0.5
    static let thresholdWithPlayback: Float = 0.3

    static func isExternal(_ e: SleepEpoch) -> Bool {
        isExternal(fraction: e.externalAudioFraction, playbackActive: e.playbackActive)
    }

    static func isExternal(fraction: Float, playbackActive: Bool) -> Bool {
        fraction > (playbackActive ? thresholdWithPlayback : thresholdSpeech)
    }
}

/// Snore intensity 1–5 (A-6) from peak dB over the noise floor.
enum SnoreIntensity {
    static func level(_ peakDbOverFloor: Float) -> Int {
        guard peakDbOverFloor.isFinite, peakDbOverFloor >= 6 else { return 1 }
        if peakDbOverFloor < 12 { return 2 }
        if peakDbOverFloor < 18 { return 3 }
        if peakDbOverFloor < 24 { return 4 }
        return 5
    }

    static func loudest(_ events: [AcousticEvent]) -> Int? {
        events.filter { $0.type == .snoreEpisode }.map { level($0.peakDbOverFloor) }.max()
    }
}

/// Median of a list, nil when empty (same contract as TrendMath.median).
func median(_ values: [Float]) -> Float? {
    guard !values.isEmpty else { return nil }
    let s = values.sorted()
    let mid = s.count / 2
    return s.count % 2 == 1 ? s[mid] : (s[mid - 1] + s[mid]) / 2
}

// MARK: - Night summarizer

enum NightSummarizer {
    private static let epochDurationS = 30
    private static let breathingMarginThresholdDb: Float = 6
    private static let signalQualityLow: Float = 0.20
    private static let signalQualityFair: Float = 0.10
    private static let smoothHalfWidth = 2
    private static let remSuppressEpochs = 120
    private static let remMinRun = 4

    /// A-1 post-processing: ±2 median filter, no REM in the first 60 min,
    /// REM runs shorter than 4 epochs merged into their neighbours.
    static func smoothPhases(_ phases: [SleepPhase]) -> [SleepPhase] {
        guard !phases.isEmpty else { return phases }
        let medianed: [SleepPhase] = phases.indices.map { i in
            if i < smoothHalfWidth || i + smoothHalfWidth >= phases.count { return phases[i] }
            let window = (i - smoothHalfWidth...i + smoothHalfWidth).map { phases[$0].rawValue }.sorted()
            return SleepPhase(ordinal: window[window.count / 2])
        }
        var out = medianed.enumerated().map { i, p in
            (i < remSuppressEpochs && p == .rem) ? SleepPhase.light : p
        }
        var i = 0
        while i < out.count {
            guard out[i] == .rem else { i += 1; continue }
            var j = i
            while j < out.count && out[j] == .rem { j += 1 }
            if j - i < remMinRun {
                let fill: SleepPhase = i > 0 ? out[i - 1] : (j < out.count ? out[j] : .light)
                for k in i..<j { out[k] = fill }
            }
            i = j
        }
        return out
    }

    /// Per-night metrics; returns the summary plus event ids to discard
    /// (midpoint inside an AWAKE or external-audio epoch — FR-2.3, A-4).
    static func compute(sessionId: Int64, epochs: [SleepEpoch], events: [AcousticEvent]) -> (NightSummary, [Int64]) {
        let phases = smoothPhases(epochs.map(\.phase))
        let windowMs = Int64(epochDurationS) * 1000
        let awakeWindows = epochs.indices.filter { phases[$0] == .awake }
            .map { (epochs[$0].timestamp - windowMs)..<epochs[$0].timestamp }
        let externalWindows = epochs.filter { ExternalAudio.isExternal($0) }
            .map { ($0.timestamp - windowMs)..<$0.timestamp }

        var discard: [Int64] = []
        var kept: [AcousticEvent] = []
        for e in events {
            let mid = e.startUtc + e.durationMs / 2
            if awakeWindows.contains(where: { $0.contains(mid) }) || externalWindows.contains(where: { $0.contains(mid) }) {
                if e.id != 0 { discard.append(e.id) }
            } else {
                kept.append(e)
            }
        }

        let sleepEpochs = epochs.indices
            .filter { phases[$0] != .awake && !ExternalAudio.isExternal(epochs[$0]) }
            .map { epochs[$0] }
        let sleepCount = sleepEpochs.count
        let tstMin = sleepCount * epochDurationS / 60

        let apnea = kept.filter { $0.type == .apneaLike }
        let hypopnea = kept.filter { $0.type == .hypopneaLike }
        let hours = Float(tstMin) / 60
        let reiA = hours > 0 ? Float(apnea.count) / hours : 0
        let longest = (apnea + hypopnea).map { Float($0.durationMs) / 1000 }.max() ?? 0

        let snorePct = sleepCount > 0 ? Float(sleepEpochs.filter(\.hasSnore).count) / Float(sleepCount) * 100 : 0
        let snoreEvents = kept.filter { $0.type == .snoreEpisode }
        let meanSnoreDb = snoreEvents.isEmpty ? 0
            : snoreEvents.map(\.meanDbOverFloor).reduce(0, +) / Float(snoreEvents.count)

        let lowMargin = sleepCount > 0
            ? Float(sleepEpochs.filter { $0.breathingMarginDb < breathingMarginThresholdDb }.count) / Float(sleepCount) : 0
        let quality: SignalQuality = lowMargin > signalQualityLow ? .low : (lowMargin > signalQualityFair ? .fair : .good)

        let summary = NightSummary(
            sessionId: sessionId, totalSleepTimeMin: tstMin, reiA: reiA,
            apneaLikeCount: apnea.count, hypopneaLikeCount: hypopnea.count, longestEventS: longest,
            snorePctOfSleep: snorePct, meanSnoreDbOverFloor: meanSnoreDb,
            signalQuality: quality, acousticBand: acousticBand(reiA: reiA))
        return (summary, discard)
    }

    /// FR-5.1: < 5 NONE, 5–14 MILD, 15–29 MODERATE, ≥ 30 SEVERE.
    static func acousticBand(reiA: Float) -> AcousticBand {
        if reiA < 5 { return .none }
        if reiA < 15 { return .mild }
        if reiA < 30 { return .moderate }
        return .severe
    }

    /// Read, compute, prune discarded events, upsert the summary.
    static func summarize(db: AppDatabase, sessionId: Int64) {
        let (summary, discard) = compute(sessionId: sessionId,
                                         epochs: db.epochs(sessionId: sessionId),
                                         events: db.events(sessionId: sessionId))
        db.deleteEvents(ids: discard)
        db.upsertSummary(summary)
    }
}

// MARK: - Risk model

enum RiskModel {
    static let minNightsForTrending = 5

    enum StopBangBand {
        case low, intermediate, high
        var label: String {
            switch self {
            case .low: "low"
            case .intermediate: "intermediate"
            case .high: "high"
            }
        }
    }

    enum RiskResult {
        case notEnoughData(nightsSoFar: Int)
        case result(riskBand: RiskBand, medianReiA: Float, acousticBand: AcousticBand, questionnaireBand: StopBangBand?)
    }

    /// FR-4.3: 0–2 LOW, 3–4 INTERMEDIATE, 5–8 HIGH.
    static func stopBangBand(_ score: Int) -> StopBangBand {
        score <= 2 ? .low : (score <= 4 ? .intermediate : .high)
    }

    /// FR-5.2: median REI-a over ≥ 5 GOOD/FAIR nights × STOP-BANG band.
    static func computeRiskBand(_ summaries: [NightSummary], latest q: QuestionnaireResult?) -> RiskResult {
        let nights = summaries.filter { $0.signalQuality != .low }
        guard nights.count >= minNightsForTrending, let med = median(nights.map(\.reiA)) else {
            return .notEnoughData(nightsSoFar: nights.count)
        }
        let band = NightSummarizer.acousticBand(reiA: med)
        let qBand = q.map { stopBangBand($0.score) }
        return .result(riskBand: riskMatrix(band, qBand ?? .intermediate), medianReiA: med,
                       acousticBand: band, questionnaireBand: qBand)
    }

    static func riskMatrix(_ a: AcousticBand, _ q: StopBangBand) -> RiskBand {
        switch (a, q) {
        case (.none, .high): .elevated
        case (.none, _): .low
        case (.mild, .low): .low
        case (.mild, _): .elevated
        case (.moderate, .low): .elevated
        case (.moderate, _): .high
        case (.severe, _): .high
        }
    }

    /// FR-4.2 suggestions (snoring, observed apnea) from ≥ 5 qualifying nights.
    static func stopBangPrefill(_ summaries: [NightSummary]) -> (snoring: Bool, observedApnea: Bool)? {
        let nights = summaries.filter { $0.signalQuality != .low }
        guard nights.count >= minNightsForTrending,
              let snore = median(nights.map(\.snorePctOfSleep)),
              let rei = median(nights.map(\.reiA)) else { return nil }
        return (snore > 10, rei >= 5)
    }
}

// MARK: - Retention (C-2)

enum Retention: String, CaseIterable, Identifiable {
    case days30 = "30", days90 = "90", days365 = "365", never

    var id: String { rawValue }

    var days: Int? {
        switch self {
        case .days30: 30
        case .days90: 90
        case .days365: 365
        case .never: nil
        }
    }

    var label: String {
        switch self {
        case .days30: "30 days"
        case .days90: "90 days"
        case .days365: "365 days"
        case .never: "Never delete"
        }
    }
}

enum RetentionPolicy {
    static func cutoffMs(now: Int64, retention: Retention) -> Int64? {
        retention.days.map { now - Int64($0) * 86_400_000 }
    }

    /// Delete finished sessions (cascade) and questionnaires past the window.
    @discardableResult
    static func purgeExpired(db: AppDatabase, now: Int64, retention: Retention) -> Int {
        guard let cutoff = cutoffMs(now: now, retention: retention) else { return 0 }
        let removed = db.deleteSessionsEnded(before: cutoff)
        db.deleteQuestionnaires(olderThan: cutoff)
        if removed > 0 { db.vacuum() }
        return removed
    }

    static func countExpiring(db: AppDatabase, now: Int64, retention: Retention) -> Int {
        guard let cutoff = cutoffMs(now: now, retention: retention) else { return 0 }
        return db.countSessionsEnded(before: cutoff)
    }
}
