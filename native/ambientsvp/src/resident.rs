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

/// The caller owns the renderer/decoder device and its submission order.
/// Y and UV are views of actual R8Unorm/Rg8Unorm planes (including
/// plane views of NV12 where TEXTURE_FORMAT_NV12 is supported). For I420
/// use separate R8Unorm U and V views. These views must be on the same
/// device and cover the configured visible dimensions, without padding.
pub enum Chroma<'a>{
    Nv12(&'a wgpu::TextureView),
    I420{u:&'a wgpu::TextureView,v:&'a wgpu::TextureView},
}
pub struct Picture<'a>{pub y:&'a wgpu::TextureView,pub chroma:Chroma<'a>}

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
            || u64::from(width)*u64::from(height)>MAX_PIXELS
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
        let flags=af|bf<<1;
        let matrix=match matrix{ColorMatrix::Bt601Limited=>0,ColorMatrix::Bt709Limited=>1,
            ColorMatrix::Bt601Full=>2,ColorMatrix::Bt709Full=>3};
        let params=uniform(&e.device,[phase.to_bits(),flags,matrix,e.grid_w]);
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
const MOTION:&str=r#"
@group(0) @binding(0) var a:texture_2d<f32>;
@group(0) @binding(1) var b:texture_2d<f32>;
@group(0) @binding(2) var<storage,read_write> fields:array<vec4i>;
@group(0) @binding(3) var<uniform> geometry:vec4u;
var<workgroup> scores:array<u32,64>;
var<workgroup> vectors:array<vec2i,64>;
fn px(t:texture_2d<f32>,p:vec2i)->i32{
    return i32(round(textureLoad(t,p,0).x*255.0));
}
fn cost(origin:vec2i,mv:vec2i,reverse:bool)->u32{
    let size=vec2i(geometry.xy);
    var sad=0u;
    var n=0u;
    for(var y=0;y<16;y+=2){
        for(var x=0;x<16;x+=2){
            let p=origin+vec2i(x,y);
            if any(p>=size){continue;}
            let q=p+mv;
            if any(q<vec2i(0))||any(q>=size){
                sad+=255u;
            }else if reverse{sad+=u32(abs(px(b,p)-px(a,q)));}
            else{sad+=u32(abs(px(a,p)-px(b,q)));}
            n+=1u;
        }
    }
    // Normalize partial blocks, and prefer smaller displacements on ties.
    let avg=sad*64u/max(n,1u);
    return avg*128u+u32(abs(mv.x)+abs(mv.y));
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
    let reverse=g.y>=geometry.w;
    let gy=g.y%geometry.w;
    let origin=vec2i(g.x*16u,gy*16u);
    scores[lane]=0xffffffffu;vectors[lane]=vec2i(0);
    for(var i=lane;i<81u;i+=64u){
        let mv=(vec2i(i32(i%9u),i32(i/9u))-vec2i(4))*4;
        choose(lane,mv,cost(origin,mv,reverse));
    }
    reduce(lane);
    let pred=vectors[0];
    let best=scores[0];
    workgroupBarrier();
    scores[lane]=0xffffffffu;vectors[lane]=vec2i(0);
    if lane==0u{choose(lane,pred,best);}
    if lane<25u{
        let mv=pred+vec2i(i32(lane%5u)-2,i32(lane/5u)-2);
        choose(lane,mv,cost(origin,mv,reverse));
    }
    reduce(lane);
    if lane==0u{
        let count=geometry.z*geometry.w;
        let index=g.x+gy*geometry.z+select(0u,count,reverse);
        fields[index]=vec4i(vectors[0],i32(scores[0]/128u),0);
    }
}
"#;

// Texture-only output. Bilinear luma/chroma sampling is explicit so
// unfilterable imported plane views can be accepted by the same layout.
// The output renderer samples the resulting RGBA texture directly.
const SYNTHESIS:&str=r#"
@group(0) @binding(0) var ay:texture_2d<f32>;
@group(0) @binding(1) var au:texture_2d<f32>;
@group(0) @binding(2) var av:texture_2d<f32>;
@group(0) @binding(3) var by:texture_2d<f32>;
@group(0) @binding(4) var bu:texture_2d<f32>;
@group(0) @binding(5) var bv:texture_2d<f32>;
@group(0) @binding(6) var<storage,read> fields:array<vec4i>;
@group(0) @binding(7) var dst:texture_storage_2d<rgba8unorm,write>;
@group(0) @binding(8) var<uniform> params:vec4u;
fn bilinear(t:texture_2d<f32>,p:vec2f)->vec4f{
    let hi=vec2i(textureDimensions(t))-vec2i(1);
    let q=clamp(p,vec2f(0),vec2f(hi));
    let i=vec2i(floor(q));
    let j=min(i+vec2i(1),hi);
    let f=fract(q);
    let a=mix(textureLoad(t,i,0),textureLoad(t,vec2i(j.x,i.y),0),f.x);
    let b=mix(textureLoad(t,vec2i(i.x,j.y),0),textureLoad(t,j,0),f.x);
    return mix(a,b,f.y);
}
fn yuv(y:texture_2d<f32>,u:texture_2d<f32>,v:texture_2d<f32>,p:vec2f,nv:bool)->vec3f{
    let chroma=p*0.5;
    let uv=bilinear(u,chroma);
    let vv=select(bilinear(v,chroma).x,uv.y,nv);
    return vec3f(bilinear(y,p).x,uv.x,vv);
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
    let size=textureDimensions(dst);
    if any(id.xy>=size){return;}
    let gy=(size.y+15u)/16u;
    let index=id.x/16u+id.y/16u*params.w;
    let f=fields[index];
    let r=fields[index+params.w*gy];
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
    let a=yuv(ay,au,av,ap,(params.y&1u)!=0u);
    let b=yuv(by,bu,bv,bp,(params.y&2u)!=0u);
    textureStore(dst,vec2i(id.xy),vec4f(rgb(mix(a,b,blend)),1.0));
}
"#;
