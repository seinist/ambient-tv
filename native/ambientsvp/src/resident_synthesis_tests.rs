//! Actual GPU coverage and synthesis-equation comparisons against the
//! pinned Open-SVPFlow mask implementation.
use super::*;
use wgpu::util::DeviceExt;
use svpflow_core::smooth::{FieldShape,VectorField};

fn device()->(wgpu::Device,wgpu::Queue){
    let instance=wgpu::Instance::new(&wgpu::InstanceDescriptor{
        backends:wgpu::Backends::VULKAN,..Default::default()
    });
    let adapter=pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()))
        .expect("Vulkan adapter required");
    pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor{
        label:Some("SVP native masks reference"),required_features:wgpu::Features::empty(),
        required_limits:wgpu::Limits::default().using_resolution(adapter.limits()),
        memory_hints:wgpu::MemoryHints::MemoryUsage,
    },None)).unwrap()
}
fn bytes(words:&[i32])->Vec<u8>{words.iter().flat_map(|x|x.to_le_bytes()).collect()}
fn words(data:&[u8])->Vec<i32>{data.chunks_exact(4).map(|p|i32::from_le_bytes(p.try_into().unwrap())).collect()}
fn read(device:&wgpu::Device,queue:&wgpu::Queue,mut encoder:wgpu::CommandEncoder,
    source:&wgpu::Buffer,size:u64)->Vec<i32>{
    let out=device.create_buffer(&wgpu::BufferDescriptor{label:None,size,
        usage:wgpu::BufferUsages::MAP_READ|wgpu::BufferUsages::COPY_DST,mapped_at_creation:false});
    encoder.copy_buffer_to_buffer(source,0,&out,0,size);
    queue.submit([encoder.finish()]);
    let slice=out.slice(..);
    let (tx,rx)=std::sync::mpsc::channel();
    slice.map_async(wgpu::MapMode::Read,move |r|{let _=tx.send(r);});
    let _=device.poll(wgpu::Maintain::Wait);
    rx.recv_timeout(std::time::Duration::from_secs(30)).unwrap().unwrap();
    let data=slice.get_mapped_range();let result=words(&data);
    drop(data);out.unmap();result
}
fn pipeline(device:&wgpu::Device,module:&wgpu::ShaderModule,layout:Option<&wgpu::PipelineLayout>,entry:&str)->wgpu::ComputePipeline{
    device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor{
        label:None,layout,module,entry_point:Some(entry),compilation_options:Default::default(),cache:None
    })
}
#[test]
#[ignore="requires Vulkan, including Mesa software Vulkan"]
fn coverage_and_sad_masks_match_upstream(){
    let (device,queue)=device();
    let layout=device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor{
        label:None,entries:&[
            wgpu::BindGroupLayoutEntry{binding:6,visibility:wgpu::ShaderStages::COMPUTE,
                ty:wgpu::BindingType::Buffer{ty:wgpu::BufferBindingType::Storage{read_only:false},
                    has_dynamic_offset:false,min_binding_size:None},count:None},
            wgpu::BindGroupLayoutEntry{binding:7,visibility:wgpu::ShaderStages::COMPUTE,
                ty:wgpu::BindingType::Buffer{ty:wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset:false,min_binding_size:None},count:None},
        ]});
    let pipeline_layout=device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor{
        label:None,bind_group_layouts:&[&layout],push_constant_ranges:&[]});
    let (gw,gh)=(7,5);let n=(gw*gh) as usize;
    for (block,overlap) in [(8,0),(16,4),(32,16)]{
        for pel in [1,2,4]{
            let step=block-overlap;
            let shape=FieldShape{flags:3,block_w:block,block_h:block,pel,
                width:gw*step+overlap,height:gh*step+overlap,overlap_x:overlap,
                overlap_y:overlap,grid_w:gw,grid_h:gh,selector:0};
            let mut field=VectorField::new(shape);
            let mut data=vec![0i32;(10*n+3)*4];
            // Exact upstream packet framing: B->A first, A->B second.
            let mut payload=vec![0i32;18+4*n];
            payload[0]=16;payload[1]=160;payload[2]=3;
            payload[16]=(2*n+1) as i32;payload[17+2*n]=(2*n+1) as i32;
            for dir in 0..2{
                for i in 0..n{
                    let j=dir*n+i;
                    let x=if i%5==0{0}else{(i as i32*17%53-26)*pel+(i as i32%pel)};
                    let y=if i%7==0{0}else{(i as i32*13%35-17)*pel-(i as i32%pel)};
                    let sign=if dir==0{1}else{-1};
                    let sad=((i*173+dir*291)%(block*block*80) as usize) as i32;
                    data[4*j..4*j+4].copy_from_slice(&[x*sign,y*sign,0,pel]);
                    data[4*(4*n+3+j)]=sad;
                    let base=if dir==1{17}else{18+2*n};
                    payload[base+2*i]=((x*sign)<<16)|((y*sign)&65535);
                    payload[base+2*i+1]=sad;
                }
            }
            assert!(field.update(&bytes(&payload)));
            for artifacts in [1,2,3]{
                let t=ResidentTuning{block:block as u32,overlap:overlap as u32,pel:pel as u32,
                    radius:1,satd:0,chroma:1,lambda:64,pnew:50,scene:50,artifacts};
                let module=device.create_shader_module(wgpu::ShaderModuleDescriptor{
                    label:None,source:wgpu::ShaderSource::Wgsl(mask_source(0,t).into())});
                let pipelines=["clear","scatter","finish"].map(|entry|pipeline(&device,&module,Some(&pipeline_layout),entry));
                let source=device.create_buffer_init(&wgpu::util::BufferInitDescriptor{
                    label:None,contents:&bytes(&data),usage:wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_SRC});
                for phase in [0,31,64,127,128,201,256]{
                    let uniform=device.create_buffer_init(&wgpu::util::BufferInitDescriptor{
                        label:None,contents:&bytes(&[gw,gh,phase,0]),usage:wgpu::BufferUsages::UNIFORM});
                    let bind=device.create_bind_group(&wgpu::BindGroupDescriptor{label:None,layout:&layout,entries:&[
                        wgpu::BindGroupEntry{binding:6,resource:source.as_entire_binding()},
                        wgpu::BindGroupEntry{binding:7,resource:uniform.as_entire_binding()},
                    ]});
                    let mut encoder=device.create_command_encoder(&Default::default());
                    for (p,groups) in pipelines.iter().zip([n.div_ceil(64), (2*n).div_ceil(64), n.div_ceil(64)]){
                        let mut pass=encoder.begin_compute_pass(&Default::default());
                        pass.set_pipeline(p);pass.set_bind_group(0,&bind,&[]);pass.dispatch_workgroups(groups as u32,1,1);
                    }
                    let actual=read(&device,&queue,encoder,&source,(data.len()*4) as u64);
                    let mut expected=[vec![0u8;n],vec![0u8;n],vec![0u8;n],vec![0u8;n]];
                    field.cover_mask(&mut Vec::new(),true,&mut expected[1],100,phase,gw,gh);
                    field.cover_mask(&mut Vec::new(),false,&mut expected[2],100,256-phase,gw,gh);
                    if artifacts>1{
                        let scale=if artifacts==2{0.005}else{0.01};
                        field.sad_mask(false,&mut expected[0],scale,1.0,gw,gh);
                        field.sad_mask(true,&mut expected[3],scale,1.0,gw,gh);
                    }
                    for j in 0..n{
                        for c in 0..4{
                            assert_eq!(actual[4*(7*n+3+j)+c],i32::from(expected[c][j]),
                                "block={block} overlap={overlap} pel={pel} artifacts={artifacts} phase={phase} cell={j} channel={c}");
                        }
                    }
                }
            }
        }
    }
}

#[test]
#[ignore="requires Vulkan, including Mesa software Vulkan"]
fn all_synthesis_equations_match_reference(){
    let (device,queue)=device();
    let t=ResidentTuning{block:16,overlap:4,pel:4,radius:1,satd:0,chroma:1,lambda:64,pnew:50,scene:50,artifacts:3};
    // Use the exact production family code, but probe just its equations
    // with independent fixed samples to isolate algorithm choice from search.
    let mut source=tuned_synthesis(SYNTHESIS,t,0).unwrap();
    source.push_str(r#"
@group(1) @binding(0) var<storage,read_write> results:array<vec4f>;
@compute @workgroup_size(1)
fn probe(@builtin(global_invocation_id) id:vec3u){
    var algorithms=array<u32,7>(1u,2u,11u,13u,21u,22u,23u);
    let algo=algorithms[id.x];
    let time=f32(id.y)/256.0;
    let mask=vec4f(0.13,0.77,0.21,0.44);
    let m=FamilyMotion(vec2f(0),vec2f(0),vec2f(0),vec2f(0),mask,time,false);
    results[id.y*7u+id.x]=family_blend(vec4f(0.15),vec4f(0.83),vec4f(0.62),vec4f(0.29),
        vec4f(0.49),vec4f(0.35),m,algo);
}
"#);
    let module=device.create_shader_module(wgpu::ShaderModuleDescriptor{
        label:None,source:wgpu::ShaderSource::Wgsl(source.into())});
    let p=pipeline(&device,&module,None,"probe");
    let size=7*257*16;
    let output=device.create_buffer(&wgpu::BufferDescriptor{label:None,size,
        usage:wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_SRC,mapped_at_creation:false});
    let empty=device.create_bind_group(&wgpu::BindGroupDescriptor{label:None,layout:&p.get_bind_group_layout(0),entries:&[]});
    let bind=device.create_bind_group(&wgpu::BindGroupDescriptor{label:None,layout:&p.get_bind_group_layout(1),entries:&[
        wgpu::BindGroupEntry{binding:0,resource:output.as_entire_binding()}]});
    let mut encoder=device.create_command_encoder(&Default::default());
    {
        let mut pass=encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&p);pass.set_bind_group(0,&empty,&[]);pass.set_bind_group(1,&bind,&[]);
        pass.dispatch_workgroups(7,257,1);
    }
    let got=read(&device,&queue,encoder,&output,size);
    let lerp=|a:f32,b:f32,t:f32|a+(b-a)*t;
    let med=|a:f32,b:f32,c:f32|a.min(b).max(a.max(b).min(c));
    let (f,b,f0,b0,ff,bb)=(0.15,0.83,0.62,0.29,0.49,0.35);
    let (x,y,z,w)=(0.13,0.77,0.21,0.44);
    for phase in 0..=256{
        let t=phase as f32/256.0;
        let sad_blend=if phase>126{1.0-0.7*(1.0-t)}else{t*0.7};
        for (i,algo) in [1,2,11,13,21,22,23].into_iter().enumerate(){
            let q=match algo{
                1=>lerp(b,b0,x),
                2 if phase>127=>lerp(b,b0,x),
                2=>lerp(f,f0,w),
                _=>{
                    let value=match algo{
                        11=>lerp(f,b,t),
                        13=>med(f,b,lerp(f0,b0,sad_blend)),
                        21=>lerp(lerp(f,b,y),lerp(b,f,z),t),
                        22=>med(lerp(f,b,y),lerp(b,f,z),lerp(f0,b0,t)),
                        23=>lerp(lerp(f,med(b,bb,f),y),lerp(b,med(b,ff,f),z),t),
                        _=>unreachable!()
                    };
                    lerp(value,lerp(f0,b0,sad_blend),f32::max(x,w))
                }
            };
            for c in 0..4{
                let actual=f32::from_bits(got[(phase*7+i)*4+c] as u32);
                assert!((actual-q).abs()<2e-6,"algorithm {algo} phase {phase}: {actual} != {q}");
            }
        }
    }
}
