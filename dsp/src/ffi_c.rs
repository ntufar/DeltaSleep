//! Plain C ABI over the global [`SessionEngine`] for the iOS app.
//!
//! Mirrors the JNI shim in `lib.rs` one-to-one: same global engine, same
//! float layouts, same ordering. Results are written into caller-owned
//! buffers instead of allocated arrays, so no memory crosses the boundary.
//! The matching C declarations live in `ios/DeltaSleep/DeltaSleepDSP.h`.
//!
//! Every function is panic-free on bad input: null pointers and short
//! buffers write nothing and return 0.

use crate::lock_engine;

/// Floats written by [`ds_process_frame`].
pub const FRAME_RESULT_LEN: usize = 6;
/// Floats written by [`ds_compute_epoch`].
pub const EPOCH_RESULT_LEN: usize = 11;
/// Floats per event written by [`ds_poll_events`].
pub const EVENT_STRIDE: usize = 8;

/// Process one 10 ms frame of 16 kHz mono PCM (`len` ≤ 160 is used).
/// Writes 6 floats into `out` (same layout as JNI `processFrame`) and
/// returns the count written, or 0 when a pointer is null / `out_len` < 6.
///
/// # Safety
/// `samples` must point to `len` readable i16s and `out` to `out_len`
/// writable f32s.
#[no_mangle]
pub unsafe extern "C" fn ds_process_frame(
    samples: *const i16,
    len: usize,
    out: *mut f32,
    out_len: usize,
) -> usize {
    if samples.is_null() || out.is_null() || out_len < FRAME_RESULT_LEN {
        return 0;
    }
    let len = len.min(crate::FRAME_SAMPLES);
    let frame_in = std::slice::from_raw_parts(samples, len);
    let frame = lock_engine().process_frame(frame_in);
    let res = [
        frame.rms,
        frame.zcr,
        frame.band_power_ratio,
        frame.noise_floor_db,
        frame.breathing_margin_db,
        if frame.breathing_present { 1.0 } else { 0.0 },
    ];
    std::slice::from_raw_parts_mut(out, FRAME_RESULT_LEN).copy_from_slice(&res);
    FRAME_RESULT_LEN
}

/// Summarise the accumulated epoch. Writes 11 floats (same layout as JNI
/// `computeEpoch`) and returns the count written, or 0 on a bad buffer.
///
/// # Safety
/// `out` must point to `out_len` writable f32s.
#[no_mangle]
pub unsafe extern "C" fn ds_compute_epoch(out: *mut f32, out_len: usize) -> usize {
    if out.is_null() || out_len < EPOCH_RESULT_LEN {
        return 0;
    }
    let epoch = lock_engine().compute_epoch();
    let res = [
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
    std::slice::from_raw_parts_mut(out, EPOCH_RESULT_LEN).copy_from_slice(&res);
    EPOCH_RESULT_LEN
}

/// Discard accumulated epoch data only (see JNI `resetEpoch`).
#[no_mangle]
pub extern "C" fn ds_reset_epoch() {
    lock_engine().reset_epoch();
}

/// Full DSP session reset (see JNI `startSession`).
#[no_mangle]
pub extern "C" fn ds_start_session() {
    lock_engine().start_session();
}

/// D-3 mic-sensitivity offset on the snore threshold, clamped engine-side.
#[no_mangle]
pub extern "C" fn ds_set_snore_threshold_offset_db(offset_db: f32) {
    lock_engine().set_snore_threshold_offset_db(offset_db);
}

/// Drain completed acoustic events into `out` with stride 8 (same layout
/// as JNI `pollEvents`). Returns the number of **events** written. Events
/// that do not fit in `out_len` are dropped rather than retained, matching
/// the drain-on-poll contract; callers pass room for the worst case.
///
/// # Safety
/// `out` must point to `out_len` writable f32s.
#[no_mangle]
pub unsafe extern "C" fn ds_poll_events(out: *mut f32, out_len: usize) -> usize {
    if out.is_null() {
        return 0;
    }
    let events = lock_engine().poll_events();
    let capacity = out_len / EVENT_STRIDE;
    let buf = std::slice::from_raw_parts_mut(out, out_len);
    let mut written = 0;
    for ev in events.iter().take(capacity) {
        let base = written * EVENT_STRIDE;
        buf[base..base + EVENT_STRIDE].copy_from_slice(&[
            ev.event_type as i32 as f32,
            ev.start_offset_ms as f32,
            ev.duration_ms as f32,
            ev.confidence,
            ev.peak_db_over_floor,
            ev.envelope_reduction_pct,
            if ev.terminated_by_gasp { 1.0 } else { 0.0 },
            ev.mean_db_over_floor,
        ]);
        written += 1;
    }
    written
}
