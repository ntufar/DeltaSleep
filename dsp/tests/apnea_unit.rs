//! T-1: unit tests for each apnea-screening primitive — noise-floor tracker,
//! periodicity tracker, event state machine transition table, gasp detector,
//! FFT spectral features, snore episodes, and the event ring.

mod fixtures;

use deltasleep_dsp::apnea::{
    db, is_gasp, AcousticEvent, EventRing, EventStateMachine, EventType, Fft256, MachineInput,
    MachineState, NoiseFloor, PeriodicityTracker, SnoreEpisodeTracker,
};
use deltasleep_dsp::apnea_config as cfg;
use fixtures::Xorshift32;

// ── Noise floor (FR-1.2) ───────────────────────────────────────────────────────

#[test]
fn noise_floor_converges_to_constant_level() {
    let mut nf = NoiseFloor::default();
    let level_db = db(0.01); // −40 dBFS
    for _ in 0..6_000 {
        nf.update(level_db);
    }
    assert!(
        (nf.floor_db() - level_db).abs() < 1.0,
        "floor {} should converge to {}",
        nf.floor_db(),
        level_db
    );
}

#[test]
fn noise_floor_tracks_percentile_not_mean() {
    let mut nf = NoiseFloor::default();
    // 20 % of 100 ms buckets are quiet (−60 dB), 80 % loud (−30 dB).
    // The 10th-percentile floor must sit at the quiet level, far below the mean.
    for i in 0..6_000u64 {
        let bucket = i / cfg::NOISE_BUCKET_FRAMES;
        let level = if bucket.is_multiple_of(5) { -60.0 } else { -30.0 };
        nf.update(level);
    }
    assert!(
        (nf.floor_db() - (-60.0)).abs() < 1.0,
        "10th percentile floor {} should be ~ -60 dB",
        nf.floor_db()
    );
}

#[test]
fn noise_floor_tracks_rising_ambient_level() {
    let mut nf = NoiseFloor::default();
    for _ in 0..6_000 {
        nf.update(-40.0);
    }
    // Full 60 s window at the new, louder level slides the old one out.
    for _ in 0..6_000 {
        nf.update(-20.0);
    }
    assert!(
        (nf.floor_db() - (-20.0)).abs() < 1.0,
        "floor {} should have risen to -20 dB",
        nf.floor_db()
    );
}

// ── Breathing periodicity (FR-1.3) ─────────────────────────────────────────────

#[test]
fn periodicity_detects_4s_period_am_signal() {
    let mut pt = PeriodicityTracker::default();
    // 4 s-period raised sinusoid sampled at 20 Hz → lag 80.
    for i in 0..cfg::PERIODICITY_RING_SAMPLES {
        let phase = 2.0 * std::f32::consts::PI * i as f32 / 80.0;
        pt.push(0.02 * (1.0 + phase.sin()));
    }
    let p = pt.compute();
    assert!(p.present, "AM breathing signal must be detected (conf {})", p.confidence);
    assert!(
        (p.period_s - 4.0).abs() < 0.5,
        "period {} should be ~4 s",
        p.period_s
    );
    assert!(p.confidence > cfg::PERIODICITY_CONFIDENCE_THRESHOLD);
}

#[test]
fn periodicity_rejects_white_noise() {
    let mut pt = PeriodicityTracker::default();
    let mut rng = Xorshift32::new(7);
    for _ in 0..cfg::PERIODICITY_RING_SAMPLES {
        pt.push(0.02 * rng.next_f32());
    }
    let p = pt.compute();
    assert!(
        !p.present,
        "white noise must not register as breathing (conf {})",
        p.confidence
    );
}

#[test]
fn periodicity_needs_minimum_history() {
    let mut pt = PeriodicityTracker::default();
    for i in 0..(cfg::PERIODICITY_MIN_SAMPLES - 1) {
        pt.push(0.02 * (1.0 + (i as f32 / 12.7).sin()));
    }
    let p = pt.compute();
    assert!(!p.present, "insufficient history must yield no verdict");
    assert_eq!(p.confidence, 0.0);
}

// ── Event state machine (FR-1.4) ───────────────────────────────────────────────

/// Convenience builder for machine inputs used by the transition tests.
#[allow(clippy::too_many_arguments)]
fn mi(
    frame_idx: u64,
    env_lin: f32,
    median_lin: f32,
    floor_db: f32,
    breathing_present: bool,
    full_rms_db: f32,
    flatness: f32,
    snore_frame: bool,
) -> MachineInput {
    MachineInput {
        frame_idx,
        env_lin,
        env_db: db(env_lin),
        floor_db,
        median_lin,
        median_db: db(median_lin),
        breathing_present,
        periodicity_conf: if breathing_present { 0.7 } else { 0.1 },
        full_rms_db,
        flatness,
        snore_frame,
    }
}

const FLOOR: f32 = -60.0;
const BREATH_ENV: f32 = 0.02;

/// Drives the machine through NO_SIGNAL → BREATHING confirmation.
fn confirm_breathing(sm: &mut EventStateMachine, ring: &mut EventRing) -> u64 {
    let confirm = (cfg::BREATHING_CONFIRM_S * 100.0) as u64;
    for i in 0..confirm {
        sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, db(BREATH_ENV), 0.1, false), ring);
    }
    assert_eq!(sm.state(), MachineState::Breathing);
    confirm
}

#[test]
fn machine_stays_in_no_signal_without_breathing() {
    let mut sm = EventStateMachine::default();
    let mut ring = EventRing::default();
    for i in 0..1_000 {
        sm.update(&mi(i, 0.001, 0.001, FLOOR, false, -60.0, 0.1, false), &mut ring);
    }
    assert_eq!(sm.state(), MachineState::NoSignal);
    assert!(ring.is_empty());
}

#[test]
fn machine_toggles_snoring_submode() {
    let mut sm = EventStateMachine::default();
    let mut ring = EventRing::default();
    let mut i = confirm_breathing(&mut sm, &mut ring);
    sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, db(BREATH_ENV), 0.1, true), &mut ring);
    assert_eq!(sm.state(), MachineState::Snoring);
    i += 1;
    sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, db(BREATH_ENV), 0.1, false), &mut ring);
    assert_eq!(sm.state(), MachineState::Breathing);
}

#[test]
fn machine_emits_apnea_like_for_deep_15s_decrement() {
    let mut sm = EventStateMachine::default();
    let mut ring = EventRing::default();
    let start = confirm_breathing(&mut sm, &mut ring);

    // 15 s of near-silence (deep decrement).
    let dec_frames = 1_500u64;
    for i in start..start + dec_frames {
        sm.update(&mi(i, 0.001, BREATH_ENV, FLOOR, false, -60.0, 0.1, false), &mut ring);
    }
    assert_eq!(sm.state(), MachineState::Decrement);

    // Recovery + gasp window expiry (no gasp).
    let window = (cfg::GASP_WINDOW_AFTER_S * 100.0) as u64;
    for i in start + dec_frames..start + dec_frames + window + 2 {
        sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, db(BREATH_ENV), 0.1, false), &mut ring);
    }
    assert_eq!(sm.state(), MachineState::Breathing);

    let events = ring.drain();
    assert_eq!(events.len(), 1);
    let ev = &events[0];
    assert_eq!(ev.event_type, EventType::ApneaLike);
    assert_eq!(ev.start_offset_ms, start * 10);
    assert!((ev.duration_ms as i64 - 15_000).unsigned_abs() <= 100);
    assert!(ev.envelope_reduction_pct > 0.9);
    assert!(!ev.terminated_by_gasp);
    assert!(ev.confidence > 0.3 && ev.confidence <= 1.0);
    // Pre-event breathing level over floor: db(0.02) − (−60) ≈ 26 dB.
    assert!((ev.peak_db_over_floor - (db(BREATH_ENV) - FLOOR)).abs() < 1.0);
}

#[test]
fn machine_emits_hypopnea_like_for_partial_reduction() {
    let mut sm = EventStateMachine::default();
    let mut ring = EventRing::default();
    let start = confirm_breathing(&mut sm, &mut ring);

    // 40 % envelope reduction (0.02 → 0.012) for 15 s.
    for i in start..start + 1_500 {
        sm.update(&mi(i, 0.012, BREATH_ENV, FLOOR, true, db(0.012), 0.1, false), &mut ring);
    }
    assert_eq!(sm.state(), MachineState::Decrement);
    let window = (cfg::GASP_WINDOW_AFTER_S * 100.0) as u64;
    for i in start + 1_500..start + 1_500 + window + 2 {
        sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, db(BREATH_ENV), 0.1, false), &mut ring);
    }

    let events = ring.drain();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].event_type, EventType::HypopneaLike);
    assert!((events[0].envelope_reduction_pct - 0.4).abs() < 0.05);
}

#[test]
fn machine_ignores_short_decrement() {
    let mut sm = EventStateMachine::default();
    let mut ring = EventRing::default();
    let start = confirm_breathing(&mut sm, &mut ring);

    // 5 s decrement — below the 10 s clinical minimum.
    for i in start..start + 500 {
        sm.update(&mi(i, 0.001, BREATH_ENV, FLOOR, false, -60.0, 0.1, false), &mut ring);
    }
    for i in start + 500..start + 600 {
        sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, db(BREATH_ENV), 0.1, false), &mut ring);
    }
    assert_eq!(sm.state(), MachineState::Breathing);
    assert!(ring.is_empty());
}

#[test]
fn machine_drops_to_no_signal_after_sanity_cap() {
    let mut sm = EventStateMachine::default();
    let mut ring = EventRing::default();
    let start = confirm_breathing(&mut sm, &mut ring);

    let over_cap = (cfg::MAX_EVENT_DURATION_S * 100.0) as u64 + 10;
    for i in start..start + over_cap {
        sm.update(&mi(i, 0.001, BREATH_ENV, FLOOR, false, -60.0, 0.1, false), &mut ring);
    }
    assert_eq!(sm.state(), MachineState::NoSignal);
    assert!(ring.is_empty(), "signal loss must not fabricate an event");
}

#[test]
fn machine_marks_event_terminated_by_gasp_and_emits_gasp() {
    let mut sm = EventStateMachine::default();
    let mut ring = EventRing::default();
    let start = confirm_breathing(&mut sm, &mut ring);

    for i in start..start + 1_500 {
        sm.update(&mi(i, 0.001, BREATH_ENV, FLOOR, false, -60.0, 0.1, false), &mut ring);
    }
    // Recovery frame enters the gasp window, then a loud broadband burst.
    let mut i = start + 1_500;
    sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, db(BREATH_ENV), 0.1, false), &mut ring);
    assert_eq!(sm.state(), MachineState::GaspWindow);
    for _ in 0..5 {
        i += 1;
        // +12 dB over median with high flatness → gasp frames.
        sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, -14.0, 0.5, false), &mut ring);
    }
    i += 1;
    sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, db(BREATH_ENV), 0.1, false), &mut ring);
    assert_eq!(sm.state(), MachineState::Breathing);

    let events = ring.drain();
    assert_eq!(events.len(), 2);
    assert_eq!(events[0].event_type, EventType::ApneaLike);
    assert!(events[0].terminated_by_gasp);
    assert_eq!(events[1].event_type, EventType::Gasp);
    assert_eq!(events[1].duration_ms, 50);
    assert!(events[1].peak_db_over_floor > 40.0); // −14 − (−60)
}

#[test]
fn machine_rejects_narrowband_burst_as_gasp() {
    let mut sm = EventStateMachine::default();
    let mut ring = EventRing::default();
    let start = confirm_breathing(&mut sm, &mut ring);

    for i in start..start + 1_500 {
        sm.update(&mi(i, 0.001, BREATH_ENV, FLOOR, false, -60.0, 0.1, false), &mut ring);
    }
    // Loud but tonal (low flatness) burst inside the gasp window.
    let window = (cfg::GASP_WINDOW_AFTER_S * 100.0) as u64;
    for i in start + 1_500..start + 1_500 + window + 2 {
        sm.update(&mi(i, BREATH_ENV, BREATH_ENV, FLOOR, true, -14.0, 0.05, false), &mut ring);
    }

    let events = ring.drain();
    assert_eq!(events.len(), 1, "no GASP event for narrowband burst");
    assert_eq!(events[0].event_type, EventType::ApneaLike);
    assert!(!events[0].terminated_by_gasp);
}

// ── Gasp condition (FR-1.5) ────────────────────────────────────────────────────

#[test]
fn gasp_condition_thresholds() {
    // +12 dB broadband → gasp.
    assert!(is_gasp(-20.0, -34.0, 0.4));
    // Loud but narrowband → no gasp.
    assert!(!is_gasp(-20.0, -34.0, 0.05));
    // Broadband but only +8 dB over the median → no gasp.
    assert!(!is_gasp(-26.0, -34.0, 0.4));
}

// ── Spectral features (FR-1.1) ─────────────────────────────────────────────────

#[test]
fn fft_flatness_separates_white_noise_from_tone() {
    let mut fft = Fft256::new();
    let mut rng = Xorshift32::new(11);

    let noise: Vec<i16> = (0..160).map(|_| (rng.next_gaussian() * 6_000.0) as i16).collect();
    fft.compute_i16(&noise);
    let flat_noise = fft.spectral_flatness();

    let tone: Vec<i16> = (0..160)
        .map(|k| {
            let t = k as f32 / 16_000.0;
            ((2.0 * std::f32::consts::PI * 1_000.0 * t).sin() * 8_000.0) as i16
        })
        .collect();
    fft.compute_i16(&tone);
    let flat_tone = fft.spectral_flatness();

    assert!(flat_noise > 0.3, "white noise flatness {flat_noise} should be high");
    assert!(flat_tone < 0.2, "tone flatness {flat_tone} should be low");
    assert!(flat_noise > 2.0 * flat_tone);
}

#[test]
fn fft_centroid_finds_tone_frequency() {
    let mut fft = Fft256::new();
    let tone: Vec<i16> = (0..160)
        .map(|k| {
            let t = k as f32 / 16_000.0;
            ((2.0 * std::f32::consts::PI * 1_000.0 * t).sin() * 8_000.0) as i16
        })
        .collect();
    fft.compute_i16(&tone);
    let centroid = fft.spectral_centroid_hz(16_000.0);
    assert!(
        (800.0..1_300.0).contains(&centroid),
        "centroid {centroid} should be near 1 kHz"
    );
}

// ── Snore episodes (FR-1.8) ────────────────────────────────────────────────────

#[test]
fn snore_tracker_builds_episode_from_contiguous_frames() {
    let mut st = SnoreEpisodeTracker::default();
    let mut event = None;
    // 1 s of snoring at +18 dB over floor, then silence.
    for i in 0..100u64 {
        assert!(st.update(i, true, 18.0).is_none());
    }
    for i in 100..220u64 {
        if let Some(ev) = st.update(i, false, 0.0) {
            event = Some(ev);
        }
    }
    let ev = event.expect("episode must close after the gap");
    assert_eq!(ev.event_type, EventType::SnoreEpisode);
    assert_eq!(ev.start_offset_ms, 0);
    assert_eq!(ev.duration_ms, 1_000);
    assert!((ev.mean_db_over_floor - 18.0).abs() < 0.1);
    assert!((ev.peak_db_over_floor - 18.0).abs() < 0.1);
}

#[test]
fn snore_tracker_ignores_blips_and_bridges_short_gaps() {
    let mut st = SnoreEpisodeTracker::default();
    // 100 ms blip — below the 300 ms minimum.
    for i in 0..10u64 {
        assert!(st.update(i, true, 12.0).is_none());
    }
    for i in 10..200u64 {
        assert!(st.update(i, false, 0.0).is_none(), "blip must not emit");
    }

    // Two 500 ms bursts separated by a 300 ms gap → ONE episode.
    let mut st = SnoreEpisodeTracker::default();
    let mut events = Vec::new();
    for i in 0..50u64 {
        st.update(i, true, 12.0);
    }
    for i in 50..80u64 {
        if let Some(e) = st.update(i, false, 0.0) {
            events.push(e);
        }
    }
    for i in 80..130u64 {
        st.update(i, true, 12.0);
    }
    for i in 130..300u64 {
        if let Some(e) = st.update(i, false, 0.0) {
            events.push(e);
        }
    }
    assert_eq!(events.len(), 1, "short gap must not split the episode");
    assert_eq!(events[0].duration_ms, 1_300);
}

// ── Event ring buffer ──────────────────────────────────────────────────────────

fn dummy_event(i: u64) -> AcousticEvent {
    AcousticEvent {
        event_type: EventType::SnoreEpisode,
        start_offset_ms: i * 1_000,
        duration_ms: 500,
        confidence: 0.5,
        peak_db_over_floor: 10.0,
        envelope_reduction_pct: 0.0,
        terminated_by_gasp: false,
        mean_db_over_floor: 8.0,
    }
}

#[test]
fn event_ring_drops_oldest_on_overflow_and_drains_fifo() {
    let mut ring = EventRing::default();
    for i in 0..300u64 {
        ring.push(dummy_event(i));
    }
    assert_eq!(ring.len(), cfg::EVENT_RING_CAPACITY);
    let events = ring.drain();
    assert_eq!(events.len(), cfg::EVENT_RING_CAPACITY);
    // Oldest 44 were dropped: first surviving event is #44, order is FIFO.
    assert_eq!(events[0].start_offset_ms, 44_000);
    assert_eq!(events.last().unwrap().start_offset_ms, 299_000);
    assert!(ring.is_empty());
    assert!(ring.drain().is_empty());
}
