# Battery / CPU test procedure (T-5, NFR-2)

Run before each release tag that touches `dsp/`. Target device: mid-range 2020
hardware (e.g., Pixel 4a). Acceptance: a whole-night session consumes ≤ 5 %
battery beyond the pre-apnea baseline; apnea DSP adds ≤ 15 % CPU over the
previous pipeline.

## Procedure

1. Install the release candidate: `./gradlew :app:installRelease` (or sideload
   the CI APK). Disable adaptive battery for the app so the OS doesn't skew
   results.
2. Charge to 100 %, unplug, reboot, wait 2 min for boot settle.
3. Reset stats: `adb shell dumpsys batterystats --reset`, then disconnect USB
   (USB power masks drain).
4. Start a sleep session, leave the phone on a nightstand in a quiet room for
   8 h (or a 2 h shortened run, scaled ×4 for comparison only).
5. Stop the session, reconnect USB, capture:
   - `adb shell dumpsys batterystats --charged io.github.ntufar.deltasleep > batterystats.txt`
   - `adb shell dumpsys cpuinfo | grep deltasleep > cpuinfo.txt`
6. Read the app's `Estimated power use` / `mAh` from batterystats and convert
   to % of the device battery capacity.
7. CPU comparison: repeat the run on the previous release (same device, same
   room) and compare the app's CPU time from cpuinfo. Record both numbers.

## Recording results

Append a row per release to the table below.

| Version | Device | Session length | Battery % (this / previous) | App CPU time (this / previous) | Pass |
|---|---|---|---|---|---|
| _pending first measured run_ | | | | | |
