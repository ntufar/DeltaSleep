import SwiftUI

enum HomeRoute: Hashable {
    case session(Int64)
    case settings
    case help
    case apneaSetup
    case apneaReport
    case questionnaire
}

struct HomeView: View {
    @Environment(\.palette) private var p
    @EnvironmentObject private var tracker: SleepTracker
    @EnvironmentObject private var settings: SettingsStore
    @State private var path: [HomeRoute] = []
    @State private var sessions: [SleepSession] = []

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("DeltaSleep").font(.largeTitle.weight(.semibold)).foregroundStyle(p.onBackground)
                        Text("Your sleep data stays in your bed").font(.footnote).foregroundStyle(p.onSurfaceVariant)
                    }
                    .padding(.top, 4)

                    Button {
                        Task { await tracker.start() }
                    } label: {
                        Label("Start Sleep", systemImage: "moon.zzz.fill")
                            .font(.title3.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 64)
                            .foregroundStyle(p.onPrimary)
                            .background(p.primary, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Starts overnight sleep tracking with the microphone")

                    if let error = tracker.startError {
                        Text(error).font(.footnote).foregroundStyle(p.error)
                    }

                    NavigationLink(value: shouldShowApneaSetup ? HomeRoute.apneaSetup : .apneaReport) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Apnea Screening").font(.subheadline.weight(.semibold))
                                Text("Risk indication from breathing sounds").font(.caption).opacity(0.75)
                            }
                            .foregroundStyle(p.onSurfaceVariant)
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(p.primary)
                        }
                        .padding(16)
                        .background(p.surfaceVariant, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)

                    Text("Previous Sessions").font(.headline).foregroundStyle(p.onBackground)
                    SessionsCalendar(sessions: sessions) { path.append(.session($0)) }
                }
                .screen(p)
            }
            .background(p.background)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    NavigationLink(value: HomeRoute.settings) {
                        Image(systemName: "gearshape").accessibilityLabel("Settings")
                    }
                    NavigationLink(value: HomeRoute.help) {
                        Image(systemName: "questionmark.circle").accessibilityLabel("Help")
                    }
                }
            }
            .navigationDestination(for: HomeRoute.self) { route in
                switch route {
                case .session(let id): SessionView(sessionId: id)
                case .settings: SettingsView(onApneaSetup: { path.append(.apneaSetup) })
                case .help: HelpView()
                case .apneaSetup: ApneaSetupView()
                case .apneaReport: ApneaReportView()
                case .questionnaire: QuestionnaireView()
                }
            }
        }
        .onAppear {
            reload()
            #if DEBUG
            // Screenshot hook: -openLatestSession YES
            if UserDefaults.standard.bool(forKey: "openLatestSession"), path.isEmpty,
               let latest = AppDatabase.shared.allSessions().first {
                path = [.session(latest.id)]
            }
            #endif
        }
        .onReceive(AppDatabase.shared.didChange) { reload() }
        .onReceive(tracker.$finishedSessionId.compactMap { $0 }) { id in
            tracker.finishedSessionId = nil
            path = [.session(id)]
        }
    }

    private var shouldShowApneaSetup: Bool {
        !settings.apneaExplainerShown || !settings.apneaScreeningEnabled
    }

    private func reload() {
        sessions = AppDatabase.shared.allSessions()
    }
}

/// Month calendar of past nights. Days with a session are filled and
/// tappable; several sessions on one day open a picker.
struct SessionsCalendar: View {
    @Environment(\.palette) private var p
    let sessions: [SleepSession]
    let onSelect: (Int64) -> Void

    @State private var month = Calendar.current.date(from: Calendar.current.dateComponents([.year, .month], from: Date()))!
    @State private var dayChoices: [SleepSession] = []
    @State private var showDayPicker = false

    private let cal = Calendar.current

    var body: some View {
        let byDay = Dictionary(grouping: sessions) { cal.startOfDay(for: Date(ms: $0.startTime)) }
        let days = cal.range(of: .day, in: .month, for: month)!.count
        let leading = (cal.component(.weekday, from: month) - cal.firstWeekday + 7) % 7
        let symbols = rotated(cal.veryShortWeekdaySymbols, by: cal.firstWeekday - 1)

        VStack(spacing: 6) {
            HStack {
                Button { shift(-1) } label: { Image(systemName: "chevron.left").padding(8) }
                    .accessibilityLabel("Previous month")
                Spacer()
                Text(month.formatted(.dateTime.month(.wide).year())).font(.subheadline.weight(.semibold))
                Spacer()
                Button { shift(1) } label: { Image(systemName: "chevron.right").padding(8) }
                    .accessibilityLabel("Next month")
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 7), spacing: 4) {
                ForEach(Array(symbols.enumerated()), id: \.offset) { _, s in
                    Text(s).font(.caption2).foregroundStyle(p.onSurfaceVariant)
                }
                ForEach(0..<(leading + days), id: \.self) { cell in
                    if cell < leading {
                        Color.clear.frame(height: 40)
                    } else {
                        dayCell(day: cell - leading + 1, byDay: byDay)
                    }
                }
            }
        }
        .confirmationDialog("Multiple sessions this day", isPresented: $showDayPicker, titleVisibility: .visible) {
            ForEach(dayChoices) { s in
                Button(sessionLabel(s)) { onSelect(s.id) }
            }
        }
    }

    @ViewBuilder
    private func dayCell(day: Int, byDay: [Date: [SleepSession]]) -> some View {
        let date = cal.date(byAdding: .day, value: day - 1, to: month)!
        let daySessions = byDay[date] ?? []
        let has = !daySessions.isEmpty
        let isToday = cal.isDateInToday(date)
        Button {
            if daySessions.count == 1 {
                onSelect(daySessions[0].id)
            } else if daySessions.count > 1 {
                dayChoices = daySessions
                showDayPicker = true
            }
        } label: {
            Text("\(day)")
                .font(.callout)
                .frame(maxWidth: .infinity, minHeight: 40)
                .foregroundStyle(has ? p.onPrimary : p.onBackground)
                .background(Circle().fill(has ? p.primary : (isToday ? p.surfaceVariant : .clear)))
        }
        .buttonStyle(.plain)
        .disabled(!has)
        .accessibilityLabel(date.formatted(date: .long, time: .omitted) + (has ? ", sleep recorded" : ""))
    }

    private func shift(_ months: Int) {
        month = cal.date(byAdding: .month, value: months, to: month)!
    }

    private func rotated(_ a: [String], by n: Int) -> [String] {
        Array(a[n...] + a[..<n])
    }

    private func sessionLabel(_ s: SleepSession) -> String {
        let start = Date(ms: s.startTime).formatted(date: .omitted, time: .shortened)
        let mins = Int(((s.endTime ?? nowMs()) - s.startTime) / 60000)
        return "\(start) — \(mins / 60)h \(mins % 60)m"
    }
}
