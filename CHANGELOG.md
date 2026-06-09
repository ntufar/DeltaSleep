# Changelog

All notable changes to DeltaSleep are documented here.  
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) — Versioning: [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.12] - 2026-06-09

## [0.1.11] - 2026-06-09

### Fixed
- Declare `foregroundServiceType="microphone"` on the sleep tracking service; previously `dataSync` allowed Android to mute `AudioRecord` when the screen turned off, producing all-zero samples classified as DEEP sleep
- Throw `IOException` from `AudioCapture` when `AudioRecord.read()` returns an error code or 200 consecutive all-zero frames (2 s), so the capture loop restarts instead of hanging silently
- Retry `AudioCapture` flow in `SleepTrackingService` with exponential backoff (5 s → 60 s) on mic loss, keeping recording alive for the full session
- Override DSP phase classification to AWAKE when epoch `mean_rms == 0` so silent frames are never counted as DEEP sleep
- Derive "Sleep time" stat from session timestamps rather than epoch count so mid-session mic loss no longer makes the session appear shorter

## [0.1.10] - 2026-06-10

### Investigated
- Identified microphone-silence bug: Android revokes mic access mid-session returning all-zero samples; `AudioCapture` does not detect this, causing the DSP to classify silence as DEEP sleep and inflate the deep-sleep percentage
- Identified capture-loop hang bug: when `AudioRecord.read()` returns an error code the flow stops emitting without completing, leaving the coroutine blocked and producing no epochs for the rest of the session (observed as ~55 min of missing data in session 10)
- Identified sleep-time under-report: `SessionViewModel` derives total sleep time from epoch count rather than session timestamps, so the missing epochs make the session appear shorter than it was

## [0.1.9] - 2026-06-08

### Added
- In-app user guide (Help screen) accessible via the "? Help" button on the home screen; covers phone placement, permissions, active-screen charts, hypnogram, CSV export, data deletion, battery tips, and an FAQ
- Web usage manual at `web/manual.html` with sidebar table of contents, step lists, CSV column reference, and troubleshooting section; linked from the main site nav

## [0.1.8] - 2026-06-08

### Fixed
- Upgrade Android Gradle Plugin to 8.5.2 so native libraries are ZIP-aligned to 16 KB (`zipalign -P 16`), satisfying the Google Play 16 KB page-size requirement for Android 15+ devices

## [0.1.7] - 2026-06-08

### Fixed
- CI caches the Gradle distribution under a stable key so wrapper.properties changes no longer bust the cache; increased wrapper retry backoff to 15 s to survive brief CDN blips

## [0.1.6] - 2026-06-08

### Fixed
- Gradle wrapper network timeout raised to 120 s with 3 retries so CI can download the distribution on cold cache

## [0.1.5] - 2026-06-08

### Fixed
- Sleep tracking service now resumes epoch recording after being restarted by Android (battery optimizer / OEM kill); previously a START_STICKY restart with a null intent skipped `startCapture()`, producing spuriously short sleep totals
- Sleep time stat card no longer shows a leading "0h" prefix for sub-hour sessions, so all three stat cards stay the same height

## [0.1.4] - 2026-06-08

## [0.1.3] - 2026-06-07

## [0.1.2] - 2026-06-08

### Added
- Active sleep monitoring screen shown immediately when tracking starts
- Rolling 30-second waveform displaying audio level (RMS), zero-crossing rate, and snore-band power as live overlaid graphs
- Current sleep-phase badge (Awake / Light / Deep) updated every 30-second epoch
- Elapsed time clock (HH:MM:SS) counting from session start
- Animated snore indicator that pulses red when the last epoch detected snoring
- Screen kept on automatically during active tracking
- CI now builds a signed AAB and publishes it to Play Store Internal Testing on every `v*` tag

## [0.1.0] - 2026-06-08

### Added
- Android foreground service (`FOREGROUND_SERVICE_DATA_SYNC`) capturing 16 kHz mono audio in 10 ms frames
- Rust DSP library (`libdeltasleep_dsp.so`) compiled via NDK with first-order IIR band-pass filter (20–300 Hz) for snore detection
- Sleep phase classification (Awake / Light / Deep) via RMS + variance heuristics every 30 s
- Snore detection per epoch using spectral band-power ratio threshold
- Local SQLite database (Room) storing sessions and 30 s epochs; raw PCM is never persisted
- Nightly hypnogram rendered on-device via Compose Canvas (Awake = red, Light = light blue, Deep = dark blue; snore tint overlay)
- Morning summary screen: total sleep time, snore %, deep sleep %, feel rating 1–5
- CSV export of full epoch data to user-chosen folder via Storage Access Framework
- "Delete all data" with DB overwrite before deletion
- Mix-with-others audio session — Spotify/Audible continue uninterrupted during tracking
- No `INTERNET` permission; CI fails if `http`, `socket`, `URL`, or `fetch` appear in source
- GitHub Actions CI: privacy audit → debug build → release APK on `v*` tags
- GitHub Pages landing site at `ntufar.github.io/DeltaSleep`
