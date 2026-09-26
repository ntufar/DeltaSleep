# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DeltaSleep is a free, open-source, fully offline sleep phase and snore tracking mobile app. **Zero network egress** is a hard constraint — no analytics, no telemetry, no crash reporting. The app must function 100% offline after install.

- License: MIT
- Platforms: Android v1.0 first, iOS v1.1 later
- MVP scope: Android only, manual start/stop, movement-based sleep phases (no REM yet), basic snore detection, nightly hypnogram, CSV export

## Architecture

```
UI Layer: Kotlin Multiplatform Compose (or React Native — TBD)
   ↓
DSP Core: Rust compiled to .so/.a via Android NDK
   ↓
Storage: Room + SQLite, files in app-private directory only
   ↓
OS: Android 8.0+ / iOS 15+
```

### Audio Pipeline (on-device, no network)

1. `AudioRecord` at 16kHz, 10ms frames
2. VAD — voice activity detection to filter podcasts/audiobooks playing via mix-with-others session
3. Feature extraction: RMS, zero-crossing rate, spectral centroid, band power 20–300 Hz
4. Rules engine classifier → "movement" | "snore" | "silence" | "external audio"
5. Aggregate into 30-second epochs → write to SQLite → discard raw PCM immediately
6. Battery target: ≤3% drain per 8h night; duty-cycle mic to 80% when no events detected

### Sleep Phase Classification

- v1: movement + breathing-rate heuristics → Awake / Light / Deep (REM is v0.2+)
- v2 (future): TFLite on-device model

### Snore Detection

Spectral analysis in the 20–300 Hz band. Each snore event records start time, end time, and intensity 1–5. Sensitivity is user-configurable.

## Privacy / Security Constraints (non-negotiable)

- `INTERNET` permission must be **absent** from the Android manifest — the app should not compile with it
- `DISABLE_NETWORK=true` compile-time flag removes all network libs
- CI must fail if `http`, `socket`, `URL`, or `fetch` appear in source
- Only allowed permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`
- Raw audio is **never** saved by default; opt-in snore clips are max 10 s and auto-purged
- "Nuke all data" must overwrite the DB before deleting it

## Data & Storage

- SQLite via Room (DB v4: `sleep_sessions`, `sleep_epochs` + `breathPeriodS`/`externalAudioFraction`/`playbackActive`, `acoustic_event`, `night_summary`, `questionnaire_result`). Schema lives in `docs/schema.md`
- Export format to be documented in `docs/export_schema_v1.json` (planned; CSV export exists, JSON import/export is open backlog C-1)
- Export targets: JSON + CSV, written to user-chosen folder via system file picker
- Data retention: user-configurable (30/90/365 days or never), default 365 days
- All files stored in app-private directory — never in shared storage except on explicit export

## Background Operation

- Android: foreground service with persistent notification, type `FOREGROUND_SERVICE_MICROPHONE`
- iOS: background audio mode
- Must survive phone calls and crashes without losing more than the last 30 s of data
- Auto start/stop (charge + flat phone detection) is opt-in and disabled by default

## Build & Run

### Prerequisites

```bash
# Android SDK + NDK via Android Studio or sdkmanager
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# gradlew is committed; make it executable after a fresh clone
chmod +x gradlew
```

### Common commands

```bash
# Full debug build (compiles Rust DSP first via preBuild hook, then Kotlin)
./gradlew :app:assembleDebug

# Install on connected device
./gradlew :app:installDebug

# Run all unit tests
./gradlew :app:test

# Run a single test class
./gradlew :app:test --tests "com.deltasleep.app.FooTest"

# Build Rust DSP library only (arm64 + x86_64 release)
cd dsp && cargo ndk -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build --release

# Check Rust code
cd dsp && cargo clippy && cargo test

# Verify 16 KB page-size alignment of the ARM64 .so (must show max-page-size=16384)
readelf -l app/src/main/jniLibs/arm64-v8a/libdeltasleep_dsp.so | grep -E "LOAD|alignment"
```

### iOS app (`ios/`)

Native SwiftUI app (iOS 17+, iPhone only) linking the same Rust DSP through a C ABI
(`dsp/src/ffi_c.rs`, declared in `ios/DeltaSleepDSP.h`). The JNI shim and `jni` crate are
compiled out on Apple targets. Swift analysis code (`ios/DeltaSleep/Analysis/`) is a 1:1 port
of the Kotlin logic — change both together. SQLite schema matches Room v5.

```bash
# Build Rust static lib by hand (the Xcode "Build Rust DSP" phase runs this automatically)
ios/build-rust.sh iphonesimulator   # or iphoneos

# Build + unit tests on simulator
cd ios && xcodebuild -project DeltaSleep.xcodeproj -scheme DeltaSleep \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test

# Archive + upload to App Store Connect (team 6CT959RWB8, automatic signing)
cd ios && xcodebuild -project DeltaSleep.xcodeproj -scheme DeltaSleep -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/DeltaSleep.xcarchive -allowProvisioningUpdates archive
xcodebuild -exportArchive -archivePath build/DeltaSleep.xcarchive -exportOptionsPlist ExportOptions.plist \
  -exportPath build/upload -allowProvisioningUpdates
```

Releases go through CI (`.github/workflows/ci-ios.yml`): push a tag `ios-vX.Y.Z` and the
workflow archives, signs (cloud-managed certificate via the App Store Connect API key in secrets
`ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_KEY_P8`) and uploads to App Store Connect. The tag sets
`MARKETING_VERSION`; the build number is the workflow run number + 1. Submitting for review is
manual in App Store Connect. iOS tags are separate from the Android `vX.Y.Z` tags. DEBUG-only launch args for screenshots: `-seedDemoData`, `-openLatestSession YES`,
`-initialTab trends|report`, `-autoStartTracking`, `-theme dark`. App Store screenshots live in
`ios/appstore/screenshots/`. The iOS simulator cannot open the Mac microphone reliably
(AURemoteIO RPC timeout abort) — test live capture on a device.

### CI network-egress check

The CI pipeline must fail if any of these strings appear in Kotlin/Java source:
`http`, `socket`, `URL`, `fetch`. Spot-check locally with:

```bash
grep -rE '\b(http|socket|URL|fetch)\b' app/src/main/java/
```

## Release Process

All release preparation happens **locally before pushing the tag**. CI only builds and publishes — it never edits files or pushes commits back to master.

Steps for a new release (replace `X.Y.Z` with the new version):

1. **Update `app/build.gradle.kts`** — bump `versionCode` (increment by 1) and `versionName` to `X.Y.Z`.
2. **Update `CHANGELOG.md`** — add a `## [X.Y.Z] - YYYY-MM-DD` section below `## [Unreleased]` with all changes listed under `### Fixed`, `### Added`, etc.
3. **Commit both files:**
   ```bash
   git add app/build.gradle.kts CHANGELOG.md
   git commit -m "chore: bump to vX.Y.Z"
   ```
4. **Tag and push:**
   ```bash
   git tag vX.Y.Z
   git push origin master
   git push origin vX.Y.Z
   ```

The `vX.Y.Z` tag triggers the CI release job, which extracts the release notes from CHANGELOG.md and publishes the GitHub Release and Play Store build.

**Do not** leave the CHANGELOG entry empty or under `[Unreleased]` when pushing a release tag — the CI extracts notes directly from the `[X.Y.Z]` section.

## Key Docs

- `docs/PRD.md` — full product requirements
- `docs/schema.md` — SQLite schema (v2, current)
- `docs/export_schema_v1.json` — open export format spec (planned)
