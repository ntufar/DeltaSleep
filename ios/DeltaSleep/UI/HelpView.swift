import SwiftUI

/// In-app user guide, adapted from the Android HelpScreen for iOS.
struct HelpView: View {
    @Environment(\.palette) private var p

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Everything you need to track, review, and export your sleep.")
                    .font(.callout).foregroundStyle(p.onSurfaceVariant)

                header("Getting Started")
                Card {
                    sub("Phone placement")
                    bullet("Place the iPhone on the mattress near your pillow, or on a nightstand within 50–80 cm.")
                    bullet("Do not put it under a pillow — muffled audio reduces accuracy.")
                    bullet("Keep the microphone (bottom edge) uncovered by thick cases or bedding.")
                    bullet("Plug in the charger for a full night.")
                }
                Card {
                    sub("Microphone access")
                    para("DeltaSleep analyses room sounds for sleep phases and snore detection. Raw audio is never saved. iOS shows the orange microphone dot while tracking is active.")
                }

                header("Starting a Session")
                Card {
                    step(1, "Tap Start Sleep.")
                    step(2, "The app calibrates to your room noise for the first few seconds.")
                    step(3, "The tracking screen opens. You can lock the phone — tracking continues in the background.")
                    step(4, "Other audio (podcasts, white noise) keeps playing.")
                }

                header("Active Screen")
                Card {
                    sub("Phase badge & timer")
                    para("The badge shows your current sleep phase; the timer counts time since you started.")
                    HStack { ForEach(SleepPhase.allCases) { LegendChip(color: $0.color, label: $0.label) } }
                }
                Card {
                    sub("Live signal — last 3 minutes")
                    para("Audio level: overall mic energy, spikes on movement or speech.")
                    para("Sound texture: zero-crossing rate, high for breathing or rustling.")
                    para("Snore band: spectral power in 20–300 Hz, elevated when snore-like sounds are present.")
                }
                Card {
                    sub("Phase history & snore events")
                    para("These charts scroll as the night progresses. Each column is one 30-second epoch; a pink overlay means snoring was detected.")
                }

                header("Stopping a Session")
                Card {
                    para("Tap Stop Tracking at the bottom of the tracking screen and confirm.")
                    para("Every 30-second epoch is saved as it completes. If the app is closed or crashes, at most the unfinished epoch is lost; the night is closed automatically the next time you open DeltaSleep.")
                    para("Phone calls pause tracking; it resumes when the call ends.")
                }

                header("Session Results")
                Card {
                    sub("Hypnogram")
                    para("A colour-coded chart of sleep stages over time with clock time along the bottom. Markers on the top edge show snore episodes (taller = louder) and, with screening on, apnea-like (red) and hypopnea-like (orange) events.")
                    sub("Feel rating")
                    para("Tap 1–5 to record how rested you felt. It is saved with the session.")
                    sub("REM (est.)")
                    para("REM is estimated from movement and breathing patterns — not validated sleep-lab staging.")
                }

                header("Export & Data")
                Card {
                    sub("Export CSV")
                    para("Tap Export CSV on a session and choose where to save it in the Files picker. The file has one row per 30-second epoch, plus acoustic events and the night summary.")
                    sub("Delete all data")
                    para("Settings › Delete all data overwrites and removes every night. It cannot be undone.")
                    sub("History")
                    para("Finished nights older than your chosen window (30/90/365 days or never) are deleted automatically.")
                }

                header("Privacy guarantee")
                Card {
                    para("Audio is processed in memory in 10 ms slices and discarded immediately. No audio files are ever written. Only per-epoch summary values are stored, in a private database on this iPhone.")
                    para("DeltaSleep contains no networking code, analytics, or crash reporting. Your data never leaves your device unless you export it.")
                }
                DisclaimerBox(text: disclaimerText)
            }
            .screen(p)
        }
        .background(p.background)
        .navigationTitle("User Guide")
    }

    private func header(_ t: String) -> some View {
        Text(t).font(.title3.weight(.semibold)).padding(.top, 12)
    }

    private func sub(_ t: String) -> some View {
        Text(t).font(.subheadline.weight(.semibold))
    }

    private func para(_ t: String) -> some View {
        Text(t).font(.footnote).foregroundStyle(p.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
    }

    private func bullet(_ t: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text("•").foregroundStyle(p.onSurfaceVariant)
            para(t)
        }
    }

    private func step(_ n: Int, _ t: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)").font(.caption.bold()).foregroundStyle(SleepPhase.light.color)
                .frame(width: 22, height: 22).background(Circle().fill(SleepPhase.light.color.opacity(0.2)))
            para(t)
        }
    }
}
