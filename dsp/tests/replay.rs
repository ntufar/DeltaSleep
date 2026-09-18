//! A-2 bit-rot test: the replay binary runs on a 60 s synthetic fixture and
//! emits sane JSONL. CI runs this; the corpus benchmark itself stays offline.
//!
//! The fixture (seed 7): quiet breathing, one 15 s apnea gap with a terminal
//! gasp at 20 s, snoring at 40–55 s. Expected: 2 epochs, ≥ 1 APNEA_LIKE
//! event near 20 s, sleep phases only (steady breathing never reads REM).

mod fixtures;

use fixtures::{ApneaGap, NightConfig, NightGenerator};
use std::io::Read;
use std::process::{Command, Stdio};

/// Extract the first unsigned integer after `"key":` on a JSONL line.
fn field(line: &str, key: &str) -> Option<u64> {
    let needle = format!("\"{key}\":");
    let start = line.find(&needle)? + needle.len();
    let digits: String = line[start..].chars().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        None
    } else {
        digits.parse().ok()
    }
}

/// Extract the first `"key":value` raw token (for floats and bare words).
fn raw_field<'a>(line: &'a str, key: &str) -> Option<&'a str> {
    let needle = format!("\"{key}\":");
    let start = line.find(&needle)? + needle.len();
    let end = line[start..]
        .find([',', '}'])
        .map(|i| start + i)
        .unwrap_or(line.len());
    Some(line[start..end].trim_matches('"'))
}

#[test]
fn replay_sixty_second_fixture() {
    // ── Build the fixture WAV from the synthetic generator ─────────────
    let mut cfg = NightConfig::baseline(7);
    cfg.total_s = 60.0;
    cfg.apnea_gaps = vec![ApneaGap { start_s: 20.0, duration_s: 15.0, with_gasp: true }];
    cfg.snore_intervals = vec![(40.0, 55.0)];
    let mut generator = NightGenerator::new(cfg.clone());
    let mut samples = Vec::with_capacity(cfg.total_frames() as usize * fixtures::FRAME_LEN);
    for idx in 0..cfg.total_frames() {
        samples.extend_from_slice(&generator.next_frame(idx));
    }
    let wav_bytes = deltasleep_dsp::wav::encode_mono16(16_000, &samples);

    let dir = std::env::temp_dir().join(format!("deltasleep-replay-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let wav_path = dir.join("fixture.wav");
    std::fs::write(&wav_path, &wav_bytes).unwrap();

    // ── Run the replay binary ──────────────────────────────────────────
    let replay = env!("CARGO_BIN_EXE_replay");
    let mut child = Command::new(replay)
        .arg(&wav_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("replay binary must run");
    let mut stdout = String::new();
    child.stdout.take().unwrap().read_to_string(&mut stdout).unwrap();
    let status = child.wait().unwrap();
    let _ = std::fs::remove_dir_all(&dir);
    assert!(status.success(), "replay exited {status}");

    let lines: Vec<&str> = stdout.lines().collect();
    assert!(!lines.is_empty(), "replay printed no output");

    // ── Run header ─────────────────────────────────────────────────────
    assert!(lines[0].starts_with("{\"type\":\"run\""), "first line: {}", lines[0]);
    assert_eq!(field(lines[0], "total_frames"), Some(6_000));

    // ── Epochs: exactly 2, valid ordinals, snore on the second ─────────
    // Epoch 0 is calm breathing (Light/Deep); epoch 1 holds the terminal
    // gasp burst plus snoring, which may legitimately push it Awake on
    // energy — so only steadiness (never REM) is asserted there.
    let epochs: Vec<&&str> = lines.iter().filter(|l| l.contains("\"type\":\"epoch\"")).collect();
    assert_eq!(epochs.len(), 2, "60 s must yield 2 epochs");
    for ep in &epochs {
        let phase = field(ep, "phase").unwrap();
        assert!((0..=3).contains(&phase), "epoch phase ordinal: {ep}");
        assert_eq!(field(ep, "frames"), Some(3_000));
    }
    assert!((1..=2).contains(&field(epochs[0], "phase").unwrap()));
    for ep in &epochs {
        assert_ne!(field(ep, "phase"), Some(3), "steady breathing reads REM: {ep}");
    }
    assert_eq!(raw_field(epochs[0], "snore"), Some("false"));
    assert_eq!(raw_field(epochs[1], "snore"), Some("true"));

    // ── Events: the 20 s apnea gap must surface as APNEA_LIKE ──────────
    let apnea_events: Vec<&&str> = lines
        .iter()
        .filter(|l| l.contains("\"type\":\"event\"") && l.contains("APNEA_LIKE"))
        .collect();
    assert!(!apnea_events.is_empty(), "gap must emit APNEA_LIKE:\n{stdout}");
    let start = field(apnea_events[0], "start_ms").unwrap();
    assert!(
        (17_000..=23_000).contains(&start),
        "apnea start {start} ms, expected ≈ 20 s"
    );
}
