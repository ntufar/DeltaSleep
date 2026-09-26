//! DeltaSleep DSP core.
//!
//! All signal processing lives in pure-Rust modules ([`engine`], [`apnea`],
//! [`features`], [`snore`], [`classifier`]) so it is testable on the host;
//! this file is only a thin JNI shim over a global [`engine::SessionEngine`].
//! The iOS app uses the equivalent C ABI in [`ffi_c`]; the JNI exports are
//! compiled out on Apple targets so the `jni` crate never ships there.

pub mod apnea;
pub mod apnea_config;
pub mod classifier;
pub mod engine;
pub mod features;
pub mod ffi_c;
pub mod phase_config;
pub mod snore;
pub mod wav;

use engine::SessionEngine;
#[cfg(not(target_vendor = "apple"))]
use jni::objects::{JFloatArray, JObject, JShortArray};
#[cfg(not(target_vendor = "apple"))]
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

/// Samples per 10 ms frame at 16 kHz.
const FRAME_SAMPLES: usize = 160;

static ENGINE: OnceLock<Mutex<SessionEngine>> = OnceLock::new();

fn engine() -> &'static Mutex<SessionEngine> {
    ENGINE.get_or_init(|| Mutex::new(SessionEngine::new()))
}

/// Lock the global engine without aborting the process: a poisoned mutex
/// means a previous JNI call panicked, so recover the inner engine instead
/// of `unwrap()`ing across FFI (a Rust panic in a `#[no_mangle]` export
/// aborts the whole app process).
pub(crate) fn lock_engine() -> std::sync::MutexGuard<'static, SessionEngine> {
    engine().lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Allocate a Java float array, throwing a catchable
/// `IllegalStateException` (handled by the Kotlin callers' retry paths)
/// instead of panicking across FFI when allocation fails.
#[cfg(not(target_vendor = "apple"))]
fn new_float_array_or_throw<'local>(mut env: JNIEnv<'local>, out: &[f32]) -> JFloatArray<'local> {
    match env.new_float_array(out.len() as i32) {
        Ok(arr) => {
            if env.set_float_array_region(&arr, 0, out).is_ok() {
                return arr;
            }
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "DSP: failed to fill result array",
            );
            JObject::null().into()
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "DSP: failed to allocate result array",
            );
            JObject::null().into()
        }
    }
}

// ── JNI exports ───────────────────────────────────────────────────────────────

/// Process one 10 ms frame of 16 kHz mono PCM.
/// Returns float[6]: [rms, zcr, band_power_ratio, noise_floor_db,
/// breathing_margin_db, breathing_present(0/1)].
#[cfg(not(target_vendor = "apple"))]
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

    let frame = lock_engine().process_frame(&buf[..len]);

    let out = [
        frame.rms,
        frame.zcr,
        frame.band_power_ratio,
        frame.noise_floor_db,
        frame.breathing_margin_db,
        if frame.breathing_present { 1.0 } else { 0.0 },
    ];
    new_float_array_or_throw(env, &out)
}

/// Summarise the accumulated epoch.
/// Returns float[11]: [mean_rms, rms_variance, mean_zcr, mean_band_ratio,
/// phase_ordinal, snore_flag, mean_breathing_margin_db,
/// breathing_present_fraction, breath_period_s, external_audio_fraction,
/// breath_period_cv].
/// breath_period_s is the mean autocorrelation breath period (seconds) over
/// breathing-present frames, or 0.0 when breathing was never present (A-7).
/// external_audio_fraction is the fraction of speech-like frames (A-4 VAD).
/// breath_period_cv is the coefficient of variation of the breath period
/// over the epoch (A-1 REM feature; 0.0 with < 2 period samples).
/// rms_variance (index 1) doubles as the classifier's movement_score.
#[cfg(not(target_vendor = "apple"))]
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_computeEpoch<'local>(
    env: JNIEnv<'local>,
    _obj: JObject<'local>,
) -> JFloatArray<'local> {
    let epoch = lock_engine().compute_epoch();

    let out = [
        epoch.mean_rms,
        epoch.rms_variance,
        epoch.mean_zcr,
        epoch.mean_band_ratio,
        epoch.phase_ordinal as f32,
        if epoch.snore_flag { 1.0 } else { 0.0 },
        epoch.mean_breathing_margin_db,
        epoch.breathing_present_fraction,
        epoch.breath_period_s,
        epoch.external_audio_fraction,
        epoch.breath_period_cv,
    ];
    new_float_array_or_throw(env, &out)
}

/// Discard accumulated epoch data ONLY. Noise floor, periodicity, state
/// machine, event ring, and frame counter all persist across epochs.
#[cfg(not(target_vendor = "apple"))]
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_resetEpoch<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
) {
    lock_engine().reset_epoch();
}

/// Full DSP session reset: clears all trackers, the event ring buffer, the
/// epoch accumulator, and the frame counter (event offsets restart at 0).
#[cfg(not(target_vendor = "apple"))]
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_startSession<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
) {
    lock_engine().start_session();
}

/// Set the D-3 mic-sensitivity offset (dB) applied to the snore RMS
/// threshold. Negative = more sensitive. Clamped to ±12 dB engine-side.
/// The offset survives [startSession]. Older native libs lack this symbol;
/// the Kotlin side calls it behind an UnsatisfiedLinkError guard.
#[cfg(not(target_vendor = "apple"))]
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_setSnoreThresholdOffsetDb<
    'local,
>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
    offset_db: f32,
) {
    lock_engine().set_snore_threshold_offset_db(offset_db);
}

/// Drain completed acoustic events. Returns a flattened float array with
/// stride 8 per event:
/// [type, start_offset_ms, duration_ms, confidence, peak_db_over_floor,
///  envelope_reduction_pct(0–1), terminated_by_gasp(0/1), mean_db_over_floor]
/// where type is 0=APNEA_LIKE, 1=HYPOPNEA_LIKE, 2=GASP, 3=SNORE_EPISODE.
/// Empty array when no events are pending.
#[cfg(not(target_vendor = "apple"))]
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_pollEvents<'local>(
    env: JNIEnv<'local>,
    _obj: JObject<'local>,
) -> JFloatArray<'local> {
    let events = lock_engine().poll_events();

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
    new_float_array_or_throw(env, &out)
}
