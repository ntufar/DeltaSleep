import SwiftUI

// Consolidated settings (D-3), backed by UserDefaults with the same frozen
// key names as the Android SettingsKeys so a future cross-platform backup
// (C-3) can map them one-to-one.

enum MicSensitivity: String, CaseIterable, Identifiable {
    case low, normal, high

    var id: String { rawValue }

    /// dB offset on the DSP snore threshold; negative catches quieter snores.
    var thresholdOffsetDb: Float {
        switch self {
        case .low: 6
        case .normal: 0
        case .high: -6
        }
    }

    var summary: String {
        switch self {
        case .low: "Low — only loud, clear snores (+6 dB bar)"
        case .normal: "Normal — default threshold"
        case .high: "High — catches quiet snores (−6 dB bar)"
        }
    }
}

enum AppTheme: String, CaseIterable, Identifiable {
    case system, light, dark, amoledBlack = "amoled_black"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        case .amoledBlack: "AMOLED black"
        }
    }
}

final class SettingsStore: ObservableObject {
    static let shared = SettingsStore()

    private enum Key {
        static let micSensitivity = "mic_sensitivity"
        static let snoreEnabled = "snore_enabled"
        static let apneaScreeningEnabled = "apnea_screening_enabled"
        static let apneaExplainerShown = "apnea_explainer_shown"
        static let theme = "theme"
        static let retention = "retention"
    }

    private let defaults: UserDefaults

    @Published var micSensitivity: MicSensitivity {
        didSet { defaults.set(micSensitivity.rawValue, forKey: Key.micSensitivity) }
    }
    @Published var snoreEnabled: Bool {
        didSet { defaults.set(snoreEnabled, forKey: Key.snoreEnabled) }
    }
    /// Opt-in (R1.1.3); default off.
    @Published var apneaScreeningEnabled: Bool {
        didSet { defaults.set(apneaScreeningEnabled, forKey: Key.apneaScreeningEnabled) }
    }
    @Published var apneaExplainerShown: Bool {
        didSet { defaults.set(apneaExplainerShown, forKey: Key.apneaExplainerShown) }
    }
    @Published var theme: AppTheme {
        didSet { defaults.set(theme.rawValue, forKey: Key.theme) }
    }
    /// Default 365 days (PRD).
    @Published var retention: Retention {
        didSet { defaults.set(retention.rawValue, forKey: Key.retention) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        micSensitivity = MicSensitivity(rawValue: defaults.string(forKey: Key.micSensitivity) ?? "") ?? .normal
        snoreEnabled = defaults.object(forKey: Key.snoreEnabled) as? Bool ?? true
        apneaScreeningEnabled = defaults.bool(forKey: Key.apneaScreeningEnabled)
        apneaExplainerShown = defaults.bool(forKey: Key.apneaExplainerShown)
        theme = AppTheme(rawValue: defaults.string(forKey: Key.theme) ?? "") ?? .system
        retention = Retention(rawValue: defaults.string(forKey: Key.retention) ?? "") ?? .days365
    }
}
