//! Host-only offline validation tool (A-2).
//!
//! Replays a 16 kHz mono 16-bit PCM WAV through the same
//! [`deltasleep_dsp::engine::SessionEngine`] the device runs and prints the
//! per-epoch feature vector plus acoustic events as JSONL on stdout:
//!
//! ```text
//! {"type":"run","tool":"replay","sample_rate_hz":16000,"total_frames":180000}
//! {"type":"epoch","index":0,"start_ms":0,"frames":3000,"phase":1,...}
//! {"type":"event","event":"APNEA_LIKE","start_ms":60000,...}
//! ```
//!
//! Epoch cadence is 30 s (3000 frames), exactly like the Kotlin service.
//! A trailing partial epoch is emitted with its real `"frames"` count.
//! `tools/validation/score.py` consumes this output; the corpus benchmark
//! itself stays offline (audio never enters the repo).

use deltasleep_dsp::apnea::EventType;
use deltasleep_dsp::engine::SessionEngine;
use std::io::{Read, Write};

/// Samples per 10 ms frame / frames per 30 s epoch (must match the device).
const FRAME_SAMPLES: usize = 160;
const EPOCH_FRAMES: u64 = 3_000;

fn event_name(t: EventType) -> &'static str {
    match t {
        EventType::ApneaLike => "APNEA_LIKE",
        EventType::HypopneaLike => "HYPOPNEA_LIKE",
        EventType::Gasp => "GASP",
        EventType::SnoreEpisode => "SNORE_EPISODE",
    }
}

fn fail(msg: String) -> ! {
    eprintln!("replay: error: {msg}");
    std::process::exit(2);
}

fn main() {
    if cfg!(target_os = "android") {
        fail("host-only tool".to_string());
    }
    let arg = std::env::args().nth(1).unwrap_or_else(|| {
        eprintln!("usage: replay <input.wav | - for stdin>");
        std::process::exit(2);
    });

    let bytes = if arg == "-" {
        let mut buf = Vec::new();
        if let Err(e) = std::io::stdin().read_to_end(&mut buf) {
            fail(format!("reading stdin: {e}"));
        }
        buf
    } else {
        std::fs::read(&arg).unwrap_or_else(|e| fail(format!("reading {arg}: {e}")))
    };
    let wav = deltasleep_dsp::wav::decode_mono16(&bytes)
        .unwrap_or_else(|e| fail(format!("{arg}: {e}")));
    if wav.sample_rate != 16_000 {
        fail(format!(
            "{arg}: need 16 kHz mono, got {} Hz (resample before replay)",
            wav.sample_rate
        ));
    }

    let stdout = std::io::stdout();
    let mut out = std::io::BufWriter::new(stdout.lock());
    let total_frames = wav.samples.len() as u64 / FRAME_SAMPLES as u64;
    writeln!(
        out,
        "{{\"type\":\"run\",\"tool\":\"replay\",\"sample_rate_hz\":16000,\"total_frames\":{total_frames}}}"
    )
    .unwrap();

    let mut engine = SessionEngine::new();
    engine.start_session();
    let mut frame = [0i16; FRAME_SAMPLES];
    let mut epoch_index: u64 = 0;
    let mut frames_in_epoch: u64 = 0;

    for chunk in wav.samples.chunks_exact(FRAME_SAMPLES) {
        frame.copy_from_slice(chunk);
        engine.process_frame(&frame);
        frames_in_epoch += 1;
        if frames_in_epoch >= EPOCH_FRAMES {
            flush_epoch(&mut engine, &mut out, epoch_index, frames_in_epoch);
            engine.reset_epoch();
            epoch_index += 1;
            frames_in_epoch = 0;
        }
    }
    if frames_in_epoch > 0 {
        flush_epoch(&mut engine, &mut out, epoch_index, frames_in_epoch);
        engine.reset_epoch();
    }
    out.flush().unwrap();
}

/// Emit one epoch summary plus all events drained in its window, exactly at
/// the production polling cadence (summary + drain per 30 s).
fn flush_epoch(
    engine: &mut SessionEngine,
    out: &mut impl Write,
    epoch_index: u64,
    frames: u64,
) {
    let ep = engine.compute_epoch();
    writeln!(
        out,
        "{{\"type\":\"epoch\",\"index\":{epoch_index},\"start_ms\":{},\"frames\":{frames},\
        \"phase\":{},\"mean_rms\":{},\"rms_variance\":{},\"snore\":{},\
        \"breathing_margin_db\":{},\"breathing_fraction\":{},\
        \"breath_period_s\":{},\"breath_period_cv\":{},\"external_audio_fraction\":{}}}",
        epoch_index * 30_000,
        ep.phase_ordinal,
        ep.mean_rms,
        ep.rms_variance,
        ep.snore_flag,
        ep.mean_breathing_margin_db,
        ep.breathing_present_fraction,
        ep.breath_period_s,
        ep.breath_period_cv,
        ep.external_audio_fraction,
    )
    .unwrap();
    for ev in engine.poll_events() {
        writeln!(
            out,
            "{{\"type\":\"event\",\"event\":\"{}\",\"start_ms\":{},\"duration_ms\":{},\
            \"confidence\":{},\"peak_db_over_floor\":{},\"envelope_reduction_pct\":{},\
            \"terminated_by_gasp\":{},\"mean_db_over_floor\":{}}}",
            event_name(ev.event_type),
            ev.start_offset_ms,
            ev.duration_ms,
            ev.confidence,
            ev.peak_db_over_floor,
            ev.envelope_reduction_pct,
            ev.terminated_by_gasp,
            ev.mean_db_over_floor,
        )
        .unwrap();
    }
}
