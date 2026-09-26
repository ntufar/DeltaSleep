import SwiftUI

@main
struct DeltaSleepApp: App {
    @StateObject private var settings = SettingsStore.shared
    @StateObject private var tracker = SleepTracker.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(settings)
                .environmentObject(tracker)
                .onAppear {
                    #if DEBUG
                    DemoData.applyLaunchArguments()
                    #endif
                    tracker.recoverOrphanedSessions()
                    let retention = settings.retention
                    DispatchQueue.global(qos: .utility).async {
                        // C-2: retention purge on app start.
                        RetentionPolicy.purgeExpired(db: .shared, now: nowMs(), retention: retention)
                    }
                }
        }
    }
}

enum Tab: Hashable { case home, trends, report }

struct RootView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var tracker: SleepTracker
    @Environment(\.colorScheme) private var systemScheme
    @State private var tab: Tab = .home

    var body: some View {
        let palette = Palette.resolve(settings.theme, system: systemScheme)
        TabView(selection: $tab) {
            HomeView()
                .tabItem { Label("Home", systemImage: "moon.zzz.fill") }
                .tag(Tab.home)
            NavigationStack { TrendsView() }
                .tabItem { Label("Trends", systemImage: "chart.bar.fill") }
                .tag(Tab.trends)
            NavigationStack { ApneaReportView() }
                .tabItem { Label("Report", systemImage: "heart.fill") }
                .tag(Tab.report)
        }
        #if DEBUG
        .onAppear {
            switch UserDefaults.standard.string(forKey: "initialTab") {
            case "trends": tab = .trends
            case "report": tab = .report
            default: break
            }
        }
        #endif
        .tint(palette.primary)
        .environment(\.palette, palette)
        .preferredColorScheme(settings.theme == .system ? nil : (palette.isDark ? .dark : .light))
        .fullScreenCover(isPresented: Binding(
            get: { tracker.isTracking },
            set: { _ in }
        )) {
            ActiveSleepView()
                .environment(\.palette, palette)
                .environmentObject(tracker)
                .environmentObject(settings)
        }
    }
}
