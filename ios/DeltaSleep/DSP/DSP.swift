import Foundation

/// Swift face of the Rust DSP core (dsp/src/ffi_c.rs). The Rust side owns
/// one global engine behind a mutex, so — as on Android — only one
/// consumer (the tracker, or the setup level test) may drive it at a time.
enum DSP {
    static let frameSamples = 160
    private static let maxEventsPerPoll = 64
    private static let eventStride = 8

    /// [rms, zcr, band_power_ratio, noise_floor_db, breathing_margin_db, breathing_present]
    static func processFrame(_ samples: UnsafeBufferPointer<Int16>) -> [Float] {
        var out = [Float](repeating: 0, count: 6)
        let n = out.withUnsafeMutableBufferPointer {
            ds_process_frame(samples.baseAddress, samples.count, $0.baseAddress, $0.count)
        }
        return n == 6 ? out : []
    }

    /// 11-float epoch summary (layout documented in DeltaSleepDSP.h).
    static func computeEpoch() -> [Float] {
        var out = [Float](repeating: 0, count: 11)
        let n = out.withUnsafeMutableBufferPointer { ds_compute_epoch($0.baseAddress, $0.count) }
        return n == 11 ? out : []
    }

    static func resetEpoch() { ds_reset_epoch() }
    static func startSession() { ds_start_session() }
    static func setSnoreThresholdOffsetDb(_ db: Float) { ds_set_snore_threshold_offset_db(db) }

    /// Drained events, flattened with stride 8.
    static func pollEvents() -> [Float] {
        var out = [Float](repeating: 0, count: maxEventsPerPoll * eventStride)
        let count = out.withUnsafeMutableBufferPointer { ds_poll_events($0.baseAddress, $0.count) }
        return Array(out.prefix(count * eventStride))
    }
}

/// An acoustic event from `pollEvents`, offsets relative to `startSession`.
struct ParsedEvent {
    let type: AcousticEventType
    let startOffsetMs: Int64
    let durationMs: Int64
    let confidence: Float
    let peakDbOverFloor: Float
    let envelopeReductionPct: Float
    let terminatedByGasp: Bool
    let meanDbOverFloor: Float
}

struct EpochResult {
    var epoch: SleepEpoch
    var events: [ParsedEvent]
}

/// Map a `computeEpoch` array to an epoch, or nil when it is too short.
/// Mirrors `epochFromResult` in EpochProcessor.kt.
func epochFromResult(_ r: [Float], snoreDetectionEnabled: Bool) -> SleepEpoch? {
    guard r.count >= 6 else { return nil }
    // mean_rms == 0 means the mic delivered pure zeros (access lost): never
    // let the classifier call that DEEP sleep.
    let phase: SleepPhase = r[0] == 0 ? .awake : SleepPhase(ordinal: Int(r[4]))
    return SleepEpoch(
        sessionId: 0,
        timestamp: nowMs(),
        phase: phase,
        hasSnore: snoreDetectionEnabled && r[5] != 0,
        rmsEnergy: r[0],
        breathingMarginDb: r.count > 6 ? r[6] : 0,
        breathingPresentFraction: r.count > 7 ? r[7] : 0,
        breathPeriodS: r.count > 8 && r[8] > 0 ? r[8] : nil,
        externalAudioFraction: r.count > 9 ? min(max(r[9], 0), 1) : 0
    )
}

/// Accumulates 10 ms frames into 30 s epochs (3,000 frames). Single-threaded:
/// call only from the tracker's processing queue.
final class EpochProcessor {
    static let framesPerEpoch = 3000

    var snoreDetectionEnabled = true
    private(set) var lastFrameMetrics: [Float] = Array(repeating: 0, count: 6)
    private var frameCount = 0

    func onFrame(_ samples: UnsafeBufferPointer<Int16>) -> EpochResult? {
        let m = DSP.processFrame(samples)
        if !m.isEmpty { lastFrameMetrics = m }
        frameCount += 1
        return frameCount >= Self.framesPerEpoch ? flush() : nil
    }

    private func flush() -> EpochResult? {
        let result = DSP.computeEpoch()
        // Drain before reset so every event of this window is captured.
        let raw = DSP.pollEvents()
        DSP.resetEpoch()
        frameCount = 0
        guard let epoch = epochFromResult(result, snoreDetectionEnabled: snoreDetectionEnabled) else { return nil }
        let events = Self.parseEvents(raw).filter { snoreDetectionEnabled || $0.type != .snoreEpisode }
        return EpochResult(epoch: epoch, events: events)
    }

    private static func parseEvents(_ raw: [Float]) -> [ParsedEvent] {
        var out: [ParsedEvent] = []
        var b = 0
        while b + 8 <= raw.count {
            let type = AcousticEventType(ordinal: Int(raw[b]))
            let start = Int64(raw[b + 1])
            let duration = Int64(raw[b + 2])
            out.append(ParsedEvent(
                type: type, startOffsetMs: start, durationMs: duration,
                confidence: raw[b + 3], peakDbOverFloor: raw[b + 4], envelopeReductionPct: raw[b + 5],
                terminatedByGasp: raw[b + 6] != 0, meanDbOverFloor: raw[b + 7]))
            b += 8
        }
        return out
    }

    func reset() {
        DSP.resetEpoch()
        frameCount = 0
    }
}
