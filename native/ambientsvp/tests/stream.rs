use ambientsvp::{Config, AmbientSvp};

fn config() -> Config {
    Config {
        abi: 2,
        width: 64,
        height: 64,
        source_num: 24,
        source_den: 1,
        target_num: 60,
        target_den: 1,
        preset: 1,
        backend: 1,
        threads: 2,
        artifacts: 0,
        scene: 0,
        algo: 0,
        block: 0,
        overlap: 0,
        pel: 0,
        search: 0,
    }
}

fn frame(n: usize) -> Vec<u8> {
    let mut data = vec![128; 64 * 64 * 3 / 2];
    for y in 0..64 {
        for x in 0..64 {
            data[y * 64 + x] = if (x + 64 - n % 64) % 64 < 24 { 200 } else { 40 };
        }
    }
    data
}

#[test]
fn rational_timestamps_and_bounded_queue() {
    let mut c = config();
    c.source_num = 24000;
    c.source_den = 1001;
    c.target_num = 60000;
    c.target_den = 1001;
    let mut s = AmbientSvp::new(c).unwrap();
    let mut out = vec![0; s.frame_len()];
    let mut pts = Vec::new();
    for n in 0..72 {
        s.push(&frame(n), 5_000_000 + n as i64 * 1_000_000 * 1001 / 24000)
            .unwrap();
        while let Some(p) = s.receive(&mut out, false).unwrap() {
            pts.push(p);
        }
        assert!(s.stats().queued_frames <= 8);
    }
    while let Some(p) = s.receive(&mut out, true).unwrap() {
        pts.push(p);
    }
    assert_eq!(pts.len(), 180);
    for (n, p) in pts.iter().enumerate() {
        assert_eq!(*p, 5_000_000 + n as i64 * 1_000_000 * 1001 / 60000);
    }
    assert!(s.stats().interpolated_frames > 50);
}

#[test]
fn constant_image_is_preserved_and_short_tail_is_drained() {
    let mut s = AmbientSvp::new(config()).unwrap();
    let input = vec![128; s.frame_len()];
    let mut out = input.clone();
    let mut count = 0;
    for n in 0..4 {
        s.push(&input, n * 1_000_000 / 24).unwrap();
        while s.receive(&mut out, false).unwrap().is_some() {
            assert_eq!(out, input);
            count += 1;
        }
    }
    while s.receive(&mut out, true).unwrap().is_some() {
        assert_eq!(out, input);
        count += 1;
    }
    assert_eq!(count, 10);
    assert!(s.receive(&mut out, true).unwrap().is_none());
}

#[test]
fn discontinuity_requires_reset_and_backpressure_is_explicit() {
    let mut s = AmbientSvp::new(config()).unwrap();
    let input = frame(0);
    s.push(&input, 0).unwrap();
    assert_eq!(s.push(&input, 3_000_000), Err(-3));
    for n in 1..10 {
        s.push(&input, n * 1_000_000 / 24).unwrap();
    }
    assert_eq!(s.push(&input, 10 * 1_000_000 / 24), Err(-2));
}

#[test]
fn reset_starts_a_new_timeline_without_clearing_safety_or_counters() {
    let mut s = AmbientSvp::new(config()).unwrap();
    let input = frame(0);
    let mut out = input.clone();
    s.push(&input, 0).unwrap();
    s.reset().unwrap();
    s.push(&input, 9_000_000).unwrap();
    assert_eq!(s.receive(&mut out, false).unwrap(), Some(9_000_000));
    assert_eq!(s.stats().input_frames, 2);
    s.bypass(1);
    s.reset().unwrap();
    assert_eq!(s.stats().bypass_reason, 1);
    assert_eq!(s.stats().queued_frames, 0);
    s.push(&input, 20_000_000).unwrap();
    assert_eq!(s.receive(&mut out, false).unwrap(), Some(20_000_000));
    assert_eq!(s.stats().input_frames, 3);
}

#[test]
fn bypass_preserves_remaining_source_timestamps_without_duplicates() {
    let mut s = AmbientSvp::new(config()).unwrap();
    let mut out = frame(0);
    let mut last = -1;
    for n in 0..12 {
        if n == 7 {
            s.bypass(3);
        }
        s.push(&frame(n), n as i64 * 1_000_000 / 24).unwrap();
        while let Some(pts) = s.receive(&mut out, false).unwrap() {
            assert!(pts > last);
            last = pts;
        }
    }
    assert_eq!(s.stats().bypass_reason, 3);
    assert_eq!(last, 11 * 1_000_000 / 24);
}

#[test]
fn invalid_formats_rates_and_lengths_are_rejected() {
    let mut c = config();
    c.width = 63;
    assert!(AmbientSvp::new(c).is_err());
    c = config();
    c.source_den = 0;
    assert!(AmbientSvp::new(c).is_err());
    c = config();
    c.target_num = 24;
    assert!(AmbientSvp::new(c).is_err());
    let mut s = AmbientSvp::new(config()).unwrap();
    assert_eq!(s.push(&[0; 12], 0), Err(-1));
}

#[test]
fn ffi_gate_blocks_creation_and_stops_an_existing_stream() {
    use ambientsvp::*;
    unsafe {
        ambient_svp_set_enabled(0);
        assert!(ambient_svp_create(&config()).is_null());
        ambient_svp_set_enabled(1);
        let engine = ambient_svp_create(&config());
        assert!(!engine.is_null());
        let input = frame(0);
        let mut output = input.clone();
        let mut pts = 0;
        assert_eq!(ambient_svp_push(engine, input.as_ptr(), input.len(), 0), 0);
        ambient_svp_set_enabled(0);
        assert_eq!(
            ambient_svp_receive(engine, output.as_mut_ptr(), output.len(), &mut pts, 0),
            1
        );
        let mut stats = Stats::default();
        ambient_svp_stats(engine, &mut stats);
        assert_eq!(stats.bypass_reason, 2);
        assert_eq!(stats.interpolated_frames, 0);
        assert_eq!(output, input);
        ambient_svp_destroy(engine);
        assert!(ambient_svp_create(&config()).is_null());
    }
}

#[test]
fn motion_produces_new_pixels_not_only_duplicate_timestamps() {
    let mut s = AmbientSvp::new(config()).unwrap();
    let mut out = frame(0);
    let mut outputs = Vec::new();
    let inputs: Vec<_> = (0..24).map(|n| frame(n * 2)).collect();
    for (n, input) in inputs.iter().enumerate() {
        s.push(input, n as i64 * 1_000_000 / 24).unwrap();
        while s.receive(&mut out, false).unwrap().is_some() {
            outputs.push(out.clone());
        }
    }
    assert!(outputs.iter().any(|o| !inputs.contains(o)));
    assert!(s.stats().interpolated_frames > 0);
}

fn moving_edge_midpoint(backend: u32) {
    let mut c = config();
    c.backend = backend;
    c.target_num = 48;
    let mut s = AmbientSvp::new(c).unwrap();
    let mut out = frame(0);
    let mut found = false;
    for n in 0..8 {
        s.push(&frame(n * 4), n as i64 * 1_000_000 / 24).unwrap();
        while let Some(pts) = s.receive(&mut out, false).unwrap() {
            if pts == 62_500 {
                // halfway between x=4 and x=8
                let row = &out[32 * 64..33 * 64];
                let weight: f64 = row.iter().map(|v| v.saturating_sub(40) as f64).sum();
                let centroid: f64 = row
                    .iter()
                    .enumerate()
                    .map(|(x, v)| x as f64 * v.saturating_sub(40) as f64)
                    .sum::<f64>()
                    / weight;
                assert!(
                    (centroid - 17.5).abs() < 1.2,
                    "Motion midpoint should move right to 17.5, got {centroid}"
                );
                // A blend would have a half-bright vertical border, not a moved edge.
                assert!(
                    row[7] > 170 && row[28] > 170,
                    "Expected compensated edge, not crossfade: {row:?}"
                );
                found = true;
            }
        }
    }
    assert!(found);
    assert_eq!(s.stats().backend, backend);
}

#[test]
fn cpu_moves_an_edge_to_the_temporal_midpoint() {
    moving_edge_midpoint(1);
}

#[test]
#[ignore = "requires a real Vulkan device; run explicitly for device validation"]
fn vulkan_synthesizes_without_cpu_fallback() {
    moving_edge_midpoint(2);
    let mut c = config();
    c.backend = 2;
    let mut s = AmbientSvp::new(c).unwrap();
    let mut out = frame(0);
    for n in 0..24 {
        s.push(&frame(n * 2), n as i64 * 1_000_000 / 24).unwrap();
        while s.receive(&mut out, false).unwrap().is_some() {}
    }
    assert_eq!(s.stats().backend, 2);
    assert!(s.stats().interpolated_frames > 0);
}

#[test]
fn custom_algorithms_stream_with_correct_lookahead_and_reset() {
    custom_stream(1);
}

#[cfg(feature = "vulkan")]
#[test]
#[ignore = "requires hardware Vulkan"]
fn custom_algorithms_vulkan() {
    custom_stream(2);
}

fn custom_stream(backend: u32) {
    for algo in [13, 21, 23] {
        for artifacts in [0, 1, 2, 3] {
            let mut c = config();
            c.backend = backend;
            c.algo = algo;
            c.artifacts = artifacts;
            c.scene = 1;
            c.block = 16;
            c.overlap = 3;
            c.pel = 2;
            c.search = 1;
            let mut s = AmbientSvp::new(c).unwrap();
            let mut out = vec![0; s.frame_len()];
            for origin in [0, 10_000_000] {
                s.reset().unwrap();
                let mut pts = Vec::new();
                for n in 0..12 {
                    s.push(&frame(n * 2), origin + n as i64 * 1_000_000 / 24).unwrap();
                    while let Some(p) = s.receive(&mut out, false).unwrap() { pts.push(p); }
                }
                while let Some(p) = s.receive(&mut out, true).unwrap() { pts.push(p); }
                assert_eq!(pts.len(), 30, "algo={algo}, artifacts={artifacts}");
                for (n, p) in pts.iter().enumerate() { assert_eq!(*p, origin + n as i64 * 1_000_000 / 60); }
                assert_eq!(s.stats().bypass_reason, 0);
                assert_eq!(s.stats().backend, backend);
                assert!(s.stats().interpolated_frames > 0, "algo={algo}, artifacts={artifacts}");
            }
        }
    }
}

#[test]
fn invalid_overrides_and_old_abi_are_rejected() {
    for field in 0..8 {
        let mut c = config();
        match field { 0 => c.abi = 1, 1 => c.algo = 99, 2 => c.block = 7,
            3 => c.overlap = 5, 4 => c.pel = 3, 5 => c.search = 4,
            6 => c.artifacts = 4, _ => c.scene = 4 }
        assert!(AmbientSvp::new(c).is_err());
    }
}

#[test]
fn small_blocks_with_eighth_overlap_still_render_on_cpu() {
    let mut c = config();
    c.block = 8;
    c.overlap = 2;
    let mut s = AmbientSvp::new(c).unwrap();
    let mut out = vec![0; s.frame_len()];
    let mut count = 0;
    for n in 0..8 {
        s.push(&frame(n * 2), n as i64 * 1_000_000 / 24).unwrap();
        while s.receive(&mut out, false).unwrap().is_some() { count += 1; }
    }
    while s.receive(&mut out, true).unwrap().is_some() { count += 1; }
    assert_eq!(count, 20);
    assert_eq!(s.stats().bypass_reason, 0);
    assert!(s.stats().interpolated_frames > 0);
}
