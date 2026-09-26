import AVFoundation
import SwiftUI

// MARK: - Setup / explainer (FR-8)

/// Runs the 10 s breathing-level test (FR-8.2). Uses the shared DSP engine,
/// so it refuses to run while tracking is active.
@MainActor
final class LevelTest: ObservableObject {
    enum State { case idle, running, done }

    @Published var state: State = .idle
    @Published var marginDb: Float = 0
    @Published var error: String?
    private var capture: AudioCapture?
    private var frames = 0

    func start() async {
        guard state != .running, !SleepTracker.shared.isTracking else { return }
        guard await SleepTracker.requestMicPermission() else {
            error = "Microphone access is off. Enable it in Settings › DeltaSleep."
            return
        }
        error = nil
        state = .running
        marginDb = 0
        frames = 0
        DSP.startSession()
        let capture = AudioCapture()
        capture.onFrame = { [weak self] frame in
            let m = DSP.processFrame(frame)
            guard m.count > 4 else { return }
            let margin = m[4]
            Task { @MainActor in self?.onFrame(margin) }
        }
        do {
            try AudioCapture.configureSession()
            try capture.start()
            self.capture = capture
        } catch {
            self.error = "Could not start the microphone."
            state = .done
            return
        }
        // Cap the test even if audio stalls.
        try? await Task.sleep(nanoseconds: 12_000_000_000)
        if state == .running { finish() }
    }

    private func onFrame(_ margin: Float) {
        guard state == .running else { return }
        frames += 1
        if frames % 20 == 0 { marginDb = margin }
        if frames >= 1000 { finish() }   // 10 s of 10 ms frames
    }

    private func finish() {
        capture?.stop()
        capture = nil
        if !SleepTracker.shared.isTracking {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        state = .done
    }

    func cancel() {
        capture?.stop()
        capture = nil
        state = .idle
        marginDb = 0
    }
}

struct ApneaSetupView: View {
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var tracker: SleepTracker
    @StateObject private var test = LevelTest()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                DisclaimerBox(text: disclaimerText)
                Card {
                    Text("What is measured").font(.subheadline.bold())
                    para("The app listens for breathing sounds and pauses (silences ≥ 10 s) during sleep. When breathing resumes with a gasp-like sound after a pause, the event is counted as an apnea-like event. No audio is ever stored — only aggregated counts and timing are saved.")
                }
                Card {
                    Text("Bed-partner caveat").font(.subheadline.bold())
                    para("Results are unreliable if another person or pet sleeps within approximately 1 m closer to the phone than you are. Their breathing or movement sounds may be confused with yours.")
                }
                Card {
                    Text("Phone placement").font(.subheadline.bold())
                    para("Place the iPhone on a nightstand or mattress edge, microphone unobstructed, 0.5–1.5 m from your head. Do not put it under a pillow.")
                }
                Card {
                    Text("Test breathing sound level").font(.subheadline.bold())
                    para("Tap to run a 10-second test. Breathe normally. A margin ≥ 10 dB is good; below 6 dB may reduce accuracy.")
                    levelTest.padding(.top, 6)
                }
                Card {
                    Toggle(isOn: $settings.apneaScreeningEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Enable apnea screening").font(.subheadline.weight(.semibold))
                            Text("Analyses breathing sounds for risk indication. Off by default.").font(.caption).foregroundStyle(p.onSurfaceVariant)
                        }
                    }
                }
                Button { dismiss() } label: {
                    Text("Save settings").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
            }
            .screen(p)
        }
        .background(p.background)
        .navigationTitle("Apnea Risk Screening")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { settings.apneaExplainerShown = true }
        .onDisappear { test.cancel() }
    }

    @ViewBuilder
    private var levelTest: some View {
        if tracker.isTracking {
            para("Cannot test while sleep tracking is active.")
        } else {
            switch test.state {
            case .idle:
                Button { Task { await test.start() } } label: {
                    Text("Test sound level (10 s)").frame(maxWidth: .infinity, minHeight: 40)
                }
                .buttonStyle(.bordered)
            case .running:
                Text("Testing… breathe normally").font(.footnote).foregroundStyle(p.primary)
                meter
            case .done:
                meter
                Text(String(format: "%.1f dB — %@", test.marginDb, marginLabel(test.marginDb)))
                    .font(.footnote.weight(.semibold)).foregroundStyle(marginColor(test.marginDb))
                Button("Test again") { test.cancel() }
            }
            if let e = test.error { Text(e).font(.footnote).foregroundStyle(p.error) }
        }
    }

    private var meter: some View {
        ProgressView(value: Double(min(max(test.marginDb / 20, 0), 1)))
            .tint(marginColor(test.marginDb))
            .accessibilityLabel(String(format: "Breathing margin %.1f decibels", test.marginDb))
    }

    private func para(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(p.onSurfaceVariant)
    }

    private func marginColor(_ db: Float) -> Color { db >= 10 ? Warn.good : (db >= 6 ? Warn.fair : Warn.bad) }
    private func marginLabel(_ db: Float) -> String {
        db >= 10 ? "Good signal" : (db >= 6 ? "Fair signal" : "Poor signal — try repositioning phone")
    }
}

// MARK: - STOP-BANG questionnaire (FR-4)

struct QuestionnaireView: View {
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss
    @State private var answers = [Bool](repeating: false, count: 8)
    @State private var prefillApplied = false
    @State private var savedScore: Int?

    private static let questions = [
        "Do you snore loudly (louder than talking, or loud enough to be heard through closed doors)?",
        "Do you often feel tired, fatigued, or sleepy during the daytime?",
        "Has anyone observed you stop breathing during your sleep?",
        "Do you have (or are you being treated for) high blood pressure?",
        "Is your BMI more than 35?",
        "Are you older than 50?",
        "Is your neck circumference more than 40 cm (about 16 inches)?",
        "Is your gender male?",
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text("Answer yes or no to each question. You can skip or edit later.")
                    .font(.footnote).foregroundStyle(p.onSurfaceVariant)
                if prefillApplied {
                    Text("Suggested from your measurements — you can change these.")
                        .font(.footnote.italic()).foregroundStyle(p.primary)
                }
                ForEach(0..<8, id: \.self) { i in
                    Card(padding: 12) {
                        Toggle(isOn: $answers[i]) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Self.questions[i]).font(.footnote).foregroundStyle(p.onSurfaceVariant)
                                if prefillApplied && (i == 0 || i == 2) {
                                    Text("suggested").font(.caption2.italic()).foregroundStyle(p.primary)
                                }
                            }
                        }
                        .disabled(savedScore != nil)
                    }
                }
                if let score = savedScore {
                    let band = RiskModel.stopBangBand(score)
                    Card {
                        Text("Score: \(score) / 8 — \(band.label) risk").font(.headline).foregroundStyle(bandColor(band))
                        Text("STOP-BANG: Chung et al., Anesthesiology 2008; validated OSA screening questionnaire.")
                            .font(.footnote).foregroundStyle(p.onSurfaceVariant)
                    }
                    Button { dismiss() } label: { Text("View report").frame(maxWidth: .infinity, minHeight: 44) }
                        .buttonStyle(.borderedProminent)
                } else {
                    HStack {
                        Button("Skip") { dismiss() }.frame(maxWidth: .infinity)
                        Button { save() } label: { Text("Save questionnaire").frame(maxWidth: .infinity, minHeight: 44) }
                            .buttonStyle(.borderedProminent)
                    }
                    .padding(.top, 8)
                }
            }
            .screen(p)
        }
        .background(p.background)
        .navigationTitle("STOP-BANG")
        .onAppear(perform: load)
    }

    private func load() {
        let db = AppDatabase.shared
        if let q = db.latestQuestionnaire() {
            answers = [q.snoring, q.tiredness, q.observedApnea, q.highPressure, q.bmiOver35, q.ageOver50, q.neckOver40cm, q.maleGender]
        } else if let pre = RiskModel.stopBangPrefill(db.recentSummaries(limit: 30, excludeLowQuality: true)) {
            answers[0] = pre.snoring
            answers[2] = pre.observedApnea
            prefillApplied = true
        }
    }

    private func save() {
        let a = answers
        let score = a.filter { $0 }.count
        AppDatabase.shared.insertQuestionnaire(QuestionnaireResult(
            dateUtc: nowMs(), snoring: a[0], tiredness: a[1], observedApnea: a[2], highPressure: a[3],
            bmiOver35: a[4], ageOver50: a[5], neckOver40cm: a[6], maleGender: a[7], score: score))
        savedScore = score
    }

    private func bandColor(_ b: RiskModel.StopBangBand) -> Color {
        switch b {
        case .low: Warn.good
        case .intermediate: Warn.fair
        case .high: Warn.bad
        }
    }
}
