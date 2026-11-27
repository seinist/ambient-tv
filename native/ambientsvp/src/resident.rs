//! Experimental GPU-resident interpolation for a renderer-owned wgpu
//! device. All picture data, search results and output stay in GPU memory.
//! This is an independent bounded motion policy, not upstream SVP parity.
//! No external Vulkan image wrapping: wgpu 24 cannot express the producer's
//! initial layout/queue ownership through create_texture_from_hal.
use wgpu::util::DeviceExt;

const BLOCK:u32=16;
const MAX_PIXELS:u64=1920*1080;

#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum ColorMatrix { Bt601Limited, Bt709Limited, Bt601Full, Bt709Full }
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
#[repr(u32)]
pub enum ChromaLocation { TopLeft=0, Left=1, Center=2 }

/// The caller owns the renderer/decoder device and its submission order.
/// Y and UV are views of actual R8Unorm/Rg8Unorm planes (including
/// plane views of NV12 where TEXTURE_FORMAT_NV12 is supported). For I420
/// use separate R8Unorm U and V views. These views must be on the same
/// device and cover the configured visible dimensions, without padding.
pub enum Chroma<'a>{
    Nv12(&'a wgpu::TextureView),
    I420{u:&'a wgpu::TextureView,v:&'a wgpu::TextureView},
}
pub struct Picture<'a>{pub y:&'a wgpu::TextureView,pub chroma:Chroma<'a>,pub chroma_location:ChromaLocation}

pub struct Resident {
    device:wgpu::Device,
    width:u32,height:u32,
    grid_w:u32,grid_h:u32,
    motion:wgpu::ComputePipeline,
    synthesis:wgpu::ComputePipeline,
    fields:wgpu::Buffer,
}

/// A pair is tied to the exact input views and the encoder into which
/// its analysis was recorded. Borrowing the resident engine mutably
/// prevents replacing its field while encoding output phases.
pub struct Pair<'a,'p>{
    engine:&'a mut Resident,
    a:Picture<'p>,b:Picture<'p>,
    encoder:&'a mut wgpu::CommandEncoder,
}
impl Resident{
    pub fn new(device:&wgpu::Device,width:u32,height:u32)->Result<Self,String>{
        let limits=device.limits();
        if width<32 || height<32 || (width|height)&1!=0
            || width>3840 || height>2160 || u64::from(width)*u64::from(height)>MAX_PIXELS
            || width>limits.max_texture_dimension_2d || height>limits.max_texture_dimension_2d
            || limits.max_sampled_textures_per_shader_stage<6
            || limits.max_storage_buffers_per_shader_stage<1
            || limits.max_storage_textures_per_shader_stage<1
            || limits.max_compute_invocations_per_workgroup<64
            || limits.max_compute_workgroup_size_x<64 || limits.max_compute_workgroup_size_y<8
        {return Err("unsupported resident GPU geometry/limits".into());}
        let (grid_w,grid_h)=(width.div_ceil(BLOCK),height.div_ceil(BLOCK));
        let groups=limits.max_compute_workgroups_per_dimension;
        let size=u64::from(grid_w)*u64::from(grid_h)*32;
        if grid_w>groups || grid_h*2>groups || width.div_ceil(8)>groups || height.div_ceil(8)>groups
            || size>limits.max_buffer_size || size>u64::from(limits.max_storage_buffer_binding_size)
        {return Err("resident dispatch exceeds device limits".into());}
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let create=|label,source:&'static str,entry|{
            let module=device.create_shader_module(wgpu::ShaderModuleDescriptor{
                label:Some(label),source:wgpu::ShaderSource::Wgsl(source.into()),
            });
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor{
                label:Some(label),layout:None,module:&module,entry_point:Some(entry),
                compilation_options:Default::default(),cache:None,
            })
        };
        let motion=create("SVP resident bidirectional motion",MOTION,"analyse");
        let synthesis=create("SVP resident motion compensated RGB output",SYNTHESIS,"synthesize");
        let fields=device.create_buffer(&wgpu::BufferDescriptor{
            label:Some("SVP GPU-only motion fields"),size,
            usage:wgpu::BufferUsages::STORAGE,mapped_at_creation:false,
        });
        if let Some(error)=pollster::block_on(device.pop_error_scope()){return Err(error.to_string());}
        Ok(Self{device:device.clone(),width,height,grid_w,grid_h,motion,synthesis,fields})
    }

    /// No upload, mapping, readback or queue submission occurs here.
    /// The caller records decoder-to-compute ownership transitions before
    /// these commands using a compatible shared-device interop layer.
    pub fn analyse<'a,'p>(&'a mut self,encoder:&'a mut wgpu::CommandEncoder,
        a:Picture<'p>,b:Picture<'p>)->Pair<'a,'p>{
        let params=uniform(&self.device,[self.width,self.height,self.grid_w,self.grid_h]);
        let group=self.device.create_bind_group(&wgpu::BindGroupDescriptor{
            label:None,layout:&self.motion.get_bind_group_layout(0),entries:&[
                view(0,a.y),view(1,b.y),
                wgpu::BindGroupEntry{binding:2,resource:self.fields.as_entire_binding()},
                wgpu::BindGroupEntry{binding:3,resource:params.as_entire_binding()},
            ],
        });
        {
            let mut pass=encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.motion);pass.set_bind_group(0,&group,&[]);
            pass.dispatch_workgroups(self.grid_w,self.grid_h*2,1);
        }
        Pair{engine:self,a,b,encoder}
    }
}
fn uniform(device:&wgpu::Device,words:[u32;4])->wgpu::Buffer{
    let bytes:Vec<u8>=words.into_iter().flat_map(u32::to_le_bytes).collect();
    device.create_buffer_init(&wgpu::util::BufferInitDescriptor{
        label:Some("SVP resident geometry/phase"),contents:&bytes,usage:wgpu::BufferUsages::UNIFORM,
    })
}
fn view(binding:u32,texture:&wgpu::TextureView)->wgpu::BindGroupEntry<'_>{
    wgpu::BindGroupEntry{binding,resource:wgpu::BindingResource::TextureView(texture)}
}
fn chroma<'a>(c:&Chroma<'a>)->(&'a wgpu::TextureView,&'a wgpu::TextureView,u32){
    match c{Chroma::Nv12(uv)=>(*uv,*uv,1),Chroma::I420{u,v}=>(*u,*v,0)}
}
impl Pair<'_,'_>{
    /// Render into a caller-owned RGBA8Unorm storage texture on this device.
    /// Multiple output phases share one GPU motion field. The caller must
    /// keep the input/output resources alive until submitted work completes.
    /// Output is SDR RGB; do not use for HDR, protected or unknown color space.
    pub fn synthesize(&mut self,phase:f32,matrix:ColorMatrix,output:&wgpu::Texture)->Result<(),String>{
        let e=&self.engine;
        if !phase.is_finite() || !(0.0..=1.0).contains(&phase)
            || output.width()!=e.width || output.height()!=e.height
            || output.depth_or_array_layers()!=1 || output.mip_level_count()!=1
            || output.sample_count()!=1 || output.dimension()!=wgpu::TextureDimension::D2
            || output.format()!=wgpu::TextureFormat::Rgba8Unorm
            || !output.usage().contains(wgpu::TextureUsages::STORAGE_BINDING)
        {return Err("invalid resident output texture or phase".into());}
        let (au,av,af)=chroma(&self.a.chroma);
        let (bu,bv,bf)=chroma(&self.b.chroma);
        let flags=af|bf<<1|(self.a.chroma_location as u32)<<2|(self.b.chroma_location as u32)<<4;
        let matrix=match matrix{ColorMatrix::Bt601Limited=>0,ColorMatrix::Bt709Limited=>1,
            ColorMatrix::Bt601Full=>2,ColorMatrix::Bt709Full=>3};
        let params=uniform(&e.device,[phase.to_bits(),flags,matrix,e.width|(e.height<<16)]);
        let dst=output.create_view(&Default::default());
        let group=e.device.create_bind_group(&wgpu::BindGroupDescriptor{
            label:None,layout:&e.synthesis.get_bind_group_layout(0),entries:&[
                view(0,self.a.y),view(1,au),view(2,av),view(3,self.b.y),view(4,bu),view(5,bv),
                wgpu::BindGroupEntry{binding:6,resource:e.fields.as_entire_binding()},
                view(7,&dst),wgpu::BindGroupEntry{binding:8,resource:params.as_entire_binding()},
            ],
        });
        let mut pass=self.encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&e.synthesis);pass.set_bind_group(0,&group,&[]);
        pass.dispatch_workgroups(e.width.div_ceil(8),e.height.div_ceil(8),1);
        Ok(())
    }
}

// One workgroup per block/direction. Coarse 9x9 grid (step four), then
// 5x5 full-resolution refinement around the coarse winner. Each lane
// computes a complete candidate on a deterministic 2x2 sample grid.
// Workgroup reduction uses cost and stable candidate order; no CPU
// predictor or intermediate readback. A full-pixel zero vector wins ties.
pub(crate) const MOTION:&str=r#"
@group(0) @binding(0) var a:texture_2d<f32>;
@group(0) @binding(1) var b:texture_2d<f32>;
@group(0) @binding(2) var<storage,read_write> fields:array<vec4i>;
@group(0) @binding(3) var<uniform> geometry:vec4u;
var<workgroup> scores:array<u32,64>;
var<workgroup> vectors:array<vec2i,64>;
var<workgroup> zero_score:u32;
var<workgroup> source_samples:array<i32,64>;
fn px(t:texture_2d<f32>,p:vec2i)->i32{
    let value=textureLoad(t,p,0);
    // RGB inputs from an Android external-format sampler use a bounded
    // luma proxy for matching. Ordinary planar frames retain exact Y.
    let luminance=select(value.x,dot(value.rgb,vec3f(0.2126,0.7152,0.0722)),
        (geometry.w&0x80000000u)!=0u);
    return i32(round(luminance*255.0));
}
fn cost(origin:vec2i,mv:vec2i,reverse:bool,bound:u32)->u32{
    let size=vec2i(geometry.xy);
    var sad=0u;
    var n=0u;
    let penalty=u32(abs(mv.x)+abs(mv.y));
    for(var y=0;y<16;y+=2){
        for(var x=0;x<16;x+=2){
            let p=origin+vec2i(x,y);
            if any(p>=size){continue;}
            let q=p+mv;
            if any(q<vec2i(0))||any(q>=size){
                sad+=255u;
            }else{
                let sample=source_samples[u32(y/2*8+x/2)];
                // The original source sample is invariant across all
                // candidate offsets; only the reference picture moves.
                if reverse{sad+=u32(abs(sample-px(a,q)));}
                else{sad+=u32(abs(sample-px(b,q)));}
            }
            n+=1u;
        }
        // At most 64 samples contribute. The normalized SAD cannot
        // be smaller than the partial raw sum, even at clipped edges.
        if sad*128u+penalty>=bound{return bound;}
    }
    // Normalize partial blocks, and prefer smaller displacements on ties.
    let avg=sad*64u/max(n,1u);
    return avg*128u+penalty;
}
fn choose(lane:u32,mv:vec2i,c:u32){
    if c<scores[lane]{
        scores[lane]=c;vectors[lane]=mv;
    }
}
fn reduce(lane:u32){
    workgroupBarrier();
    for(var stride=32u;stride>0u;stride/=2u){
        if lane<stride{
            choose(lane,vectors[lane+stride],scores[lane+stride]);
        }
        workgroupBarrier();
    }
}
@compute @workgroup_size(64)
fn analyse(@builtin(workgroup_id) g:vec3u,@builtin(local_invocation_index) lane:u32){
    let gh=geometry.w&0x7fffffffu;
    let reverse=g.y>=gh;
    let gy=g.y%gh;
    let origin=vec2i(vec2u(g.x*16u,gy*16u));
    scores[lane]=0xffffffffu;vectors[lane]=vec2i(0);
    let p=origin+vec2i(i32(lane%8u)*2,i32(lane/8u)*2);
    source_samples[lane]=0;
    if all(p<vec2i(geometry.xy)){
        if reverse{source_samples[lane]=px(b,p);}
        else{source_samples[lane]=px(a,p);}
    }
    workgroupBarrier();
    // Compute zero displacement once. A zero score is the absolute
    // minimum and uniquely selects zero; other blocks can use it as
    // an upper bound. +1 preserves every equal-score tie.
    if lane==0u{zero_score=cost(origin,vec2i(0),reverse,0xffffffffu);}
    workgroupBarrier();
    let zero=zero_score;
    for(var i=lane;i<81u;i+=64u){
        if i==40u{choose(lane,vec2i(0),zero);}
        else if zero!=0u{
            let mv=(vec2i(i32(i%9u),i32(i/9u))-vec2i(4))*4;
            choose(lane,mv,cost(origin,mv,reverse,min(scores[lane],zero+1u)));
        }
    }
    reduce(lane);
    let pred=vectors[0];
    let best=scores[0];
    workgroupBarrier();
    scores[lane]=0xffffffffu;vectors[lane]=vec2i(0);
    if lane==0u{choose(lane,pred,best);}
    if lane<25u && best!=0u{
        let mv=pred+vec2i(i32(lane%5u)-2,i32(lane/5u)-2);
        choose(lane,mv,cost(origin,mv,reverse,min(scores[lane],best+1u)));
    }
    reduce(lane);
    if lane==0u{
        let count=geometry.z*gh;
        let index=g.x+gy*geometry.z+select(0u,count,reverse);
        fields[index]=vec4i(vectors[0],i32(scores[0]/128u),0);
    }
}
"#;

// Texture-only output. Bilinear luma/chroma sampling is explicit so
// unfilterable imported plane views can be accepted by the same layout.
// The output renderer samples the resulting RGBA texture directly.
pub(crate) const SYNTHESIS:&str=r#"
@group(0) @binding(0) var ay:texture_2d<f32>;
@group(0) @binding(1) var au:texture_2d<f32>;
@group(0) @binding(2) var av:texture_2d<f32>;
@group(0) @binding(3) var by:texture_2d<f32>;
@group(0) @binding(4) var bu:texture_2d<f32>;
@group(0) @binding(5) var bv:texture_2d<f32>;
@group(0) @binding(6) var<storage,read> fields:array<vec4i>;
@group(0) @binding(7) var dst:texture_storage_2d<rgba8unorm,write>;
@group(0) @binding(8) var<uniform> params:vec4u;
fn bilinear(t:texture_2d<f32>,p:vec2f,visible:vec2u)->vec4f{
    let hi=vec2i(min(textureDimensions(t),visible))-vec2i(1);
    let q=clamp(p,vec2f(0),vec2f(hi));
    let i=vec2i(floor(q));
    let j=min(i+vec2i(1),hi);
    let f=fract(q);
    // Integer sample positions are common at source ticks and in
    // stationary regions; avoid the three redundant texture fetches.
    if all(f==vec2f(0)){return textureLoad(t,i,0);}
    let a=mix(textureLoad(t,i,0),textureLoad(t,vec2i(j.x,i.y),0),f.x);
    let b=mix(textureLoad(t,vec2i(i.x,j.y),0),textureLoad(t,j,0),f.x);
    return mix(a,b,f.y);
}
fn yuv(y:texture_2d<f32>,u:texture_2d<f32>,v:texture_2d<f32>,p:vec2f,nv:bool,location:u32,visible:vec2u)->vec3f{
    let offset=vec2f(select(0.0,0.5,location==2u),select(0.0,0.5,location!=0u));
    let chroma=(p-offset)*0.5;
    let uv=bilinear(u,chroma,visible/2u);
    // NV12 stores both components in one sampled view; avoid a second
    // bilinear lookup of the same plane. I420 keeps its separate V plane.
    var vv=uv.y;
    if !nv{vv=bilinear(v,chroma,visible/2u).x;}
    return vec3f(bilinear(y,p,visible).x,uv.x,vv);
}
fn rgb(yuv:vec3f)->vec3f{
    let full=params.z>=2u;
    let bt709=(params.z&1u)!=0u;
    let y=select((yuv.x*255.0-16.0)/219.0,yuv.x,full);
    let uv=(yuv.yz*255.0-vec2f(128.0))/select(224.0,255.0,full);
    let kr=select(0.299,0.2126,bt709);
    let kb=select(0.114,0.0722,bt709);
    let r=y+2.0*(1.0-kr)*uv.y;
    let b=y+2.0*(1.0-kb)*uv.x;
    let g=(y-kr*r-kb*b)/(1.0-kr-kb);
    return clamp(vec3f(r,g,b),vec3f(0),vec3f(1));
}
@compute @workgroup_size(8,8)
fn synthesize(@builtin(global_invocation_id) id:vec3u){
    let visible=vec2u(params.w&65535u,params.w>>16u);
    if any(id.xy>=visible)||any(id.xy>=textureDimensions(dst)){return;}
    let grid=(visible+vec2u(15))/16u;
    let index=id.x/16u+id.y/16u*grid.x;
    let f=fields[index];
    let r=fields[index+grid.x*grid.y];
    let phase=bitcast<f32>(params.x);
    let p=vec2f(id.xy);
    // Opposite motion estimates, warped toward the same output position.
    // On large residuals or inconsistent motion, prefer the nearer frame
    // instead of interpolating across a cut or a severe occlusion.
    var ap=p-vec2f(f.xy)*phase;
    var bp=p-vec2f(r.xy)*(1.0-phase);
    var blend=phase;
    let mismatch=abs(f.x+r.x)+abs(f.y+r.y);
    if f.z>64*48||r.z>64*48||mismatch>6{
        ap=p;bp=p;blend=select(0.0,1.0,phase>=0.5);
    }
    // At source ticks or on a hard-cut fallback only one source can
    // contribute. Avoid all texture fetches from the discarded picture.
    if blend==0.0 || blend==1.0{
        var pixel:vec3f;
        if (params.y&64u)!=0u{
            if blend==0.0{pixel=bilinear(ay,ap,visible).rgb;}
            else{pixel=bilinear(by,bp,visible).rgb;}
        }else{
            if blend==0.0{pixel=rgb(yuv(ay,au,av,ap,(params.y&1u)!=0u,(params.y>>2u)&3u,visible));}
            else{pixel=rgb(yuv(by,bu,bv,bp,(params.y&2u)!=0u,(params.y>>4u)&3u,visible));}
        }
        textureStore(dst,vec2i(id.xy),vec4f(pixel,1.0));
        return;
    }
    if (params.y&64u)!=0u{
        // Imported Android hardware pixels are already converted to
        // encoded SDR RGB by the immutable Vulkan YCbCr sampler.
        let a=bilinear(ay,ap,visible).rgb;
        let b=bilinear(by,bp,visible).rgb;
        textureStore(dst,vec2i(id.xy),vec4f(mix(a,b,blend),1.0));
    }else{
        let a=yuv(ay,au,av,ap,(params.y&1u)!=0u,(params.y>>2u)&3u,visible);
        let b=yuv(by,bu,bv,bp,(params.y&2u)!=0u,(params.y>>4u)&3u,visible);
        textureStore(dst,vec2i(id.xy),vec4f(rgb(mix(a,b,blend)),1.0));
    }
}
"#;


#[cfg(test)]
mod tests{
    use super::*;
    // Independent scalar oracle for the original unpruned integer
    // search. Model the strict lane-local and tree reduction tie order.
    fn reference(a:&[u8],b:&[u8],w:u32,h:u32,gx:u32,gy:u32)->[i32;4]{
        let cost=|dx:i32,dy:i32|{
            let mut sad=0u32;let mut n=0u32;
            for y in (0..16).step_by(2){for x in (0..16).step_by(2){
                let (px,py)=((gx*16+x) as i32,(gy*16+y) as i32);
                if px>=w as i32||py>=h as i32{continue;}
                let (qx,qy)=(px+dx,py+dy);
                sad+=if qx<0||qy<0||qx>=w as i32||qy>=h as i32{255}
                    else{u32::from(a[(py as u32*w+px as u32) as usize]
                        .abs_diff(b[(qy as u32*w+qx as u32) as usize]))};
                n+=1;
            }}
            sad*64/n.max(1)*128+dx.unsigned_abs()+dy.unsigned_abs()
        };
        let choose=|a:(u32,i32,i32),b:(u32,i32,i32)|if b.0<a.0{b}else{a};
        let reduce=|mut lanes:[(u32,i32,i32);64]|{
            for stride in [32,16,8,4,2,1]{for i in 0..stride{
                lanes[i]=choose(lanes[i],lanes[i+stride]);
            }}
            lanes[0]
        };
        let mut lanes=[(u32::MAX,0,0);64];
        for i in 0..81{
            let dx=(i as i32%9-4)*4;let dy=(i as i32/9-4)*4;
            lanes[i%64]=choose(lanes[i%64],(cost(dx,dy),dx,dy));
        }
        let best=reduce(lanes);
        lanes.fill((u32::MAX,0,0));lanes[0]=best;
        for i in 0..25{
            let dx=best.1+i as i32%5-2;let dy=best.2+i as i32/5-2;
            lanes[i]=choose(lanes[i],(cost(dx,dy),dx,dy));
        }
        let winner=reduce(lanes);
        [winner.1,winner.2,(winner.0/128) as i32,0]
    }

    #[test]
    #[ignore="requires Vulkan, including software Vulkan"]
    fn resident_motion_matches_unpruned_scalar_search(){
        let instance=wgpu::Instance::new(&wgpu::InstanceDescriptor{
            backends:wgpu::Backends::VULKAN,..Default::default()});
        let adapter=pollster::block_on(instance.request_adapter(&Default::default())).unwrap();
        let (device,queue)=pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor{
            label:Some("SVP resident exact search regression"),required_features:wgpu::Features::empty(),
            required_limits:wgpu::Limits::default().using_resolution(adapter.limits()),
            memory_hints:wgpu::MemoryHints::MemoryUsage,
        },None)).unwrap();
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let (w,h)=(66u32,34u32);
        let (gw,gh)=(w.div_ceil(16),h.div_ceil(16));
        let size=u64::from(gw*gh*32);
        let output=device.create_buffer(&wgpu::BufferDescriptor{label:None,size,
            usage:wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_SRC,mapped_at_creation:false});
        let staging=device.create_buffer(&wgpu::BufferDescriptor{label:None,size,
            usage:wgpu::BufferUsages::MAP_READ|wgpu::BufferUsages::COPY_DST,mapped_at_creation:false});
        let shader=device.create_shader_module(wgpu::ShaderModuleDescriptor{label:None,
            source:wgpu::ShaderSource::Wgsl(MOTION.into())});
        let pipeline=device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor{
            label:None,layout:None,module:&shader,entry_point:Some("analyse"),
            compilation_options:Default::default(),cache:None});
        let upload=|data:&[u8]|{
            let extent=wgpu::Extent3d{width:w,height:h,depth_or_array_layers:1};
            let t=device.create_texture(&wgpu::TextureDescriptor{label:None,size:extent,
                mip_level_count:1,sample_count:1,dimension:wgpu::TextureDimension::D2,
                format:wgpu::TextureFormat::R8Unorm,view_formats:&[],
                usage:wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::COPY_DST});
            queue.write_texture(t.as_image_copy(),data,wgpu::TexelCopyBufferLayout{
                offset:0,bytes_per_row:Some(w),rows_per_image:Some(h)},extent);
            t
        };
        let pattern=|shift:u32|->Vec<u8>{(0..w*h).map(|i|{
            let x=(i%w+shift)%w;let y=i/w;((x*17+y*31+x*y*3)%256) as u8
        }).collect()};
        let base=pattern(0);
        let shifted=pattern(4);
        let fractional=pattern(1);
        let unrelated:Vec<u8>=(0..w*h).map(|i|((i*31+i/7+31)%256) as u8).collect();
        let flat=vec![80; (w*h) as usize];
        let flat_other=vec![120; (w*h) as usize];
        for (case,(a,b)) in [(&base,&base),(&base,&shifted),(&base,&fractional),
            (&base,&unrelated),(&flat,&flat_other)].into_iter().enumerate(){
            let at=upload(a);let bt=upload(b);
            let av=at.create_view(&Default::default());let bv=bt.create_view(&Default::default());
            let params=uniform(&device,[w,h,gw,gh]);
            let group=device.create_bind_group(&wgpu::BindGroupDescriptor{label:None,
                layout:&pipeline.get_bind_group_layout(0),entries:&[
                    view(0,&av),view(1,&bv),
                    wgpu::BindGroupEntry{binding:2,resource:output.as_entire_binding()},
                    wgpu::BindGroupEntry{binding:3,resource:params.as_entire_binding()},
                ]});
            let mut encoder=device.create_command_encoder(&Default::default());
            {let mut pass=encoder.begin_compute_pass(&Default::default());
                pass.set_pipeline(&pipeline);pass.set_bind_group(0,&group,&[]);
                pass.dispatch_workgroups(gw,gh*2,1);}
            encoder.copy_buffer_to_buffer(&output,0,&staging,0,size);
            queue.submit([encoder.finish()]);
            let (tx,rx)=std::sync::mpsc::sync_channel(1);
            staging.slice(..).map_async(wgpu::MapMode::Read,move|r|{let _=tx.send(r);});
            let deadline=std::time::Instant::now()+std::time::Duration::from_secs(20);
            loop{
                device.poll(wgpu::Maintain::Poll);
                match rx.try_recv(){
                    Ok(r)=>{r.unwrap();break;}
                    Err(std::sync::mpsc::TryRecvError::Disconnected)=>panic!("map disconnected"),
                    Err(_) if std::time::Instant::now()>=deadline=>panic!("resident motion timeout"),
                    Err(_)=>std::thread::sleep(std::time::Duration::from_millis(1)),
                }
            }
            let bytes=staging.slice(..).get_mapped_range();
            for (i,raw) in bytes.chunks_exact(16).enumerate(){
                let actual:[i32;4]=std::array::from_fn(|j|i32::from_le_bytes(raw[j*4..j*4+4].try_into().unwrap()));
                let i=i as u32;let reverse=i>=gw*gh;
                let (x,y)=(i%gw,i/gw%gh);
                let expected=if reverse{reference(b,a,w,h,x,y)}else{reference(a,b,w,h,x,y)};
                assert_eq!(actual,expected,"case={case}, block={x},{y}, reverse={reverse}");
            }
            drop(bytes);staging.unmap();
        }
        assert!(pollster::block_on(device.pop_error_scope()).is_none());
    }
}
