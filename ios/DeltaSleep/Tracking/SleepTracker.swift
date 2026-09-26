import AVFoundation
import Combine
import UIKit

/// Live per-frame metrics published at ~0.5 Hz for the active screen.
struct LiveFrame {
    var rms: Float
    var zcr: Float
    var bandRatio: Float
    var noiseFloorDb: Float
    var breathingMarginDb: Float
    var breathingPresent: Bool
}

/// The iOS counterpart of Android's SleepTrackingService.
///
/// Background operation relies on the `audio` background mode: an active
/// recording session keeps the app running with the screen locked. Every
/// 30 s epoch is written to SQLite as soon as it closes, so a crash or
/// force-quit loses at most the unfinished epoch; [recoverOrphanedSessions]
/// closes such nights on the next launch.
@MainActor
final class SleepTracker: ObservableObject {
    static let shared = SleepTracker()

    static let historySize = 90   // 90 samples × 2 s = 3 min of live signal

    @Published private(set) var activeSessionId: Int64?
    @Published private(set) var rmsHistory: [Float] = []
    @Published private(set) var zcrHistory: [Float] = []
    @Published private(set) var bandHistory: [Float] = []
    /// The session just stopped; Home navigates to its results.
    @Published var finishedSessionId: Int64?
    /// Set when the mic could not be started (permission denied, busy).
    @Published var startError: String?

    var isTracking: Bool { activeSessionId != nil }

    private let db: AppDatabase
    private let settings: SettingsStore
    private let processor = EpochProcessor()
    /// One queue for all captures so a restarted capture never runs the
    /// processor concurrently with frames still queued from the last one.
    private let processingQueue = DispatchQueue(label: "deltasleep.processing", qos: .userInitiated)
    private var capture: AudioCapture?
    private var dspStartWallMs: Int64 = 0
    private var observers: [NSObjectProtocol] = []
    private var restartBackoff: TimeInterval = 5

    init(db: AppDatabase = .shared, settings: SettingsStore = .shared) {
        self.db = db
        self.settings = settings
    }

    // MARK: - Start / stop

    func start() async {
        guard !isTracking else { return }
        guard await Self.requestMicPermission() else {
            startError = "Microphone access is off. Enable it in Settings › DeltaSleep to track sleep."
            return
        }
        let id = db.insertSession(startTime: nowMs())
        rmsHistory = []
        zcrHistory = []
        bandHistory = []
        activeSessionId = id
        beginCapture()
        observeAudioSession()
    }

    /// Close the session, summarise, purge, and release the microphone.
    func stop() {
        guard let id = activeSessionId else { return }
        teardownCapture()
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        UIApplication.shared.isIdleTimerDisabled = false
        activeSessionId = nil

        let screening = settings.apneaScreeningEnabled
        let retention = settings.retention
        let db = db
        DispatchQueue.global(qos: .userInitiated).async {
            if var s = db.session(id: id) {
                s.endTime = nowMs()
                db.updateSession(s)
            }
            if screening { NightSummarizer.summarize(db: db, sessionId: id) }
            RetentionPolicy.purgeExpired(db: db, now: nowMs(), retention: retention)
            DispatchQueue.main.async { SleepTracker.shared.finishedSessionId = id }
        }
    }

    /// Close nights left open by a crash or force-quit: end them at their
    /// last stored epoch (the data we actually have) and summarise.
    func recoverOrphanedSessions() {
        let db = db
        let screening = settings.apneaScreeningEnabled
        let active = activeSessionId
        DispatchQueue.global(qos: .utility).async {
            for var s in db.openSessions() where s.id != active {
                s.endTime = db.epochs(sessionId: s.id).last?.timestamp ?? s.startTime
                db.updateSession(s)
                if screening { NightSummarizer.summarize(db: db, sessionId: s.id) }
            }
        }
    }

    // MARK: - Capture

    private func beginCapture() {
        guard let sessionId = activeSessionId else { return }
        // Full DSP reset per start/restart; events are timed from here.
        DSP.startSession()
        DSP.setSnoreThresholdOffsetDb(settings.micSensitivity.thresholdOffsetDb)
        processor.snoreDetectionEnabled = settings.snoreEnabled
        dspStartWallMs = nowMs()

        let capture = AudioCapture(queue: processingQueue)
        let processor = processor
        let db = db
        let screening = settings.apneaScreeningEnabled
        let startWall = dspStartWallMs
        var emitCounter = 0

        capture.onFrame = { [weak self] frame in
            if let result = processor.onFrame(frame) {
                Self.persist(result, sessionId: sessionId, startWall: startWall,
                             screening: screening, snore: processor.snoreDetectionEnabled, db: db)
            }
            emitCounter += 1
            if emitCounter >= 200 {
                emitCounter = 0
                let m = processor.lastFrameMetrics
                let live = LiveFrame(rms: m[0], zcr: m[1], bandRatio: m[2],
                                     noiseFloorDb: m[3], breathingMarginDb: m[4], breathingPresent: m[5] != 0)
                Task { @MainActor in self?.push(live) }
            }
        }
        capture.onSilenceStall = { [weak self] in
            Task { @MainActor in self?.restartCapture() }
        }

        do {
            try AudioCapture.configureSession()
            try capture.start()
            self.capture = capture
            restartBackoff = 5
        } catch {
            self.capture = nil
            scheduleRestart()
        }
    }

    private static func persist(_ result: EpochResult, sessionId: Int64, startWall: Int64,
                                screening: Bool, snore: Bool, db: AppDatabase) {
        var epoch = result.epoch
        epoch.sessionId = sessionId
        // A-4: another app rendering audio lowers the external-audio bar.
        epoch.playbackActive = AVAudioSession.sharedInstance().isOtherAudioPlaying
        db.insertEpoch(epoch)
        // Apnea types only with screening on (FR-8.1); snore episodes follow
        // the snore toggle (A-6).
        let events = result.events
            .filter { $0.type == .snoreEpisode ? snore : screening }
            .map { p in
                AcousticEvent(sessionId: sessionId, type: p.type, startUtc: startWall + p.startOffsetMs,
                              durationMs: p.durationMs, confidence: p.confidence,
                              peakDbOverFloor: p.peakDbOverFloor, envelopeReductionPct: p.envelopeReductionPct,
                              terminatedByGasp: p.terminatedByGasp, meanDbOverFloor: p.meanDbOverFloor)
            }
        db.insertEvents(events)
    }

    private func push(_ f: LiveFrame) {
        guard isTracking else { return }
        rmsHistory = Array((rmsHistory + [f.rms]).suffix(Self.historySize))
        zcrHistory = Array((zcrHistory + [f.zcr]).suffix(Self.historySize))
        bandHistory = Array((bandHistory + [f.bandRatio]).suffix(Self.historySize))
    }

    private func teardownCapture() {
        guard let capture else { return }
        capture.stop()
        let processor = processor
        capture.queue.sync { processor.reset() }
        self.capture = nil
    }

    private func restartCapture() {
        guard isTracking else { return }
        teardownCapture()
        beginCapture()
    }

    /// Retry with exponential backoff (5 s → 60 s), like the Android loop.
    private func scheduleRestart() {
        let delay = restartBackoff
        restartBackoff = min(restartBackoff * 2, 60)
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, self.isTracking, self.capture == nil else { return }
            self.beginCapture()
        }
    }

    // MARK: - Interruptions (phone calls, Siri, route changes)

    private func observeAudioSession() {
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let type = raw.flatMap(AVAudioSession.InterruptionType.init(rawValue:))
            Task { @MainActor in
                guard let self, self.isTracking else { return }
                switch type {
                case .began: self.teardownCapture()
                case .ended: self.beginCapture()
                default: break
                }
            }
        })
        observers.append(nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.restartCapture() }
        })
        observers.append(nc.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.restartCapture() }
        })
    }

    static func requestMicPermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .denied: return false
        default: return await AVAudioApplication.requestRecordPermission()
        }
    }
}
