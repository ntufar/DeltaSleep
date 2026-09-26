import XCTest
@testable import DeltaSleep

/// Parity tests: the same fixtures and expectations as the Kotlin unit tests
/// (NightSummarizerTest, RiskModelTest, CsvExporterTest, TrendsMathTest), so
/// both platforms stay in lock-step.
final class NightSummarizerTests: XCTestCase {
    private func epochs(_ n: Int, _ phase: SleepPhase, base: Int64 = 0, margin: Float = 10) -> [SleepEpoch] {
        (0..<n).map { i in
            SleepEpoch(id: Int64(i + 1), sessionId: 1, timestamp: base + Int64(i + 1) * 30_000,
                       phase: phase, hasSnore: false, rmsEnergy: 0.5, breathingMarginDb: margin)
        }
    }

    private func event(_ type: AcousticEventType, mid: Int64, id: Int64 = 0) -> AcousticEvent {
        AcousticEvent(id: id, sessionId: 1, type: type, startUtc: mid - 5_000, durationMs: 10_000, confidence: 0.8,
                      peakDbOverFloor: 10, envelopeReductionPct: 0.9, terminatedByGasp: false, meanDbOverFloor: 5)
    }

    func testReiAIsZeroWithoutSleep() {
        let (s, _) = NightSummarizer.compute(sessionId: 1, epochs: [], events: [])
        XCTAssertEqual(s.reiA, 0)
        XCTAssertEqual(s.apneaLikeCount, 0)
    }

    func testEightHoursEightApneasIsOnePerHour() {
        let e = epochs(960, .light)
        let ev = (0..<8).map { event(.apneaLike, mid: Int64($0) * 3_600_000 + 60_000) }
        let (s, _) = NightSummarizer.compute(sessionId: 1, epochs: e, events: ev)
        XCTAssertEqual(s.reiA, 1.0, accuracy: 0.01)
        XCTAssertEqual(s.apneaLikeCount, 8)
    }

    func testHypopneaNotCountedInReiA() {
        let (s, _) = NightSummarizer.compute(sessionId: 1, epochs: epochs(60, .light),
                                             events: [event(.apneaLike, mid: 100_000), event(.hypopneaLike, mid: 200_000)])
        XCTAssertEqual(s.apneaLikeCount, 1)
        XCTAssertEqual(s.hypopneaLikeCount, 1)
        XCTAssertEqual(s.reiA, 2.0, accuracy: 0.1)
    }

    func testEventsInAwakeEpochsAreDiscarded() {
        let e = epochs(10, .awake) + epochs(10, .light, base: 300_000)
        let (_, discard) = NightSummarizer.compute(sessionId: 1, epochs: e,
                                                   events: [event(.apneaLike, mid: 100_000, id: 7), event(.apneaLike, mid: 450_000, id: 8)])
        XCTAssertEqual(discard, [7])
    }

    func testSignalQualityBands() {
        let good = NightSummarizer.compute(sessionId: 1, epochs: epochs(100, .light, margin: 10), events: []).0
        XCTAssertEqual(good.signalQuality, .good)
        let low = NightSummarizer.compute(sessionId: 1, epochs: epochs(70, .light, margin: 10) + epochs(30, .light, base: 5_000_000, margin: 2), events: []).0
        XCTAssertEqual(low.signalQuality, .low)
        let fair = NightSummarizer.compute(sessionId: 1, epochs: epochs(85, .light, margin: 10) + epochs(15, .light, base: 5_000_000, margin: 2), events: []).0
        XCTAssertEqual(fair.signalQuality, .fair)
    }

    func testAcousticBandThresholds() {
        XCTAssertEqual(NightSummarizer.acousticBand(reiA: 4.9), .none)
        XCTAssertEqual(NightSummarizer.acousticBand(reiA: 5), .mild)
        XCTAssertEqual(NightSummarizer.acousticBand(reiA: 14.9), .mild)
        XCTAssertEqual(NightSummarizer.acousticBand(reiA: 15), .moderate)
        XCTAssertEqual(NightSummarizer.acousticBand(reiA: 29.9), .moderate)
        XCTAssertEqual(NightSummarizer.acousticBand(reiA: 30), .severe)
    }

    func testExternalEpochsLeaveDenominators() {
        var e = epochs(120, .light)
        for i in 0..<60 { e[i].externalAudioFraction = 0.9 }
        let (s, _) = NightSummarizer.compute(sessionId: 1, epochs: e, events: [])
        XCTAssertEqual(s.totalSleepTimeMin, 30)
    }

    func testPlaybackLowersExternalBar() {
        XCTAssertFalse(ExternalAudio.isExternal(fraction: 0.4, playbackActive: false))
        XCTAssertTrue(ExternalAudio.isExternal(fraction: 0.4, playbackActive: true))
    }

    // A-1 REM post-processing

    func testSmoothEmpty() {
        XCTAssertEqual(NightSummarizer.smoothPhases([]), [])
    }

    func testSingleRemIslandRemoved() {
        let phases = Array(repeating: SleepPhase.light, count: 130) + [.rem] + Array(repeating: .light, count: 5)
        XCTAssertFalse(NightSummarizer.smoothPhases(phases).contains(.rem))
    }

    func testShortRemRunMerges() {
        let phases = Array(repeating: SleepPhase.light, count: 130) + Array(repeating: .rem, count: 3) + Array(repeating: .light, count: 10)
        XCTAssertEqual(NightSummarizer.smoothPhases(phases), Array(repeating: .light, count: phases.count))
    }

    func testLongRemRunKept() {
        let phases = Array(repeating: SleepPhase.light, count: 130) + Array(repeating: .rem, count: 6) + Array(repeating: .light, count: 10)
        XCTAssertEqual(NightSummarizer.smoothPhases(phases), phases)
    }

    func testRemInFirstHourSuppressed() {
        let phases = Array(repeating: SleepPhase.light, count: 10) + Array(repeating: .rem, count: 6) + Array(repeating: .light, count: 130)
        XCTAssertFalse(NightSummarizer.smoothPhases(phases).prefix(120).contains(.rem))
    }

    func testStableRunsAreFixedPoint() {
        let phases = Array(repeating: SleepPhase.light, count: 130) + Array(repeating: .deep, count: 70)
        XCTAssertEqual(NightSummarizer.smoothPhases(phases), phases)
    }
}

final class RiskModelTests: XCTestCase {
    private func night(_ rei: Float, _ q: SignalQuality = .good, snore: Float = 0) -> NightSummary {
        NightSummary(sessionId: 1, totalSleepTimeMin: 420, reiA: rei, apneaLikeCount: 0, hypopneaLikeCount: 0,
                     longestEventS: 0, snorePctOfSleep: snore, meanSnoreDbOverFloor: 0, signalQuality: q,
                     acousticBand: NightSummarizer.acousticBand(reiA: rei))
    }

    func testStopBangBands() {
        XCTAssertEqual(RiskModel.stopBangBand(2), .low)
        XCTAssertEqual(RiskModel.stopBangBand(3), .intermediate)
        XCTAssertEqual(RiskModel.stopBangBand(4), .intermediate)
        XCTAssertEqual(RiskModel.stopBangBand(5), .high)
    }

    func testMatrix() {
        XCTAssertEqual(RiskModel.riskMatrix(.none, .intermediate), .low)
        XCTAssertEqual(RiskModel.riskMatrix(.none, .high), .elevated)
        XCTAssertEqual(RiskModel.riskMatrix(.mild, .low), .low)
        XCTAssertEqual(RiskModel.riskMatrix(.mild, .intermediate), .elevated)
        XCTAssertEqual(RiskModel.riskMatrix(.moderate, .low), .elevated)
        XCTAssertEqual(RiskModel.riskMatrix(.moderate, .high), .high)
        XCTAssertEqual(RiskModel.riskMatrix(.severe, .low), .high)
    }

    func testNeedsFiveGoodNights() {
        let nights = [night(40), night(40), night(40), night(40), night(40, .low)]
        guard case .notEnoughData(let n) = RiskModel.computeRiskBand(nights, latest: nil) else { return XCTFail() }
        XCTAssertEqual(n, 4)
    }

    func testSingleOutlierCannotProduceHigh() {
        let nights = [night(1), night(2), night(1), night(3), night(80)]
        guard case .result(let band, let med, _, _) = RiskModel.computeRiskBand(nights, latest: nil) else { return XCTFail() }
        XCTAssertEqual(med, 2)
        XCTAssertEqual(band, .low)
    }

    func testPrefill() {
        let nights = (0..<5).map { _ in night(6, snore: 20) }
        let pre = RiskModel.stopBangPrefill(nights)
        XCTAssertEqual(pre?.snoring, true)
        XCTAssertEqual(pre?.observedApnea, true)
        XCTAssertNil(RiskModel.stopBangPrefill(Array(nights.prefix(4))))
    }
}

final class ExportAndTrendTests: XCTestCase {
    func testCsvSectionsAndNullBreathPeriod() {
        let session = SleepSession(id: 3, startTime: 1000, endTime: 2000)
        let e = SleepEpoch(sessionId: 3, timestamp: 1500, phase: .rem, hasSnore: true, rmsEnergy: 0.25)
        let csv = CsvExporter.build(session: session, epochs: [e], events: [], summary: nil)
        let lines = csv.components(separatedBy: "\n")
        XCTAssertEqual(lines[0], "session_id,start_time_ms,end_time_ms,epoch_timestamp_ms,phase,has_snore,rms_energy,breathing_margin_db,breathing_present_fraction,breath_period_s,external_audio_fraction,playback_active")
        XCTAssertEqual(lines[1], "3,1000,2000,1500,REM,true,0.25,0.0,0.0,,0.0,false")
        XCTAssertTrue(csv.contains("\n# acoustic_events\n"))
        XCTAssertTrue(csv.contains("\n# night_summary\n"))
    }

    func testMinutesSinceDayBoundary() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        func ms(_ h: Int, _ m: Int) -> Int64 {
            Int64(cal.date(from: DateComponents(year: 2026, month: 1, day: 2, hour: h, minute: m))!.timeIntervalSince1970 * 1000)
        }
        XCTAssertEqual(TrendMath.minutesSinceDayBoundary(ms(23, 30), calendar: cal), 330)
        XCTAssertEqual(TrendMath.minutesSinceDayBoundary(ms(0, 30), calendar: cal), 390)
        XCTAssertEqual(TrendMath.minutesSinceDayBoundary(ms(7, 0), calendar: cal), 780)
    }

    func testRegularity() {
        XCTAssertNil(TrendMath.regularityScore([]))
        XCTAssertEqual(TrendMath.regularityScore([300, 310, 320, 500]), 0.75)
    }

    func testSnoreIntensityBuckets() {
        XCTAssertEqual(SnoreIntensity.level(.nan), 1)
        XCTAssertEqual(SnoreIntensity.level(5.9), 1)
        XCTAssertEqual(SnoreIntensity.level(6), 2)
        XCTAssertEqual(SnoreIntensity.level(18), 4)
        XCTAssertEqual(SnoreIntensity.level(30), 5)
    }
}

final class DSPBridgeTests: XCTestCase {
    /// End-to-end through the Rust static library: 30 s of low noise must
    /// produce one epoch with the documented layout.
    func testRustEngineProducesEpoch() {
        DSP.startSession()
        let processor = EpochProcessor()
        var result: EpochResult?
        var rng = SystemRandomNumberGenerator()
        var frame = [Int16](repeating: 0, count: DSP.frameSamples)
        for _ in 0..<EpochProcessor.framesPerEpoch {
            for i in frame.indices { frame[i] = Int16.random(in: -200...200, using: &rng) }
            if let r = frame.withUnsafeBufferPointer({ processor.onFrame($0) }) { result = r }
        }
        let epoch = try! XCTUnwrap(result).epoch
        XCTAssertGreaterThan(epoch.rmsEnergy, 0)
        XCTAssertEqual(processor.lastFrameMetrics.count, 6)
    }

    func testDatabaseRoundTripAndSecureNuke() {
        let path = NSTemporaryDirectory() + "ds-test-\(UUID().uuidString).db"
        let db = AppDatabase(path: path)
        let id = db.insertSession(startTime: 1)
        db.insertEpoch(SleepEpoch(sessionId: id, timestamp: 30_001, phase: .deep, hasSnore: false, rmsEnergy: 0.1, breathPeriodS: 4))
        XCTAssertEqual(db.epochs(sessionId: id).first?.breathPeriodS, 4)
        XCTAssertEqual(db.epochs(sessionId: id).first?.phase, .deep)
        db.deleteAll()
        XCTAssertTrue(db.allSessions().isEmpty)
        XCTAssertTrue(db.epochs(sessionId: id).isEmpty)
    }
}
