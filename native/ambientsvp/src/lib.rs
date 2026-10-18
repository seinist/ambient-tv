//! Shared Open-SVPFlow engine. No Android, player, VapourSynth or Python runtime.
use std::{
    collections::BTreeMap,
    panic::{AssertUnwindSafe, catch_unwind},
    time::Instant,
};
use svpflow_wasm::Smoother;
use svpflow1_vs::{Analyser, SuperBuilder, SuperFrame};
#[cfg(feature = "vulkan")]
mod gpu;

const MAX_QUEUE: usize = 10;
const MAX_FRAMES: i32 = 100_000_000;
static ENABLED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static INTERPOLATED: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static GPU_FRAMES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct Config {
    pub abi: u32,
    pub width: u32,
    pub height: u32,
    pub source_num: u32,
    pub source_den: u32,
    pub target_num: u32,
    pub target_den: u32,
    pub preset: u32,
    pub backend: u32,
    pub threads: u32,
    pub artifacts: u32,
    pub scene: u32,
    pub algo: u32,
    pub block: u32,
    pub overlap: u32,
    pub pel: u32,
    pub search: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct Stats {
    pub input_frames: u64,
    pub output_frames: u64,
    pub interpolated_frames: u64,
    pub analysis_us: u64,
    pub synthesis_us: u64,
    pub queued_frames: u32,
    pub backend: u32,
    pub bypass_reason: u32,
    pub preset: u32,
}

struct Source {
    data: Vec<u8>,
    pts: i64,
}

pub struct AmbientSvp {
    config: Config,
    builder: SuperBuilder,
    analyser: Analyser,
    smoother: Smoother,
    pool: rayon::ThreadPool,
    previous: Option<SuperFrame>,
    frames: BTreeMap<i32, Source>,
    input: i32,
    output: i32,
    origin: Option<i64>,
    eof: bool,
    stats: Stats,
    last_output: Option<i64>,
    window_cost_us: u64,
    window_inputs: u32,
    slow_windows: u32,
    slow_inputs: u32,
    options: String,
    needs_neighbors: bool,
    #[cfg(feature = "vulkan")]
    gpu: Option<gpu::Gpu>,
}

impl AmbientSvp {
    pub fn new(c: Config) -> Result<Self, String> {
        if c.abi != 2
            || c.width < 32
            || c.height < 32
            || c.width > 3840
            || c.height > 2160
            || c.width % 2 != 0
            || c.height % 2 != 0
            || c.source_num == 0
            || c.source_den == 0
            || c.target_num == 0
            || c.target_den == 0
            || c.preset > 3
            || c.backend > 2
            || c.threads > 8 || c.artifacts > 3 || c.scene > 3
            || !matches!(c.algo, 0 | 13 | 21 | 23)
            || !matches!(c.block, 0 | 8 | 16 | 32)
            || c.overlap > 4 || !matches!(c.pel, 0 | 1 | 2 | 4) || c.search > 3
            || [c.source_num, c.source_den, c.target_num, c.target_den]
                .iter()
                .any(|n| *n > 1_000_000)
        {
            return Err("unsupported configuration".into());
        }
        let src = c.source_num as f64 / c.source_den as f64;
        let dst = c.target_num as f64 / c.target_den as f64;
        if !(5.0..=120.0).contains(&src) || dst <= src || dst > 120.0 || dst / src > 5.0 {
            return Err("unsupported frame rate".into());
        }
        let threads = if c.threads == 0 {
            std::thread::available_parallelism()
                .map_or(2, |n| n.get())
                .min(4)
        } else {
            c.threads.clamp(1, 8) as usize
        };
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(threads)
            .build()
            .map_err(|e| e.to_string())?;
        // Start automatic mode conservatively. No architecture-wide AVX/NEON requirement.
        let preset = if c.preset == 0 { 1 } else { c.preset };
        let pel = if c.pel != 0 { c.pel } else if preset == 3 { 2 } else { 1 };
        let builder = SuperBuilder::new(c.width as i32, c.height as i32, pel as i32)?;
        let block = if c.block != 0 { c.block } else if preset == 1 { 32 } else { 16 };
        let overlap = if c.overlap != 0 { c.overlap - 1 } else if preset == 1 { 0 } else { 2 };
        // Keep every configuration CPU-compatible, including a later Vulkan fallback.
        let overlap = if block == 8 && overlap == 1 { 2 } else { overlap };
        let search = match c.search {
            1 => ",main:{search:{type:4,distance:1,coarse:{distance:1,trymany:false}}}",
            2 => ",main:{search:{type:4,distance:2,coarse:{distance:2,trymany:false}}}",
            3 => ",main:{search:{type:4,distance:4,coarse:{distance:4,trymany:true}}}",
            _ => "",
        };
        let analyser = Analyser::with_options(&builder,
            &format!("{{block:{{w:{block},h:{block},overlap:{overlap}}},vectors:3{search}}}"))?;
        let algo = if c.algo != 0 { c.algo } else if preset == 3 { 23 } else { 13 };
        // Keep zero=0: perfect motion matches must not be mistaken for scene cuts.
        let blocks = match c.scene { 1 => 30, 3 => 70, _ => 50 };
        // SAD masks work for both the pairwise and coverage algorithms.
        // Keep coverage enabled: cover=0 silently changes upstream algorithms 21/23 to 11.
        let mask = match c.artifacts {
            1 => ",mask:{cover:100,area:0}",
            2 => ",mask:{cover:100,area:50,area_sharp:1,area_blend:0.4}",
            3 => ",mask:{cover:100,area:100,area_sharp:1,area_blend:0.7}",
            _ => "",
        };
        let options = format!(
            "{{rate:{{abs:true,num:{},den:{}}},algo:{algo},scene:{{mode:0,limits:{{zero:0,blocks:{blocks}}}}},gpu:false{mask}}}",
            c.target_num, c.target_den,
        );
        let smoother = Smoother::new(
            c.width as i32,
            c.height as i32,
            c.source_num as i32,
            c.source_den as i32,
            MAX_FRAMES,
            &options,
            &analyser.vector_header(),
        )?;
        #[cfg(feature = "vulkan")]
        let gpu = if c.backend != 1 {
            gpu::Gpu::new(c.width, c.height).ok()
        } else {
            None
        };
        #[cfg(feature = "vulkan")]
        let backend = if gpu.is_some() { 2 } else { 1 };
        #[cfg(not(feature = "vulkan"))]
        let backend = 1;
        // An explicitly requested but unavailable Vulkan device falls back to CPU synthesis,
        // like automatic selection; stats.backend reports what actually runs.
        Ok(Self {
            config: c,
            builder,
            analyser,
            smoother,
            pool,
            previous: None,
            frames: BTreeMap::new(),
            input: 0,
            output: 0,
            origin: None,
            eof: false,
            stats: Stats {
                backend,
                preset,
                ..Stats::default()
            },
            last_output: None,
            window_cost_us: 0,
            window_inputs: 0,
            slow_windows: 0,
            slow_inputs: 0,
            options,
            needs_neighbors: algo == 23,
            #[cfg(feature = "vulkan")]
            gpu,
        })
    }

    pub fn frame_len(&self) -> usize {
        self.config.width as usize * self.config.height as usize * 3 / 2
    }

    pub fn reset(&mut self) -> Result<(), String> {
        self.smoother = Smoother::new(
            self.config.width as i32,
            self.config.height as i32,
            self.config.source_num as i32,
            self.config.source_den as i32,
            MAX_FRAMES,
            &self.options,
            &self.analyser.vector_header(),
        )?;
        self.frames.clear();
        self.previous = None;
        self.input = 0;
        self.output = 0;
        self.origin = None;
        self.last_output = None;
        self.eof = false;
        // A vout flush is a timeline reset, not a new performance session.
        // Retain lifetime counters, the cost budget and any safety bypass so
        // repeated late-frame flushes cannot defeat overload protection.
        self.stats.queued_frames = 0;
        #[cfg(feature = "vulkan")]
        if let Some(gpu) = self.gpu.as_mut() {
            gpu.clear_sources();
        }
        Ok(())
    }

    pub fn push(&mut self, data: &[u8], pts: i64) -> Result<(), i32> {
        if self.eof || data.len() != self.frame_len() || self.input >= MAX_FRAMES - 16 {
            return Err(-1);
        }
        if self.frames.len() >= MAX_QUEUE {
            return Err(-2);
        }
        if let Some(origin) = self.origin.filter(|_| self.stats.bypass_reason == 0) {
            let expected = origin.checked_add(self.source_time(self.input)).ok_or(-3)?;
            // Reject VFR/discontinuity rather than gradually losing A/V sync.
            if (pts as i128 - expected as i128).abs() > (self.source_time(1) / 4).max(3000) as i128
            {
                return Err(-3);
            }
        } else if self.origin.is_none() {
            self.origin = Some(pts);
        }
        let begin = Instant::now();
        if self.stats.bypass_reason == 0 {
            let current = self
                .pool
                .install(|| self.builder.build(data))
                .map_err(|_| -1)?;
            if let Some(previous) = &self.previous {
                let vectors = self
                    .pool
                    .install(|| self.analyser.analyse(previous, &current))
                    .map_err(|_| -1)?;
                self.smoother.set_vectors(self.input - 1, &vectors);
            }
            if self.smoother.uses_super() {
                self.smoother
                    .set_super(self.input, &current)
                    .map_err(|_| -1)?;
            } else {
                self.smoother.set_source(self.input, data).map_err(|_| -1)?;
            }
            self.previous = Some(current);
        }
        self.frames.insert(
            self.input,
            Source {
                data: data.to_vec(),
                pts,
            },
        );
        self.input += 1;
        self.stats.input_frames += 1;
        let cost = begin.elapsed().as_micros() as u64;
        self.stats.analysis_us += cost;
        self.window_cost_us += cost;
        self.window_inputs += 1;
        self.stats.queued_frames = self.frames.len() as u32;
        // Fail fast on grossly inadequate decoding+analysis devices; do not
        // wait several source-seconds while processing them far below real time.
        if self.input > 1 && cost > (self.source_time(1) as u64 * 2).max(75_000) {
            self.slow_inputs += 1;
            if self.slow_inputs >= 2 || cost > 500_000 {
                self.bypass(1);
            }
        } else {
            self.slow_inputs = 0;
        }
        // A full source-second window amortizes multi-output synthesis and analysis.
        // Two slow windows are required; initial shader setup is excluded.
        if self.window_inputs as i64 * self.source_time(1) >= 1_000_000 {
            if self.stats.input_frames > (self.window_inputs * 2) as u64
                && self.window_cost_us > 850_000
            {
                self.slow_windows += 1;
            } else {
                self.slow_windows = 0;
            }
            if self.slow_windows >= 2 {
                self.bypass(1);
            }
            self.window_inputs = 0;
            self.window_cost_us = 0;
        }
        Ok(())
    }

    fn source_time(&self, index: i32) -> i64 {
        (index as i128 * 1_000_000 * self.config.source_den as i128
            / self.config.source_num as i128) as i64
    }

    pub fn bypass(&mut self, reason: u32) {
        self.stats.bypass_reason = reason.max(1);
        self.previous = None;
        self.smoother.forget_before(self.input);
        #[cfg(feature = "vulkan")]
        {
            self.gpu = None;
        }
    }

    pub fn receive(&mut self, out: &mut [u8], eof: bool) -> Result<Option<i64>, String> {
        if out.len() != self.frame_len() {
            return Err("invalid output size".into());
        }
        self.eof |= eof;
        if self.input == 0 {
            return Ok(None);
        }
        if self.stats.bypass_reason != 0 {
            while let Some((_, source)) = self.frames.pop_first() {
                if self.last_output.is_some_and(|pts| source.pts <= pts) {
                    continue;
                }
                out.copy_from_slice(&source.data);
                self.last_output = Some(source.pts);
                self.stats.output_frames += 1;
                self.stats.queued_frames = self.frames.len() as u32;
                return Ok(Some(source.pts));
            }
            return Ok(None);
        }
        let offset = (self.output as i128 * 1_000_000 * self.config.target_den as i128
            / self.config.target_num as i128) as i64;
        if self.eof && offset >= self.source_time(self.input) {
            return Ok(None);
        }
        let plan = self.smoother.plan(self.output);
        // Algo 13 with flags=0 uses only the current pair. The upstream plan is
        // conservative and also requests neighbor vectors; waiting for those
        // would unnecessarily double latency. Algo 23 really needs neighbors.
        let required = if plan[2] == plan[3] {
            plan[0]
        } else if !self.needs_neighbors {
            plan[1]
        } else {
            plan[1].max(plan[3])
        };
        if !self.eof && required >= self.input {
            return Ok(None);
        }
        // At EOF there are no future vectors. Copy the last known frame for the
        // short tail; never manufacture motion across an absent future frame.
        if self.eof && required >= self.input {
            out.copy_from_slice(&self.frames.last_key_value().ok_or("empty tail")?.1.data);
        } else {
            let begin = Instant::now();
            #[cfg(feature = "vulkan")]
            let gpu_done = if let Some(gpu) = self.gpu.as_mut() {
                use svpflow_core::smooth_engine::Output;
                match self.smoother.prepare_gpu(self.output) {
                    Output::Copy(k) => {
                        out.copy_from_slice(&self.frames.get(&k).ok_or("missing copy")?.data);
                        true
                    }
                    Output::Gpu(job) => {
                        let current = &self.frames.get(&job.n).ok_or("missing current")?.data;
                        let next = &self.frames.get(&(job.n + 1)).ok_or("missing next")?.data;
                        match gpu.render(&job, current, next, out) {
                            Ok(()) => {
                                GPU_FRAMES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                                true
                            }
                            Err(_) => {
                                self.gpu = None;
                                self.stats.backend = 1;
                                false
                            }
                        }
                    }
                    _ => false,
                }
            } else {
                false
            };
            #[cfg(not(feature = "vulkan"))]
            let gpu_done = false;
            if !gpu_done {
                self.pool
                    .install(|| self.smoother.render_into(self.output, out))?;
            }
            let cost = begin.elapsed().as_micros() as u64;
            self.stats.synthesis_us += cost;
            self.window_cost_us += cost;
            // Count novel pictures, not merely fractional output timestamps:
            // scene cuts and unchanged images can legitimately be copies.
            if self
                .frames
                .get(&plan[0])
                .is_some_and(|s| s.data.as_slice() != out)
                && self
                    .frames
                    .get(&plan[1])
                    .is_some_and(|s| s.data.as_slice() != out)
            {
                self.stats.interpolated_frames += 1;
                INTERPOLATED.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            }
        }
        let pts = self
            .origin
            .unwrap()
            .checked_add(offset)
            .ok_or("timestamp overflow")?;
        self.output += 1;
        self.stats.output_frames += 1;
        self.last_output = Some(pts);
        let keep = self.smoother.plan(self.output)[0].saturating_sub(3);
        self.frames = self.frames.split_off(&keep);
        self.smoother.forget_before(keep);
        self.stats.queued_frames = self.frames.len() as u32;
        Ok(Some(pts))
    }

    pub fn stats(&self) -> Stats {
        self.stats
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn ambient_svp_abi() -> u32 {
    2
}

#[unsafe(no_mangle)]
pub extern "C" fn ambient_svp_enabled() -> i32 {
    ENABLED.load(std::sync::atomic::Ordering::Acquire) as i32
}
#[unsafe(no_mangle)]
pub extern "C" fn ambient_svp_set_enabled(enabled: i32) {
    ENABLED.store(enabled != 0, std::sync::atomic::Ordering::Release);
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ambient_tv_ui_player_backend_SvpNative_setEnabled(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    enabled: u8,
) {
    ambient_svp_set_enabled(enabled as i32);
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ambient_tv_ui_player_backend_SvpNative_abi(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> i32 {
    ambient_svp_abi() as i32
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ambient_tv_ui_player_backend_SvpNative_interpolatedFrames(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> i64 {
    INTERPOLATED.load(std::sync::atomic::Ordering::Relaxed) as i64
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ambient_tv_ui_player_backend_SvpNative_gpuFrames(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> i64 {
    GPU_FRAMES.load(std::sync::atomic::Ordering::Relaxed) as i64
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_ambient_tv_ui_player_backend_SvpNative_enabled(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> u8 {
    ambient_svp_enabled() as u8
}

// Foreign buffers/handles must follow include/ambientsvp.h. Rust panics never
// unwind through a C player. A failed operation is an observable fallback.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_create(c: *const Config) -> *mut AmbientSvp {
    if ambient_svp_enabled() == 0 {
        return std::ptr::null_mut();
    }
    catch_unwind(AssertUnwindSafe(|| {
        let c = unsafe { c.as_ref() }?;
        AmbientSvp::new(*c).ok().map(|s| Box::into_raw(Box::new(s)))
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_destroy(s: *mut AmbientSvp) {
    if !s.is_null() {
        let _ = catch_unwind(AssertUnwindSafe(|| drop(unsafe { Box::from_raw(s) })));
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_reset(s: *mut AmbientSvp) -> i32 {
    if s.is_null() {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| unsafe {
        (*s).reset().map_or(-1, |_| 0)
    }))
    .unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_push(
    s: *mut AmbientSvp,
    data: *const u8,
    len: usize,
    pts: i64,
) -> i32 {
    if s.is_null() || data.is_null() {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let s = unsafe { &mut *s };
        if ambient_svp_enabled() == 0 {
            s.bypass(2);
        }
        if len != s.frame_len() {
            return -1;
        }
        s.push(unsafe { std::slice::from_raw_parts(data, len) }, pts)
            .map_or_else(|e| e, |_| 0)
    }))
    .unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_receive(
    s: *mut AmbientSvp,
    data: *mut u8,
    len: usize,
    pts: *mut i64,
    eof: i32,
) -> i32 {
    if s.is_null() || data.is_null() || pts.is_null() {
        return -1;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let s = unsafe { &mut *s };
        if ambient_svp_enabled() == 0 {
            s.bypass(2);
        }
        if len != s.frame_len() {
            return -1;
        }
        match s.receive(
            unsafe { std::slice::from_raw_parts_mut(data, len) },
            eof != 0,
        ) {
            Ok(Some(p)) => {
                unsafe {
                    *pts = p;
                }
                1
            }
            Ok(None) => 0,
            Err(_) => -1,
        }
    }))
    .unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_stats(s: *const AmbientSvp, stats: *mut Stats) -> i32 {
    if s.is_null() || stats.is_null() {
        return -1;
    }
    unsafe {
        *stats = (*s).stats();
    }
    0
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_bypass(s: *mut AmbientSvp, reason: u32) {
    if !s.is_null() {
        let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
            (*s).bypass(reason);
        }));
    }
}
