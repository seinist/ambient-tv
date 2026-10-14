use svpflow_core::{
    metadata::{VectorRecord, vector_data},
    smooth::{FieldShape, VectorField},
    smooth_engine::Output,
};
use svpflow_wasm::Smoother;
use svpflow1_vs::{Analyser, SuperBuilder};

#[test]
fn portable_analysis_produces_renderable_motion() {
    let (w, h) = (128, 96);
    let builder = SuperBuilder::new(w, h, 1).unwrap();
    let analyser = Analyser::new(&builder, 32, 32, 0, 3).unwrap();
    let header = analyser.vector_header();
    let VectorRecord::Ready(meta) = vector_data(
        &header
            .iter()
            .flat_map(|v| v.to_le_bytes())
            .collect::<Vec<_>>(),
    ) else {
        panic!("header");
    };
    let shape = FieldShape::from_data(&meta);
    let mut field = VectorField::new(shape);
    let mut smoother = Smoother::new(
        w,
        h,
        24,
        1,
        100,
        "{rate:{abs:true,num:60,den:1},algo:13,scene:{mode:0,limits:{zero:0,blocks:50}},gpu:false}",
        &header,
    )
    .unwrap();
    let mut previous = None;
    for n in 0..8 {
        let mut frame = vec![128; (w * h * 3 / 2) as usize];
        for y in 0..h {
            for x in 0..w {
                frame[(y * w + x) as usize] =
                    if x >= 32 + n * 2 && x < 64 + n * 2 && y >= 32 && y < 64 {
                        200
                    } else {
                        40
                    };
            }
        }
        let current = builder.build(&frame).unwrap();
        if let Some(prev) = &previous {
            let vectors = analyser.analyse(prev, &current).unwrap();
            assert!(
                field.update(&vectors),
                "Invalid packed vectors: header {header:?}, lengths {} / grid {}",
                vectors.len(),
                shape.blocks()
            );
            smoother.set_vectors(n - 1, &vectors);
        }
        smoother.set_source(n, &frame).unwrap();
        previous = Some(current);
    }
    let mut jobs = 0;
    for n in 0..15 {
        if let Output::Gpu(_) = smoother.prepare_gpu(n) {
            jobs += 1;
        }
    }
    assert!(jobs > 3, "Only {jobs} real motion jobs");
}
