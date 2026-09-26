//! C ABI contract for the iOS app (src/ffi_c.rs): buffer sizes, layouts,
//! and null/short-buffer safety. Runs on the host like the other tests.

use deltasleep_dsp::ffi_c::*;

#[test]
fn frame_and_epoch_layouts_match_jni_contract() {
    ds_start_session();
    let frame = [0i16; 160];
    let mut out = [f32::NAN; FRAME_RESULT_LEN];
    for _ in 0..100 {
        let n = unsafe { ds_process_frame(frame.as_ptr(), frame.len(), out.as_mut_ptr(), out.len()) };
        assert_eq!(n, FRAME_RESULT_LEN);
    }
    assert!(out.iter().all(|v| v.is_finite()));
    assert!(out[5] == 0.0 || out[5] == 1.0, "breathing_present is a 0/1 flag");

    let mut epoch = [f32::NAN; EPOCH_RESULT_LEN];
    let n = unsafe { ds_compute_epoch(epoch.as_mut_ptr(), epoch.len()) };
    assert_eq!(n, EPOCH_RESULT_LEN);
    assert!((0.0..=3.0).contains(&epoch[4]), "phase ordinal in SleepPhase range");
    ds_reset_epoch();
}

#[test]
fn bad_buffers_write_nothing() {
    let frame = [0i16; 160];
    let mut short = [0f32; 3];
    unsafe {
        assert_eq!(ds_process_frame(frame.as_ptr(), 160, short.as_mut_ptr(), short.len()), 0);
        assert_eq!(ds_process_frame(std::ptr::null(), 160, short.as_mut_ptr(), 6), 0);
        assert_eq!(ds_compute_epoch(short.as_mut_ptr(), short.len()), 0);
        assert_eq!(ds_compute_epoch(std::ptr::null_mut(), 11), 0);
        assert_eq!(ds_poll_events(std::ptr::null_mut(), 64), 0);
    }
}
