//! DeltaSleep DSP core.
//!
//! All signal processing lives in pure-Rust modules ([`engine`], [`apnea`],
//! [`features`], [`snore`], [`classifier`]) so it is testable on the host;
//! this file is only a thin JNI shim over a global [`engine::SessionEngine`].

pub mod apnea;
pub mod apnea_config;
pub mod classifier;
pub mod engine;
pub mod features;
pub mod snore;

use engine::SessionEngine;
use jni::objects::{JFloatArray, JObject, JShortArray};
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

/// Samples per 10 ms frame at 16 kHz.
const FRAME_SAMPLES: usize = 160;

static ENGINE: OnceLock<Mutex<SessionEngine>> = OnceLock::new();

fn engine() -> &'static Mutex<SessionEngine> {
    ENGINE.get_or_init(|| Mutex::new(SessionEngine::new()))
}

// ── JNI exports ───────────────────────────────────────────────────────────────

/// Process one 10 ms frame of 16 kHz mono PCM.
/// Returns float[6]: [rms, zcr, band_power_ratio, noise_floor_db,
/// breathing_margin_db, breathing_present(0/1)].
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_processFrame<'local>(
    env: JNIEnv<'local>,
    _obj: JObject<'local>,
    samples: JShortArray<'local>,
) -> JFloatArray<'local> {
    // Fixed stack buffer — no per-frame heap allocation (NFR-3). Frames are
    // always 160 samples; anything longer is truncated defensively.
    let mut buf = [0i16; FRAME_SAMPLES];
    let len = (env.get_array_length(&samples).unwrap_or(0) as usize).min(FRAME_SAMPLES);
    env.get_short_array_region(&samples, 0, &mut buf[..len]).unwrap_or(());

    let frame = engine().lock().unwrap().process_frame(&buf[..len]);

    let out = [
        frame.rms,
        frame.zcr,
        frame.band_power_ratio,
        frame.noise_floor_db,
        frame.breathing_margin_db,
        if frame.breathing_present { 1.0 } else { 0.0 },
    ];
    let arr = env.new_float_array(out.len() as i32).unwrap();
    env.set_float_array_region(&arr, 0, &out).unwrap();
    arr
}

/// Summarise the accumulated epoch.
/// Returns float[8]: [mean_rms, rms_variance, mean_zcr, mean_band_ratio,
/// phase_ordinal, snore_flag, mean_breathing_margin_db,
/// breathing_present_fraction].
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_computeEpoch<'local>(
    env: JNIEnv<'local>,
    _obj: JObject<'local>,
) -> JFloatArray<'local> {
    let epoch = engine().lock().unwrap().compute_epoch();

    let out = [
        epoch.mean_rms,
        epoch.rms_variance,
        epoch.mean_zcr,
        epoch.mean_band_ratio,
        epoch.phase_ordinal as f32,
        if epoch.snore_flag { 1.0 } else { 0.0 },
        epoch.mean_breathing_margin_db,
        epoch.breathing_present_fraction,
    ];
    let arr = env.new_float_array(out.len() as i32).unwrap();
    env.set_float_array_region(&arr, 0, &out).unwrap();
    arr
}

/// Discard accumulated epoch data ONLY. Noise floor, periodicity, state
/// machine, event ring, and frame counter all persist across epochs.
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_resetEpoch<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
) {
    engine().lock().unwrap().reset_epoch();
}

/// Full DSP session reset: clears all trackers, the event ring buffer, the
/// epoch accumulator, and the frame counter (event offsets restart at 0).
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_startSession<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
) {
    engine().lock().unwrap().start_session();
}

/// Drain completed acoustic events. Returns a flattened float array with
/// stride 8 per event:
/// [type, start_offset_ms, duration_ms, confidence, peak_db_over_floor,
///  envelope_reduction_pct(0–1), terminated_by_gasp(0/1), mean_db_over_floor]
/// where type is 0=APNEA_LIKE, 1=HYPOPNEA_LIKE, 2=GASP, 3=SNORE_EPISODE.
/// Empty array when no events are pending.
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_pollEvents<'local>(
    env: JNIEnv<'local>,
    _obj: JObject<'local>,
) -> JFloatArray<'local> {
    let events = engine().lock().unwrap().poll_events();

    let mut out = Vec::with_capacity(events.len() * 8);
    for ev in &events {
        out.push(ev.event_type as i32 as f32);
        out.push(ev.start_offset_ms as f32);
        out.push(ev.duration_ms as f32);
        out.push(ev.confidence);
        out.push(ev.peak_db_over_floor);
        out.push(ev.envelope_reduction_pct);
        out.push(if ev.terminated_by_gasp { 1.0 } else { 0.0 });
        out.push(ev.mean_db_over_floor);
    }
    let arr = env.new_float_array(out.len() as i32).unwrap();
    if !out.is_empty() {
        env.set_float_array_region(&arr, 0, &out).unwrap();
    }
    arr
}
