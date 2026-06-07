# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DeltaSleep is a free, open-source, fully offline sleep phase and snore tracking mobile app. **Zero network egress** is a hard constraint — no analytics, no telemetry, no crash reporting. The app must function 100% offline after install.

- License: GPLv3
- Platforms: Android v1.0 first, iOS v1.1 later
- MVP scope: Android only, manual start/stop, movement-based sleep phases (no REM yet), basic snore detection, nightly hypnogram, CSV export

## Architecture

```
UI Layer: Kotlin Multiplatform Compose (or React Native — TBD)
   ↓
DSP Core: Rust compiled to .so/.a via Android NDK
   ↓
Storage: SQLDelight + SQLite, files in app-private directory only
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
- Only allowed permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`
- Raw audio is **never** saved by default; opt-in snore clips are max 10 s and auto-purged
- "Nuke all data" must overwrite the DB before deleting it

## Data & Storage

- SQLite via SQLDelight. Schema lives in `docs/schema.md` (to be created)
- Export format documented in `docs/export_schema_v1.json`
- Export targets: JSON + CSV, written to user-chosen folder via system file picker
- Data retention: user-configurable (30/90/365 days or never), default 365 days
- All files stored in app-private directory — never in shared storage except on explicit export

## Background Operation

- Android: foreground service with persistent notification, type `FOREGROUND_SERVICE_DATA_SYNC`
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

### CI network-egress check

The CI pipeline must fail if any of these strings appear in Kotlin/Java source:
`http`, `socket`, `URL`, `fetch`. Spot-check locally with:

```bash
grep -rE '\b(http|socket|URL|fetch)\b' app/src/main/java/
```

## Key Docs

- `docs/PRD.md` — full product requirements
- `docs/schema.md` — SQLite schema (planned)
- `docs/export_schema_v1.json` — open export format spec (planned)
