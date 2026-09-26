import SwiftUI

/// Colour tokens from the Android theme (ui/theme/Theme.kt), resolved from
/// the user's theme setting plus the system appearance.
struct Palette {
    let background: Color
    let surface: Color
    let surfaceVariant: Color
    let onBackground: Color
    let onSurfaceVariant: Color
    let primary: Color
    let onPrimary: Color
    let secondary: Color
    let outline: Color
    let error: Color
    let isDark: Bool

    static let dark = Palette(
        background: Color(hex: 0x0B0F19), surface: Color(hex: 0x121826), surfaceVariant: Color(hex: 0x1C2438),
        onBackground: Color(hex: 0xE3E9F7), onSurfaceVariant: Color(hex: 0xA9B6D1),
        primary: Color(hex: 0x8AB4FF), onPrimary: Color(hex: 0x0A1A33), secondary: Color(hex: 0x7DD3C0),
        outline: Color(hex: 0x2A3550), error: Color(hex: 0xFFB4AB), isDark: true)

    /// Pure black: every non-black pixel is glare next to a sleeping user.
    static let amoled = Palette(
        background: .black, surface: .black, surfaceVariant: Color(hex: 0x101014),
        onBackground: Color(hex: 0xE3E9F7), onSurfaceVariant: Color(hex: 0xA9B6D1),
        primary: Color(hex: 0x8AB4FF), onPrimary: .black, secondary: Color(hex: 0x7DD3C0),
        outline: Color(hex: 0x26262B), error: Color(hex: 0xFFB4AB), isDark: true)

    static let light = Palette(
        background: Color(hex: 0xF6F8FC), surface: .white, surfaceVariant: Color(hex: 0xE8EDF6),
        onBackground: Color(hex: 0x101828), onSurfaceVariant: Color(hex: 0x4A5878),
        primary: Color(hex: 0x2456A6), onPrimary: .white, secondary: Color(hex: 0x0E6B5C),
        outline: Color(hex: 0xCBD5E8), error: Color(hex: 0xB3261E), isDark: false)

    static func resolve(_ theme: AppTheme, system: ColorScheme) -> Palette {
        switch theme {
        case .light: .light
        case .dark: .dark
        case .amoledBlack: .amoled
        case .system: system == .dark ? .dark : .light
        }
    }
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette.dark
}

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

enum Warn {
    static let orange = Color(hex: 0xE65100)
    static let good = Color(hex: 0x4CAF50)
    static let fair = Color(hex: 0xFF9800)
    static let bad = Color(hex: 0xE53935)
    static let snore = Color(hex: 0xFF4081)
}

// MARK: - Shared building blocks

struct Card<Content: View>: View {
    @Environment(\.palette) private var p
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(p.surfaceVariant, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct StatCard: View {
    @Environment(\.palette) private var p
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.weight(.semibold)).foregroundStyle(p.onSurfaceVariant)
                .minimumScaleFactor(0.6).lineLimit(1)
            Text(label.uppercased()).font(.caption2).foregroundStyle(p.onSurfaceVariant.opacity(0.7))
        }
        .padding(.vertical, 14).padding(.horizontal, 8)
        .frame(maxWidth: .infinity)
        .background(p.surfaceVariant, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}

struct DisclaimerBox: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(Warn.orange)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Warn.orange.opacity(0.13), in: RoundedRectangle(cornerRadius: 8))
    }
}

struct LegendChip: View {
    let color: Color
    let label: String

    var body: some View {
        HStack(spacing: 5) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 14, height: 10)
            Text(label).font(.caption)
        }
    }
}

extension View {
    /// Standard scrolling screen body on the palette background.
    func screen(_ p: Palette) -> some View {
        self.frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
    }
}

let disclaimerText = "This is not a medical device and does not diagnose any condition. Only a sleep study interpreted by a clinician can diagnose sleep apnea. If your results suggest elevated risk, discuss them with a doctor."
