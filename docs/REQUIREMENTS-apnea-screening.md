# DeltaSleep — Sleep Apnea Risk Screening (v0.2)

**Status:** Draft for implementation
**Depends on:** existing audio pipeline (16 kHz / 10 ms frames), Rust DSP (`dsp/`), Room DB, epoch model
**License/positioning:** GPLv3, on-device only, screening — NOT diagnosis

---

## 1. Goal and scope

Add acoustic screening for obstructive sleep apnea (OSA) risk. The app detects
breathing-related acoustic events during sleep, computes a nightly **Respiratory
Event Index (REI-a)** — an acoustic proxy for the clinical Apnea–Hypopnea Index
(AHI) — combines it with the validated **STOP-BANG questionnaire**, and produces
a trend-aware risk report the user can take to a physician.

**Out of scope:** diagnosis, therapy guidance (CPAP etc.), SpO2/heart-rate
sensors, cloud anything, central/obstructive apnea differentiation (acoustically
unreliable).

### 1.1 Regulatory positioning

- R1.1.1 All UI copy MUST describe the feature as "screening" / "risk
  indication", never "diagnosis", "detection of sleep apnea", or "medical
  advice".
- R1.1.2 The report screen and every exported report MUST carry a fixed
  disclaimer: *"This is not a medical device and does not diagnose any
  condition. Only a sleep study interpreted by a clinician can diagnose sleep
  apnea. If your results suggest elevated risk, discuss them with a doctor."*
- R1.1.3 The feature MUST be opt-in (off by default) with a first-run
  explainer of what is measured and the disclaimer above.

---

## 2. Scientific model (informative)

Obstructive events have an acoustic signature audible at the bedside:

1. Rhythmic breathing/snoring sounds →
2. **Silence or strong amplitude reduction ≥ 10 s** (airway obstruction) →
3. **Resumption gasp/snort** — a loud, broadband burst — followed by return of
   rhythmic breathing.

The detector therefore needs three primitives on top of the existing snore
flag: a **breathing envelope tracker** (is periodic respiratory sound
present?), a **silence/decrement detector** referenced to an adaptive noise
floor, and a **gasp classifier**. An event = decrement ≥ 10 s bounded by
respiratory sound, with confidence boosted when terminated by a gasp.
Hypopnea-like events (partial reduction ≥ 30–50 % of envelope, not full
silence) SHOULD be counted separately with lower confidence.

Known limitations to design around: bed-partner sounds, fans/AC, blanket
muffling, mouth vs. nose breathing, phone placement. These drive the
calibration and confidence requirements below.

---

## 3. Functional requirements

### FR-1 Audio pipeline extension (Rust, `dsp/`)

- FR-1.1 The DSP layer MUST compute, per 10 ms frame, in addition to existing
  features: full-band RMS envelope, band-limited respiratory envelope
  (~100–2000 Hz band-pass, smoothed ~300 ms), spectral flatness, and spectral
  centroid.
- FR-1.2 The DSP layer MUST maintain an **adaptive noise floor** (e.g.,
  minimum-statistics or percentile tracker over a 60 s sliding window) so that
  "silence" is defined relative to the room, not absolute level.
- FR-1.3 A **breathing periodicity tracker** MUST estimate whether periodic
  respiratory sound is present, via autocorrelation of the respiratory
  envelope over a 30–60 s window, accepting periods of 2–8 s (7.5–30
  breaths/min). Output: `breathing_present: bool`, `breath_period_s: f32`,
  `periodicity_confidence: 0..1`.
- FR-1.4 An **event state machine** MUST run continuously with states:
  `NO_SIGNAL`, `BREATHING`, `SNORING`, `DECREMENT`, `GASP`. Transitions are
  driven by the features above. It MUST emit `AcousticEvent` records (see
  FR-3) when a `DECREMENT` of ≥ 10 s occurs between periods of confirmed
  respiratory sound.
- FR-1.5 A **gasp detector** MUST flag frames where full-band RMS exceeds the
  trailing 30 s median envelope by ≥ 12 dB with high spectral flatness,
  within 5 s after a `DECREMENT` ends.
- FR-1.6 Raw PCM MUST continue to be discarded after feature extraction; all
  new processing operates on features only. The existing privacy CI checks
  MUST continue to pass.
- FR-1.7 All thresholds (dB offsets, band edges, min event duration, envelope
  reduction ratios) MUST be compile-time constants in one Rust module
  (`apnea_config.rs`) with documented defaults, so they are tunable during
  validation.
- FR-1.8 Existing snore detection MUST be upgraded from per-epoch yes/no to
  **event-level**: contiguous snore episodes with start time, duration, mean
  and peak band power.

### FR-2 Event classification and confidence

- FR-2.1 Every emitted event MUST carry `type` (`APNEA_LIKE`, `HYPOPNEA_LIKE`,
  `GASP`, `SNORE_EPISODE`) and `confidence` (0–1).
- FR-2.2 Confidence MUST incorporate at minimum: periodicity confidence before
  and after the event, depth of envelope decrement, presence of terminal
  gasp, and ambient-noise margin (noise floor vs. breathing level).
- FR-2.3 Events during epochs classified `Awake` by the existing phase
  classifier MUST be discarded.
- FR-2.4 If the margin between breathing sound level and noise floor is
  < 6 dB for more than 20 % of the night, the night MUST be flagged
  `LOW_SIGNAL_QUALITY` and excluded from risk trending (FR-5).

### FR-3 Data model (Room + Rust FFI)

- FR-3.1 New table `acoustic_event`: `id`, `session_id`, `type`, `start_utc`,
  `duration_ms`, `confidence`, `peak_db_over_floor`, `envelope_reduction_pct`,
  `terminated_by_gasp: bool`.
- FR-3.2 New table `night_summary`: `session_id`, `total_sleep_time_min`
  (from existing phases), `rei_a` (events/h of sleep), `apnea_like_count`,
  `hypopnea_like_count`, `longest_event_s`, `snore_pct_of_sleep`,
  `mean_snore_db_over_floor`, `signal_quality` (`GOOD`/`FAIR`/`LOW`),
  `risk_band` (see FR-5).
- FR-3.3 New table `questionnaire_result`: `date`, 8 STOP-BANG boolean
  answers, `score` (0–8). Height/weight/age/sex/neck answers MUST be stored
  locally only and be individually erasable.
- FR-3.4 The existing "Delete all data" flow MUST cover all new tables and
  follow the same overwrite-before-delete behavior.
- FR-3.5 JNI boundary: the Rust side MUST expose events via the existing
  `DspBridge` pattern (append-only ring buffer polled per epoch), not
  callbacks, to keep the FFI surface minimal.

### FR-4 STOP-BANG questionnaire

- FR-4.1 Implement the standard 8-item STOP-BANG (Snoring, Tiredness,
  Observed apnea, Pressure/hypertension, BMI > 35, Age > 50, Neck > 40 cm,
  male Gender) as a one-screen Compose form, skippable, editable later.
- FR-4.2 The "Snoring" and "Observed apnea" items SHOULD be pre-filled
  (suggested, user-overridable) from the app's own measurements once ≥ 5
  nights of data exist.
- FR-4.3 Scoring: 0–2 low, 3–4 intermediate, 5–8 high risk, per published
  STOP-BANG thresholds. Cite the source in the About screen.

### FR-5 Risk model and report

- FR-5.1 Per-night acoustic banding from REI-a (events/h): `< 5` none/low,
  `5–14` mild-range, `15–29` moderate-range, `≥ 30` severe-range. UI wording
  MUST say "in the range associated with mild/moderate/severe OSA", never a
  diagnosis.
- FR-5.2 The headline **risk band** shown to the user MUST be computed from
  ≥ 5 `GOOD`/`FAIR` nights (median REI-a) combined with STOP-BANG score via a
  simple documented matrix (acoustic band × questionnaire band → LOW /
  ELEVATED / HIGH). A single bad night MUST NOT produce a HIGH banner.
- FR-5.3 Report screen MUST show: risk band with plain-language explanation,
  REI-a trend chart (nightly, 30-day), per-night event timeline overlaid on
  the existing hypnogram, longest event, snore percentage, signal-quality
  indicator, and the disclaimer (R1.1.2).
- FR-5.4 If risk band is ELEVATED or HIGH, the report MUST show a "What to do
  next" section: see a doctor, mention home sleep apnea testing, bring the
  exported report.

### FR-6 Event verification clips (opt-in)

- FR-6.1 By default, NO audio is stored (unchanged guarantee). An explicit
  setting ("Save short clips of detected events for my own review", default
  OFF) MAY enable storing up to 15 s of audio around each `APNEA_LIKE` event.
- FR-6.2 Clips MUST be stored app-private, capped (e.g., 20 clips/night, FIFO),
  playable from the event timeline, and included in "Delete all data".
- FR-6.3 When the setting is off, the code path that touches PCM persistence
  MUST be compiled out or unreachable, and the README privacy table MUST
  document the conditional behavior. CI SHOULD assert the default-off state.

### FR-7 Export

- FR-7.1 CSV export MUST be extended with `acoustic_event` and
  `night_summary` tables.
- FR-7.2 A **physician report** (single-file PDF or printable HTML, generated
  on-device, no network) MUST include: identity-free summary, methodology
  paragraph (how events are detected acoustically, known limitations),
  nightly REI-a table for the last 30 nights with signal quality, STOP-BANG
  score and date, trend chart, and disclaimer.
- FR-7.3 Export goes through the existing system file picker; no new
  permissions.

### FR-8 UX

- FR-8.1 Recording flow is unchanged; apnea analysis runs inside the existing
  foreground service session.
- FR-8.2 A placement/calibration hint screen MUST instruct: phone on
  nightstand or mattress edge, mic unobstructed, 0.5–1.5 m from head; and
  SHOULD include a 10 s "test breathing sound level" meter showing the
  breathing-to-noise margin (reuses FR-1.2 floor).
- FR-8.3 Bed-partner caveat MUST be surfaced once: results are unreliable if
  another person (or pet) sleeps within ~1 m closer to the phone.
- FR-8.4 No alarms/wake-ups on events (screening, not intervention).

---

## 4. Non-functional requirements

- NFR-1 **On-device only.** No `INTERNET` permission, no new permissions at
  all. All new code passes the existing grep-based CI privacy check.
- NFR-2 **CPU/battery:** incremental cost of apnea DSP ≤ 15 % over current
  pipeline; whole-night session on a mid-range 2020 device (e.g., Pixel 4a)
  MUST consume ≤ 5 % battery beyond current usage. Measure via
  `dumpsys batterystats` in a documented procedure.
- NFR-3 **Memory:** DSP working set increase ≤ 4 MB; no allocations in the
  per-frame hot path (pre-allocated ring buffers).
- NFR-4 **Storage:** ≤ 200 KB per night without clips (events + summary).
- NFR-5 Min SDK stays 26; Rust code stays `no_std`-friendly if it currently
  is, and builds via existing `cargo ndk` targets.
- NFR-6 All user-facing strings localized through the existing string
  resource mechanism.

---

## 5. Validation and testing

- T-1 **Rust unit tests** for each primitive: noise-floor tracker, periodicity
  tracker, state machine transitions, gasp detector — with synthetic signals
  (generated sine/noise compositions checked into `dsp/tests/fixtures` as
  generator code, not audio files).
- T-2 **Synthetic night generator:** a test utility that composes breathing
  cycles, snores, N apnea gaps of configurable duration, gasps, and ambient
  noise at configurable SNR. CI MUST assert recall ≥ 0.9 and precision ≥ 0.9
  on synthetic nights at ≥ 10 dB breathing-to-noise margin, and graceful
  degradation flags (`LOW_SIGNAL_QUALITY`) below 6 dB.
- T-3 **Real-data benchmark (offline, not in CI):** validate against a
  PSG-annotated public corpus (e.g., PSG-Audio / A3 corpus with tracheal or
  ambient microphone channels). Document per-night REI-a vs. AHI correlation
  in `docs/validation.md`. Target for release: Pearson r ≥ 0.7 and correct
  side of the AHI ≥ 15 threshold in ≥ 80 % of subjects. If targets are not
  met, ship the feature labeled "experimental" with the measured numbers
  published — honesty is the feature.
- T-4 **Golden-file regression:** feature vectors and emitted events for fixed
  synthetic inputs are snapshotted; any diff fails CI.
- T-5 **Battery/CPU test procedure** documented and run before each release
  tag that touches `dsp/`.

---

## 6. Implementation milestones

| Milestone | Deliverable | Acceptance |
|---|---|---|
| M1 | FR-1 primitives + unit tests (T-1) | CI green, features visible in debug overlay |
| M2 | Event state machine + Room tables (FR-2, FR-3), event timeline on hypnogram | Synthetic nights pass T-2 |
| M3 | Night summary, REI-a, risk banding, STOP-BANG (FR-4, FR-5) | Report screen complete with disclaimers |
| M4 | Exports (FR-7), calibration UX (FR-8), opt-in clips (FR-6) | Physician PDF renders on-device |
| M5 | Offline validation vs. public corpus (T-3), tune `apnea_config.rs` | `docs/validation.md` published |

Suggested order of code touch-points: `dsp/src/features.rs` (new features) →
new `dsp/src/apnea.rs` + `apnea_config.rs` → `DspBridge` FFI → Room entities/
DAO → nightly summarizer (Kotlin) → Compose report screens → export.

---

## 7. Open questions (decide before M3)

1. Should hypopnea-like events count into REI-a at full weight, half weight,
   or be reported separately? (Validation data should decide; start separate.)
2. Pre-fill STOP-BANG BMI from user-entered height/weight, or keep the app
   free of body data and ask the boolean directly? (Privacy-lean default:
   boolean only.)
3. Phone-on-mattress placement also captures movement via accelerometer —
   worth adding as a corroborating signal in v0.3?
