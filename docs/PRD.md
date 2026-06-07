### **DeltaSleep - Requirements Document v1.0**

#### **1. Project Overview**

**Product**: DeltaSleep  
**Tagline**: "Your sleep data stays in your bed"  
**Type**: Free, open-source mobile app for sleep phase + snore tracking  
**License**: GPLv3 - if you improve it, everyone benefits  
**Platforms**: Android v1.0, iOS v1.1  

**Core Principle**: Zero network egress. No analytics, no crash reporting, no "anonymous telemetry". The app works 100% offline after install.

#### **2. Functional Requirements**

**2.1 Core Tracking**

| Feature | Requirement | Notes |
| --- | --- | --- |
| **Audio Capture** | Record via mic at 16kHz mono while other apps play audio | Use `mix-with-others` audio session. Must not pause Spotify/Audible |
| **Background Ops** | Run as foreground service with persistent notification | Android: `FOREGROUND_SERVICE_DATA_SYNC`. iOS: background audio mode |
| **Sleep Phases** | Classify Awake, Light, Deep, REM every 30s | v1: movement + breathing rate heuristics. v2: TFLite model |
| **Snore Detection** | Detect snore events: start/end time, intensity 1-5 | Spectral analysis 20-300Hz. Configurable sensitivity |
| **Sleep Metrics** | Total sleep, efficiency %, latency, WASO, snore % | All computed on-device |
| **Auto Start/Stop** | Optional: start on charging + flat phone, stop on movement | User must opt-in explicitly |

**2.2 Charts & Visualization**

All charts render locally using on-device libraries. No webviews, no external chart services.

1. **Nightly Hypnogram**: Time vs sleep stage chart. Color coded: Awake=red, Light=light blue, Deep=dark blue, REM=purple
2. **Snore Timeline**: Overlay on hypnogram showing snore bursts as vertical lines or heatmap
3. **Sleep Metrics Cards**: Duration, score 0-100, snore time, wake events. Big numbers for morning glance
4. **Trends Dashboard**: 
   - Weekly sleep duration bar chart
   - Deep sleep % line chart over 30 days  
   - Snore frequency heatmap by day of week
   - Bedtime/wake time consistency scatter plot
5. **Audio Level Graph**: RMS energy over time, to debug false positives

**2.3 Data Management**

| Requirement | Detail |
| --- | --- |
| **Local Storage** | SQLite DB. Schema documented in `/docs/schema.md` |
| **No Network Code** | App must compile with `INTERNET` permission removed on Android |
| **Export** | JSON + CSV export to device storage. User picks folder via system picker |
| **Import** | Restore from previous JSON export |
| **Data Retention** | User-set auto-delete: 30/90/365 days or never. Default 365 |
| **Raw Audio** | Never saved by default. Opt-in "Save snore clips" stores max 10s clips, auto-purged |
| **Deletion** | "Nuke all data" button in settings. Overwrites DB before delete |

**2.4 User Experience**

- **Start Flow**: Tap "Sleep" → 0/5/15min countdown → mic calibrates room noise → tracking starts
- **Morning Flow**: Stop tracking → immediate hypnogram + summary → "How did you feel?" 1-5 rating
- **Settings**: Mic sensitivity slider, disable snore detection, theme, data controls
- **Permissions**: Only `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`. No location, no contacts

#### **3. Technical Requirements**

**3.1 Architecture**
```
UI Layer: Kotlin Multiplatform Compose OR React Native
   ↓
DSP Core: Rust compiled to .so/.a via NDK
   ↓  
Storage: SQLDelight/SQLite, files in app-private dir only
   ↓
OS: Android 8.0+ / iOS 15+
```

**3.2 Audio Pipeline - All On-Device**
1. **Capture**: `AudioRecord` 16kHz, 10ms frames
2. **VAD**: Voice activity detection to ignore podcasts/audiobooks  
3. **Feature Extract**: RMS, zero-crossing rate, spectral centroid, band power 20-300Hz
4. **Classify**: Rules engine v1 → "movement", "snore", "silence", "external audio"
5. **Aggregate**: Write 30s epochs to DB. Discard raw PCM immediately
6. **Battery**: <3% per 8h night target. Duty cycle mic 80% if no events detected

**3.3 Privacy & Security Guarantees**
1. **Static Analysis**: CI job fails if `http`, `socket`, `URL`, `fetch` found in source
2. **Build Flag**: `DISABLE_NETWORK=true` compile-time check removes all network libs
3. **Permissions Audit**: F-Droid reproducible build, verify no hidden perms
4. **Open Data Format**: `/docs/export_schema_v1.json` so users own their data forever

#### **4. Non-Functional Requirements**

| Category | Requirement |
| --- | --- |
| **Performance** | UI 60fps, chart render <500ms for 1 year of data |
| **Reliability** | Resume after crash/phone call. Don’t lose more than last 30s |
| **Battery** | ≤5% drain per 8h on Pixel 6 / iPhone 12 baseline |
| **Accessibility** | TalkBack/VoiceOver support, dynamic text, high contrast mode |
| **Size** | APK <15MB, no model downloads |
| **Open Source** | All code public, no binary blobs, buildable via F-Droid |

#### **5. MVP Scope - v0.1.0**

Ship in 4-6 weeks:
1. Android only
2. Manual start/stop sleep tracking
3. Movement-based Light/Deep/Awake only - no REM yet
4. Basic snore counter yes/no per 30s epoch
5. Nightly hypnogram + 3 summary stats
6. CSV export
7. No settings except "Delete all data"

Everything else is v0.2+.

#### **6. Out of Scope for v1.0**
Medical claims, CPAP, smart alarm, cloud sync, account system, wearable integration, multi-user, social sharing.
