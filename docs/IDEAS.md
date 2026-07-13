# DeltaSleep — Improvement & Feature Ideas

**Status:** brainstorm / candidate backlog — nothing here is committed scope.
**Baseline:** v0.2.2 (Android-only, manual start/stop, Awake/Light/Deep phases,
event-level snore detection, opt-in apnea screening with REI-a + STOP-BANG,
hypnogram + session calendar, CSV export, physician HTML report).

Every idea must respect the non-negotiables: **zero network egress**, no new
dangerous permissions, raw PCM never persisted by default, GPLv3, buildable by
F-Droid. Ideas that bend a constraint say so explicitly.

Effort scale: **S** ≤ 3 days, **M** ≤ 2 weeks, **L** ≥ 2 weeks.

---

## A. Sleep science & DSP core

### A-1. REM stage estimation (heuristic, v0.3) — **L**

The PRD promises Awake/Light/Deep/REM; REM is still missing. The apnea work
already added the two signals REM needs: a breathing-periodicity tracker and a
respiratory envelope.

**Signal model.** REM is characterized by (a) near-atonia — very low body
movement, (b) irregular breathing — high variance of breath period, (c) it
occurs in ~90 min cycles, weighted toward the back half of the night.

**Spec:**
- Extend the per-epoch feature vector from the DSP with
  `breath_period_mean_s`, `breath_period_cv` (coefficient of variation over
  the epoch's autocorrelation windows), and `movement_score` (already implied
  by RMS variance in `classifier.rs`).
- New rule in `dsp/src/classifier.rs`: candidate REM epoch =
  `movement_score < REM_MOVEMENT_MAX` AND `breathing_present` AND
  `breath_period_cv > REM_BREATH_CV_MIN` (defaults: 0.15 / 0.25, constants in
  a new `phase_config.rs` mirroring the `apnea_config.rs` pattern).
- Post-processing pass in Kotlin (`NightSummarizer` is the natural home):
  median-filter phases over 5 epochs (2.5 min), suppress REM in the first
  60 min of sleep, and merge REM runs < 4 epochs into neighbors — removes
  physiologically impossible flicker.
- Schema: `sleep_epochs.phase` gains value `3=REM` (Room migration v3; keep
  the enum ordinal stable). Hypnogram gets a fourth row, purple per PRD.
- **Honesty rule:** label REM "estimated" in UI until validated (see A-2),
  same policy as the apnea "experimental" tag in `validation.md`.

**Testing:** synthetic-night generator (already in `dsp/tests/`) gains a REM
segment composer (irregular breath spacing, no movement bursts); golden-file
regression extended; assert median-filter removes single-epoch REM islands.

### A-2. Offline validation harness as a first-class tool — **M**

`validation.md` T-3 is still pending, and A-1 will need the same rig. Build it
once, generically.

**Spec:**
- New crate/bin `dsp/src/bin/replay.rs` (host-only, behind
  `#[cfg(not(target_os = "android"))]` or a `replay` cargo feature): reads
  16 kHz mono WAV from stdin/path, feeds 10 ms frames through the same
  `SessionEngine` used on device, prints per-epoch phases, snore episodes,
  and `AcousticEvent`s as JSONL.
- Python (or Rust) scorer script in `tools/validation/`: aligns JSONL output
  against PSG annotation files (PSG-Audio/A3 corpus formats), computes
  epoch-level Cohen's kappa for phases and per-night REI-a vs AHI Pearson r,
  emits the results table `validation.md` promises.
- Train/held-out split logic per T-3.5; scorer refuses to report held-out
  numbers until config is frozen (a `--freeze thresholds.hash` guard).
- CI runs the replay binary on a 60 s fixture to prevent bit-rot; the corpus
  benchmark itself stays offline (audio can't live in the repo).

**Payoff:** unblocks removing the "experimental" label, and every future DSP
change gets a regression rig against real audio.

### A-3. Accelerometer fusion for movement & position — **M**

Open question 3 in `REQUIREMENTS-apnea-screening.md`. The mic infers movement
from sound; the accelerometer measures it directly, costs ~0 battery at low
rates, and needs **no permission**.

**Spec:**
- `SensorManager` listener at `SENSOR_DELAY_NORMAL` (~5 Hz), registered only
  during active sessions inside `SleepTrackingService`. Compute per-epoch:
  `accel_movement_rms` (high-pass-filtered magnitude) and, when the phone is
  on the mattress, `orientation_bucket` (supine/side/prone proxy from gravity
  vector — only meaningful in mattress placement, so gate on a placement
  setting from the apnea setup screen).
- New nullable columns on `sleep_epochs`: `accelRms REAL`, `orientation
  INTEGER` (Room migration; null = sensor unavailable, e.g., tablet).
- Classifier fusion (Kotlin side first, no FFI change): movement epoch =
  `audio_movement OR accel_movement_rms > threshold`. Fixes the known
  failure mode where a silent-but-restless sleeper reads as Deep.
- Apnea corroboration: positional OSA is common — the report screen and the
  physician report gain "events by position" (supine vs side REI-a) when
  orientation data exists.
- Batching: use `SensorManager.registerListener` with
  `maxReportLatencyUs = 30s` so the SoC batches in hardware and the AP
  sleeps.

**Testing:** unit tests with recorded sensor traces (arrays in test fixtures);
verify null-column path on devices without the sensor.

### A-4. VAD / "external audio" class — close the PRD gap — **M**

The PRD pipeline step 2 (filter podcasts/audiobooks playing mix-with-others)
is not truly implemented; speech will currently pollute snore/apnea stats.

**Spec:**
- Cheap on-device heuristic VAD in `dsp/src/features.rs`: speech has high
  spectral-centroid modulation at 2–8 Hz syllabic rate and energy
  concentrated 300–3000 Hz; snore is 20–300 Hz dominant. Compute
  `speech_likelihood` per frame from (band-ratio 300–3000/full, centroid
  variance over 500 ms).
- Additionally — and more robustly — Kotlin side: `AudioManager
  .isMusicActive` and `AudioPlaybackCallback` tell us *for free* when another
  app is rendering audio. No audio analysis needed, no permission. When
  playback is active, tag frames `EXTERNAL_AUDIO_SUSPECT`.
- Classifier: epochs with dominant external audio get the existing (but
  unused) `external audio` label; snore/apnea state machines treat them like
  `LOW_SIGNAL_QUALITY` time (excluded from REI-a denominator per FR-2.4
  precedent).
- UI: session detail shows "external audio filtered: NN min" so users trust
  the numbers.

### A-5. Personal baseline calibration — **M**

All thresholds are global compile-time constants (`apnea_config.rs`). Rooms
and phones differ by >20 dB.

**Spec:**
- After each night, store per-session calibration stats in `night_summary`
  (already has signal-quality fields; add `noise_floor_db_median`,
  `breathing_level_db_median`).
- A `PersonalBaseline` Kotlin object computes rolling medians over the last
  14 GOOD nights and derives per-user offsets (clamped to ±6 dB from
  defaults) passed into the DSP at session start via a new
  `SessionEngine::with_offsets()` FFI entry — one new JNI function on
  `DspBridge`, still no callbacks.
- Settings: "Reset calibration" button; calibration state included in "Nuke
  all data".
- Guard: offsets only apply when ≥ 5 GOOD nights exist (mirrors the FR-5.2
  ≥ 5-night rule).

### A-6. Snore intensity 1–5 surfaced end-to-end — **S**

PRD 2.1 specifies intensity 1–5; the DSP already records mean/peak band power
per episode, but the UI shows only percentages.

**Spec:** map `peak_db_over_floor` to 1–5 buckets (e.g., <6 / 6–12 / 12–18 /
18–24 / ≥24 dB) as a pure Kotlin function with unit tests; show intensity as
bar height in the snore timeline overlay and a "loudest snore" stat card.
No schema change — derived at read time.

### A-7. Nightly breathing-rate chart & long-term respiratory trend — **S/M**

The periodicity tracker already computes breath period; it's discarded except
for apnea logic.

**Spec:** persist `breathPeriodS REAL` per epoch (migration piggybacks on
A-1's). Session screen gains a breaths-per-minute line (Compose Canvas, same
chart infra as hypnogram). Trends screen (D-1) gains 30-day median nightly
respiratory rate — an elevated resting RR trend is a genuinely useful,
non-diagnostic wellness signal ("your average overnight breathing rate rose
from 14 to 17/min this week").

### A-8. Environment report: room noise profile — **S**

The adaptive noise floor (FR-1.2) already measures the room all night.

**Spec:** store hourly noise-floor percentiles in a small JSON column on
`night_summary` (or a `noise_profile` table, 8–10 rows/night). Morning
summary gains "Room noise" card: quietest hour, loudest disturbance count
(floor jumps > 15 dB), and correlation hint ("3 of your 5 wake epochs
followed a noise spike"). Pure aggregation, no new DSP.

---

## B. Battery, reliability & service hardening

### B-1. Mic duty-cycling — the promised ≤3%/night — **M**

PRD 3.2.6 ("duty cycle mic 80% if no events") was never built.

**Spec:**
- State machine in `SleepTrackingService`/`AudioCapture`: after 10 min with
  zero events (no snore, no movement, no apnea candidates, stable floor),
  switch to duty cycle: capture 8 s, pause 2 s (80%). Any event candidate →
  full capture instantly for ≥ 10 min.
- Critical constraint: **never duty-cycle during a `DECREMENT`** — a paused
  mic is indistinguishable from an apnea silence. The DSP must export a
  `safe_to_pause()` predicate (state == `BREATHING`/`SNORING`, no decrement
  in last 60 s) over the FFI.
- Pause = stop reading but keep `AudioRecord` open (re-open costs ~100 ms and
  risks the 0.1.11-class zero-sample bug); gaps are recorded as
  `duty_cycle_gap` so epoch math stays honest.
- Measure with the existing `docs/battery-test-procedure.md` — this feature
  is the reason that table finally gets a row.

### B-2. In-app battery accounting — **S**

The battery test procedure is manual and has never been run.

**Spec:** at session start/stop, snapshot `BatteryManager.EXTRA_LEVEL` +
`BATTERY_PROPERTY_CHARGE_COUNTER` and charging state; store on
`sleep_sessions` (`batteryStartPct`, `batteryEndPct`, `wasCharging`).
Session screen shows "battery used: 4%". Aggregated over releases this gives
the T-5 table real data from the developer's own phone with zero adb
ceremony. Skip the stat when `wasCharging` (meaningless).

### B-3. Crash journal & session auto-resume audit — **S**

0.1.5/0.1.11 fixed specific restart bugs reactively. Make resilience testable.

**Spec:** an instrumented test (androidTest) that kills the process
(`ActivityManager.killBackgroundProcesses` / `Runtime.halt` from a test
hook) mid-session and asserts: session row survives, epochs resume within
60 s of restart, ≤ 30 s of data lost (PRD reliability bound). Add a
`service_restarts` counter to `sleep_sessions` surfaced in the session debug
view — users reporting "short sessions" can screenshot it.

### B-4. Auto start/stop (opt-in) — PRD 2.1 gap — **M**

**Spec:** opt-in setting (default OFF, per PRD). Trigger: charging
(`ACTION_POWER_CONNECTED`) AND flat orientation (gravity z ≈ ±9.8 for
5 min) AND within user-defined time window (e.g., 21:00–03:00) → post a
**notification asking to start** (full auto-start of the mic without a user
tap is both creepy and, on Android 14+, restricted for mic foreground
services from the background — the notification tap is the compliant path).
Auto-stop: sustained movement + unplugged + after minimum 3 h → prompt to
stop, auto-stop after 15 min without response. Implement as an exported-false
`BroadcastReceiver` + the existing service; no new permissions.

### B-5. Doze/OEM-killer survival kit — **S**

**Spec:** detect at session start whether battery optimization is enabled for
the app (`PowerManager.isIgnoringBatteryOptimizations`) and whether the OEM
is a known aggressive killer (Xiaomi/Huawei/Samsung list, hardcoded — no
network); show a one-time setup hint linking to the exact settings screen via
`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` intent. Document in Help. This
is the #1 silent failure mode for sleep trackers in the wild.

---

## C. Data, export & interop

### C-1. JSON export + import/restore — PRD 2.3 gap, spec already promised — **M**

`docs/export_schema_v1.json` is still "(planned)" and there is no import.

**Spec:**
- Write the actual schema doc: one JSON object per export — `schema_version`,
  `app_version`, `exported_at`, arrays for `sessions`, `epochs`,
  `acoustic_events`, `night_summaries`, `questionnaire_results`. Field names
  = Room column names. Times ISO-8601 UTC.
- `JsonExporter` alongside `CsvExporter` (kotlinx.serialization — already no
  network deps), streamed via SAF `OutputStream` so a year of epochs
  (~1M rows worst case) doesn't OOM: write arrays incrementally with
  `JsonWriter`-style streaming, not one giant object in memory.
- Import: SAF open → validate `schema_version` → dedupe by
  `(startTime, endTime)` session key → insert in one transaction with
  progress dialog. Reject files > some sane cap with a clear error.
- Round-trip test: export → nuke → import → row-count and checksum equality.

### C-2. Data retention auto-delete — PRD 2.3 gap — **S**

**Spec:** setting 30/90/365/never (default 365 per PRD). No WorkManager
needed: purge on app start + session stop (`DELETE FROM sleep_sessions WHERE
endTime < cutoff`; cascades cover epochs/events; then the same
overwrite-relevant-pages story as nuke — practically: run `VACUUM` after
purge, which the nuke path already does). Show "next purge will remove N
sessions" preview before enabling.

### C-3. Encrypted backup file — **M**

Users who nuke phones lose a year of data; cloud sync is forbidden. Give them
a safe single-file backup they can move themselves.

**Spec:** "Backup (encrypted)" produces `deltasleep-YYYYMMDD.dsbak` via SAF:
the C-1 JSON, gzipped, encrypted AES-256-GCM with a key derived from a user
passphrase via Argon2id (pure-Rust `argon2` crate in the existing DSP lib —
avoids adding a Java crypto dependency and keeps params compile-time pinned;
salt + params in the file header). Restore = C-1 import after decrypt.
Explicitly **not** automatic and **not** to any cloud — user moves the file.
Passphrase never stored. Threat model documented in README privacy table.

### C-4. Health Connect writer (build-flavor gated) — **M**, *bends a constraint knowingly*

Many users want their sleep stages in one place. Health Connect is an
**on-device** datastore (no network egress by Google's design), but it adds a
dependency and a visible integration, which purists will hate.

**Spec:** separate Gradle product flavor `hc` (default flavor stays pristine;
F-Droid builds the pristine one). `androidx.health.connect:connect-client`,
write-only: `SleepSessionRecord` with stage intervals mapped from epochs
(Awake→`AWAKE`, Light→`LIGHT`, Deep→`DEEP`, REM→`REM`). Opt-in switch, off by
default. CI privacy grep must still pass on both flavors (the HC client is
IPC, not sockets — verify, and pin the exact artifact version). If the grep
or dexdump check (G-2) flags anything network-shaped in the HC client, the
idea dies — state that acceptance gate up front.

### C-5. `docs/schema.md` truth pass — **S**

The doc still describes v1 (3 tables, claims SQLDelight; the code is Room and
v2 added `acoustic_event`, `night_summary`, `questionnaire_result`). Ideas
above add migrations v3+. Regenerate the doc from the Room exported schema
JSON (`room.schemaLocation` — enable it if not already) with a small script
in `tools/`, and add a CI check that the doc's table list matches the
exported schema so it can never rot again.

---

## D. UX & visualization

### D-1. Trends dashboard — the biggest unbuilt PRD promise — **L**

PRD 2.2.4 in full: weekly duration bars, 30-day deep-% line, snore heatmap by
weekday, bedtime/wake consistency scatter.

**Spec:**
- Data: everything computable from `sleep_sessions` + `night_summary`; add a
  `TrendsRepository` with one SQL query per chart (indexed on `startTime`),
  target < 50 ms for 365 nights (PRD: chart render < 500 ms/year).
- Rendering: extend the existing Compose-Canvas chart approach
  (`HypnogramChart.kt` establishes the pattern) into a tiny internal chart
  kit: `BarChart`, `LineChart`, `Heatmap`, `Scatter` — ~600 lines total, no
  third-party chart lib (keeps APK budget and license surface).
- Consistency scatter: x = date, y = clock-time-of-day (wrap at chosen
  "day boundary" 18:00 so 23:30 and 00:30 plot adjacently); overlay ±30 min
  target band; compute a "regularity score" = % of nights within 30 min of
  median bedtime (this is the Sleep Regularity Index, simplified — cite it).
- Navigation: third top-level destination (Home / Trends / Report).

### D-2. Explainable sleep score 0–100 — **M**

PRD mentions "score 0-100"; nothing computes it. Black-box scores are the
most-hated feature of commercial trackers — make explainability the feature.

**Spec:** deterministic, documented formula, e.g.
`score = 40·duration_component + 20·efficiency + 15·deep_pct + 10·(1-fragmentation) + 10·(1-snore_pct) + 5·regularity`,
each component a clamped 0–1 function with published breakpoints (duration
component peaks at user-set sleep need, default 8 h). Implement as pure
Kotlin object `SleepScore` with table-driven unit tests; score screen shows
the six bars and exactly how many points each contributed. Formula version
stored with the score so historical scores don't silently change when the
formula is tuned (`score_v` column on `night_summary`).

### D-3. Settings screen (consolidated) — **M**

Currently settings are scattered (apnea prefs, delete-all). PRD 2.4 wants
mic sensitivity, snore toggle, theme, data controls.

**Spec:** single Compose settings screen backed by DataStore Preferences:
mic sensitivity (maps to a dB offset into the snore threshold — reuse A-5
plumbing), snore detection on/off, apnea screening on/off (moves
`ApneaPrefs` in), theme (system/light/dark/AMOLED-black — pure black matters
for a screen that's on at night), retention (C-2), auto start/stop (B-4),
clock format, sleep-need hours (feeds D-2). Every setting gets a stable key
documented in one file for backup inclusion (C-3).

### D-4. Sleep diary tags & correlation hints — **M**

**Spec:** morning flow (after the existing feel-rating) offers optional tag
chips: alcohol, caffeine-late, exercise, stress, illness, sick-partner-snoring,
custom tags. Table `session_tag(sessionId, tag TEXT)`. Trends screen adds
"with vs without" comparisons: median deep %, score, REI-a split by tag, with
minimum-sample guard (≥ 5 nights each side) and honest wording
("association, not causation"). No ML, just medians — cheap and genuinely
useful.

### D-5. Smart alarm (wake in light sleep) — **M/L**, *scope change from PRD §6*

Out of scope for v1.0 by PRD, but it's the most-requested feature of every
sleep app and needs no network.

**Spec:** user sets latest-wake time + window (default 30 min). During the
window, the live classifier (already producing a phase per 30 s) triggers the
alarm at the first Light/Awake epoch; hard-fire at window end regardless.
Implementation: the tracking service is already alive — no `AlarmManager`
needed for the in-window trigger, only a fallback exact alarm for the
hard-fire in case the service died. **Permission cost:** exact alarms need
`SCHEDULE_EXACT_ALARM` (Android 12+) or `USE_EXACT_ALARM` — this violates
the "only 4 permissions" rule in CLAUDE.md, so it must be a deliberate,
README-documented exception (`USE_EXACT_ALARM` is granted automatically for
alarm apps and is arguably in spirit). Alarm sound from bundled assets;
full-screen intent notification; gradual volume ramp.

### D-6. Accessibility pass — PRD NFR, unaudited — **M**

**Spec:** every chart gets a `contentDescription` summary AND a "view as
table" toggle rendering the same data as a plain list (TalkBack-navigable —
this is the only honest way to make a hypnogram accessible). Verify color
pairs (Awake red / Light blue / Deep dark-blue / REM purple) against WCAG on
both themes; add pattern/hatching option for color-blind users (the 0.1.15
Y-axis labels already started this). Dynamic type audit with font scale 2.0.
Add `androidTest` accessibility checks via
`AccessibilityChecks.enable()` in Espresso.

### D-7. Home-screen widget — **S/M**

**Spec:** Glance (`androidx.glance`) widget: last night's score, duration,
and a mini 24 px hypnogram strip; tap opens the session. Updates only when a
session ends (no periodic work). Glance is local rendering — no constraint
issues.

### D-8. Live "why" debug overlay — **S**

PRD 2.2.5 (audio level graph to debug false positives) exists on the active
screen; extend to a developer-mode overlay showing the raw per-epoch feature
vector and the apnea state-machine state (`BREATHING`/`DECREMENT`/…) live.
Toggle hidden behind 7 taps on the version number. Costs nothing (data is
already flowing over the FFI) and turns every user bug report into a usable
one.

---

## E. Apnea feature completion (spec'd but unfinished)

### E-1. Opt-in event verification clips (FR-6) — **M**

The requirement exists; the CHANGELOG shows it was not shipped in 0.2.1.
Spec is already written in REQUIREMENTS-apnea-screening.md FR-6.1–6.3: 15 s
pre/post-event PCM ring buffer (in the Rust side, pre-allocated per NFR-3),
persisted only when the default-OFF setting is on, ≤ 20 clips/night FIFO,
playable from the event timeline, covered by nuke, **compiled out when
disabled** — implement the code path behind a Gradle `buildConfigField` so
the pristine build literally contains no PCM-persistence code, and add the
CI assertion FR-6.3 asks for (grep for the writer class in the default
flavor's dex).

### E-2. Run the T-3 real-data benchmark — **M** (mostly A-2)

The single highest-credibility item available: `validation.md` publishes
per-night REI-a vs AHI on PSG-Audio, whatever the numbers are. Blocked only
on A-2 tooling + corpus download + an evening of compute.

### E-3. Bed-partner / two-sleeper mode — **M**

FR-8.3 currently just warns. Better: a "two sleepers" setting that (a) raises
the snore-attribution bar (only count episodes above a higher
`db_over_floor`, since the nearer person dominates), (b) annotates the report
("recorded with a bed partner — snore/apnea attribution is unreliable"), and
(c) suppresses STOP-BANG pre-fill from measurements (FR-4.2) since the
measured snoring may be the partner's. Honest-limitations UX, trivially
implementable, prevents the #1 garbage-in complaint.

---

## F. Platform & distribution

### F-1. F-Droid submission + reproducible build — **M**

PRD 3.3.3 promises it. **Spec:** pin NDK + Rust toolchain versions in a
committed `toolchain.versions`; make the Rust build reproducible
(`--remap-path-prefix`, locked Cargo.lock, `SOURCE_DATE_EPOCH`); verify two
clean-room builds produce identical APK digests (module reproducibility
check in CI comparing unsigned APK hashes from two independent jobs); write
the fdroiddata metadata file; document the signing story
(reproducible + published signature so F-Droid can use upstream signing).

### F-2. iOS groundwork: extract shared core — **L** (multi-release)

PRD says iOS v1.1; the UI is Android-only Compose. Don't port UI first —
port the spine. **Spec:** (1) the Rust DSP already compiles to `.a` for iOS
targets (`aarch64-apple-ios`) — add the target to CI to keep it green;
(2) move phase post-processing, `SleepScore`, `RiskModel`, `NightSummarizer`
math into a Kotlin Multiplatform `:core` module with no Android imports
(they're nearly pure already); (3) storage: migrating Room → SQLDelight
would match the original CLAUDE.md architecture and is KMP-ready, but is a
big migration — decide here whether to accept Room (Room has KMP support
since 2.7) instead; Room-KMP is likely the cheaper path. iOS app itself
(SwiftUI or CMP) is a separate later L.

### F-3. APK size & performance budgets in CI — **S**

PRD: APK < 15 MB, UI 60 fps, charts < 500 ms/year. **Spec:** CI job fails if
release APK exceeds 15 MB; macrobenchmark module with a baseline profile
(`androidx.benchmark`) generating startup + chart-render timings on a
firebase-free local emulator run (store numbers as CI artifacts, fail on
2× regression). Baseline profile also genuinely speeds cold start.

### F-4. DSP robustness: fuzzing + property tests + sanitizers — **M**

The Rust core parses nothing external, but it must never panic across the
JNI boundary (a panic = aborted session = lost night). **Spec:**
`cargo-fuzz` targets for `SessionEngine::process_frame` (arbitrary f32
frames incl. NaN/Inf/denormals — a NaN RMS poisoning the noise floor is a
real bug class); property tests (proptest): noise floor is monotone under
level shifts, state machine never emits events with `duration < min`,
REI-a is placement-invariant under gain changes; run tests under Miri and
`cargo careful` in a weekly CI job; add `catch_unwind` at every JNI entry
returning an error code (audit that this already holds).

### F-5. Localization infrastructure — **S** then community-driven

NFR-6 requires the mechanism; only English exists. **Spec:** audit for
hardcoded strings (lint `MissingTranslation` + a CI check that composables
don't contain string literals via a custom lint rule); extract everything to
`strings.xml`; add Weblate-friendly repo layout (F-Droid community norm —
translations arrive as PRs, no runtime network). Medical-adjacent strings
(disclaimers) flagged `translatable="false"`? No — translate, but require
the English disclaimer to also always render on the physician report.

---

## G. Privacy & trust hardening

### G-1. Database encryption at rest (optional) — **M**

App-private storage is already sandboxed, but sleep + STOP-BANG answers are
health data; users with rooted phones or bad backup hygiene may want more.
**Spec:** optional (default off — key management adds failure modes)
SQLCipher via `net.zetetic:sqlcipher-android` with the key in Android
Keystore (`setUserAuthenticationRequired(false)` — must decrypt during
unattended overnight writes). Migration path both directions
(`sqlcipher_export`). License check: SQLCipher community edition is
BSD-style, GPLv3-compatible. APK cost ~3 MB — check against F-3 budget; if
it busts the budget, flavor-gate it.

### G-2. Harden the network-egress CI beyond grep — **S/M**

The grep (`http|socket|URL|fetch`) is symbolic; it can't see transitive deps.
**Spec:** add three real checks: (1) manifest assertion — parse the merged
manifest in CI, fail on any permission outside the allowed four and on any
`uses-permission` addition from libraries; (2) dexdump/`apkanalyzer` scan of
the release APK for references to `java.net.Socket`, `java.net.URL`,
`javax.net.ssl`, `okhttp`, failing on any hit outside an allowlist
(`java.net.URI` for SAF is fine — this also finally lets the source grep
stop false-positiving on the word "URL" in comments); (3)
`./gradlew :app:dependencies` diffed against a committed lockfile so a new
transitive dependency requires an explicit, reviewable commit.

### G-3. "Privacy report" screen — **S**

Turn the guarantees into UI. **Spec:** static screen (About → Privacy) that
displays: permissions actually held (queried live from `PackageManager`, not
hardcoded — so it's self-auditing), INTERNET absent ✓, bytes of audio stored
(live count of clip files, normally 0), DB size, retention setting, link to
the reproducible-build verification instructions. Cheap, and it's the
marketing the app refuses to do elsewhere.

### G-4. Per-session delete + panic wipe polish — **S**

Verify per-session delete exists (long-press on calendar day → delete with
the same overwrite semantics); add "delete this night's questionnaire/body
answers individually" per FR-3.3's individually-erasable requirement — audit
that it's actually implemented, it's easy to have missed.

---

## H. Suggested sequencing

| Tier | Items | Rationale |
|---|---|---|
| **Now (v0.3)** | C-5, G-2, B-2, A-6, D-8, C-2 | Small, close documented gaps, harden trust; all S-effort |
| **Next (v0.3–0.4)** | A-2 → E-2, B-1, C-1, D-3, D-1 | Validation credibility, the battery promise, the two biggest PRD gaps (import/export, trends) |
| **Then (v0.4–0.5)** | A-1, A-3, A-4, D-2, D-4, E-1, E-3, F-3, F-5 | REM + sensor fusion build on A-2's validation rig; UX depth |
| **Later (v0.5+)** | D-5, C-3, C-4, F-1, F-2, G-1, A-5, A-7, A-8, B-4, D-6, D-7, F-4, G-3, G-4, B-3, B-5 | Bigger bets, flavor-gated integrations, iOS groundwork |

Dependencies worth respecting: A-2 before A-1/E-2 (never ship REM or remove
"experimental" without the rig); C-1 before C-3 (backup wraps JSON export);
A-1's migration before A-7 (share the schema bump); D-3 before B-4/C-2/D-5
(they all need a settings home); G-2 before C-4 (the flavor must prove
itself against the hardened checks).
