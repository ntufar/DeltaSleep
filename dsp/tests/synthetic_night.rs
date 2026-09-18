//! T-2: synthetic-night end-to-end validation of the apnea detector.
//!
//! Composes deterministic synthetic nights (see `fixtures/`) and feeds them
//! through `SessionEngine` frame by frame, polling epochs and events exactly
//! like the Kotlin service does (30 s cadence). Asserts the spec numbers:
//! recall ≥ 0.9 AND precision ≥ 0.9 at ≥ 10 dB breathing-to-noise margin,
//! and that the per-epoch margin output reflects low-margin nights so the
//! Kotlin side can flag LOW_SIGNAL_QUALITY (FR-2.4).

mod fixtures;

use deltasleep_dsp::apnea::{AcousticEvent, EventType};
use deltasleep_dsp::engine::{EpochOutput, SessionEngine};
use fixtures::{score_events, ApneaGap, NightConfig, NightGenerator};

/// Frames per 30 s epoch.
const EPOCH_FRAMES: u64 = 3_000;

struct NightRun {
    events: Vec<AcousticEvent>,
    epochs: Vec<EpochOutput>,
}

/// Runs a full synthetic night through the engine with the production
/// polling cadence (epoch summary + event drain every 30 s).
fn run_night(cfg: &NightConfig) -> NightRun {
    let mut engine = SessionEngine::new();
    engine.start_session();
    let mut generator = NightGenerator::new(cfg.clone());
    let mut events = Vec::new();
    let mut epochs = Vec::new();

    for idx in 0..cfg.total_frames() {
        let frame = generator.next_frame(idx);
        engine.process_frame(&frame);
        if (idx + 1) % EPOCH_FRAMES == 0 {
            epochs.push(engine.compute_epoch());
            engine.reset_epoch();
            events.extend(engine.poll_events());
        }
    }
    events.extend(engine.poll_events());
    NightRun { events, epochs }
}

fn median_f32(values: &mut [f32]) -> f32 {
    values.sort_by(|a, b| a.partial_cmp(b).unwrap());
    values[values.len() / 2]
}

// ── High-margin night: recall/precision ≥ 0.9 (spec T-2) ──────────────────────

#[test]
fn apnea_recall_and_precision_at_high_margin() {
    // Three independent nights (different seeds, gap layouts, breath rates);
    // recall/precision are scored on the aggregate so the 0.9 targets are
    // not a single-seed fluke.
    let scenarios: [(u32, f32, Vec<ApneaGap>); 3] = [
        (
            42,
            4.0,
            vec![
                ApneaGap { start_s: 60.0, duration_s: 14.0, with_gasp: true },
                ApneaGap { start_s: 110.0, duration_s: 16.0, with_gasp: true },
                ApneaGap { start_s: 170.0, duration_s: 13.0, with_gasp: false },
                ApneaGap { start_s: 230.0, duration_s: 18.0, with_gasp: true },
            ],
        ),
        (
            777,
            5.0,
            vec![
                ApneaGap { start_s: 50.0, duration_s: 12.0, with_gasp: false },
                ApneaGap { start_s: 130.0, duration_s: 25.0, with_gasp: true },
                ApneaGap { start_s: 210.0, duration_s: 15.0, with_gasp: true },
            ],
        ),
        (
            31_415,
            3.0,
            vec![
                ApneaGap { start_s: 70.0, duration_s: 13.0, with_gasp: true },
                ApneaGap { start_s: 140.0, duration_s: 17.0, with_gasp: false },
                ApneaGap { start_s: 200.0, duration_s: 14.0, with_gasp: true },
                ApneaGap { start_s: 255.0, duration_s: 12.0, with_gasp: true },
            ],
        ),
    ];

    let (mut tp_total, mut fp_total, mut fn_total) = (0usize, 0usize, 0usize);
    let mut gasp_terminated_total = 0usize;
    let mut gasp_events_total = 0usize;

    for (seed, period_s, truth) in &scenarios {
        let mut cfg = NightConfig::baseline(*seed);
        cfg.breathing_period_s = *period_s;
        cfg.apnea_gaps = truth.clone();
        let run = run_night(&cfg);

        // Premise check: breathing-to-noise margin of this night is ≥ 10 dB.
        let mut margins: Vec<f32> =
            run.epochs.iter().map(|e| e.mean_breathing_margin_db).collect();
        let median_margin = median_f32(&mut margins);
        assert!(
            median_margin >= 10.0,
            "seed {seed}: test premise violated: median epoch margin {median_margin} dB < 10 dB"
        );

        let detected: Vec<u64> = run
            .events
            .iter()
            .filter(|e| e.event_type == EventType::ApneaLike)
            .map(|e| e.start_offset_ms)
            .collect();
        let truth_starts: Vec<f32> = truth.iter().map(|g| g.start_s).collect();
        let (tp, fp, fn_count) = score_events(&detected, &truth_starts, 6.0);
        tp_total += tp;
        fp_total += fp;
        fn_total += fn_count;

        // Durations of emitted events must be in the right ballpark (≥ 10 s,
        // bounded by the longest true gap plus slack).
        for ev in run.events.iter().filter(|e| e.event_type == EventType::ApneaLike) {
            assert!(ev.duration_ms >= 10_000, "seed {seed}: event below clinical minimum");
            assert!(ev.duration_ms <= 31_000, "seed {seed}: event absurdly long: {} ms", ev.duration_ms);
            assert!(ev.envelope_reduction_pct > 0.5, "seed {seed}: full gaps must score deep");
            assert!(ev.confidence > 0.4, "seed {seed}: high-SNR events must be confident");
        }

        gasp_terminated_total += run
            .events
            .iter()
            .filter(|e| e.event_type == EventType::ApneaLike && e.terminated_by_gasp)
            .count();
        gasp_events_total +=
            run.events.iter().filter(|e| e.event_type == EventType::Gasp).count();
    }

    let recall = tp_total as f32 / (tp_total + fn_total).max(1) as f32;
    let precision = tp_total as f32 / (tp_total + fp_total).max(1) as f32;
    assert!(
        recall >= 0.9,
        "recall {recall} < 0.9 (tp={tp_total}, fn={fn_total})"
    );
    assert!(
        precision >= 0.9,
        "precision {precision} < 0.9 (tp={tp_total}, fp={fp_total})"
    );

    // Gasp attribution: 8 of the 11 true gaps end in a gasp; most must be
    // attributed and produce standalone GASP records.
    assert!(
        gasp_terminated_total >= 6,
        "expected ≥ 6 gasp-terminated events, got {gasp_terminated_total}"
    );
    assert!(gasp_events_total >= 6, "expected ≥ 6 GASP events, got {gasp_events_total}");
}

// ── Quiet night: no false events ───────────────────────────────────────────────

#[test]
fn quiet_breathing_night_produces_no_respiratory_events() {
    let cfg = NightConfig::baseline(1337); // no gaps, no snores
    let run = run_night(&cfg);
    let false_events = run
        .events
        .iter()
        .filter(|e| matches!(e.event_type, EventType::ApneaLike | EventType::HypopneaLike))
        .count();
    assert_eq!(false_events, 0, "clean breathing night must not emit decrement events");

    // Breathing must be recognised for the bulk of the night.
    let breathing_epochs = run
        .epochs
        .iter()
        .skip(2) // allow tracker warm-up
        .filter(|e| e.breathing_present_fraction > 0.5)
        .count();
    assert!(
        breathing_epochs >= run.epochs.len() - 3,
        "breathing not recognised: {breathing_epochs}/{} epochs",
        run.epochs.len()
    );
}

// ── Snoring night: SNORE_EPISODE events (FR-1.8) ───────────────────────────────

#[test]
fn snoring_night_emits_snore_episodes_and_epoch_flag() {
    let mut cfg = NightConfig::baseline(99);
    cfg.total_s = 120.0;
    cfg.snore_intervals = vec![(40.0, 70.0)];
    let run = run_night(&cfg);

    let snores: Vec<&AcousticEvent> =
        run.events.iter().filter(|e| e.event_type == EventType::SnoreEpisode).collect();
    assert!(!snores.is_empty(), "snoring interval must produce SNORE_EPISODE events");
    for ev in &snores {
        let start_s = ev.start_offset_ms as f32 / 1_000.0;
        assert!(
            (38.0..72.0).contains(&start_s),
            "snore episode at {start_s}s outside the snoring interval"
        );
        assert!(ev.peak_db_over_floor > 0.0);
        assert!(ev.mean_db_over_floor > 0.0);
    }

    // Epoch-level snore flag (existing behaviour) must fire for the snoring
    // epochs (40–70 s spans epochs 1 and 2) and stay off elsewhere.
    assert!(run.epochs[1].snore_flag || run.epochs[2].snore_flag, "epoch snore flag missing");
    assert!(!run.epochs[0].snore_flag, "epoch 0 has no snoring");
}

// ── Breath period export (A-7) ─────────────────────────────────────────────────

#[test]
fn epoch_breath_period_matches_synthesized_rate() {
    // Ground truth: the fixture breathes at a 4.0 s period all night.
    let cfg = NightConfig::baseline(2024);
    let run = run_night(&cfg);

    let mut periods: Vec<f32> = run
        .epochs
        .iter()
        .skip(2) // allow periodicity-ring warm-up
        .filter(|e| e.breathing_present_fraction > 0.5)
        .map(|e| e.breath_period_s)
        .collect();
    assert!(
        periods.len() >= run.epochs.len() - 3,
        "breathing not recognised: {}/{} epochs",
        periods.len(),
        run.epochs.len()
    );
    for p in &periods {
        assert!(
            (2.0..=8.0).contains(p),
            "breath period {p} outside tracker range 2–8 s"
        );
    }
    let median = median_f32(&mut periods);
    assert!(
        (median - 4.0).abs() <= 0.3,
        "median breath period {median} far from synthesized 4.0 s"
    );
}

#[test]
fn slow_breathing_period_is_not_pulled_down() {
    // Octave tie-break must only resolve true ties: a genuine 7 s rhythm
    // (sub-harmonic lags anti-correlate) must still read ≈ 7 s.
    let mut cfg = NightConfig::baseline(555);
    cfg.total_s = 150.0;
    cfg.breathing_period_s = 7.0;
    let run = run_night(&cfg);

    let mut periods: Vec<f32> = run
        .epochs
        .iter()
        .skip(2)
        .filter(|e| e.breathing_present_fraction > 0.5)
        .map(|e| e.breath_period_s)
        .collect();
    assert!(!periods.is_empty(), "breathing must be recognised on the slow night");
    let median = median_f32(&mut periods);
    assert!(
        (median - 7.0).abs() <= 0.5,
        "median breath period {median} far from synthesized 7.0 s"
    );
}

// ── External-audio VAD (A-4) ───────────────────────────────────────────────────

/// Speech intervals must flag their epochs; clean, snore, and edge epochs
/// must stay below the verdict threshold.
#[test]
fn speech_intervals_flag_external_audio_epochs() {
    let mut cfg = NightConfig::baseline(11);
    cfg.total_s = 300.0;
    cfg.speech_intervals = vec![(60.0, 120.0), (180.0, 240.0)];
    cfg.snore_intervals = vec![(250.0, 280.0)];
    let run = run_night(&cfg);
    assert_eq!(run.epochs.len(), 10);

    // Fully-covered speech epochs (2,3 and 6,7) read dominant.
    for &i in &[2, 3, 6, 7] {
        assert!(
            run.epochs[i].external_audio_fraction > 0.5,
            "speech epoch {i} fraction {} should be dominant",
            run.epochs[i].external_audio_fraction
        );
    }
    // Fully-clean epochs stay near zero (warm-up + steady breathing/noise).
    for &i in &[0, 1, 5] {
        assert!(
            run.epochs[i].external_audio_fraction < 0.3,
            "clean epoch {i} fraction {} should be near zero",
            run.epochs[i].external_audio_fraction
        );
    }
    // Edge epochs (speech just ended/ring draining) and snore epochs stay
    // below the verdict threshold.
    for &i in &[4, 8, 9] {
        assert!(
            run.epochs[i].external_audio_fraction < 0.5,
            "edge/snore epoch {i} fraction {} must not read dominant",
            run.epochs[i].external_audio_fraction
        );
    }
}

#[test]
fn clean_night_has_no_external_audio() {
    let cfg = NightConfig::baseline(4242);
    let run = run_night(&cfg);
    for (i, epoch) in run.epochs.iter().enumerate() {
        assert!(
            epoch.external_audio_fraction < 0.3,
            "clean epoch {i} fraction {} should be near zero",
            epoch.external_audio_fraction
        );
    }
}

// ── REM estimation (A-1) ───────────────────────────────────────────────────────

/// A night with a 120–240 s irregular-breathing segment (synthetic REM)
/// must read phase 3 on the fully-covered epochs and must not read REM
/// anywhere else; the same night without the segment must read no REM at
/// all (specificity). Seed fixed: the detector is stochastic-input
/// sensitive, so this is a golden-style regression — re-baseline on
/// purpose, never by tweaking the seed to fit.
#[test]
fn rem_segment_classified_as_rem() {
    let mut cfg = NightConfig::baseline(90210);
    cfg.total_s = 300.0;
    cfg.rem_intervals = vec![(120.0, 240.0)];
    let run = run_night(&cfg);
    assert_eq!(run.epochs.len(), 10);

    // Epochs 4–7 (120–240 s) are fully inside the REM segment.
    for &i in &[4, 5, 6, 7] {
        assert_eq!(
            run.epochs[i].phase_ordinal, 3,
            "REM epoch {i}: phase {} cv {} bpf {}",
            run.epochs[i].phase_ordinal,
            run.epochs[i].breath_period_cv,
            run.epochs[i].breathing_present_fraction,
        );
    }
    // Fully-clean epochs on both sides must not read REM.
    for &i in &[0, 1, 2, 3, 8, 9] {
        assert_ne!(run.epochs[i].phase_ordinal, 3, "clean epoch {i} reads REM");
    }
    // REM-epoch CVs must clear the threshold with margin, clean ones must
    // sit far below it (separation, not just the verdict).
    for &i in &[4, 5, 6, 7] {
        assert!(
            run.epochs[i].breath_period_cv > 0.25,
            "REM epoch {i} cv {}",
            run.epochs[i].breath_period_cv
        );
    }
    for &i in &[0, 1, 2, 3, 9] {
        assert!(
            run.epochs[i].breath_period_cv < 0.15,
            "clean epoch {i} cv {}",
            run.epochs[i].breath_period_cv
        );
    }
}

#[test]
fn steady_night_produces_no_rem() {
    let cfg = NightConfig::baseline(90210); // same seed, no REM segment
    let run = run_night(&cfg);
    for (i, epoch) in run.epochs.iter().enumerate() {
        assert_ne!(epoch.phase_ordinal, 3, "steady epoch {i} reads REM");
    }
}

// ── Low-margin night: LOW_SIGNAL_QUALITY visibility (FR-2.4) ──────────────────

#[test]
fn low_margin_night_reports_low_epoch_margins() {
    let mut cfg = NightConfig::baseline(7);
    cfg.total_s = 120.0;
    cfg.noise_amp = 0.06; // ambient ≈ breathing level → margin well below 6 dB
    let run = run_night(&cfg);

    let low_margin_epochs = run
        .epochs
        .iter()
        .filter(|e| e.mean_breathing_margin_db < 6.0)
        .count();
    // FR-2.4: > 20 % low-margin epochs flags the night; on this night
    // essentially every epoch must read as low-margin.
    assert!(
        low_margin_epochs as f32 >= 0.8 * run.epochs.len() as f32,
        "only {low_margin_epochs}/{} epochs report < 6 dB margin",
        run.epochs.len()
    );
}
