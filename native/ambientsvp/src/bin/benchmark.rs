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
    for cycle in 0..cycles {
    let c = Config {
        abi: 2,
        width: w,
        height: h,
        source_num: source_fps,
        source_den: 1,
        target_num: 60,
        target_den: 1,
        preset: 1,
        backend,
        threads: 0,
        artifacts: 0,
        scene: 0,
        algo: if cycles > 1 { [13, 21, 23][cycle as usize % 3] } else { 0 },
        block: 0,
        overlap: 0,
        pel: 0,
        search: 0,
    };
    let mut engine = AmbientSvp::new(c).expect("engine initialization failed");
    let mut input = vec![128; engine.frame_len()];
    let mut output = input.clone();
    let start = Instant::now();
    let mut checksum = 0u64;
    for n in 0..count {
        for y in 0..h {
            for x in 0..w {
                input[(y * w + x) as usize] = (((x + n * 3) / 12 ^ y / 12) % 2 * 160 + 40) as u8;
            }
        }
        engine.push(&input, n as i64 * 1_000_000 / source_fps as i64).unwrap();
        while engine.receive(&mut output, false).unwrap().is_some() {
            checksum = checksum.wrapping_add(output.iter().map(|x| *x as u64).sum::<u64>());
        }
    }
    while engine.receive(&mut output, true).unwrap().is_some() {
        checksum = checksum.wrapping_add(output.iter().map(|x| *x as u64).sum::<u64>());
    }
    println!(
        "cycle={cycle} {w}x{h} {source_fps}->60 wall_ms={} checksum={checksum} {:?}",
        start.elapsed().as_millis(),
        engine.stats()
    );
    if a.iter().any(|s| s == "--verify") {
        assert_eq!(engine.stats().backend, backend);
        assert!(engine.stats().interpolated_frames > 0, "no novel output frames");
        assert_eq!(engine.stats().bypass_reason, 0, "unexpected bypass");
    }
    }
}
