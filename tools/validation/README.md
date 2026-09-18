# Offline validation harness (A-2)

Host-only tools for the T-3 real-data benchmark. Nothing here ships in the
app; the corpus itself never enters the repo (audio can't live here).

## Pipeline

```
corpus WAV (16 kHz mono) ──> dsp replay bin ──> JSONL ──> score.py ──> table for docs/validation.md
```

## 1. Replay (`dsp/src/bin/replay.rs`)

```bash
cd dsp && cargo run --bin replay -- /path/to/night.wav > night.jsonl
# or: ffmpeg -i in.wav -ar 16000 -ac 1 -sample_fmt s16 - | cargo run --bin replay -- - > night.jsonl
```

Feeds 10 ms frames through the same `SessionEngine` the device runs, at the
same 30 s epoch cadence, and prints per-epoch feature vectors plus acoustic
events as JSONL. Requires 16 kHz mono 16-bit PCM — resample first (see
above); anything else exits non-zero. Bit-rot covered by
`dsp/tests/replay.rs`, which runs the binary on a 60 s synthetic fixture.

## 2. Score (`score.py`, stdlib only)

```bash
# Manifest CSV: night_id,jsonl,annotations,split,ahi
python3 tools/validation/score.py --manifest nights.csv
python3 tools/validation/score.py --self-test   # no corpus needed (CI runs this)
```

- `annotations` is an epoch-label CSV (`epoch_index,phase`, phases
  `awake|light|deep|rem`, 30 s epochs). Convert corpus hypnograms
  (PSG-Audio / A3 EDF annotations) to this shape with a one-off script —
  the mapping is corpus-specific, so it lives with whoever holds the data,
  not here. Leave the field empty for REI-vs-AHI-only nights.
- Reports epoch-level Cohen's κ per annotated night, plus REI-a vs AHI
  Pearson r and the AHI ≥ 15 two-sided accuracy. Targets (validation.md):
  r ≥ 0.7, ≥ 80 % correct side.

## 3. Split discipline (T-3.5)

Tune thresholds on `train` rows only. When the config is final:

```bash
python3 tools/validation/score.py --write-freeze thresholds.hash
python3 tools/validation/score.py --manifest nights.csv --freeze thresholds.hash
```

Held-out numbers print only when the hash matches the current
`dsp/src/{apnea_config,phase_config,classifier,engine}.rs` contents —
any threshold edit after freezing re-withholds held-out until re-frozen.
Paste the frozen output into `docs/validation.md`.
