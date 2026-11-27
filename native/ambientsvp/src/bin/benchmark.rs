use ambientsvp::{Config, AmbientSvp};
use std::time::Instant;

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
    let no_checksum = a.iter().any(|s| s == "--no-checksum");
    let realtime = a.iter().any(|s| s == "--require-realtime");
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
        algo: if cycles > 1 { [13, 21, 23][cycle as usize % 3] } else { 0 },
        block: 0,
        overlap: 0,
        pel: 0,
        search: 0,
    };
    let init = Instant::now();
    let mut engine = AmbientSvp::new(c).expect("engine initialization failed");
    let init_ms = init.elapsed().as_secs_f64() * 1000.0;
    let mut input = vec![128; engine.frame_len()];
    let mut output = input.clone();
    let start = Instant::now();
    let mut checksum = 0u64;
    let mut processing_us = 0u128;
    for n in 0..count {
        for y in 0..h {
            for x in 0..w {
                input[(y * w + x) as usize] = (((x + n * 3) / 12 ^ y / 12) % 2 * 160 + 40) as u8;
            }
        }
        let processing = Instant::now();
        engine.push(&input, n as i64 * 1_000_000 / source_fps as i64).unwrap();
        while engine.receive(&mut output, false).unwrap().is_some() {
            if !no_checksum {
                checksum = checksum.wrapping_add(output.iter().map(|x| *x as u64).sum::<u64>());
            }
        }
        processing_us += processing.elapsed().as_micros();
    }
    let drain = Instant::now();
    while engine.receive(&mut output, true).unwrap().is_some() {
        if !no_checksum {
            checksum = checksum.wrapping_add(output.iter().map(|x| *x as u64).sum::<u64>());
        }
    }
    processing_us += drain.elapsed().as_micros();
    let stats = engine.stats();
    let compute_ms = processing_us as f64 / 1000.0;
    let input_fps = count as f64 * 1_000_000.0 / processing_us.max(1) as f64;
    let novel_fps = stats.interpolated_frames as f64 * 1_000_000.0 / processing_us.max(1) as f64;
    println!(
        "cycle={cycle} {w}x{h} {source_fps}->{target_fps} threads={threads} preset={preset} init_ms={init_ms:.2} wall_ms={} processing_ms={compute_ms:.2} input_fps={input_fps:.2} novel_fps={novel_fps:.2} checksum={checksum} {:?}",
        start.elapsed().as_millis(), stats
    );
    if realtime {
        assert!(no_checksum, "--require-realtime requires --no-checksum");
        assert_eq!(stats.bypass_reason, 0, "protective bypass is not real-time SVP");
        assert!(stats.interpolated_frames > 0, "no interpolation");
        assert!(input_fps >= source_fps as f64, "below source cadence");
    }
    if a.iter().any(|s| s == "--verify") {
        assert_eq!(engine.stats().backend, backend);
        assert!(engine.stats().interpolated_frames > 0, "no novel output frames");
        assert_eq!(engine.stats().bypass_reason, 0, "unexpected bypass");
    }
    }
}
