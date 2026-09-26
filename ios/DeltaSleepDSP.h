// C declarations for the Rust DSP core (dsp/src/ffi_c.rs), exposed to
// Swift as the project's bridging header. Layouts match the Android JNI
// contract documented in DspBridge.kt.
#ifndef DELTASLEEP_DSP_H
#define DELTASLEEP_DSP_H

#include <stddef.h>
#include <stdint.h>

/// One 10 ms frame (160 samples @ 16 kHz) → 6 floats:
/// [rms, zcr, band_power_ratio, noise_floor_db, breathing_margin_db, breathing_present]
size_t ds_process_frame(const int16_t *samples, size_t len, float *out, size_t out_len);

/// Epoch summary → 11 floats:
/// [mean_rms, rms_variance, mean_zcr, mean_band_ratio, phase_ordinal, snore_flag,
///  mean_breathing_margin_db, breathing_present_fraction, breath_period_s,
///  external_audio_fraction, breath_period_cv]
size_t ds_compute_epoch(float *out, size_t out_len);

void ds_reset_epoch(void);
void ds_start_session(void);
void ds_set_snore_threshold_offset_db(float offset_db);

/// Drain events, stride 8: [type, start_offset_ms, duration_ms, confidence,
/// peak_db_over_floor, envelope_reduction_pct, terminated_by_gasp, mean_db_over_floor].
/// Returns the number of events written.
size_t ds_poll_events(float *out, size_t out_len);

#endif
