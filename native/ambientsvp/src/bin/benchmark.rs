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
    let c = Config {
        abi: 1,
        width: w,
        height: h,
        source_num: 24,
        source_den: 1,
        target_num: 60,
        target_den: 1,
        preset: 1,
        backend,
        threads: 0,
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
        engine.push(&input, n as i64 * 1_000_000 / 24).unwrap();
        while engine.receive(&mut output, false).unwrap().is_some() {
            checksum = checksum.wrapping_add(output.iter().map(|x| *x as u64).sum::<u64>());
        }
    }
    while engine.receive(&mut output, true).unwrap().is_some() {
        checksum = checksum.wrapping_add(output.iter().map(|x| *x as u64).sum::<u64>());
    }
    println!(
        "{w}x{h} 24->60 wall_ms={} checksum={checksum} {:?}",
        start.elapsed().as_millis(),
        engine.stats()
    );
}
