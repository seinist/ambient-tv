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
// Experimental reusable exact metric; not a replacement for the upstream analyser.
#[cfg(feature = "vulkan")]
pub mod motion;

const MAX_QUEUE: usize = 10;
const MAX_FRAMES: i32 = 100_000_000;
const MAX_RECYCLED_FRAME_BYTES: usize = 32 * 1024 * 1024;
const ADAPTIVE_PRESET: u32 = 4;
const MAX_ADAPTIVE_LEVEL: u32 = 2;

// Decoder, audio and render threads must retain CPU time. Small images
// don't amortize dispatch overhead as well as HD images. Explicit settings
// remain authoritative, including on constrained cpusets.
fn worker_count(requested: u32, available: usize, pixels: u64) -> usize {
    if requested != 0 { return requested.clamp(1, 8) as usize; }
    let available = available.max(1);
    let reserved = if available >= 8 { 2 } else { 1 };
    let budget = available.saturating_sub(reserved).max(1);
    let target = if pixels <= 320 * 240 { 2 }
        else if pixels <= 1280 * 720 { 4 }
        else { 6 };
    budget.min(target)
}

#[cfg(test)]
mod worker_tests {
    use super::*;
    #[test]
    fn auto_workers_reserve_cpu_for_playback_and_scale_with_work() {
        assert_eq!(worker_count(0, 1, 1920*1080), 1);
        assert_eq!(worker_count(0, 4, 1920*1080), 3);
        assert_eq!(worker_count(0, 8, 320*180), 2);
        assert_eq!(worker_count(0, 8, 1280*720), 4);
        assert_eq!(worker_count(0, 8, 3840*2160), 6);
        assert_eq!(worker_count(8, 2, 320*180), 8);
    }
}
static ENABLED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static INTERPOLATED: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static GPU_FRAMES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Must equal AMBIENTSVP_ABI in include/ambientsvp.h and SVP_NATIVE_ABI in SvpNative.kt.
/// The engine, both player adapters and the JNI check are rebuilt together when it changes.
pub const AMBIENTSVP_ABI: u32 = 2;

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
    analysis_options: String,
    adaptive_level: u32,
    smoother: Smoother,
    pool: rayon::ThreadPool,
    previous: Option<SuperFrame>,
    repeated_vectors: Option<Vec<u8>>,
    frames: BTreeMap<i32, Source>,
    recycled_frames: Vec<Vec<u8>>,
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
    #[cfg(feature = "vulkan")]
    prefetched: Option<i32>,
    #[cfg(feature = "vulkan")]
    prefetch_buffer: Vec<u8>,
    #[cfg(all(test, feature = "vulkan"))]
    disable_prefetch: bool,
}

impl AmbientSvp {
    pub fn new(c: Config) -> Result<Self, String> {
        if c.abi != AMBIENTSVP_ABI
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
            || c.preset > ADAPTIVE_PRESET
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
        let threads = worker_count(c.threads,
            std::thread::available_parallelism().map_or(2, |n| n.get()),
            u64::from(c.width) * u64::from(c.height));
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(threads)
            .thread_name(|index| format!("svp-worker-{index}"))
            .build()
            .map_err(|e| e.to_string())?;
        // Start automatic mode conservatively. No architecture-wide AVX/NEON requirement.
        let preset = if c.preset == 0 || c.preset == ADAPTIVE_PRESET { 1 } else { c.preset };
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
        // No changes to the old presets or explicit tuning. Only the new
        // opt-in adaptive preset may switch upstream fast analysis levels.
        let analysis_options = format!("{{block:{{w:{block},h:{block},overlap:{overlap}}},vectors:3{search}");
        let analyser = Analyser::with_options(&builder, &format!("{analysis_options},fast:0}}"))?;
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
            analysis_options,
            adaptive_level: 0,
            smoother,
            pool,
            previous: None,
            repeated_vectors: None,
            frames: BTreeMap::new(),
            recycled_frames: Vec::new(),
            input: 0,
            output: 0,
            origin: None,
            eof: false,
            stats: Stats {
                backend,
                preset: if c.preset == ADAPTIVE_PRESET { ADAPTIVE_PRESET } else { preset },
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
            #[cfg(feature = "vulkan")]
            prefetched: None,
            #[cfg(feature = "vulkan")]
            prefetch_buffer: Vec::new(),
            #[cfg(all(test, feature = "vulkan"))]
            disable_prefetch: false,
        })
    }

    pub fn frame_len(&self) -> usize {
        self.config.width as usize * self.config.height as usize * 3 / 2
    }

    // Cap spare capacity separately from live frames: 4K I420 is about
    // 12 MiB per allocation, so ten spare frames are not appropriate.
    fn recycle_frame(&mut self, data: Vec<u8>) {
        let bytes: usize = self.recycled_frames.iter().map(Vec::capacity).sum();
        if self.recycled_frames.len() < MAX_QUEUE
            && bytes.saturating_add(data.capacity()) <= MAX_RECYCLED_FRAME_BYTES {
            self.recycled_frames.push(data);
        }
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
        for source in std::mem::take(&mut self.frames).into_values() {
            self.recycle_frame(source.data);
        }
        self.previous = None;
        self.repeated_vectors = None;
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
        {
            self.prefetched = None;
            if let Some(gpu) = self.gpu.as_mut() { gpu.clear_sources(); }
        }
        Ok(())
    }

    /// Changing search effort does not change block layout, pel, cadence or
    /// timestamps. Future pairs use the cheaper analysis; existing vectors
    /// remain valid only if the upstream vector contract is identical.
    fn reduce_analysis_effort(&mut self) -> bool {
        if self.config.preset != ADAPTIVE_PRESET || self.adaptive_level >= MAX_ADAPTIVE_LEVEL
            || self.stats.bypass_reason != 0 {
            return false;
        }
        let next = self.adaptive_level + 1;
        let Ok(analyser) = Analyser::with_options(
            &self.builder, &format!("{},fast:{next}}}", self.analysis_options)
        ) else { return false; };
        if analyser.vector_header() != self.analyser.vector_header() { return false; }
        self.analyser = analyser;
        self.adaptive_level = next;
        self.repeated_vectors = None;
        self.slow_inputs = 0;
        self.slow_windows = 0;
        self.window_cost_us = 0;
        self.window_inputs = 0;
        true
    }

    pub fn push(&mut self, data: &[u8], pts: i64) -> Result<(), i32> {
        if data.len() != self.frame_len() { return Err(-1); }
        self.push_filled(pts, |dst| {
            dst.copy_from_slice(data);
            Ok(())
        })
    }

    // The adapter can write directly into the engine-owned I420 allocation.
    // Validate capacity/timing before invoking the writer; no source pointer
    // survives the call. A failed writer must not advance the timeline.
    fn push_filled(&mut self, pts: i64, fill: impl FnOnce(&mut [u8]) -> Result<(), i32>) -> Result<(), i32> {
        if self.eof || self.input >= MAX_FRAMES - 16 {
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
        }
        let begin = Instant::now();
        let mut stored = self.recycled_frames.pop().unwrap_or_default();
        stored.resize(self.frame_len(), 0);
        if let Err(e) = fill(&mut stored) {
            self.recycle_frame(stored);
            return Err(e);
        }
        if self.origin.is_none() { self.origin = Some(pts); }
        let data = stored.as_slice();
        #[cfg(feature = "vulkan")]
        if self.stats.bypass_reason == 0 && self.gpu.as_mut().is_some_and(|gpu| gpu.stage_source(self.input, data).is_err()) {
            self.gpu = None;
            self.stats.backend = 1;
            self.prefetched = None;
            self.prefetch_buffer = Vec::new();
        }
        let analysis = (|| -> Result<(), i32> {
        if self.stats.bypass_reason == 0 {
            // Exact repeated source pictures can share the previous super
            // frame and the analysis of an identical pair. No sampled hashes,
            // luma-only comparison or guessed static vectors are used.
            let repeated = self.previous.is_some()
                && self.frames.get(&(self.input - 1)).is_some_and(|frame| frame.data.as_slice() == data);
            if repeated {
                let previous = self.previous.as_ref().unwrap();
                if self.repeated_vectors.is_none() {
                    let vectors = self.pool.install(|| self.analyser.analyse(previous, previous))
                        .map_err(|_| -1)?;
                    self.repeated_vectors = Some(vectors);
                }
                self.smoother.set_vectors(self.input - 1, self.repeated_vectors.as_ref().unwrap());
                if self.smoother.uses_super() {
                    self.smoother.set_super(self.input, previous).map_err(|_| -1)?;
                } else {
                    self.smoother.set_source(self.input, data).map_err(|_| -1)?;
                }
            } else {
                self.repeated_vectors = None;
                // Enter the session worker pool once for both CPU stages.
                let (current, vectors) = self.pool.install(|| {
                    let current = self.builder.build(data).map_err(|_| -1)?;
                    let vectors = self.previous.as_ref()
                        .map(|previous| self.analyser.analyse(previous, &current))
                        .transpose().map_err(|_| -1)?;
                    Ok::<_, i32>((current, vectors))
                })?;
                if let Some(vectors) = vectors {
                    self.smoother.set_vectors(self.input - 1, &vectors);
                }
                if self.smoother.uses_super() {
                    self.smoother.set_super(self.input, &current).map_err(|_| -1)?;
                } else {
                    self.smoother.set_source(self.input, data).map_err(|_| -1)?;
                }
                self.previous = Some(current);
            }
        }
        Ok(())
        })();
        if let Err(error) = analysis {
            #[cfg(feature = "vulkan")]
            if let Some(gpu) = self.gpu.as_mut() { gpu.discard_source(self.input); }
            self.recycle_frame(stored);
            if self.input == 0 { self.origin = None; }
            return Err(error);
        }
        self.frames.insert(self.input, Source { data: stored, pts });
        self.input += 1;
        self.stats.input_frames += 1;
        let cost = begin.elapsed().as_micros() as u64;
        self.stats.analysis_us += cost;
        self.window_cost_us += cost;
        self.window_inputs += 1;
        self.stats.queued_frames = self.frames.len() as u32;
        // Fail fast on grossly inadequate decoding+analysis devices; do not
        // wait several source-seconds while processing them far below real time.
        let adaptive = self.config.preset == ADAPTIVE_PRESET
            && self.adaptive_level < MAX_ADAPTIVE_LEVEL && self.stats.bypass_reason == 0;
        let analysis_limit = if adaptive {
            (self.source_time(1) as u64 * 4 / 5).max(5_000)
        } else {
            (self.source_time(1) as u64 * 2).max(75_000)
        };
        if self.stats.bypass_reason == 0 && self.input > 1 && cost > analysis_limit {
            self.slow_inputs += 1;
            if cost > 500_000 {
                // Keep the existing absolute safety ceiling even in adaptive mode.
                self.bypass(1);
            } else if self.slow_inputs >= 2 && !self.reduce_analysis_effort() {
                self.bypass(1);
            }
        } else {
            self.slow_inputs = 0;
        }
        // A full source-second window amortizes multi-output synthesis and analysis.
        // Two slow windows are required; initial shader setup is excluded.
        if self.stats.bypass_reason == 0 && self.window_inputs as i64 * self.source_time(1) >= 1_000_000 {
            if self.stats.input_frames > (self.window_inputs * 2) as u64
                && self.window_cost_us > 850_000
            {
                self.slow_windows += 1;
            } else {
                self.slow_windows = 0;
            }
            if (self.slow_windows >= 2 || adaptive && self.slow_windows >= 1)
                && !self.reduce_analysis_effort() {
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
        self.repeated_vectors = None;
        self.smoother.forget_before(self.input);
        #[cfg(feature = "vulkan")]
        {
            self.gpu = None;
            self.prefetched = None;
            self.prefetch_buffer = Vec::new();
        }
    }

    fn required_input(&self, plan: [i32; 4]) -> i32 {
        if plan[2] == plan[3] { plan[0] }
        else if !self.needs_neighbors { plan[1] }
        else { plan[1].max(plan[3]) }
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
                    self.recycle_frame(source.data);
                    continue;
                }
                out.copy_from_slice(&source.data);
                self.recycle_frame(source.data);
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
        let required = self.required_input(plan);
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
            let (gpu_done, known_copy) = if self.prefetched.take() == Some(self.output) {
                out.copy_from_slice(&self.prefetch_buffer);
                GPU_FRAMES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                (true, false)
            } else if self.gpu.is_some() {
                // Only prefetch a ready consecutive output from the same
                // source pair, never across a seek, EOF tail or lookahead gap.
                // Bound extra readback + CPU storage to <= 1080p.
                let next_index = self.output + 1;
                let next_plan = self.smoother.plan(next_index);
                let next_offset = (next_index as i128 * 1_000_000 * self.config.target_den as i128
                    / self.config.target_num as i128) as i64;
                let can_prefetch = self.frame_len() <= 1920 * 1080 * 3 / 2
                    && next_plan[0] == plan[0]
                    && self.required_input(next_plan) < self.input
                    && (!self.eof || next_offset < self.source_time(self.input));
                #[cfg(test)]
                let can_prefetch = can_prefetch && !self.disable_prefetch;
                use svpflow_core::smooth_engine::Output;
                match self.smoother.prepare_gpu(self.output) {
                    Output::Copy(k) => {
                        out.copy_from_slice(&self.frames.get(&k).ok_or("missing copy")?.data);
                        (true, k == plan[0] || k == plan[1])
                    }
                    Output::Gpu(job) => {
                        let current = &self.frames.get(&job.n).ok_or("missing current")?.data;
                        let next = &self.frames.get(&(job.n + 1)).ok_or("missing next")?.data;
                        let next_job = if can_prefetch {
                            match self.smoother.prepare_gpu(next_index) {
                                Output::Gpu(next_job) if next_job.n == job.n => Some(next_job),
                                _ => None,
                            }
                        } else { None };
                        let gpu = self.gpu.as_mut().unwrap();
                        let result = if let Some(next_job) = next_job {
                            self.prefetch_buffer.resize(out.len(), 0);
                            let result = gpu.render_pair(&job, &next_job, current, next, out, &mut self.prefetch_buffer);
                            if result.is_ok() { self.prefetched = Some(next_index); }
                            result
                        } else {
                            gpu.render(&job, current, next, out)
                        };
                        match result {
                            Ok(()) => {
                                GPU_FRAMES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                                (true, false)
                            }
                            Err(_) => {
                                self.gpu = None;
                                self.prefetched = None;
                                self.prefetch_buffer = Vec::new();
                                self.stats.backend = 1;
                                (false, false)
                            }
                        }
                    }
                    _ => (false, false),
                }
            } else {
                (false, false)
            };
            #[cfg(not(feature = "vulkan"))]
            let (gpu_done, known_copy) = (false, false);
            if !gpu_done {
                self.pool
                    .install(|| self.smoother.render_into(self.output, out))?;
            }
            let cost = begin.elapsed().as_micros() as u64;
            self.stats.synthesis_us += cost;
            self.window_cost_us += cost;
            // Count novel pictures, not merely fractional output timestamps:
            // scene cuts and unchanged images can legitimately be copies.
            if !known_copy && self
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
        while self.frames.first_key_value().is_some_and(|(key, _)| *key < keep) {
            let (_, source) = self.frames.pop_first().unwrap();
            self.recycle_frame(source.data);
        }
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
    AMBIENTSVP_ABI
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
/// Optional adapter fast path. The synchronous C writer may fill exactly
/// len bytes and must not retain the pointer, re-enter the engine or unwind.
/// The caller owns context for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_push_fill(
    s: *mut AmbientSvp,
    pts: i64,
    context: *mut std::ffi::c_void,
    fill: Option<unsafe extern "C" fn(*mut std::ffi::c_void, *mut u8, usize) -> i32>,
) -> i32 {
    if s.is_null() || fill.is_none() { return -1; }
    catch_unwind(AssertUnwindSafe(|| {
        let s = unsafe { &mut *s };
        if ambient_svp_enabled() == 0 { s.bypass(2); }
        s.push_filled(pts, |dst| {
            let code = unsafe { fill.unwrap()(context, dst.as_mut_ptr(), dst.len()) };
            if code == 0 { Ok(()) } else { Err(-1) }
        }).map_or_else(|e| e, |_| 0)
    })).unwrap_or(-1)
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

#[cfg(all(test, feature = "vulkan"))]
mod pipelining_tests {
    use super::*;

    fn config() -> Config {
        Config {
            abi: AMBIENTSVP_ABI, width: 96, height: 66,
            source_num: 24, source_den: 1, target_num: 120, target_den: 1,
            preset: 1, backend: 2, threads: 2, artifacts: 2, scene: 1,
            algo: 13, block: 16, overlap: 2, pel: 1, search: 1,
        }
    }

    fn image(n: usize) -> Vec<u8> {
        let mut data = vec![128; 96 * 66 * 3 / 2];
        for y in 0..66 {
            for x in 0..96 {
                data[y*96+x] = if (x + 96 - n*2 % 96) % 96 < 32 { 180 } else { 40 };
            }
        }
        data
    }

    #[test]
    #[ignore = "requires hardware Vulkan"]
    fn dual_output_matches_single_and_drops_prefetch_on_seek() {
        let mut pipelined = AmbientSvp::new(config()).unwrap();
        let mut serial = AmbientSvp::new(config()).unwrap();
        serial.disable_prefetch = true;
        let mut a = vec![0; pipelined.frame_len()];
        let mut b = a.clone();
        let mut saw_prefetch = false;
        for origin in [0, 9_000_000] {
            pipelined.reset().unwrap();
            serial.reset().unwrap();
            assert!(pipelined.prefetched.is_none());
            'feed: for n in 0..16 {
                let input = image(n);
                let pts = origin + n as i64 * 1_000_000 / 24;
                pipelined.push(&input, pts).unwrap();
                serial.push(&input, pts).unwrap();
                loop {
                    let ap = pipelined.receive(&mut a, false).unwrap();
                    let bp = serial.receive(&mut b, false).unwrap();
                    assert_eq!(ap, bp);
                    if ap.is_none() { break; }
                    assert_eq!(a, b, "different output at {ap:?}");
                    if pipelined.prefetched.is_some() {
                        saw_prefetch = true;
                        // Seek with an already computed but not delivered frame.
                        if origin == 0 { break 'feed; }
                    }
                }
            }
            if origin != 0 {
                loop {
                    let ap = pipelined.receive(&mut a, true).unwrap();
                    let bp = serial.receive(&mut b, true).unwrap();
                    assert_eq!(ap, bp);
                    if ap.is_none() { break; }
                    assert_eq!(a, b);
                }
            }
            assert_eq!(pipelined.stats.backend, 2);
            assert_eq!(serial.stats.backend, 2);
            assert_eq!(pipelined.stats.bypass_reason, 0);
        }
        assert!(saw_prefetch, "test must exercise two in-flight jobs");
    }

    #[test]
    #[ignore = "requires hardware Vulkan with optional 16-bit normalized textures"]
    fn compact_motion_fields_match_float_fields() {
        let c = config();
        let mut compact = AmbientSvp::new(c).unwrap();
        let mut float = AmbientSvp::new(c).unwrap();
        if !compact.gpu.as_ref().is_some_and(|gpu| gpu.uses_compact_fields()) {
            eprintln!("SKIP: device does not support compact fields");
            return;
        }
        float.gpu = Some(gpu::Gpu::new_with_compact_fields(c.width, c.height, false).unwrap());
        compact.disable_prefetch = true;
        float.disable_prefetch = true;
        let mut a = vec![0; compact.frame_len()];
        let mut b = a.clone();
        let mut compared = 0;
        for n in 0..16 {
            let input = image(n);
            let pts = n as i64 * 1_000_000 / 24;
            compact.push(&input, pts).unwrap();
            float.push(&input, pts).unwrap();
            loop {
                let ap = compact.receive(&mut a, false).unwrap();
                let bp = float.receive(&mut b, false).unwrap();
                assert_eq!(ap, bp);
                if ap.is_none() { break; }
                assert_eq!(a, b, "compact field changed output at {ap:?}");
                compared += 1;
            }
        }
        assert!(compared > 30);
        assert!(compact.stats.interpolated_frames > 0);
        assert_eq!(compact.stats.backend, 2);
        assert_eq!(float.stats.backend, 2);
        assert_eq!(compact.stats.bypass_reason, 0);
        assert_eq!(float.stats.bypass_reason, 0);
    }

}

#[cfg(test)]
mod adaptive_tests {
    use super::*;
    fn config(preset: u32) -> Config {
        Config {
            abi: AMBIENTSVP_ABI, width: 96, height: 96,
            source_num: 24, source_den: 1, target_num: 60, target_den: 1,
            preset, backend: 1, threads: 2, artifacts: 0, scene: 0,
            algo: 0, block: 0, overlap: 0, pel: 0, search: 0,
        }
    }

    #[test]
    fn direct_fill_validates_before_callback_and_matches_normal_push() {
        let mut direct = AmbientSvp::new(config(1)).unwrap();
        let mut normal = AmbientSvp::new(config(1)).unwrap();
        let mut out_a = vec![0; direct.frame_len()];
        let mut out_b = out_a.clone();
        let len = out_a.len();
        assert_eq!(direct.push_filled(100, |_| Err(-1)), Err(-1));
        assert!(direct.origin.is_none());
        for n in 0..12 {
            let pts = n * 1_000_000 / 24;
            let data = vec![80 + n as u8; len];
            direct.push_filled(pts, |out| { out.copy_from_slice(&data); Ok(()) }).unwrap();
            normal.push(&data, pts).unwrap();
            assert_eq!(direct.push_filled(pts + 9_000_000, |_| panic!("invalid timing called writer")), Err(-3));
            loop {
                let a = direct.receive(&mut out_a, false).unwrap();
                let b = normal.receive(&mut out_b, false).unwrap();
                assert_eq!(a,b);
                if a.is_none() { break; }
                assert_eq!(out_a,out_b);
            }
        }
        loop {
            let a = direct.receive(&mut out_a, true).unwrap();
            let b = normal.receive(&mut out_b, true).unwrap();
            assert_eq!(a,b);
            if a.is_none() { break; }
            assert_eq!(out_a,out_b);
        }
        assert_eq!(direct.push_filled(0, |_| panic!("EOF called writer")), Err(-1));
    }

    #[test]
    fn adaptive_levels_preserve_header_and_stop_at_a_bounded_floor() {
        let mut engine = AmbientSvp::new(config(ADAPTIVE_PRESET)).unwrap();
        let header = engine.analyser.vector_header();
        for level in 1..=MAX_ADAPTIVE_LEVEL {
            assert!(engine.reduce_analysis_effort());
            assert_eq!(engine.adaptive_level, level);
            assert_eq!(header, engine.analyser.vector_header());
        }
        assert!(!engine.reduce_analysis_effort());
        engine.reset().unwrap();
        assert_eq!(engine.adaptive_level, MAX_ADAPTIVE_LEVEL);
        engine.bypass(1);
        assert!(!engine.reduce_analysis_effort());
        let mut fixed = AmbientSvp::new(config(1)).unwrap();
        assert!(!fixed.reduce_analysis_effort());
    }

    #[test]
    fn adaptive_analysis_changes_keep_output_timeline_and_reset() {
        let mut engine = AmbientSvp::new(config(ADAPTIVE_PRESET)).unwrap();
        let mut out = vec![0; engine.frame_len()];
        for origin in [0, 3_000_000] {
            engine.reset().unwrap();
            let mut pts = Vec::new();
            for n in 0..12 {
                let mut input = vec![128; engine.frame_len()];
                for y in 0..96 {
                    for x in 0..96 {
                        input[y*96+x] = if (x + 96 - n*2 % 96) % 96 < 32 { 180 } else { 40 };
                    }
                }
                if origin == 0 && (n == 4 || n == 8) { assert!(engine.reduce_analysis_effort()); }
                engine.push(&input, origin + n as i64 * 1_000_000 / 24).unwrap();
                while let Some(p) = engine.receive(&mut out, false).unwrap() { pts.push(p); }
            }
            while let Some(p) = engine.receive(&mut out, true).unwrap() { pts.push(p); }
            assert_eq!(pts.len(), 30);
            for (n,p) in pts.iter().enumerate() { assert_eq!(*p, origin + n as i64 * 1_000_000 / 60); }
            assert_eq!(engine.stats.bypass_reason, 0);
        }
    }
}
