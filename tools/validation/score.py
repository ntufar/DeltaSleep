#!/usr/bin/env python3
"""Offline validation scorer (A-2). Stdlib only — no numpy/pandas.

Scores `replay` JSONL output against clinician annotations and emits the
results table `docs/validation.md` promises:

  python3 tools/validation/score.py --manifest nights.csv [--freeze thresholds.hash]

Manifest CSV columns: night_id,jsonl,annotations,split,ahi
  - jsonl:        replay JSONL for the night (16 kHz mono feed)
  - annotations: epoch-label CSV (may be empty when only AHI is known)
  - split:        train | heldout
  - ahi:          clinician-scored AHI (may be empty; excluded from r)

Annotations CSV columns: epoch_index,phase  with phase in
awake|light|deep|rem (30 s epochs, index 0 = first).

Metrics:
  - epoch-level Cohen's kappa over phases (replay ordinals 0-3), per night
    with annotations; truncated to the shorter of replay/annotations and
    skipping partial trailing replay epochs (frames < 3000).
  - per-night REI-a = APNEA_LIKE events / sleep hours, where sleep hours
    cover full non-awake epochs with external_audio_fraction < 0.5
    (playbackActive is a device-only signal and unavailable offline).
  - Pearson r of REI-a vs AHI plus the AHI>=15 two-sided accuracy.

Split discipline (validation.md item 5 / T-3.5): train rows always score.
Held-out rows score ONLY when --freeze points at a hash file matching the
current DSP thresholds (see --write-freeze); otherwise the held-out section
is withheld with a refusal notice. Tune on train, freeze, then look once.

  python3 tools/validation/score.py --write-freeze thresholds.hash
  python3 tools/validation/score.py --self-test   # no corpus needed
"""

import argparse
import csv
import hashlib
import json
import math
import os
import sys
import tempfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# Every file whose edit changes detector behavior (thresholds AND the rules
# that consume them): the freeze hash covers all of them.
THRESHOLD_FILES = [
    "dsp/src/apnea_config.rs",
    "dsp/src/phase_config.rs",
    "dsp/src/classifier.rs",
    "dsp/src/engine.rs",
]

PHASE_LABELS = {"awake": 0, "light": 1, "deep": 2, "rem": 3}
AHI_THRESHOLD = 15.0


def freeze_hash(files=THRESHOLD_FILES, root=REPO_ROOT):
    h = hashlib.sha256()
    for rel in files:
        with open(os.path.join(root, rel), "rb") as f:
            h.update(rel.encode())
            h.update(b"\0")
            h.update(f.read())
            h.update(b"\0")
    return h.hexdigest()


def load_replay(path):
    epochs, events = [], []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if obj.get("type") == "epoch":
                epochs.append(obj)
            elif obj.get("type") == "event":
                events.append(obj)
    return epochs, events


def load_annotations(path):
    labels = {}
    with open(path) as f:
        for row in csv.DictReader(f):
            labels[int(row["epoch_index"])] = PHASE_LABELS[row["phase"].strip().lower()]
    return labels


def cohen_kappa(actual, predicted, n_classes=4):
    n = len(actual)
    if n == 0:
        return float("nan")
    agree = sum(1 for a, p in zip(actual, predicted) if a == p) / n
    pe = 0.0
    for c in range(n_classes):
        pe += (sum(1 for a in actual if a == c) / n) * (sum(1 for p in predicted if p == c) / n)
    if pe >= 1.0:
        return 1.0 if agree >= 1.0 else 0.0
    return (agree - pe) / (1 - pe)


def pearson_r(xs, ys):
    n = len(xs)
    if n < 2:
        return float("nan")
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx == 0 or syy == 0:
        return float("nan")
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / math.sqrt(sxx * syy)


def rei_a(epochs, events):
    sleep_epochs = [
        e for e in epochs
        if e.get("frames", 3000) == 3000
        and e.get("phase", 0) != 0
        and e.get("external_audio_fraction", 0) < 0.5
    ]
    hours = len(sleep_epochs) * 30.0 / 3600.0
    apnea = sum(1 for e in events if e.get("event") == "APNEA_LIKE")
    return apnea / hours if hours > 0 else 0.0


def score_night(night_id, jsonl, annotations):
    epochs, events = load_replay(jsonl)
    out = {"night": night_id, "rei_a": rei_a(epochs, events), "kappa": None, "n_epochs": 0}
    if annotations:
        labels = load_annotations(annotations)
        full = [e for e in epochs if e.get("frames", 3000) == 3000]
        actual, predicted = [], []
        for e in full:
            idx = e.get("index", len(actual))
            if idx in labels:
                actual.append(labels[idx])
                predicted.append(e.get("phase", 0))
        out["kappa"] = cohen_kappa(actual, predicted)
        out["n_epochs"] = len(actual)
    return out


def render_table(rows):
    lines = ["| night | REI-a | phase κ (n epochs) |", "|---|---|---|"]
    for r in rows:
        k = f"{r['kappa']:.3f} ({r['n_epochs']})" if r["kappa"] is not None and r["kappa"] == r["kappa"] else "n/a"
        lines.append(f"| {r['night']} | {r['rei_a']:.2f} | {k} |")
    return "\n".join(lines)


def render_correlation(rows):
    pairs = [(r["rei_a"], r["ahi"]) for r in rows if r.get("ahi") is not None]
    if len(pairs) < 2:
        return "REI-a vs AHI: need ≥ 2 nights with AHI (have %d)." % len(pairs)
    rs, ahs = zip(*pairs)
    r = pearson_r(list(rs), list(ahs))
    both = sum(1 for rei, ahi in pairs if (rei >= AHI_THRESHOLD) == (ahi >= AHI_THRESHOLD))
    return (
        "REI-a vs AHI over %d nights: Pearson r = %.3f; "
        "correct side of AHI ≥ 15 in %d/%d (%.0f%%)." % (
            len(pairs), r, both, len(pairs), 100.0 * both / len(pairs))
    )


def self_test():
    tmp = tempfile.mkdtemp(prefix="score-selftest-")

    def write(name, text):
        p = os.path.join(tmp, name)
        with open(p, "w") as f:
            f.write(text)
        return p

    # Kappa oracle by hand: actual [0,0,1,1] vs predicted [0,1,1,1]:
    # agree .75; pe = .5*.25 + .5*.75 = .5; κ = (.75-.5)/.5 = 0.5.
    jl = write("n1.jsonl", "".join(
        '{"type":"epoch","index":%d,"start_ms":%d,"frames":3000,"phase":%d,'
        '"mean_rms":0.01,"rms_variance":0.0001,"snore":false,'
        '"breathing_margin_db":12.0,"breathing_fraction":1.0,'
        '"breath_period_s":4.0,"breath_period_cv":0.05,'
        '"external_audio_fraction":0.0}\n' % (i, i * 30000, p)
        for i, p in enumerate([0, 1, 1, 1])
    ))
    an = write("n1.csv", "epoch_index,phase\n0,awake\n1,awake\n2,light\n3,light\n")
    r = score_night("n1", jl, an)
    assert r["kappa"] == 0.5, r
    assert r["n_epochs"] == 4, r

    # Perfect agreement -> 1.0.
    jl2 = write("n2.jsonl", "".join(
        '{"type":"epoch","index":%d,"start_ms":%d,"frames":3000,"phase":%d,'
        '"mean_rms":0.01,"rms_variance":0.0001,"snore":false,'
        '"breathing_margin_db":12.0,"breathing_fraction":1.0,'
        '"breath_period_s":4.0,"breath_period_cv":0.05,'
        '"external_audio_fraction":0.0}\n' % (i, i * 30000, p)
        for i, p in enumerate([0, 1, 2, 3, 1])
    ))
    an2 = write("n2.csv", "epoch_index,phase\n0,awake\n1,light\n2,deep\n3,rem\n4,light\n")
    r2 = score_night("n2", jl2, an2)
    assert r2["kappa"] == 1.0, r2

    # Pearson oracles.
    assert pearson_r([1, 2, 3, 4], [2, 4, 6, 8]) == 1.0
    assert pearson_r([1, 2, 3, 4], [8, 6, 4, 2]) == -1.0
    assert math.isnan(pearson_r([1], [2]))

    # REI-a: 2 APNEA_LIKE in 4 sleep epochs (2 min = 1/30 h) -> 60.0;
    # awake epochs excluded from the denominator.
    jl3 = write("n3.jsonl",
        '{"type":"run","tool":"replay","sample_rate_hz":16000,"total_frames":18000}\n' +
        "".join(
            '{"type":"epoch","index":%d,"start_ms":%d,"frames":3000,"phase":%d,'
            '"mean_rms":0.01,"rms_variance":0.0001,"snore":false,'
            '"breathing_margin_db":12.0,"breathing_fraction":1.0,'
            '"breath_period_s":4.0,"breath_period_cv":0.05,'
            '"external_audio_fraction":0.0}\n' % (i, i * 30000, p)
            for i, p in enumerate([0, 1, 1, 1, 1, 0])
        ) +
        '{"type":"event","event":"APNEA_LIKE","start_ms":60000,"duration_ms":12000,'
        '"confidence":0.8,"peak_db_over_floor":18.0,"envelope_reduction_pct":0.9,'
        '"terminated_by_gasp":true,"mean_db_over_floor":10.0}\n'
        '{"type":"event","event":"APNEA_LIKE","start_ms":120000,"duration_ms":14000,'
        '"confidence":0.8,"peak_db_over_floor":18.0,"envelope_reduction_pct":0.9,'
        '"terminated_by_gasp":false,"mean_db_over_floor":10.0}\n')
    epochs3, events3 = load_replay(jl3)
    assert rei_a(epochs3, events3) == 60.0, rei_a(epochs3, events3)

    # Freeze gate: bogus hash refuses, real hash accepts.
    assert freeze_hash() == freeze_hash()
    assert freeze_hash() != "0" * 64
    print("self-test: all assertions passed")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Offline validation scorer (A-2).")
    ap.add_argument("--manifest", help="nights CSV (night_id,jsonl,annotations,split,ahi)")
    ap.add_argument("--freeze", help="hash file; held-out scores only on match")
    ap.add_argument("--write-freeze", metavar="FILE", help="write current thresholds hash and exit")
    ap.add_argument("--self-test", action="store_true", help="run built-in oracles, no corpus needed")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if args.write_freeze:
        with open(args.write_freeze, "w") as f:
            f.write(freeze_hash() + "\n")
        print(f"froze {freeze_hash()} -> {args.write_freeze}")
        return 0
    if not args.manifest:
        ap.error("--manifest is required (or --self-test / --write-freeze)")

    train, heldout = [], []
    with open(args.manifest, newline="") as f:
        for row in csv.DictReader(f):
            ahi = row.get("ahi", "").strip()
            rec = score_night(
                row["night_id"], row["jsonl"], row.get("annotations", "").strip() or None)
            rec["ahi"] = float(ahi) if ahi else None
            (train if row["split"].strip() == "train" else heldout).append(rec)

    print("## Train split (always reported)\n")
    print(render_table(train) + "\n")
    print(render_correlation(train) + "\n")

    frozen = False
    if args.freeze:
        try:
            with open(args.freeze) as f:
                frozen = (f.read().strip() == freeze_hash())
        except OSError:
            frozen = False
    print("## Held-out split\n")
    if frozen:
        print(render_table(heldout) + "\n")
        print(render_correlation(heldout))
    else:
        print(
            "WITHHELD — config not frozen (validation.md item 5 / T-3.5).\n"
            "Tune thresholds on the train split only, then run:\n"
            "  python3 tools/validation/score.py --write-freeze thresholds.hash\n"
            "and re-run with --freeze thresholds.hash to view held-out numbers.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
