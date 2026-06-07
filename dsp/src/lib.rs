mod classifier;
mod features;
mod snore;

use features::{BandPassState, FrameFeatures};
use jni::objects::{JFloatArray, JObject, JShortArray};
use jni::sys::jfloat;
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

// ── Epoch accumulator ──────────────────────────────────────────────────────────

#[derive(Default)]
struct Accumulator {
    rms_sum: f64,
    rms_sq_sum: f64,  // for variance: E[x²] - E[x]²
    zcr_sum: f64,
    band_ratio_sum: f64,
    count: usize,
    bp: BandPassState,
}

impl Accumulator {
    fn add(&mut self, f: FrameFeatures) {
        self.rms_sum += f.rms as f64;
        self.rms_sq_sum += (f.rms * f.rms) as f64;
        self.zcr_sum += f.zcr as f64;
        self.band_ratio_sum += f.band_power_ratio as f64;
        self.count += 1;
    }

    fn mean_rms(&self) -> f32 {
        if self.count == 0 { return 0.0; }
        (self.rms_sum / self.count as f64) as f32
    }

    fn rms_variance(&self) -> f32 {
        if self.count == 0 { return 0.0; }
        let n = self.count as f64;
        let mean = self.rms_sum / n;
        let mean_sq = self.rms_sq_sum / n;
        (mean_sq - mean * mean).max(0.0) as f32
    }

    fn mean_band_ratio(&self) -> f32 {
        if self.count == 0 { return 0.0; }
        (self.band_ratio_sum / self.count as f64) as f32
    }

    fn reset(&mut self) {
        *self = Self::default();
    }
}

static ACC: OnceLock<Mutex<Accumulator>> = OnceLock::new();

fn acc() -> &'static Mutex<Accumulator> {
    ACC.get_or_init(|| Mutex::new(Accumulator::default()))
}

// ── JNI exports ───────────────────────────────────────────────────────────────

/// Process one 10 ms frame of 16 kHz mono PCM.
/// Returns float[3]: [rms, zcr, band_power_ratio].
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_processFrame<'local>(
    env: JNIEnv<'local>,
    _obj: JObject<'local>,
    samples: JShortArray<'local>,
) -> JFloatArray<'local> {
    let len = env.get_array_length(&samples).unwrap_or(0) as usize;
    let mut buf = vec![0i16; len];
    env.get_short_array_region(&samples, 0, &mut buf).unwrap_or(());

    let frame = {
        let mut guard = acc().lock().unwrap();
        let f = features::compute(&buf, &mut guard.bp);
        guard.add(f);
        f
    };

    let out = [frame.rms, frame.zcr, frame.band_power_ratio];
    let arr = env.new_float_array(3).unwrap();
    env.set_float_array_region(&arr, 0, &out).unwrap();
    arr
}

/// Summarise the accumulated epoch.
/// Returns float[6]: [mean_rms, rms_variance, mean_zcr, mean_band_ratio, phase_ordinal, snore_flag].
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_computeEpoch<'local>(
    env: JNIEnv<'local>,
    _obj: JObject<'local>,
) -> JFloatArray<'local> {
    let guard = acc().lock().unwrap();
    let mean_rms = guard.mean_rms();
    let variance = guard.rms_variance();
    let mean_zcr = if guard.count == 0 { 0.0 } else { (guard.zcr_sum / guard.count as f64) as f32 };
    let mean_band = guard.mean_band_ratio();
    drop(guard);

    let phase = classifier::classify(mean_rms, variance) as jfloat;
    let snore = if snore::detect(mean_rms, mean_band) { 1.0 } else { 0.0 };

    let out = [mean_rms, variance, mean_zcr, mean_band, phase, snore];
    let arr = env.new_float_array(6).unwrap();
    env.set_float_array_region(&arr, 0, &out).unwrap();
    arr
}

/// Discard accumulated epoch data and reset IIR filter state.
#[no_mangle]
pub extern "system" fn Java_io_github_ntufar_deltasleep_audio_DspBridge_resetEpoch<'local>(
    _env: JNIEnv<'local>,
    _obj: JObject<'local>,
) {
    acc().lock().unwrap().reset();
}
