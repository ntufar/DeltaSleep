//! T-4: golden-file regression. One fixed synthetic input (seeded PRNG,
//! fixed scenario) is fed through `SessionEngine`; the emitted event list
//! and selected feature-vector values are asserted against hard-coded
//! expectations. Any behavioural drift in the DSP fails this test.
//!
//! Tolerances are tight but non-zero to absorb libm rounding differences
//! across platforms; counts, types, ordering, and flags are exact.

mod fixtures;

use deltasleep_dsp::apnea::EventType;
use deltasleep_dsp::engine::SessionEngine;
use fixtures::{ApneaGap, NightConfig, NightGenerator};

/// The frozen golden scenario. DO NOT change seed or layout without
/// re-baselining the expectations below.
fn golden_config() -> NightConfig {
    let mut cfg = NightConfig::baseline(0x000D_5EE9);
    cfg.total_s = 260.0;
    cfg.apnea_gaps = vec![
        ApneaGap { start_s: 60.0, duration_s: 12.0, with_gasp: true },
        ApneaGap { start_s: 150.0, duration_s: 20.0, with_gasp: false },
    ];
    cfg.snore_intervals = vec![(200.0, 230.0)];
    cfg
}

#[test]
fn golden_night_events_and_features() {
    let cfg = golden_config();
    let mut engine = SessionEngine::new();
    engine.start_session();
    let mut generator = NightGenerator::new(cfg.clone());

    // Snapshot per-frame feature vectors at fixed probe frames.
    // Probe A: frame 3000 (t = 30 s, mid-breathing, pre-events).
    // Probe B: frame 6700 (t = 67 s, inside the first apnea gap).
    let mut probe_a = None;
    let mut probe_b = None;
    let mut epoch_one = None; // epoch covering 30–60 s (clean breathing)

    for idx in 0..cfg.total_frames() {
        let frame = generator.next_frame(idx);
        let out = engine.process_frame(&frame);
        if idx == 3_000 {
            probe_a = Some(out);
        }
        if idx == 6_700 {
            probe_b = Some(out);
        }
        if idx + 1 == 3_000 {
            engine.reset_epoch(); // discard the warm-up epoch (0–30 s)
        }
        if idx + 1 == 6_000 {
            epoch_one = Some(engine.compute_epoch());
            engine.reset_epoch();
        }
    }
    let events = engine.poll_events();

    // ── Event list snapshot ────────────────────────────────────────────────
    let respiratory: Vec<_> = events
        .iter()
        .filter(|e| {
            matches!(e.event_type, EventType::ApneaLike | EventType::HypopneaLike | EventType::Gasp)
        })
        .collect();
    assert_eq!(
        respiratory.len(),
        3,
        "expected [APNEA+gasp, GASP, APNEA]: {respiratory:#?}"
    );

    // Event 0: first apnea (60 s / 12 s), terminated by gasp.
    let e0 = respiratory[0];
    assert_eq!(e0.event_type, EventType::ApneaLike);
    assert!(e0.terminated_by_gasp);
    assert!(
        (e0.start_offset_ms as i64 - 59_000).unsigned_abs() <= 2_000,
        "e0 start {} ms",
        e0.start_offset_ms
    );
    assert!(
        (e0.duration_ms as i64 - 13_000).unsigned_abs() <= 2_500,
        "e0 duration {} ms",
        e0.duration_ms
    );
    assert!(e0.envelope_reduction_pct > 0.80, "e0 reduction {}", e0.envelope_reduction_pct);
    assert!((0.55..=1.0).contains(&e0.confidence), "e0 confidence {}", e0.confidence);
    assert!(e0.peak_db_over_floor > 15.0, "e0 peak/floor {}", e0.peak_db_over_floor);

    // Event 1: the standalone GASP record for the same resumption.
    let e1 = respiratory[1];
    assert_eq!(e1.event_type, EventType::Gasp);
    assert!(
        (e1.start_offset_ms as i64 - 72_000).unsigned_abs() <= 1_500,
        "e1 start {} ms",
        e1.start_offset_ms
    );
    assert!(e1.peak_db_over_floor > 30.0, "e1 peak/floor {}", e1.peak_db_over_floor);

    // Event 2: second apnea (150 s / 20 s), NOT gasp-terminated.
    let e2 = respiratory[2];
    assert_eq!(e2.event_type, EventType::ApneaLike);
    assert!(!e2.terminated_by_gasp);
    assert!(
        (e2.start_offset_ms as i64 - 149_000).unsigned_abs() <= 2_000,
        "e2 start {} ms",
        e2.start_offset_ms
    );
    assert!(
        (e2.duration_ms as i64 - 21_000).unsigned_abs() <= 3_000,
        "e2 duration {} ms",
        e2.duration_ms
    );
    assert!(e2.envelope_reduction_pct > 0.80, "e2 reduction {}", e2.envelope_reduction_pct);

    // Snore episodes confined to the 200–230 s interval.
    let snores: Vec<_> =
        events.iter().filter(|e| e.event_type == EventType::SnoreEpisode).collect();
    assert!(
        (5..=10).contains(&snores.len()),
        "expected ~7 snore episodes (one per breath), got {}",
        snores.len()
    );
    for s in &snores {
        let t = s.start_offset_ms as f32 / 1_000.0;
        assert!((198.0..232.0).contains(&t), "snore at {t} s outside interval");
    }

    // ── Feature-vector snapshots ───────────────────────────────────────────
    let a = probe_a.unwrap();
    assert!(a.breathing_present, "breathing must be recognised at t=30 s");
    assert!(a.breathing_margin_db > 10.0, "probe A margin {}", a.breathing_margin_db);
    assert!(
        (-62.0..=-48.0).contains(&a.noise_floor_db),
        "probe A floor {} dB",
        a.noise_floor_db
    );
    assert!(a.rms > 0.005 && a.rms < 0.2, "probe A rms {}", a.rms);

    let b = probe_b.unwrap();
    assert!(
        b.breathing_margin_db < 6.0,
        "probe B (inside apnea) margin {} must be near the floor",
        b.breathing_margin_db
    );
    assert!(b.rms < 0.01, "probe B rms {} should be ambient-only", b.rms);

    // Epoch 30–60 s: clean breathing, no snoring.
    let ep = epoch_one.unwrap();
    assert!(!ep.snore_flag);
    assert!(ep.breathing_present_fraction > 0.9, "epoch bpf {}", ep.breathing_present_fraction);
    assert!(ep.mean_breathing_margin_db > 10.0, "epoch margin {}", ep.mean_breathing_margin_db);
    assert!(ep.mean_rms > 0.005 && ep.mean_rms < 0.1, "epoch mean_rms {}", ep.mean_rms);
}
