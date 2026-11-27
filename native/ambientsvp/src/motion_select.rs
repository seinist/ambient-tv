//! Ordered candidate selection for one already prepared SVP search stage.
//! The caller supplies the exact predictor, initial winner, and ordered
//! candidate list. This does not generate upstream wavefront dependencies,
//! candidate coordinates, or serialized vectors.
use crate::motion::Cost;
use wgpu::util::DeviceExt;

const MAX_BATCH: usize = 65_535;
const MAX_PER_BLOCK: u32 = 4096;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Choice {
    pub dx: i32,
    pub dy: i32,
    pub sad: i32,
    pub cost: i32,
    pub raw_sad: i32,
    /// Index in the supplied candidate list, or -1 for the initial winner.
    pub winner: i32,
}

#[derive(Clone, Copy, Debug)]
pub struct Search {
    pub start: u32,
    pub count: u32,
    pub predictor: [i32; 2],
    pub lambda: i32,
    pub pnew: i32,
    pub chroma: bool,
    pub sad_gate: bool,
    pub initial: Choice,
}

#[derive(Clone, Copy, Debug)]
pub struct ScoredCandidate {
    pub dx: i32,
    pub dy: i32,
    pub metric: u32,
    /// Index of the raw-SAD cost entry for this exact luma sample.
    /// Ignored when the stage has no SAD-gated SATD.
    pub raw: u32,
}

fn motion_component(x:i32, y:i32, search:&Search) -> i32 {
    let dx=search.predictor[0].wrapping_sub(x);
    let dy=search.predictor[1].wrapping_sub(y);
    search.lambda.wrapping_mul(dx.wrapping_mul(dx).wrapping_add(dy.wrapping_mul(dy))) >> 8
}
fn penalty(sad:i32,pnew:i32)->i32{
    sad.wrapping_add(pnew.wrapping_mul(sad)>>8)
}
fn mv_valid(x:i32,y:i32)->bool{
    i16::try_from(x).is_ok() && i16::try_from(y).is_ok()
}
fn valid(search:&Search,candidates:&[ScoredCandidate],cost_count:usize)->bool{
    let start=search.start as usize;
    let Some(end)=start.checked_add(search.count as usize) else{return false;};
    if search.count>MAX_PER_BLOCK || end>candidates.len()
        || !mv_valid(search.predictor[0],search.predictor[1])
        || !mv_valid(search.initial.dx,search.initial.dy)
        || search.initial.winner != -1 || search.initial.sad<0
        || search.initial.raw_sad < -1 || search.initial.raw_sad>32*32*255
    {return false;}
    candidates[start..end].iter().all(|c|mv_valid(c.dx,c.dy)
        && (c.metric as usize)<cost_count
        && (!search.sad_gate || (c.raw as usize)<cost_count))
}

/// Match the pinned upstream try_candidate_at ordering, not a min-reduction:
/// SAD gating depends on the evolving winner, and ties retain that winner.
/// The caller has already generated valid candidates in the correct order.
pub fn reference(search:&Search,candidates:&[ScoredCandidate],costs:&[Cost])->Option<Choice>{
    if !valid(search,candidates,costs.len()){return None;}
    let mut best=search.initial;
    for i in search.start..search.start+search.count {
        let c=candidates[i as usize];
        let mut cost=motion_component(c.dx,c.dy,search);
        if cost>=best.cost{continue;}
        let raw=if search.sad_gate && best.raw_sad>=0{
            let raw=i32::try_from(costs[c.raw as usize].y).ok()?;
            if raw.wrapping_mul(4)>best.raw_sad.wrapping_mul(5){continue;}
            raw
        }else{-1};
        let m=costs[c.metric as usize];
        let y=m.y as i32;
        cost=cost.wrapping_add(penalty(y,search.pnew));
        if cost>=best.cost{continue;}
        let uv=if search.chroma{(m.u as i32).wrapping_add(m.v as i32).wrapping_shl(2)}else{0};
        cost=cost.wrapping_add(penalty(uv,search.pnew));
        if cost>=best.cost{continue;}
        best.dx=c.dx;best.dy=c.dy;best.sad=y.wrapping_add(uv);
        best.cost=cost;best.winner=i as i32;
        if search.sad_gate && raw>=0{best.raw_sad=raw;}
    }
    Some(best)
}

pub struct SelectionKernel {
    pipeline: wgpu::ComputePipeline,
}
impl SelectionKernel {
    pub fn new(device:&wgpu::Device)->Self{
        let shader=device.create_shader_module(wgpu::ShaderModuleDescriptor{
            label:Some("SVP ordered motion selection"),
            source:wgpu::ShaderSource::Wgsl(std::borrow::Cow::Borrowed(WGSL)),
        });
        let pipeline=device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor{
            label:Some("SVP independent ordered block search"),
            layout:None,module:&shader,entry_point:Some("select_block"),
            compilation_options:Default::default(),cache:None,
        });
        Self{pipeline}
    }

    /// The cost buffer is produced by MotionCostKernel::encode. Keep it on
    /// the device, then select independent blocks in the same encoder.
    /// One invocation per block retains exact ordered state and tie rules.
    /// Returns two vec4<i32> records per block:
    /// (dx,dy,sad,cost), (raw_sad,winner,0,0).
    pub fn encode(&self,device:&wgpu::Device,encoder:&mut wgpu::CommandEncoder,
                  costs:&wgpu::Buffer,searches:&[Search],candidates:&[ScoredCandidate])
                  ->Result<wgpu::Buffer,String>{
        let limit=device.limits();
        if searches.is_empty() || searches.len()>MAX_BATCH || candidates.is_empty()
            || candidates.len()>MAX_BATCH || costs.size()==0 || costs.size()%16!=0
            || costs.size()/16>MAX_BATCH as u64
            || !costs.usage().contains(wgpu::BufferUsages::STORAGE)
            || searches.len().div_ceil(64)>limit.max_compute_workgroups_per_dimension as usize
            || [searches.len() as u64*48,searches.len() as u64*32,candidates.len() as u64*16,costs.size()]
                .into_iter().any(|n|n>limit.max_buffer_size || n>u64::from(limit.max_storage_buffer_binding_size))
            || searches.iter().any(|s|!valid(s,candidates,(costs.size()/16) as usize))
        {return Err("invalid ordered motion selection batch".into());}
        let jobs:Vec<u8>=searches.iter().flat_map(|s|[
            s.start as i32,s.count as i32,s.predictor[0],s.predictor[1],
            s.lambda,s.pnew,i32::from(s.chroma)|i32::from(s.sad_gate)*2,s.initial.cost,
            s.initial.dx,s.initial.dy,s.initial.sad,s.initial.raw_sad,
        ].into_iter().flat_map(i32::to_le_bytes)).collect();
        let indices:Vec<u8>=candidates.iter().flat_map(|c|[
            c.dx,c.dy,c.metric as i32,c.raw as i32,
        ].into_iter().flat_map(i32::to_le_bytes)).collect();
        let storage=|label,bytes:&[u8]|device.create_buffer_init(&wgpu::util::BufferInitDescriptor{
            label:Some(label),contents:bytes,usage:wgpu::BufferUsages::STORAGE,
        });
        let jobs=storage("SVP search state",&jobs);
        let indices=storage("SVP candidate order",&indices);
        let output=device.create_buffer(&wgpu::BufferDescriptor{
            label:Some("SVP winning vectors"),
            size:searches.len() as u64*32,
            usage:wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_SRC,mapped_at_creation:false,
        });
        let group=device.create_bind_group(&wgpu::BindGroupDescriptor{
            label:None,layout:&self.pipeline.get_bind_group_layout(0),
            entries:&[
                wgpu::BindGroupEntry{binding:0,resource:costs.as_entire_binding()},
                wgpu::BindGroupEntry{binding:1,resource:jobs.as_entire_binding()},
                wgpu::BindGroupEntry{binding:2,resource:indices.as_entire_binding()},
                wgpu::BindGroupEntry{binding:3,resource:output.as_entire_binding()},
            ],
        });
        {
            let mut pass=encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0,&group,&[]);
            pass.dispatch_workgroups(searches.len().div_ceil(64) as u32,1,1);
        }
        Ok(output)
    }
}

const WGSL:&str=r#"
struct Job {
    range_predictor:vec4i,
    settings:vec4i,
    initial:vec4i,
};
struct Choice {
    vector:vec4i,
    state:vec4i,
};
@group(0) @binding(0) var<storage,read> costs:array<vec4u>;
@group(0) @binding(1) var<storage,read> jobs:array<Job>;
@group(0) @binding(2) var<storage,read> candidates:array<vec4i>;
@group(0) @binding(3) var<storage,read_write> choices:array<Choice>;

// Express wrapping arithmetic explicitly in unsigned space, then use
// signed arithmetic shifts to match the upstream i32 implementation.
fn add(a:i32,b:i32)->i32{return bitcast<i32>(bitcast<u32>(a)+bitcast<u32>(b));}
fn mul(a:i32,b:i32)->i32{return bitcast<i32>(bitcast<u32>(a)*bitcast<u32>(b));}
fn penalty(sad:i32,pnew:i32)->i32{return add(sad,mul(pnew,sad)>>8u);}
@compute @workgroup_size(64)
fn select_block(@builtin(global_invocation_id) id:vec3u){
    if id.x>=arrayLength(&jobs){return;}
    let j=jobs[id.x];
    let flags=j.settings.z;
    let chroma=(flags&1)!=0;
    let gated=(flags&2)!=0;
    var best=vec4i(j.initial.xyz,j.settings.w);
    var raw_best=j.initial.w;
    var winner=-1;
    let end=j.range_predictor.x+j.range_predictor.y;
    for(var i=j.range_predictor.x;i<end;i++){
        let c=candidates[u32(i)];
        let delta=j.range_predictor.zw-c.xy;
        var cost=mul(j.settings.x,add(mul(delta.x,delta.x),mul(delta.y,delta.y)))>>8u;
        if cost>=best.w{continue;}
        var raw=-1;
        if gated && raw_best>=0 {
            raw=i32(costs[u32(c.w)].x);
            if mul(raw,4)>mul(raw_best,5){continue;}
        }
        let m=costs[u32(c.z)];
        let y=i32(m.x);
        cost=add(cost,penalty(y,j.settings.y));
        if cost>=best.w{continue;}
        let uv=select(0,bitcast<i32>((m.y+m.z)<<2u),chroma);
        cost=add(cost,penalty(uv,j.settings.y));
        if cost>=best.w{continue;}
        best=vec4i(c.xy,add(y,uv),cost);
        winner=i;
        if gated && raw>=0{raw_best=raw;}
    }
    choices[id.x]=Choice(best,vec4i(raw_best,winner,0,0));
}
"#;

#[cfg(test)]
mod tests{
    use super::*;
    fn initial()->Choice{Choice{dx:0,dy:0,sad:1000,cost:1000,raw_sad:100,winner:-1}}
    fn fixture()->(Vec<Search>,Vec<ScoredCandidate>,Vec<Cost>){
        let costs=(0..257).map(|i|Cost{y:(i*17+31)%431,u:(i*3)%79,v:(i*11)%83}).collect::<Vec<_>>();
        let candidates=(0..256).map(|i|ScoredCandidate{
            dx:i%17-8,dy:i/17-7,metric:i as u32,raw:(i as u32+1)%257,
        }).collect::<Vec<_>>();
        let searches=(0..130).map(|i|Search{
            start:(i%4)*16,count:64,predictor:[i as i32%7-3,i as i32%11-5],
            lambda:[0,32,200,65536,i32::MAX][i as usize%5],
            pnew:[0,50,-128,512,i32::MAX][i as usize%5],
            chroma:i%2==0,sad_gate:i%3==0,
            initial:Choice{raw_sad:if i%7==0{-1}else{100},..initial()},
        }).collect();
        (searches,candidates,costs)
    }
    #[test]
    fn ordered_selection_preserves_ties_and_sad_gate(){
        let costs=[Cost{y:50,u:0,v:0},Cost{y:20,u:0,v:0},Cost{y:10,u:0,v:0},Cost{y:200,u:0,v:0},Cost{y:50,u:0,v:0}];
        let candidates=[
            ScoredCandidate{dx:1,dy:0,metric:0,raw:4},
            ScoredCandidate{dx:2,dy:0,metric:2,raw:3}, // rejected by evolving raw gate
            ScoredCandidate{dx:3,dy:0,metric:0,raw:4}, // tie retains first
            ScoredCandidate{dx:4,dy:0,metric:1,raw:4},
        ];
        let s=Search{start:0,count:4,predictor:[0,0],lambda:0,pnew:0,chroma:false,sad_gate:true,initial:initial()};
        let result=reference(&s,&candidates,&costs).unwrap();
        assert_eq!(result,Choice{dx:4,dy:0,sad:20,cost:20,raw_sad:50,winner:3});
        assert_eq!(reference(&Search{count:3,..s},&candidates,&costs).unwrap().winner,0);
        assert!(reference(&Search{count:5,..s},&candidates,&costs).is_none());
    }
    #[test]
    #[ignore="requires a Vulkan adapter, including software Vulkan"]
    fn vulkan_ordered_selection_matches_cpu(){
        let (searches,candidates,costs)=fixture();
        let instance=wgpu::Instance::new(&wgpu::InstanceDescriptor{backends:wgpu::Backends::VULKAN,..Default::default()});
        let adapter=pollster::block_on(instance.request_adapter(&Default::default())).unwrap();
        let (device,queue)=pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor{
            label:Some("SVP ordered search parity"),required_features:wgpu::Features::empty(),
            required_limits:wgpu::Limits::default().using_resolution(adapter.limits()),
            memory_hints:wgpu::MemoryHints::MemoryUsage,
        },None)).unwrap();
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let bytes:Vec<u8>=costs.iter().flat_map(|c|[c.y,c.u,c.v,c.y+c.u+c.v].into_iter().flat_map(u32::to_le_bytes)).collect();
        let metrics=device.create_buffer_init(&wgpu::util::BufferInitDescriptor{
            label:None,contents:&bytes,usage:wgpu::BufferUsages::STORAGE,
        });
        let kernel=SelectionKernel::new(&device);
        let mut encoder=device.create_command_encoder(&Default::default());
        let output=kernel.encode(&device,&mut encoder,&metrics,&searches,&candidates).unwrap();
        let choices=read_choices(&device,&queue,encoder,&output);
        assert!(pollster::block_on(device.pop_error_scope()).is_none());
        for (search,actual) in searches.iter().zip(choices){
            assert_eq!(actual,reference(search,&candidates,&costs).unwrap(),"{search:?}");
        }
    }

    fn read_choices(device:&wgpu::Device,queue:&wgpu::Queue,mut encoder:wgpu::CommandEncoder,
                    output:&wgpu::Buffer)->Vec<Choice>{
        let readback=device.create_buffer(&wgpu::BufferDescriptor{
            label:None,size:output.size(),usage:wgpu::BufferUsages::COPY_DST|wgpu::BufferUsages::MAP_READ,mapped_at_creation:false,
        });
        encoder.copy_buffer_to_buffer(output,0,&readback,0,output.size());
        queue.submit([encoder.finish()]);
        let (tx,rx)=std::sync::mpsc::sync_channel(1);
        readback.slice(..).map_async(wgpu::MapMode::Read,move|r|{let _=tx.send(r);});
        let until=std::time::Instant::now()+std::time::Duration::from_secs(20);
        loop{
            device.poll(wgpu::Maintain::Poll);
            match rx.try_recv(){
                Ok(r)=>{r.unwrap();break;}
                Err(std::sync::mpsc::TryRecvError::Disconnected)=>panic!("map disconnected"),
                Err(_) if std::time::Instant::now()>=until=>panic!("motion selection timeout"),
                Err(_)=>std::thread::sleep(std::time::Duration::from_millis(1)),
            }
        }
        let bytes=readback.slice(..).get_mapped_range();
        let choices=bytes.chunks_exact(32).map(|raw|{
            let word=|i:usize|i32::from_le_bytes(raw[i*4..i*4+4].try_into().unwrap());
            Choice{dx:word(0),dy:word(1),sad:word(2),cost:word(3),raw_sad:word(4),winner:word(5)}
        }).collect();
        drop(bytes);readback.unmap();
        choices
    }

    #[test]
    #[ignore="requires a Vulkan adapter, including software Vulkan"]
    fn chained_cost_and_selection_without_intermediate_readback(){
        use crate::motion::{Candidate,MotionCostKernel,SuperLevel,reference_super_cost};
        let instance=wgpu::Instance::new(&wgpu::InstanceDescriptor{backends:wgpu::Backends::VULKAN,..Default::default()});
        let adapter=pollster::block_on(instance.request_adapter(&Default::default())).unwrap();
        let (device,queue)=pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor{
            label:Some("SVP chained metric/selection parity"),required_features:wgpu::Features::empty(),
            required_limits:wgpu::Limits::default().using_resolution(adapter.limits()),
            memory_hints:wgpu::MemoryHints::MemoryUsage,
        },None)).unwrap();
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let metric=MotionCostKernel::new(&device);
        let selector=SelectionKernel::new(&device);
        for pel in [1,2,4]{
            let builder=svpflow1_vs::SuperBuilder::new(96,66,pel).unwrap();
            let a:Vec<u8>=(0..builder.source_len()).map(|i|((i*17+i/13)%256) as u8).collect();
            let b:Vec<u8>=(0..a.len()).map(|i|((i*31+i/7+31)%256) as u8).collect();
            let a=builder.build(&a).unwrap();
            let b=builder.build(&b).unwrap();
            for level in 0..builder.levels() as u32{
                let al=SuperLevel::new(&builder,&a,level).unwrap();
                let bl=SuperLevel::new(&builder,&b,level).unwrap();
                if al.width<32 || al.height<24{continue;}
                let mut metrics=Vec::new();
                let mut candidates=Vec::new();
                let mut searches=Vec::new();
                for reverse in [false,true]{
                    for satd in [false,true]{
                        for chroma in [false,true]{
                            let start=candidates.len() as u32;
                            for dy in -3..=3{
                                for dx in -3..=3{
                                    let c=Candidate{x:8,y:8,dx,dy,width:8,height:8,reverse,satd};
                                    let index=metrics.len() as u32;
                                    metrics.push(c);
                                    let raw=if satd{
                                        metrics.push(Candidate{satd:false,..c});
                                        index+1
                                    }else{index};
                                    candidates.push(ScoredCandidate{dx,dy,metric:index,raw});
                                }
                            }
                            searches.push(Search{start,count:49,predictor:[1,-1],lambda:100,pnew:50,chroma,
                                sad_gate:satd,initial:Choice{dx:0,dy:0,sad:100000,cost:100000,raw_sad:12000,winner:-1}});
                        }
                    }
                }
                let expected:Vec<_>=metrics.iter().map(|&c|reference_super_cost(&al,&bl,c).unwrap()).collect();
                let mut encoder=device.create_command_encoder(&Default::default());
                let at=al.upload(&device,&mut encoder).unwrap();
                let bt=bl.upload(&device,&mut encoder).unwrap();
                let costs=metric.encode(&device,&mut encoder,&at,&bt,al.width,al.height,&metrics).unwrap();
                let selected=selector.encode(&device,&mut encoder,&costs,&searches,&candidates).unwrap();
                // Only selected vectors leave the GPU. No cost-buffer map,
                // readback, or queue wait between the two compute stages.
                let actual=read_choices(&device,&queue,encoder,&selected);
                for (s,choice) in searches.iter().zip(actual){
                    assert_eq!(choice,reference(s,&candidates,&expected).unwrap(),"pel={pel} level={level}");
                }
            }
        }
        assert!(pollster::block_on(device.pop_error_scope()).is_none());

    }
}
