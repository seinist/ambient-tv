use ambientsvp::{Config, AmbientSvp};
use std::time::Instant;

#[path = "../benchmark_quality.rs"]
mod benchmark_quality;
use benchmark_quality::{Quality,translation_pixel};

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let arg = |i: usize, default: u32| {
        a.get(i)
            .and_then(|s| s.parse::<u32>().ok())
            .unwrap_or(default)
    };
    let (w, h, count, backend) = (arg(1, 640), arg(2, 360), arg(3, 48), arg(4, 1));
    let source_fps = arg(5, 24);
    let cycles = arg(6, 1);
    // Keep the existing six positional arguments. Optional named settings
    // allow controlled A/B comparisons of workers and profiles.
    let option = |name: &str, default: u32| {
        a.iter().find_map(|s| s.strip_prefix(name).and_then(|v| v.parse().ok())).unwrap_or(default)
    };
    let threads = option("--threads=", 0);
    let preset = option("--preset=", 1);
    let target_fps = option("--target=", 60);
    let algo = option("--algo=", 0);
    let min_novel_percent = option("--min-novel-percent=", 50);
    assert!(matches!(algo, 0 | 13 | 21 | 23) && min_novel_percent <= 100);
    let no_checksum = a.iter().any(|s| s == "--no-checksum");
    let realtime = a.iter().any(|s| s == "--require-realtime");
    let experimental_motion = a.iter().any(|s| s == "--experimental-motion");
    let quality_translation=a.iter().any(|s|s=="--quality-translation");
    assert!(!quality_translation || (target_fps==source_fps.saturating_mul(2) && count>=3 && w>=64 && h>=64 && !realtime),
        "quality translation requires 2x cadence, >=3 frames, >=64x64 and a separate non-realtime run");
    assert!(!experimental_motion || (backend==2 && count>=2), "experimental motion requires explicit Vulkan backend and at least two frames");
    assert!(count > 0 && cycles > 0, "frames and cycles must be positive");
    for cycle in 0..cycles {
    let c = Config {
        abi: 2,
        width: w,
        height: h,
        source_num: source_fps,
        source_den: 1,
        target_num: target_fps,
        target_den: 1,
        preset,
        backend,
        threads,
        artifacts: 0,
        scene: 0,
        algo: if algo != 0 { algo } else if cycles > 1 { [13, 21, 23][cycle as usize % 3] } else { 0 },
        block: 0,
        overlap: 0,
        pel: 0,
        search: 0,
    };
    let init = Instant::now();
    let mut engine = AmbientSvp::new(c).expect("engine initialization failed");
    if experimental_motion {
        engine.enable_experimental_motion().expect("unsupported experimental motion configuration");
    }
    let init_ms = init.elapsed().as_secs_f64() * 1000.0;
    let mut input = vec![128; engine.frame_len()];
    let mut output = input.clone();
    let start = Instant::now();
    let mut checksum = 0u64;
    let mut processing_us = 0u128;
    let mut timings = Vec::with_capacity(count as usize);
    let mut outputs = 0u64;
    let mut last_pts = None;
    let mut monotonic = true;
    let mut quality=Quality::default();
    let mut quality_us=0u128;
    let mut record = |pts: i64| {
        if last_pts.is_some_and(|last| pts <= last) { monotonic = false; }
        last_pts = Some(pts);
        let index=outputs;
        outputs += 1;
        index
    };
    for n in 0..count {
        for y in 0..h {
            for x in 0..w {
                input[(y * w + x) as usize] = if quality_translation {translation_pixel(w,x,y,u64::from(n)*4)}
                    else {(((x + n * 3) / 12 ^ y / 12) % 2 * 160 + 40) as u8};
            }
        }
        let processing = Instant::now();
        engine.push(&input, n as i64 * 1_000_000 / source_fps as i64).unwrap();
        while let Some(pts) = engine.receive(&mut output, false).unwrap() {
            let index=record(pts);
            if quality_translation {
                let t=Instant::now();
                quality.observe(&output,w,h,index,count);
                quality_us+=t.elapsed().as_micros();
            }
            if !no_checksum {
                checksum = checksum.wrapping_add(output.iter().map(|x| *x as u64).sum::<u64>());
            }
        }
        let elapsed = processing.elapsed().as_micros();
        timings.push(elapsed);
        processing_us += elapsed;
    }
    let drain = Instant::now();
    while let Some(pts) = engine.receive(&mut output, true).unwrap() {
        let index=record(pts);
        if quality_translation {
            let t=Instant::now();
            quality.observe(&output,w,h,index,count);
            quality_us+=t.elapsed().as_micros();
        }
        if !no_checksum {
            checksum = checksum.wrapping_add(output.iter().map(|x| *x as u64).sum::<u64>());
        }
    }
    processing_us += drain.elapsed().as_micros();
    let stats = engine.stats();
    timings.sort_unstable();
    let percentile = |p: usize| timings[(timings.len() * p).div_ceil(100).saturating_sub(1)] as f64 / 1000.0;
    let expected = (u64::from(count) * u64::from(target_fps)).div_ceil(u64::from(source_fps));
    let output_fps = outputs as f64 * 1_000_000.0 / processing_us.max(1) as f64;
    let compute_ms = processing_us as f64 / 1000.0;
    let input_fps = count as f64 * 1_000_000.0 / processing_us.max(1) as f64;
    let novel_fps = stats.interpolated_frames as f64 * 1_000_000.0 / processing_us.max(1) as f64;
    println!(
        "cycle={cycle} {w}x{h} {source_fps}->{target_fps} threads={threads} preset={preset} init_ms={init_ms:.2} wall_ms={} processing_ms={compute_ms:.2} input_fps={input_fps:.2} novel_fps={novel_fps:.2} checksum={checksum} {:?}",
        start.elapsed().as_millis(), stats
    );
    println!("outputs={outputs}/{expected} output_fps={output_fps:.2} push_drain_p50_ms={:.2} p95_ms={:.2} p99_ms={:.2} monotonic={monotonic}", percentile(50), percentile(95), percentile(99));
    if quality_translation {
        assert_eq!(quality.frames,u64::from(count-1),"missing halfway quality frames");
        assert!(monotonic && outputs==expected && stats.bypass_reason==0,"invalid quality run");
        if backend!=0{assert_eq!(stats.backend,backend,"quality run backend fallback");}
        println!("translation_quality frames={} pixels={} luma_psnr_db={:.3} luma_mae={:.3} nearest_source_psnr_db={:.3} quality_evaluation_ms={:.3} (timing includes quality evaluation; use a separate performance run)",
            quality.frames,quality.pixels,quality.psnr(quality.squared),
            quality.absolute as f64/quality.pixels.max(1) as f64,
            quality.psnr(quality.nearest_squared),quality_us as f64/1000.0);
    }
    if experimental_motion {
        let (pairs,failures)=engine.experimental_motion_stats();
        println!("experimental_motion=true pairs={pairs} failures={failures} quality_parity_with_upstream=unverified");
        assert_eq!(failures,0,"experimental analyser failed");
        assert_eq!(pairs,u64::from(count-1),"experimental analyser did not process all changing pairs");
        assert_eq!(stats.backend,2,"experimental session fell back to CPU synthesis");
        assert_eq!(stats.bypass_reason,0,"experimental session entered bypass");
        assert!(monotonic && outputs==expected && stats.interpolated_frames>0,"experimental output cadence/novelty failed");
    }
    if realtime {
        assert!(no_checksum, "--require-realtime requires --no-checksum");
        assert_eq!(stats.bypass_reason, 0, "protective bypass is not real-time SVP");
        assert!(monotonic && outputs == expected && outputs == stats.output_frames, "incorrect output cadence");
        if backend != 0 { assert_eq!(stats.backend, backend, "requested backend fell back"); }
        let nominal_novel = expected.saturating_sub(u64::from(count)).saturating_sub(u64::from(target_fps.div_ceil(source_fps)));
        assert!(stats.interpolated_frames > 0 && stats.interpolated_frames * 100 >= nominal_novel * u64::from(min_novel_percent), "insufficient novel pictures");
        assert!(input_fps >= source_fps as f64, "below source cadence");
    }
    if a.iter().any(|s| s == "--verify") {
        if backend != 0 { assert_eq!(engine.stats().backend, backend); }
        assert!(monotonic && outputs == expected, "incorrect output cadence");
        assert!(engine.stats().interpolated_frames > 0, "no novel output frames");
        assert_eq!(engine.stats().bypass_reason, 0, "unexpected bypass");
    }
    }
}

