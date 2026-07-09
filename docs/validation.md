# Apnea screening validation (T-3) — status

**Status: not yet performed.** Until the benchmark below is completed and the
targets are met, the apnea screening feature must be labeled **"experimental"**
in release notes and store listings, per T-3 of
[REQUIREMENTS-apnea-screening.md](REQUIREMENTS-apnea-screening.md).

## What has been validated so far

- Synthetic-night CI suite (`dsp/tests/`): recall ≥ 0.9 and precision ≥ 0.9
  for apnea-like events at ≥ 10 dB breathing-to-noise margin; low-margin
  nights produce degraded margin metrics that the app flags as
  `LOW_SIGNAL_QUALITY` and excludes from risk trending.
- Golden-file regression of feature vectors and emitted events for fixed
  synthetic inputs.

Synthetic tests validate the detector logic, not real-world performance.

## Planned real-data benchmark (offline, not in CI)

1. Corpus: PSG-annotated public dataset with ambient or tracheal microphone
   channels (e.g., PSG-Audio / A3 corpus).
2. Resample audio to 16 kHz mono, feed frame-by-frame through `SessionEngine`
   (host build), collect emitted events per night.
3. Compute per-night REI-a and compare against clinician-scored AHI.
4. Release targets: Pearson r ≥ 0.7 between REI-a and AHI; correct side of
   the AHI ≥ 15 threshold in ≥ 80 % of subjects.
5. Tune `dsp/src/apnea_config.rs` thresholds against a training split only;
   report results on a held-out split.
6. Publish per-night results table and correlation plot in this file. If
   targets are not met, ship with the measured numbers published — honesty is
   the feature.
