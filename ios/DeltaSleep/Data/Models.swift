import SwiftUI

// Swift mirrors of the Android Room entities (app/.../data/model). Stored
// ordinals and column names are identical so exports and docs/schema.md
// describe both platforms.

/// Sleep phase; raw values are the stored ordinals (REM appended last, A-1).
enum SleepPhase: Int, CaseIterable, Identifiable {
    case awake = 0, light = 1, deep = 2, rem = 3

    var id: Int { rawValue }

    var label: String {
        switch self {
        case .awake: "Awake"
        case .light: "Light"
        case .deep: "Deep"
        case .rem: "REM (est.)"
        }
    }

    /// CSV name, matching Kotlin `SleepPhase.name`.
    var name: String {
        switch self {
        case .awake: "AWAKE"
        case .light: "LIGHT"
        case .deep: "DEEP"
        case .rem: "REM"
        }
    }

    var color: Color {
        switch self {
        case .awake: Color(hex: 0xE53935)
        case .light: Color(hex: 0x42A5F5)
        case .deep: Color(hex: 0x1565C0)
        case .rem: Color(hex: 0x9C27B0)
        }
    }

    /// Hypnogram row, top to bottom: Awake, Light, REM, Deep.
    var hypnogramRow: Int {
        switch self {
        case .awake: 0
        case .light: 1
        case .rem: 2
        case .deep: 3
        }
    }

    static let hypnogramOrder: [SleepPhase] = [.awake, .light, .rem, .deep]

    init(ordinal: Int) {
        self = SleepPhase(rawValue: min(max(ordinal, 0), SleepPhase.allCases.count - 1)) ?? .awake
    }
}

enum AcousticEventType: Int, CaseIterable {
    case apneaLike = 0, hypopneaLike = 1, gasp = 2, snoreEpisode = 3

    var name: String {
        switch self {
        case .apneaLike: "APNEA_LIKE"
        case .hypopneaLike: "HYPOPNEA_LIKE"
        case .gasp: "GASP"
        case .snoreEpisode: "SNORE_EPISODE"
        }
    }

    init(ordinal: Int) {
        self = AcousticEventType(rawValue: min(max(ordinal, 0), 3)) ?? .apneaLike
    }
}

enum SignalQuality: Int {
    case good = 0, fair = 1, low = 2

    var name: String {
        switch self {
        case .good: "GOOD"
        case .fair: "FAIR"
        case .low: "LOW"
        }
    }
}

enum AcousticBand: Int {
    case none = 0, mild = 1, moderate = 2, severe = 3

    var name: String {
        switch self {
        case .none: "NONE"
        case .mild: "MILD"
        case .moderate: "MODERATE"
        case .severe: "SEVERE"
        }
    }
}

enum RiskBand {
    case low, elevated, high

    var name: String {
        switch self {
        case .low: "LOW"
        case .elevated: "ELEVATED"
        case .high: "HIGH"
        }
    }
}

struct SleepSession: Identifiable, Hashable {
    var id: Int64 = 0
    var startTime: Int64
    var endTime: Int64?
    var feelRating: Int?
}

struct SleepEpoch: Identifiable {
    var id: Int64 = 0
    var sessionId: Int64
    /// Wall-clock ms at epoch flush (end of the 30 s window).
    var timestamp: Int64
    var phase: SleepPhase
    var hasSnore: Bool
    var rmsEnergy: Float
    var breathingMarginDb: Float = 0
    var breathingPresentFraction: Float = 0
    /// Mean breath period (s); nil when breathing was never present (A-7).
    var breathPeriodS: Float?
    var externalAudioFraction: Float = 0
    /// Another app was playing audio at flush (A-4).
    var playbackActive: Bool = false
}

struct AcousticEvent: Identifiable {
    var id: Int64 = 0
    var sessionId: Int64
    var type: AcousticEventType
    var startUtc: Int64
    var durationMs: Int64
    var confidence: Float
    var peakDbOverFloor: Float
    var envelopeReductionPct: Float
    var terminatedByGasp: Bool
    var meanDbOverFloor: Float
}

struct NightSummary {
    var sessionId: Int64
    var totalSleepTimeMin: Int
    var reiA: Float
    var apneaLikeCount: Int
    var hypopneaLikeCount: Int
    var longestEventS: Float
    var snorePctOfSleep: Float
    var meanSnoreDbOverFloor: Float
    var signalQuality: SignalQuality
    var acousticBand: AcousticBand
}

struct QuestionnaireResult {
    var id: Int64 = 0
    var dateUtc: Int64
    var snoring: Bool
    var tiredness: Bool
    var observedApnea: Bool
    var highPressure: Bool
    var bmiOver35: Bool
    var ageOver50: Bool
    var neckOver40cm: Bool
    var maleGender: Bool
    var score: Int
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

/// Current wall-clock time in ms, matching `System.currentTimeMillis()`.
func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

extension Date {
    init(ms: Int64) { self.init(timeIntervalSince1970: TimeInterval(ms) / 1000) }
}
