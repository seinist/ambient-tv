//! Exact integer SAD/SATD batch primitive for a future Vulkan motion analyser.
//! It deliberately does not replace upstream search stages: those require
//! pyramid, predictor, search ordering, penalties and border parity.
use wgpu::util::DeviceExt;

const MAX_CANDIDATES: usize = 65_535;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Candidate {
    pub x: u32,
    pub y: u32,
    pub dx: i32,
    pub dy: i32,
    pub width: u32,
    pub height: u32,
    pub reverse: bool,
    pub satd: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cost {
    pub y: u32,
    pub u: u32,
    pub v: u32,
}

impl Candidate {
    fn valid(self, width: u32, height: u32) -> bool {
        // Interior pel=1 I420 blocks only. Padding and fractional pel are
        // handled by later kernels, not by implicit edge clamping here.
        let inside = |x: i64, y: i64, w: u32, h: u32| {
            x >= 0 && y >= 0
                && x + i64::from(self.width) <= i64::from(w)
                && y + i64::from(self.height) <= i64::from(h)
        };
        matches!(self.width, 8 | 16 | 32)
            && matches!(self.height, 8 | 16 | 32)
            && self.x % 2 == 0 && self.y % 2 == 0
            && width % 2 == 0 && height % 2 == 0
            && inside(i64::from(self.x), i64::from(self.y), width, height)
            && inside(i64::from(self.x) + i64::from(self.dx),
                      i64::from(self.y) + i64::from(self.dy), width, height)
    }
}

impl Cost {
    // Upstream I420 full_sad scales chroma by 2^(shift_x+shift_y).
    pub fn weighted(self, chroma: bool) -> u32 {
        self.y + if chroma { 4 * (self.u + self.v) } else { 0 }
    }
}

fn hadamard(p: [i32; 4]) -> [i32; 4] {
    let (a,b,c,d)=(p[0]+p[1],p[2]+p[3],p[0]-p[1],p[2]-p[3]);
    [a+b,a-b,c+d,c-d]
}

fn tile_satd(mut diff: impl FnMut(usize,usize)->i32) -> u32 {
    let rows: [[i32;4];4]=std::array::from_fn(|y|hadamard(std::array::from_fn(|x|diff(x,y))));
    (0..4).map(|x|hadamard(std::array::from_fn(|y|rows[y][x]))
        .into_iter().map(i32::unsigned_abs).sum::<u32>()).sum()
}

/// CPU reference for the raw per-plane metrics, not upstream vector selection.
pub fn reference_cost(a: &[u8], b: &[u8], width: u32, height: u32, c: Candidate) -> Option<Cost> {
    let len = (width as usize).checked_mul(height as usize)?.checked_mul(3)?.checked_div(2)?;
    if a.len() != len || b.len() != len || !c.valid(width, height) { return None; }
    let (a,b) = if c.reverse { (b,a) } else { (a,b) };
    let luma = width as usize * height as usize;
    let mut result = [0;3];
    for plane in 0..3 {
        let shift = usize::from(plane != 0);
        let pitch = width as usize >> shift;
        let base = [0,luma,luma*5/4][plane];
        let sx = c.x as i64 >> shift;
        let sy = c.y as i64 >> shift;
        let rx = (i64::from(c.x) + i64::from(c.dx)) >> shift;
        let ry = (i64::from(c.y) + i64::from(c.dy)) >> shift;
        let diff = |x:usize,y:usize| {
            let left = a[base + (sy as usize+y)*pitch + sx as usize+x];
            let right = b[base + (ry as usize+y)*pitch + rx as usize+x];
            i32::from(left)-i32::from(right)
        };
        let (bw,bh)=(c.width as usize >> shift,c.height as usize >> shift);
        if c.satd {
            for y in (0..bh).step_by(4) {
                for x in (0..bw).step_by(4) {
                    result[plane]+=tile_satd(|tx,ty|diff(x+tx,y+ty));
                }
            }
            result[plane]/=2;
        } else {
            for y in 0..bh {
                for x in 0..bw { result[plane]+=diff(x,y).unsigned_abs(); }
            }
        }
    }
    Some(Cost {y:result[0],u:result[1],v:result[2]})
}

pub struct MotionCostKernel {
    pipeline: wgpu::ComputePipeline,
}

impl MotionCostKernel {
    pub fn new(device: &wgpu::Device) -> Self {
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("SVP exact motion candidate costs"),
            source: wgpu::ShaderSource::Wgsl(std::borrow::Cow::Borrowed(WGSL)),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("SVP candidate SAD/SATD reduction"),
            layout: None,
            module: &shader,
            entry_point: Some("cost"),
            compilation_options: Default::default(),
            cache: None,
        });
        Self { pipeline }
    }

    /// Enqueue one independent workgroup per candidate on the caller's
    /// queue. Source textures use the existing packed SVP layout (Y above
    /// side-by-side U/V); no additional pixel upload is needed. The output
    /// is vec4<u32> (Y, U, V, sum) per candidate, ready for later GPU stages.
    pub fn encode(&self, device: &wgpu::Device, encoder: &mut wgpu::CommandEncoder,
                  a: &wgpu::Texture, b: &wgpu::Texture,
                  width: u32, height: u32, candidates: &[Candidate])
                  -> Result<wgpu::Buffer, String> {
        if width == 0 || height == 0 || width > 3840 || height > 2160
            || a.width() != width || b.width() != width
            || a.height() != height*3/2 || b.height() != height*3/2
            || a.format() != wgpu::TextureFormat::R8Unorm || b.format() != a.format()
            || a.dimension() != wgpu::TextureDimension::D2 || b.dimension() != a.dimension()
            || a.depth_or_array_layers() != 1 || b.depth_or_array_layers() != 1
            || a.sample_count() != 1 || b.sample_count() != 1
            || !a.usage().contains(wgpu::TextureUsages::TEXTURE_BINDING)
            || !b.usage().contains(wgpu::TextureUsages::TEXTURE_BINDING)
            || candidates.is_empty() || candidates.len() > MAX_CANDIDATES
            || candidates.len() > device.limits().max_compute_workgroups_per_dimension as usize
            || candidates.len() as u64 * 32 > u64::from(device.limits().max_storage_buffer_binding_size)
            || candidates.len() as u64 * 32 > device.limits().max_buffer_size
            || candidates.iter().any(|c| !c.valid(width,height))
        { return Err("unsupported motion candidate batch".into()); }
        let bytes: Vec<u8> = candidates.iter().flat_map(|c| {
            [c.x as i32,c.y as i32,c.dx,c.dy,c.width as i32,c.height as i32,
             i32::from(c.reverse),i32::from(c.satd)].into_iter().flat_map(i32::to_le_bytes)
        }).collect();
        let input = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("SVP candidate descriptors"), contents: &bytes,
            usage: wgpu::BufferUsages::STORAGE,
        });
        let output = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("SVP candidate costs"),
            size: candidates.len() as u64 * 16,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        });
        let av = a.create_view(&Default::default());
        let bv = b.create_view(&Default::default());
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None, layout: &self.pipeline.get_bind_group_layout(0),
            entries: &[
                wgpu::BindGroupEntry {binding:0,resource:wgpu::BindingResource::TextureView(&av)},
                wgpu::BindGroupEntry {binding:1,resource:wgpu::BindingResource::TextureView(&bv)},
                wgpu::BindGroupEntry {binding:2,resource:input.as_entire_binding()},
                wgpu::BindGroupEntry {binding:3,resource:output.as_entire_binding()},
            ],
        });
        {
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0,&group,&[]);
            pass.dispatch_workgroups(candidates.len() as u32,1,1);
        }
        Ok(output)
    }
}

const WGSL: &str = r#"
struct Candidate { x:i32,y:i32,dx:i32,dy:i32,w:i32,h:i32,reverse:i32,satd:i32 };
@group(0) @binding(0) var a: texture_2d<f32>;
@group(0) @binding(1) var b: texture_2d<f32>;
@group(0) @binding(2) var<storage,read> candidates: array<Candidate>;
@group(0) @binding(3) var<storage,read_write> costs: array<vec4u>;
var<workgroup> partial: array<vec3u,64>;

fn pixel(tex: texture_2d<f32>, p:vec2i) -> i32 {
    return i32(round(textureLoad(tex,p,0).x*255.0));
}
fn signed_diff(p:vec2i,q:vec2i,reverse:bool) -> i32 {
    if reverse { return pixel(b,p)-pixel(a,q); }
    return pixel(a,p)-pixel(b,q);
}
fn diff(p:vec2i,q:vec2i,reverse:bool) -> u32 {
    return u32(abs(signed_diff(p,q,reverse)));
}
fn hadamard(p:vec4i)->vec4i {
    let a=p.x+p.y;
    let b=p.z+p.w;
    let c=p.x-p.y;
    let d=p.z-p.w;
    return vec4i(a+b,a-b,c+d,c-d);
}
fn satd_tile(p:vec2i,q:vec2i,reverse:bool)->u32 {
    var rows:array<vec4i,4>;
    for(var y=0;y<4;y++){
        var row=vec4i(0);
        for(var x=0;x<4;x++){
            let offset=vec2i(x,y);
            row[x]=signed_diff(p+offset,q+offset,reverse);
        }
        rows[y]=hadamard(row);
    }
    var sum=0u;
    for(var x=0;x<4;x++){
        let col=vec4u(abs(hadamard(vec4i(rows[0][x],rows[1][x],rows[2][x],rows[3][x]))));
        sum+=col.x+col.y+col.z+col.w;
    }
    return sum;
}
@compute @workgroup_size(64)
fn cost(@builtin(workgroup_id) group:vec3u,@builtin(local_invocation_index) lane:u32) {
    let c=candidates[group.x];
    let size=vec2i(textureDimensions(a));
    let height=size.y*2/3;
    let src=vec2i(c.x,c.y);
    let dst=src+vec2i(c.dx,c.dy);
    var sum=vec3u(0);
    if c.satd!=0 {
        let tiles=c.w/4;
        for(var i=lane;i<u32(tiles*(c.h/4));i+=64u){
            let offset=vec2i(i32(i)%tiles,i32(i)/tiles)*4;
            sum.x+=satd_tile(src+offset,dst+offset,c.reverse!=0);
        }
        let ct=c.w/8;
        for(var i=lane;i<u32(ct*(c.h/8));i+=64u){
            let offset=vec2i(i32(i)%ct,i32(i)/ct)*4;
            let p=src/2+offset+vec2i(0,height);
            let q=dst/2+offset+vec2i(0,height);
            sum.y+=satd_tile(p,q,c.reverse!=0);
            sum.z+=satd_tile(p+vec2i(size.x/2,0),q+vec2i(size.x/2,0),c.reverse!=0);
        }
    } else {
        for(var i=lane;i<u32(c.w*c.h);i+=64u){
            let offset=vec2i(i32(i)%c.w,i32(i)/c.w);
            sum.x+=diff(src+offset,dst+offset,c.reverse!=0);
        }
        let chroma_width=c.w/2;
        for(var i=lane;i<u32(chroma_width*(c.h/2));i+=64u){
            let offset=vec2i(i32(i)%chroma_width,i32(i)/chroma_width);
            let p=src/2+offset+vec2i(0,height);
            let q=dst/2+offset+vec2i(0,height);
            sum.y+=diff(p,q,c.reverse!=0);
            sum.z+=diff(p+vec2i(size.x/2,0),q+vec2i(size.x/2,0),c.reverse!=0);
        }
    }
    partial[lane]=sum;
    workgroupBarrier();
    for(var stride=32u;stride>0u;stride/=2u){
        if lane<stride { partial[lane]+=partial[lane+stride]; }
        workgroupBarrier();
    }
    if lane==0u {
        var s=partial[0];
        if c.satd!=0 { s/=vec3u(2); }
        costs[group.x]=vec4u(s,s.x+s.y+s.z);
    }
}
"#;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_metric_and_invalid_geometry() {
        let (w,h)=(64,64);
        let a=vec![0;w*h*3/2];
        let b=vec![255;a.len()];
        let c=Candidate{x:16,y:16,dx:-3,dy:5,width:32,height:16,reverse:false,satd:false};
        assert_eq!(reference_cost(&a,&b,w as u32,h as u32,c),Some(Cost{y:32*16*255,u:8*16*255,v:8*16*255}));
        let satd=reference_cost(&a,&b,w as u32,h as u32,Candidate{satd:true,..c}).unwrap();
        assert_eq!(satd,Cost{y:32*16*255/2,u:8*16*255/2,v:8*16*255/2});
        assert_eq!(satd.weighted(true),satd.y+4*(satd.u+satd.v));
        assert_eq!(reference_cost(&a,&a,w as u32,h as u32,c),Some(Cost{y:0,u:0,v:0}));
        assert!(reference_cost(&a,&b,w as u32,h as u32,Candidate{dx:-17,..c}).is_none());
        assert!(reference_cost(&a,&b,w as u32,h as u32,Candidate{x:15,..c}).is_none());
    }
    // Software Vulkan is sufficient for integer correctness and WGSL
    // validation. Actual throughput still requires a physical GPU.
    #[test]
    #[ignore = "requires a Vulkan adapter, including software Vulkan"]
    fn vulkan_candidate_costs_match_cpu() {
        let instance=wgpu::Instance::new(&wgpu::InstanceDescriptor{
            backends:wgpu::Backends::VULKAN,..Default::default()
        });
        let adapter=pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()))
            .expect("Vulkan adapter required");
        let (device,queue)=pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor{
            label:Some("SVP motion parity"),
            required_features:wgpu::Features::empty(),
            required_limits:wgpu::Limits::default().using_resolution(adapter.limits()),
            memory_hints:wgpu::MemoryHints::MemoryUsage,
        },None)).unwrap();
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let (w,h)=(96u32,66u32);
        let len=(w*h*3/2) as usize;
        let a:Vec<u8>=(0..len).map(|i|((i*17+i/13)%256) as u8).collect();
        let b:Vec<u8>=(0..len).map(|i|((i*31+i/7+31)%256) as u8).collect();
        let upload=|data:&[u8]| {
            let tex=device.create_texture(&wgpu::TextureDescriptor{
                label:None,size:wgpu::Extent3d{width:w,height:h*3/2,depth_or_array_layers:1},
                mip_level_count:1,sample_count:1,dimension:wgpu::TextureDimension::D2,
                format:wgpu::TextureFormat::R8Unorm,view_formats:&[],
                usage:wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::COPY_DST,
            });
            let luma=(w*h) as usize;
            let mut packed=data[..luma].to_vec();
            for y in 0..h as usize/2 {
                let x=y*w as usize/2;
                let half=w as usize/2;
                packed.extend_from_slice(&data[luma+x..luma+x+half]);
                packed.extend_from_slice(&data[luma*5/4+x..luma*5/4+x+half]);
            }
            queue.write_texture(tex.as_image_copy(),&packed,wgpu::TexelCopyBufferLayout{
                offset:0,bytes_per_row:Some(w),rows_per_image:Some(h*3/2),
            },wgpu::Extent3d{width:w,height:h*3/2,depth_or_array_layers:1});
            tex
        };
        let at=upload(&a);
        let bt=upload(&b);
        let kernel=MotionCostKernel::new(&device);
        let mut candidates=Vec::new();
        for bw in [8,16,32] {
            for bh in [8,16,32] {
                for dx in [-7,-2,0,1,6] {
                    for dy in [-5,0,3] {
                        for reverse in [false,true] {
                            for satd in [false,true] {
                                candidates.push(Candidate{x:16,y:16,dx,dy,width:bw,height:bh,reverse,satd});
                            }
                        }
                    }
                }
            }
        }
        let mut encoder=device.create_command_encoder(&Default::default());
        let output=kernel.encode(&device,&mut encoder,&at,&bt,w,h,&candidates).unwrap();
        let readback=device.create_buffer(&wgpu::BufferDescriptor{
            label:None,size:output.size(),usage:wgpu::BufferUsages::COPY_DST|wgpu::BufferUsages::MAP_READ,
            mapped_at_creation:false,
        });
        encoder.copy_buffer_to_buffer(&output,0,&readback,0,output.size());
        queue.submit([encoder.finish()]);
        let (tx,rx)=std::sync::mpsc::sync_channel(1);
        readback.slice(..).map_async(wgpu::MapMode::Read,move |r|{let _=tx.send(r);});
        let deadline=std::time::Instant::now()+std::time::Duration::from_secs(20);
        loop {
            device.poll(wgpu::Maintain::Poll);
            match rx.try_recv(){
                Ok(result)=>{result.unwrap();break;}
                Err(std::sync::mpsc::TryRecvError::Disconnected)=>panic!("map disconnected"),
                Err(_) if std::time::Instant::now()>=deadline=>panic!("motion cost timeout"),
                Err(_)=>std::thread::sleep(std::time::Duration::from_millis(1)),
            }
        }
        assert!(pollster::block_on(device.pop_error_scope()).is_none());
        let bytes=readback.slice(..).get_mapped_range();
        for (c,raw) in candidates.iter().zip(bytes.chunks_exact(16)) {
            let word=|i:usize|u32::from_le_bytes(raw[i*4..i*4+4].try_into().unwrap());
            let expected=reference_cost(&a,&b,w,h,*c).unwrap();
            assert_eq!(Cost{y:word(0),u:word(1),v:word(2)},expected,"{c:?}");
            assert_eq!(word(3),expected.y+expected.u+expected.v);
        }
        drop(bytes);
        readback.unmap();
    }

}
