import SwiftUI

/// Consolidated settings (D-3). Auto-tracking, clock format and sleep need
/// are Android settings with no behaviour behind them yet, so the iOS app
/// leaves them out until those features land.
struct SettingsView: View {
    var onApneaSetup: () -> Void = {}
    @Environment(\.palette) private var p
    @EnvironmentObject private var settings: SettingsStore
    @State private var expiring: Int?
    @State private var confirmNuke = false
    @State private var nuked = false

    var body: some View {
        Form {
            Section {
                Picker("Mic sensitivity", selection: $settings.micSensitivity) {
                    ForEach(MicSensitivity.allCases) { Text($0.rawValue.capitalized).tag($0) }
                }
                .pickerStyle(.segmented)
                Toggle(isOn: $settings.snoreEnabled) {
                    VStack(alignment: .leading) {
                        Text("Snore detection")
                        Text("Record snore flags and episodes").font(.caption).foregroundStyle(p.onSurfaceVariant)
                    }
                }
            } header: { Text("Audio") } footer: { Text(settings.micSensitivity.summary) }

            Section("Screening") {
                Toggle(isOn: Binding(
                    get: { settings.apneaScreeningEnabled },
                    set: { on in
                        if on && !settings.apneaExplainerShown { onApneaSetup() } else { settings.apneaScreeningEnabled = on }
                    })) {
                    VStack(alignment: .leading) {
                        Text("Apnea screening")
                        Text("Acoustic risk indication from breathing sounds").font(.caption).foregroundStyle(p.onSurfaceVariant)
                    }
                }
                Button("Review setup and methodology", action: onApneaSetup)
            }

            Section {
                Picker("Theme", selection: $settings.theme) {
                    ForEach(AppTheme.allCases) { Text($0.label).tag($0) }
                }
            } header: { Text("Display") } footer: { Text("AMOLED black is pure black for night use.") }

            Section {
                Picker("Keep history", selection: $settings.retention) {
                    ForEach(Retention.allCases) { Text($0.label).tag($0) }
                }
                Button("Delete all data", role: .destructive) { confirmNuke = true }
            } header: { Text("Data") } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if let expiring {
                        Text(expiring == 0 ? "Nothing older than the window — nothing to purge."
                             : "\(expiring) finished session(s) older than the window will be removed.")
                    }
                    Text("Everything stays on this iPhone. DeltaSleep has no network code — nothing is uploaded, ever.")
                    if nuked { Text("All sleep data deleted.").foregroundStyle(p.primary) }
                }
            }

            Section("About") {
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–")
                LabeledContent("License", value: "MIT, open source")
            }
        }
        .scrollContentBackground(.hidden)
        .background(p.background)
        .navigationTitle("Settings")
        .onAppear(perform: refresh)
        .onChange(of: settings.retention) { _, _ in refresh() }
        .alert("Delete all sleep data?", isPresented: $confirmNuke) {
            Button("Delete", role: .destructive) {
                DispatchQueue.global(qos: .userInitiated).async {
                    AppDatabase.shared.deleteAll()
                    DispatchQueue.main.async { nuked = true; refresh() }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Every night, event and questionnaire is overwritten and removed. This cannot be undone.")
        }
    }

    private func refresh() {
        expiring = RetentionPolicy.countExpiring(db: .shared, now: nowMs(), retention: settings.retention)
    }
}
