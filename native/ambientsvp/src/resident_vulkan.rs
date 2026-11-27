//! Native Vulkan entry points for the GPU-resident interpolation kernels.
//! The player supplies real VkImageViews on its existing VkDevice. No
//! wgpu re-wrapping, external layout guessing, pixel mapping or readback.
//! All command submission, image transitions and external-memory acquire/
//! release remain the caller's responsibility. See ambientsvp_vulkan.h.
use ash::vk;
use std::{sync::{Arc,Mutex,atomic::{AtomicBool,AtomicU32,Ordering}},panic::{catch_unwind,AssertUnwindSafe},ptr};
use crate::resident::{MOTION,SYNTHESIS};
#[path="resident_hdr_stats.rs"]
mod hdr_stats;
use hdr_stats::{HdrWindow,HdrResult,HdrTrack,StatsJob,StatsPipelines};

const MAX_PHASES:usize=8;
const MAX_IN_FLIGHT:u32=4;
const MAX_PIXELS:u64=1920*1080;
type Result<T>=std::result::Result<T,String>;
fn err(e:vk::Result)->String{format!("Vulkan result {}",e.as_raw())}
fn spirv(source:&str,uniform:&str,entry:&str)->Result<Vec<u32>>{
    if source.matches(uniform).count()!=1{return Err("invalid resident uniform contract".into());}
    let name=if matches!(entry,"analyse"|"scene"){"geometry"}else{"params"};
    let source=source.replace(uniform,&format!("var<push_constant> {name}:vec4u;"));
    let module=naga::front::wgsl::parse_str(&source).map_err(|e|e.emit_to_string(&source))?;
    let info=naga::valid::Validator::new(naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::PUSH_CONSTANT|naga::valid::Capabilities::STORAGE_TEXTURE_16BIT_NORM_FORMATS).validate(&module).map_err(|e|e.to_string())?;
    naga::back::spv::write_vec(&module,&info,&Default::default(),Some(&naga::back::spv::PipelineOptions{
        shader_stage:naga::ShaderStage::Compute,entry_point:entry.into(),
    })).map_err(|e|e.to_string())
}
fn motion_spirv()->Result<Vec<u32>>{
    spirv(MOTION,"@group(0) @binding(3) var<uniform> geometry:vec4u;","analyse")
}
fn synthesis_spirv()->Result<Vec<u32>>{
    spirv(SYNTHESIS,"@group(0) @binding(8) var<uniform> params:vec4u;","synthesize")
}
// High-depth sessions use separate shader variants, not a mutable
// per-frame interpretation of the legacy RGBA8 ABI. R16 UNORM stores
// P010 in the high ten bits and planar 10-bit in the low ten bits.
fn high_motion_spirv(mode:u32)->Result<Vec<u32>>{
    let scale=match mode{1|4=>"65535.0/64.0",2|5=>"65535.0",3=>"1023.0",_=>return Err("unknown high-depth mode".into())};
    for (anchor,count) in [("luminance*255.0",1),("sad+=255u;",1),("i32(scores[0]/128u)",2)]{
        if MOTION.matches(anchor).count()!=count{return Err("high-depth motion shader contract changed".into());}
    }
    // Search on all 1024 luma levels. Keep the existing synthesis cut
    // threshold in approximately eight-bit SAD units; selection itself
    // uses the complete ten-bit SAD, including clipped block penalties.
    let source=MOTION.replace("luminance*255.0",&format!("clamp(luminance*({scale}),0.0,1023.0)"))
        .replace("sad+=255u;","sad+=1023u;")
        .replace("i32(scores[0]/128u)","i32(scores[0]/512u)");
    spirv(&source,"@group(0) @binding(3) var<uniform> geometry:vec4u;","analyse")
}
fn high_synthesis_spirv(mode:u32)->Result<Vec<u32>>{high_synthesis_config(mode,None)}
fn high_synthesis_config(mode:u32,t:Option<ResidentTuning>)->Result<Vec<u32>>{
    if mode==3{
        let mut source=SYNTHESIS.replace("rgba8unorm,write","rgba16float,write");
        if let Some(t)=t{source=tuned_synthesis(&source,t)?;}
        return spirv(&source,"@group(0) @binding(8) var<uniform> params:vec4u;","synthesize");
    }
    let scale=match mode{1=>"65535.0/64.0",2=>"65535.0",_=>return Err("unknown high-depth mode".into())};
    let old_start=SYNTHESIS.find("fn rgb(yuv:vec3f)").ok_or("missing RGB kernel")?;
    let old_end=old_start+SYNTHESIS[old_start..].find("@compute").ok_or("missing synthesis entry")?;
    let conversion=format!(r#"fn rgb(yuv:vec3f)->vec3f{{
    let full=(params.z&2u)!=0u;
    let bt2020=(params.z&4u)!=0u;
    let code=yuv*({scale});
    let y=select((code.x-64.0)/876.0,code.x/1023.0,full);
    let uv=(code.yz-vec2f(512.0))/select(896.0,1023.0,full);
    let kr=select(select(0.299,0.2126,(params.z&1u)!=0u),0.2627,bt2020);
    let kb=select(select(0.114,0.0722,(params.z&1u)!=0u),0.0593,bt2020);
    let r=y+2.0*(1.0-kr)*uv.y;
    let b=y+2.0*(1.0-kb)*uv.x;
    let g=(y-kr*r-kb*b)/(1.0-kr-kb);
    // Keep encoded R'G'B' and headroom for the renderer's HDR pipeline.
    // No EOTF, tone mapping or clipping in the interpolation transport.
    return vec3f(r,g,b);
}}
"#);
    let mut source=format!("{}{}{}",&SYNTHESIS[..old_start],conversion,&SYNTHESIS[old_end..])
        .replace("rgba8unorm,write","rgba16float,write");
    if let Some(t)=t{source=tuned_synthesis(&source,t)?;}
    spirv(&source,"@group(0) @binding(8) var<uniform> params:vec4u;","synthesize")
}

fn raw_synthesis_spirv(mode:u32)->Result<Vec<u32>>{raw_synthesis_config(mode,None)}
fn raw_synthesis_config(mode:u32,t:Option<ResidentTuning>)->Result<Vec<u32>>{
    if !matches!(mode,4|5){return Err("invalid raw YUV format".into());}
    // Reuse the exact bilinear sampler and motion-field layout. Do not
    // use any RGB conversion, transfer function or Dolby reshaping here.
    let prefix=SYNTHESIS.find("fn yuv(").ok_or("missing bilinear shader")?;
    let mut source=SYNTHESIS[..prefix].replace(
        "@group(0) @binding(7) var dst:texture_storage_2d<rgba8unorm,write>;",
        if mode==4{
            "@group(0) @binding(7) var dy:texture_storage_2d<r16unorm,write>;\n@group(0) @binding(8) var du:texture_storage_2d<rg16unorm,write>;"
        }else{
            "@group(0) @binding(7) var dy:texture_storage_2d<r16unorm,write>;\n@group(0) @binding(8) var du:texture_storage_2d<r16unorm,write>;\n@group(0) @binding(9) var dv:texture_storage_2d<r16unorm,write>;"
        }).replace("@group(0) @binding(8) var<uniform> params:vec4u;",
            "@group(0) @binding(10) var<uniform> params:vec4u;");
    let quant=if mode==4{"round(clamp(value*65535.0/64.0,vec4f(0),vec4f(1023)))*64.0/65535.0"}
        else{"round(clamp(value*65535.0,vec4f(0),vec4f(1023)))/65535.0"};
    source.push_str(&format!("fn pack10(value:vec4f)->vec4f{{return {quant};}}\n"));
    source.push_str(r#"
fn sample_pair(a:texture_2d<f32>,b:texture_2d<f32>,ap:vec2f,bp:vec2f,blend:f32,visible:vec2u)->vec4f{
    if blend==0.0{return bilinear(a,ap,visible);}
    if blend==1.0{return bilinear(b,bp,visible);}
    return mix(bilinear(a,ap,visible),bilinear(b,bp,visible),blend);
}
@compute @workgroup_size(8,8)
fn synthesize(@builtin(global_invocation_id) id:vec3u){
    let visible=vec2u(params.w&65535u,params.w>>16u);
    if any(id.xy>=visible)||any(id.xy>=textureDimensions(dy)){return;}
    let grid=(visible+vec2u(15))/16u;
    let index=id.x/16u+id.y/16u*grid.x;
    let f=fields[index];
    let r=fields[index+grid.x*grid.y];
    let phase=bitcast<f32>(params.x);
    let p=vec2f(id.xy);
    var ap=p-vec2f(f.xy)*phase;
    var bp=p-vec2f(r.xy)*(1.0-phase);
    var blend=phase;
    if f.z>64*48||r.z>64*48||abs(f.x+r.x)+abs(f.y+r.y)>6{
        ap=p;bp=p;blend=select(0.0,1.0,phase>=0.5);
    }
    textureStore(dy,vec2i(id.xy),pack10(sample_pair(ay,by,ap,bp,blend,visible)));
    // Identical 4:2:0 chroma siting on both sources and output. The
    // sample-center offsets cancel, so only motion is scaled by 1/2.
    if all((id.xy&vec2u(1))==vec2u(0)){
        let cp=id.xy/2u;
        textureStore(du,vec2i(cp),pack10(sample_pair(au,bu,ap*0.5,bp*0.5,blend,visible/2u)));
"#);
    if mode==5{source.push_str("        textureStore(dv,vec2i(cp),pack10(sample_pair(av,bv,ap*0.5,bp*0.5,blend,visible/2u)));\n");}
    source.push_str("    }\n}\n");
    if let Some(t)=t{source=tuned_synthesis(&source,t)?;}
    spirv(&source,"@group(0) @binding(10) var<uniform> params:vec4u;","synthesize")
}
/// Optional configured quality path. Legacy callers keep the original
/// fixed grid and kernels. All lengths are explicit pixels/pel units.
#[repr(C)]
#[derive(Clone,Copy,Debug)]
pub struct ResidentTuning{
    pub block:u32,pub overlap:u32,pub pel:u32,pub radius:u32,
    pub satd:u32,pub chroma:u32,pub lambda:u32,pub pnew:u32,
    pub scene:u32,pub artifacts:u32,
}
impl ResidentTuning{
    fn valid(self)->bool{
        matches!(self.block,8|16|32)&&self.overlap<=self.block/2&&
        (self.overlap==0||[self.block/8,self.block/4,self.block/2].contains(&self.overlap))&&
        (self.block-self.overlap)%2==0&&matches!(self.pel,1|2|4)&&
        (1..=4).contains(&self.radius)&&self.satd<=1&&self.chroma<=1&&
        self.lambda<=4096&&self.pnew<=100&&(1..=100).contains(&self.scene)&&self.artifacts<=3
    }
    fn step(self)->u32{self.block-self.overlap}
}
fn tuned_motion_spirv(mode:u32,t:ResidentTuning)->Result<Vec<u32>>{
    let sample=t.block/8;
    let mut source=MOTION.replace("y<16;y+=2",&format!("y<{};y+={sample}",t.block))
        .replace("x<16;x+=2",&format!("x<{};x+={sample}",t.block))
        .replace("y/2*8+x/2",&format!("y/{sample}*8+x/{sample}"))
        .replace("gx*16u,gy*16u",&format!("gx*{}u,gy*{}u",t.step(),t.step()))
        .replace("i32(lane%8u)*2,i32(lane/8u)*2",&format!("i32(lane%8u)*{sample},i32(lane/8u)*{sample}"));
    if mode!=0{
        let scale=match mode{1|4=>"65535.0/64.0",2|5=>"65535.0",3=>"1023.0",_=>return Err("unknown tuning format".into())};
        source=source.replace("luminance*255.0",&format!("clamp(luminance*({scale}),0.0,1023.0)"))
            .replace("sad+=255u;","sad+=1023u;").replace("i32(scores[0]/128u)","i32(scores[0]/512u)");
    }
    spirv(&source,"@group(0) @binding(3) var<uniform> geometry:vec4u;","analyse")
}
fn quality_spirv(mode:u32,t:ResidentTuning,entry:&str)->Result<Vec<u32>>{
    let scale=match mode{0=>"255.0",1|4=>"65535.0/64.0",2|5=>"65535.0",3=>"1023.0",_=>return Err("unknown quality format".into())};
    let mut source=include_str!("resident_quality.wgsl").to_owned();
    for (name,value) in [("SVP_BLOCK",t.block.to_string()),("SVP_STEP",t.step().to_string()),
        ("SVP_PEL",t.pel.to_string()),("SVP_RADIUS",t.radius.to_string()),("SVP_SCALE",scale.into()),
        ("SVP_CODE_MAX",if mode==0{"255"}else{"1023"}.into()),("SVP_SATD",(t.satd!=0).to_string()),
        ("SVP_CHROMA",(t.chroma!=0).to_string()),("SVP_LAMBDA",format!("{}u",t.lambda)),
        ("SVP_PNEW",format!("{}u",t.pnew)),("SVP_SCENE",format!("{}u",t.scene))]{
        if source.matches(name).count()!=1{return Err(format!("quality shader placeholder {name}"));}
        source=source.replace(name,&value);
    }
    spirv(&source,"@group(0) @binding(7) var<uniform> geometry:vec4u;",entry)
}
fn tuned_synthesis(source:&str,t:ResidentTuning)->Result<String>{
    let entry=source.find("@compute @workgroup_size(8,8)").ok_or("tuned synthesis entry")?;
    let threshold=match t.artifacts{1=>64,2=>48,3=>32,_=>48};
    let mismatch=match t.artifacts{1=>8,2=>6,3=>4,_=>6};
    let helper=format!(r#"
struct BlendGrid {{ indices:vec4u, weights:vec4f }}
struct Warp {{ ap:vec2f, bp:vec2f, blend:f32 }}
fn decode_mv(v:vec4i)->vec4f{{
    return vec4f(vec2f(v.xy)/f32(max(v.w,1)),f32(v.z),1.0);
}}
fn overlap_grid(p:vec2u,grid:vec2u)->BlendGrid{{
    let cell=min(p/{step}u,grid-vec2u(1));
    let prev=cell-min(cell,vec2u(1));
    let frac=clamp((vec2f(p-cell*{step}u)+vec2f(0.5))/f32(max({overlap}u,1u)),vec2f(0),vec2f(1));
    let rise=0.5-0.5*cos(3.141592653589793*frac);
    let w=select(rise,vec2f(1),cell==vec2u(0));
    let x=select(w.x,1.0,{overlap}u==0u);
    let y=select(w.y,1.0,{overlap}u==0u);
    return BlendGrid(vec4u(prev.x+prev.y*grid.x,cell.x+prev.y*grid.x,
        prev.x+cell.y*grid.x,cell.x+cell.y*grid.x),
        vec4f((1.0-x)*(1.0-y),x*(1.0-y),(1.0-x)*y,x*y));
}}
fn warp_block(p:vec2f,grid:vec2u,index:u32,phase:f32)->Warp{{
    let n=grid.x*grid.y;
    let f=decode_mv(fields[index]);let r=decode_mv(fields[index+n]);
    if phase==0.0||phase==1.0{{return Warp(p,p,phase);}}
    if fields[2u*n].x!=0||f.z>64*{threshold}||r.z>64*{threshold}||
        abs(f.x+r.x)+abs(f.y+r.y)>{mismatch}{{
        return Warp(p,p,select(0.0,1.0,phase>=0.5));
    }}
    return Warp(p-f.xy*phase,p-r.xy*(1.0-phase),phase);
}}
"#,step=t.step(),overlap=t.overlap,threshold=threshold,mismatch=mismatch);
    let mut result=source[..entry].to_owned();
    result.push_str(&helper);
    if source.contains("var dy:texture_storage"){
        result.push_str(r#"
@compute @workgroup_size(8,8)
fn synthesize(@builtin(global_invocation_id) id:vec3u){
    let visible=vec2u(params.w&65535u,params.w>>16u);
    if any(id.xy>=visible)||any(id.xy>=textureDimensions(dy)){return;}
    let grid=(visible+vec2u(SVP_STEP-1u))/SVP_STEP;
    let phase=bitcast<f32>(params.x);
    let blocks=overlap_grid(id.xy,grid);
    let p=vec2f(id.xy);
    var y=vec4f(0);var u=vec4f(0);var v=vec4f(0);
    let chroma=all((id.xy&vec2u(1))==vec2u(0));
    for(var i=0u;i<4u;i++){
        let weight=blocks.weights[i];
        if weight<=0.0{continue;}
        let m=warp_block(p,grid,blocks.indices[i],phase);
        y+=weight*sample_pair(ay,by,m.ap,m.bp,m.blend,visible);
        if chroma{
            u+=weight*sample_pair(au,bu,m.ap*0.5,m.bp*0.5,m.blend,visible/2u);
            SVP_V
        }
    }
    textureStore(dy,vec2i(id.xy),pack10(y));
    if chroma{
        let cp=vec2i(id.xy/2u);
        textureStore(du,cp,pack10(u));
        SVP_STORE_V
    }
}
"#);
        result=result.replace("SVP_STORE_V",if source.contains("var dv:texture_storage"){"textureStore(dv,cp,pack10(v));"}else{""})
            .replace("SVP_V",if source.contains("var dv:texture_storage"){"v+=weight*sample_pair(av,bv,m.ap*0.5,m.bp*0.5,m.blend,visible/2u);"}else{""});
    }else{
        result.push_str(r#"
fn sample_warp(m:Warp,visible:vec2u)->vec3f{
    if (params.y&64u)!=0u{
        if m.blend==0.0{return bilinear(ay,m.ap,visible).rgb;}
        if m.blend==1.0{return bilinear(by,m.bp,visible).rgb;}
        return mix(bilinear(ay,m.ap,visible).rgb,bilinear(by,m.bp,visible).rgb,m.blend);
    }
    if m.blend==0.0{return yuv(ay,au,av,m.ap,(params.y&1u)!=0u,(params.y>>2u)&3u,visible);}
    if m.blend==1.0{return yuv(by,bu,bv,m.bp,(params.y&2u)!=0u,(params.y>>4u)&3u,visible);}
    return mix(yuv(ay,au,av,m.ap,(params.y&1u)!=0u,(params.y>>2u)&3u,visible),
        yuv(by,bu,bv,m.bp,(params.y&2u)!=0u,(params.y>>4u)&3u,visible),m.blend);
}
@compute @workgroup_size(8,8)
fn synthesize(@builtin(global_invocation_id) id:vec3u){
    let visible=vec2u(params.w&65535u,params.w>>16u);
    if any(id.xy>=visible)||any(id.xy>=textureDimensions(dst)){return;}
    let grid=(visible+vec2u(SVP_STEP-1u))/SVP_STEP;
    let phase=bitcast<f32>(params.x);
    let blocks=overlap_grid(id.xy,grid);
    var pixel=vec3f(0);
    for(var i=0u;i<4u;i++){
        let weight=blocks.weights[i];
        if weight<=0.0{continue;}
        pixel+=weight*sample_warp(warp_block(vec2f(id.xy),grid,blocks.indices[i],phase),visible);
    }
    // Sum overlapped warped YUV blocks before the one RGB conversion.
    // Imported RGB inputs never repeat the matrix conversion.
    if (params.y&64u)==0u{pixel=rgb(pixel);}
    textureStore(dst,vec2i(id.xy),vec4f(pixel,1.0));
}
"#);
    }
    Ok(result.replace("SVP_STEP",&format!("{}u",t.step())))
}
fn raw_planes(mode:u32)->usize{match mode{4=>2,5=>3,_=>1}}
struct Pipeline{
    device:ash::Device,
    set:vk::DescriptorSetLayout,
    layout:vk::PipelineLayout,
    pipeline:vk::Pipeline,
}
impl Drop for Pipeline{
    fn drop(&mut self){unsafe{
        self.device.destroy_pipeline(self.pipeline,None);
        self.device.destroy_pipeline_layout(self.layout,None);
        self.device.destroy_descriptor_set_layout(self.set,None);
    }}
}
impl Pipeline{
    fn new(device:&ash::Device,types:&[vk::DescriptorType],words:&[u32],entry:&std::ffi::CStr)->Result<Self>{
        let mut p=Self{device:device.clone(),set:vk::DescriptorSetLayout::null(),
            layout:vk::PipelineLayout::null(),pipeline:vk::Pipeline::null()};
        let bindings=types.iter().enumerate().map(|(i,&t)|vk::DescriptorSetLayoutBinding::default()
            .binding(i as u32).descriptor_count(1).descriptor_type(t).stage_flags(vk::ShaderStageFlags::COMPUTE)
        ).collect::<Vec<_>>();
        unsafe{
            p.set=device.create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),None).map_err(err)?;
            let layouts=[p.set];
            let ranges=[vk::PushConstantRange::default().stage_flags(vk::ShaderStageFlags::COMPUTE).offset(0).size(16)];
            p.layout=device.create_pipeline_layout(&vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts)
                .push_constant_ranges(&ranges),None).map_err(err)?;
            let shader=device.create_shader_module(&vk::ShaderModuleCreateInfo::default().code(words),None).map_err(err)?;
            let stage=vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::COMPUTE).module(shader).name(entry);
            let result=device.create_compute_pipelines(vk::PipelineCache::null(),
                &[vk::ComputePipelineCreateInfo::default().stage(stage).layout(p.layout)],None);
            device.destroy_shader_module(shader,None);
            p.pipeline=match result{
                Ok(pipelines)=>pipelines[0],
                Err((partial,e))=>{for pipeline in partial{device.destroy_pipeline(pipeline,None);}return Err(err(e));}
            };
        }
        Ok(p)
    }
    unsafe fn dispatch(&self,cmd:vk::CommandBuffer,set:vk::DescriptorSet,words:[u32;4],x:u32,y:u32){
        let bytes:Vec<u8>=words.into_iter().flat_map(u32::to_ne_bytes).collect();
        unsafe{
            self.device.cmd_bind_pipeline(cmd,vk::PipelineBindPoint::COMPUTE,self.pipeline);
            self.device.cmd_bind_descriptor_sets(cmd,vk::PipelineBindPoint::COMPUTE,self.layout,0,&[set],&[]);
            self.device.cmd_push_constants(cmd,self.layout,vk::ShaderStageFlags::COMPUTE,0,&bytes);
            self.device.cmd_dispatch(cmd,x,y,1);
        }
    }
}

// Descriptor sets and device-local fields are private to one pair until
// its exact submission fence retires. Cache only fully initialized bundles.
struct PairResources{
    device:ash::Device,pool:vk::DescriptorPool,field:vk::Buffer,
    memory:vk::DeviceMemory,sets:Vec<vk::DescriptorSet>,
}
impl Drop for PairResources{
    fn drop(&mut self){unsafe{
        self.device.destroy_descriptor_pool(self.pool,None);
        self.device.destroy_buffer(self.field,None);
        self.device.free_memory(self.memory,None);
    }}
}
struct Core{
    device:ash::Device,
    memory:vk::PhysicalDeviceMemoryProperties,
    motion:Pipeline,synthesis:Pipeline,
    quality:Option<(Pipeline,Pipeline)>,tuning:Option<ResidentTuning>,
    width:u32,height:u32,gw:u32,gh:u32,field_size:u64,mode:u32,
    in_flight:AtomicU32,
    idle:Mutex<Vec<PairResources>>,
    lost:AtomicBool,
    stats_supported:bool,
    stats:Mutex<Option<Arc<StatsPipelines>>>,
}
pub struct VulkanResident(Arc<Core>);
#[repr(C)]
#[derive(Clone,Copy)]
pub struct VulkanPicture{
    y:vk::ImageView,u:vk::ImageView,v:vk::ImageView,
    // 0: separate R8 U/V; 1: RG8 interleaved UV; 2: SDR RGB in y.
    nv12:u32,
    chroma_location:u32,
}
impl VulkanPicture{
    fn valid(self)->bool{
        self.nv12<=2 && self.chroma_location<=2 && self.y!=vk::ImageView::null()
            && (self.nv12==2 || self.u!=vk::ImageView::null()
            && (self.nv12==1 || self.v!=vk::ImageView::null()))
    }
    fn views(self)->[vk::ImageView;3]{
        if self.nv12==2{[self.y;3]}else{[self.y,self.u,if self.nv12==1{self.u}else{self.v}]}
    }
    fn rgb(self)->bool{self.nv12==2}
}

pub struct VulkanPair{
    core:Arc<Core>,
    pool:vk::DescriptorPool,
    field:vk::Buffer,
    memory:vk::DeviceMemory,
    sets:Vec<vk::DescriptorSet>,
    command:vk::CommandBuffer,
    a:VulkanPicture,b:VulkanPicture,
    phases:usize,
    rgb_outputs:[vk::ImageView;MAX_PHASES],
    hdr_jobs:[Option<StatsJob>;MAX_PHASES],
    fence:vk::Fence,
    #[cfg(target_os="android")]
    ahb_inputs:Option<[*const crate::ahb_vulkan::Job;2]>,
}
impl Drop for VulkanPair{
    fn drop(&mut self){unsafe{
        let d=&self.core.device;
        d.destroy_descriptor_pool(self.pool,None);
        d.destroy_buffer(self.field,None);
        d.free_memory(self.memory,None);
        #[cfg(target_os="android")]
        if let Some(inputs)=self.ahb_inputs.take(){for p in inputs{(&*p).release_consumer();}}
        self.core.in_flight.fetch_sub(1,Ordering::AcqRel);
    }}
}
impl VulkanPair{
    fn new(core:Arc<Core>,command:vk::CommandBuffer,a:VulkanPicture,b:VulkanPicture)->Result<Self>{
        if core.lost.load(Ordering::Acquire) || command==vk::CommandBuffer::null() || !a.valid() || !b.valid() || a.rgb()!=b.rgb()
            || (matches!(core.mode,1|4)&&(a.nv12!=1||b.nv12!=1)) || (matches!(core.mode,2|5)&&(a.nv12!=0||b.nv12!=0)) || (core.mode==3&&!a.rgb())
            || (core.mode>=4&&a.chroma_location!=b.chroma_location)
        {return Err("invalid resident pictures".into());}
        core.in_flight.fetch_update(Ordering::AcqRel,Ordering::Acquire,|n|
            (n<MAX_IN_FLIGHT).then_some(n+1)).map_err(|_|"resident in-flight budget")?;
        let mut p=Self{core,pool:vk::DescriptorPool::null(),field:vk::Buffer::null(),
            memory:vk::DeviceMemory::null(),sets:Vec::new(),command,a,b,phases:0,
            rgb_outputs:[vk::ImageView::null();MAX_PHASES],hdr_jobs:std::array::from_fn(|_|None),fence:vk::Fence::null(),
            #[cfg(target_os="android")]
            ahb_inputs:None};
        let cached=p.core.idle.lock().unwrap_or_else(|e|e.into_inner()).pop();
        if let Some(mut cached)=cached{
            p.pool=std::mem::replace(&mut cached.pool,vk::DescriptorPool::null());
            p.field=std::mem::replace(&mut cached.field,vk::Buffer::null());
            p.memory=std::mem::replace(&mut cached.memory,vk::DeviceMemory::null());
            p.sets=std::mem::take(&mut cached.sets);
            return Ok(p);
        }
        let c=&p.core;
        let d=&c.device;
        unsafe{
            p.field=d.create_buffer(&vk::BufferCreateInfo::default().size(c.field_size)
                .usage(vk::BufferUsageFlags::STORAGE_BUFFER).sharing_mode(vk::SharingMode::EXCLUSIVE),None).map_err(err)?;
            let req=d.get_buffer_memory_requirements(p.field);
            let memory_type=(0..c.memory.memory_type_count).find(|&i|
                req.memory_type_bits&(1u32<<i)!=0 &&
                c.memory.memory_types[i as usize].property_flags.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL))
                .ok_or("no device-local motion field memory")?;
            p.memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size)
                .memory_type_index(memory_type),None).map_err(err)?;
            d.bind_buffer_memory(p.field,p.memory,0).map_err(err)?;
            let extra=u32::from(c.quality.is_some());
            let pool_sizes=[
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::SAMPLED_IMAGE).descriptor_count(2+6*MAX_PHASES as u32+6*extra),
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::STORAGE_BUFFER).descriptor_count(1+MAX_PHASES as u32+extra),
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::STORAGE_IMAGE).descriptor_count(MAX_PHASES as u32*raw_planes(c.mode) as u32),
            ];
            p.pool=d.create_descriptor_pool(&vk::DescriptorPoolCreateInfo::default().max_sets(1+MAX_PHASES as u32+extra)
                .pool_sizes(&pool_sizes),None).map_err(err)?;
            let mut layouts=vec![c.synthesis.set;MAX_PHASES+1];layouts[0]=c.motion.set;
            if let Some((q,_))=&c.quality{layouts.push(q.set);}
            p.sets=d.allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(p.pool)
                .set_layouts(&layouts)).map_err(err)?;
        }
        Ok(p)
    }
    // Called only after successful fence retirement or explicit cancellation
    // of a discarded command buffer. The next pair rewrites every used set.
    fn recycle(&mut self){
        if self.core.lost.load(Ordering::Acquire){return;}
        let mut idle=self.core.idle.lock().unwrap_or_else(|e|e.into_inner());
        if idle.len()>=MAX_IN_FLIGHT as usize{return;}
        idle.push(PairResources{
            device:self.core.device.clone(),
            pool:std::mem::replace(&mut self.pool,vk::DescriptorPool::null()),
            field:std::mem::replace(&mut self.field,vk::Buffer::null()),
            memory:std::mem::replace(&mut self.memory,vk::DeviceMemory::null()),
            sets:std::mem::take(&mut self.sets),
        });
    }
    // One driver call per descriptor set, with stack-backed write
    // storage. No per-binding update or heap allocation on each phase.
    fn descriptors(&self,set:vk::DescriptorSet,images:&[(u32,vk::ImageView,vk::DescriptorType)],field_binding:u32){
        assert!(images.len()<=9);
        let mut info=[vk::DescriptorImageInfo::default();9];
        for (i,&(_,view,_)) in images.iter().enumerate(){
            info[i]=vk::DescriptorImageInfo::default().image_view(view).image_layout(vk::ImageLayout::GENERAL);
        }
        let field=[vk::DescriptorBufferInfo::default().buffer(self.field).offset(0).range(self.core.field_size)];
        let mut writes=[vk::WriteDescriptorSet::default();10];
        for (i,&(binding,_,ty)) in images.iter().enumerate(){
            writes[i]=vk::WriteDescriptorSet::default().dst_set(set).dst_binding(binding)
                .descriptor_type(ty).image_info(std::slice::from_ref(&info[i]));
        }
        writes[images.len()]=vk::WriteDescriptorSet::default().dst_set(set).dst_binding(field_binding)
            .descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&field);
        unsafe{self.core.device.update_descriptor_sets(&writes[..images.len()+1],&[]);}
    }
    unsafe fn analyse(&self){
        let c=&self.core;
        let set=self.sets[0];
        self.descriptors(set,&[(0,self.a.y,vk::DescriptorType::SAMPLED_IMAGE),
            (1,self.b.y,vk::DescriptorType::SAMPLED_IMAGE)],2);
        unsafe{
            let flags=c.gh|if self.a.rgb(){1<<31}else{0};
            let barrier=[vk::BufferMemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ|vk::AccessFlags::SHADER_WRITE)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(self.field).offset(0).size(c.field_size)];
            for pass in [0,1<<30,(1<<30)|(1<<29)]{
                c.motion.dispatch(self.command,set,[c.width,c.height,c.gw,flags|pass],c.gw,c.gh*2);
                c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
            }
            // Compact causal diagonals. Each workgroup handles one row
            // and direction, rejecting cells outside the active diagonal.
            // All winners remain in device-local fields.
            for wave in 0..c.gw+2*(c.gh-1){
                let mode=flags|(1<<28)|(wave<<16);
                c.motion.dispatch(self.command,set,[c.width,c.height,c.gw,mode],c.gh,2);
                c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
            }
            if let Some((quality,scene))=&c.quality{
                let set=self.sets[MAX_PHASES+1];
                let [ay,au,av]=self.a.views();let [by,bu,bv]=self.b.views();
                let sampled=vk::DescriptorType::SAMPLED_IMAGE;
                self.descriptors(set,&[(0,ay,sampled),(1,au,sampled),(2,av,sampled),
                    (3,by,sampled),(4,bu,sampled),(5,bv,sampled)],6);
                let flags=c.gh|((self.a.nv12==1) as u32)<<16|((self.b.nv12==1) as u32)<<17|
                    ((self.a.rgb()) as u32)<<18;
                quality.dispatch(self.command,set,[c.width,c.height,c.gw,flags],c.gw,c.gh*2);
                c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
                scene.dispatch(self.command,set,[c.width,c.height,c.gw,flags],1,1);
                c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
            }
        }
    }
    unsafe fn render(&mut self,phase:f32,matrix:u32,output:vk::ImageView)->Result<()>{
        if self.core.mode>=4 || self.fence!=vk::Fence::null() || self.phases>=MAX_PHASES || !phase.is_finite()
            || !(0.0..=1.0).contains(&phase) || (matrix>3 && (self.core.mode==0 || !matches!(matrix,4|6))) || output==vk::ImageView::null()
            || self.a.views().contains(&output) || self.b.views().contains(&output)
        {return Err("invalid resident output phase/view".into());}
        let set=self.sets[1+self.phases];
        let [ay,au,av]=self.a.views();
        let [by,bu,bv]=self.b.views();
        let sampled=vk::DescriptorType::SAMPLED_IMAGE;
        self.descriptors(set,&[(0,ay,sampled),(1,au,sampled),(2,av,sampled),
            (3,by,sampled),(4,bu,sampled),(5,bv,sampled),(7,output,vk::DescriptorType::STORAGE_IMAGE)],6);
        let c=&self.core;
        let flags=if self.a.rgb(){64}else{self.a.nv12|(self.b.nv12<<1)|(self.a.chroma_location<<2)|(self.b.chroma_location<<4)};
        unsafe{c.synthesis.dispatch(self.command,set,[phase.to_bits(),flags,matrix,c.width|(c.height<<16)],
            c.width.div_ceil(8),c.height.div_ceil(8));}
        self.rgb_outputs[self.phases]=output;
        self.phases+=1;
        Ok(())
    }
    unsafe fn render_raw(&mut self,phase:f32,outputs:&[vk::ImageView])->Result<()>{
        if self.core.mode<4 || outputs.len()!=raw_planes(self.core.mode) || self.fence!=vk::Fence::null()
            || self.phases>=MAX_PHASES || !phase.is_finite() || !(0.0..=1.0).contains(&phase)
            || outputs.iter().enumerate().any(|(i,v)|*v==vk::ImageView::null()||outputs[..i].contains(v)
                ||self.a.views().contains(v)||self.b.views().contains(v)){
            return Err("invalid raw resident output".into());
        }
        let set=self.sets[1+self.phases];
        let [ay,au,av]=self.a.views();let [by,bu,bv]=self.b.views();
        let sampled=vk::DescriptorType::SAMPLED_IMAGE;
        let storage=vk::DescriptorType::STORAGE_IMAGE;
        let images=[(0,ay,sampled),(1,au,sampled),(2,av,sampled),(3,by,sampled),(4,bu,sampled),(5,bv,sampled),
            (7,outputs[0],storage),(8,outputs[1],storage),(9,outputs.get(2).copied().unwrap_or(vk::ImageView::null()),storage)];
        self.descriptors(set,&images[..6+outputs.len()],6);
        let c=&self.core;
        unsafe{c.synthesis.dispatch(self.command,set,[phase.to_bits(),0,0,c.width|(c.height<<16)],
            c.width.div_ceil(8),c.height.div_ceil(8));}
        self.phases+=1;
        Ok(())
    }
}

fn new_core(instance:&ash::Instance,physical:vk::PhysicalDevice,device:ash::Device,width:u32,height:u32,mode:u32,tuning:Option<ResidentTuning>)->Result<VulkanResident>{
    if tuning.is_some_and(|t|!t.valid()){return Err("invalid resident tuning".into());}
    if width<32 || height<32 || width>3840 || height>2160 || (width|height)&1!=0 || u64::from(width)*u64::from(height)>MAX_PIXELS{
        return Err("unsupported resident dimensions".into());
    }
    let properties=unsafe{instance.get_physical_device_properties(physical)};
    let memory=unsafe{instance.get_physical_device_memory_properties(physical)};
    let limits=properties.limits;
    let step=tuning.map_or(16,ResidentTuning::step);
    let (gw,gh)=(width.div_ceil(step),height.div_ceil(step));
    if tuning.is_some() && u64::from(gw)*u64::from(gh)>32768{return Err("resident quality block budget".into());}
    let field_size=u64::from(gw)*u64::from(gh)*32+if tuning.is_some(){16}else{0};
    if width>limits.max_image_dimension2_d || height>limits.max_image_dimension2_d
        || limits.max_per_stage_descriptor_sampled_images<6 || limits.max_per_stage_descriptor_storage_images<raw_planes(mode) as u32
        || limits.max_per_stage_descriptor_storage_buffers<1 || limits.max_push_constants_size<16
        || limits.max_compute_work_group_invocations<64 || limits.max_compute_work_group_size[0]<64
        || limits.max_compute_work_group_size[1]<8 || gw>limits.max_compute_work_group_count[0]
        || gh*2>limits.max_compute_work_group_count[1] || width.div_ceil(8)>limits.max_compute_work_group_count[0]
        || height.div_ceil(8)>limits.max_compute_work_group_count[1] || field_size>u64::from(limits.max_storage_buffer_range)
    {return Err("native Vulkan resident limits".into());}
    let (formats,output)=match mode{
        0=>([vk::Format::R8_UNORM,vk::Format::R8G8_UNORM,vk::Format::R8G8B8A8_UNORM],vk::Format::R8G8B8A8_UNORM),
        1=>([vk::Format::R16_UNORM,vk::Format::R16G16_UNORM,vk::Format::R16G16B16A16_SFLOAT],vk::Format::R16G16B16A16_SFLOAT),
        2=>([vk::Format::R16_UNORM,vk::Format::R16_UNORM,vk::Format::R16G16B16A16_SFLOAT],vk::Format::R16G16B16A16_SFLOAT),
        3=>([vk::Format::R16G16B16A16_SFLOAT;3],vk::Format::R16G16B16A16_SFLOAT),
        4=>([vk::Format::R16_UNORM,vk::Format::R16G16_UNORM,vk::Format::R16_UNORM],vk::Format::R16_UNORM),
        5=>([vk::Format::R16_UNORM;3],vk::Format::R16_UNORM),
        _=>return Err("unknown native resident mode".into())
    };
    for format in formats{
        let f=unsafe{instance.get_physical_device_format_properties(physical,format)};
        let needed=if mode>=4 || format==output{
            vk::FormatFeatureFlags::STORAGE_IMAGE|vk::FormatFeatureFlags::SAMPLED_IMAGE
        }else{vk::FormatFeatureFlags::SAMPLED_IMAGE};
        if !f.optimal_tiling_features.contains(needed){return Err("resident image format unsupported".into());}
    }
    let motion_words=if let Some(t)=tuning{tuned_motion_spirv(mode,t)?}else if mode==0{motion_spirv()?}else{high_motion_spirv(mode)?};
    let motion=Pipeline::new(&device,&[vk::DescriptorType::SAMPLED_IMAGE,vk::DescriptorType::SAMPLED_IMAGE,
        vk::DescriptorType::STORAGE_BUFFER],&motion_words,c"analyse")?;
    let mut types=vec![vk::DescriptorType::SAMPLED_IMAGE;6];
    types.push(vk::DescriptorType::STORAGE_BUFFER);
    types.extend(std::iter::repeat_n(vk::DescriptorType::STORAGE_IMAGE,raw_planes(mode)));
    let synthesis_words=if mode==0{
        if let Some(t)=tuning{spirv(&tuned_synthesis(SYNTHESIS,t)?,"@group(0) @binding(8) var<uniform> params:vec4u;","synthesize")?}
        else{synthesis_spirv()?}
    }else if mode>=4{raw_synthesis_config(mode,tuning)?}else{high_synthesis_config(mode,tuning)?};
    let synthesis=Pipeline::new(&device,&types,&synthesis_words,c"synthesize")?;
    let quality=if let Some(t)=tuning{
        let mut types=vec![vk::DescriptorType::SAMPLED_IMAGE;6];types.push(vk::DescriptorType::STORAGE_BUFFER);
        Some((Pipeline::new(&device,&types,&quality_spirv(mode,t,"analyse")?,c"analyse")?,
            Pipeline::new(&device,&types,&quality_spirv(mode,t,"scene")?,c"scene")?))
    }else{None};
    Ok(VulkanResident(Arc::new(Core{device,memory,motion,synthesis,quality,tuning,width,height,gw,gh,field_size,mode,in_flight:AtomicU32::new(0),idle:Mutex::new(Vec::new()),lost:AtomicBool::new(false),
        stats_supported:matches!(mode,1..=3)&&limits.max_per_stage_descriptor_storage_buffers>=3&&
            limits.max_storage_buffer_range>=100001*4*3,
        stats:Mutex::new(None)})))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_create(instance:vk::Instance,physical:vk::PhysicalDevice,device:vk::Device,
    get_proc:Option<vk::PFN_vkGetInstanceProcAddr>,width:u32,height:u32)->*mut VulkanResident{
    unsafe{ambient_svp_vk_create_format(instance,physical,device,get_proc,width,height,0)}
}
/// Optional ABI: mode 1=P010 high bits, 2=I420 10-bit low bits; output RGBA16F.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_create_format(instance:vk::Instance,physical:vk::PhysicalDevice,device:vk::Device,
    get_proc:Option<vk::PFN_vkGetInstanceProcAddr>,width:u32,height:u32,mode:u32)->*mut VulkanResident{
    if instance==vk::Instance::null() || physical==vk::PhysicalDevice::null() || device==vk::Device::null() || get_proc.is_none() || mode>5{return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let f=ash::StaticFn{get_instance_proc_addr:get_proc.unwrap()};
        let instance=unsafe{ash::Instance::load(&f,instance)};
        let device=unsafe{ash::Device::load(instance.fp_v1_0(),device)};
        new_core(&instance,physical,device,width,height,mode,None).map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s)))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_create_config(instance:vk::Instance,physical:vk::PhysicalDevice,device:vk::Device,
    get_proc:Option<vk::PFN_vkGetInstanceProcAddr>,width:u32,height:u32,mode:u32,tuning:*const ResidentTuning)->*mut VulkanResident{
    if instance==vk::Instance::null()||physical==vk::PhysicalDevice::null()||device==vk::Device::null()||
        get_proc.is_none()||mode>5||tuning.is_null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let f=ash::StaticFn{get_instance_proc_addr:get_proc.unwrap()};
        let instance=unsafe{ash::Instance::load(&f,instance)};
        let device=unsafe{ash::Device::load(instance.fp_v1_0(),device)};
        new_core(&instance,physical,device,width,height,mode,Some(unsafe{*tuning}))
            .map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s)))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_destroy(s:*mut VulkanResident){
    if !s.is_null(){let _=catch_unwind(AssertUnwindSafe(||unsafe{drop(Box::from_raw(s))}));}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_begin(s:*const VulkanResident,command:vk::CommandBuffer,
    a:*const VulkanPicture,b:*const VulkanPicture)->*mut VulkanPair{
    if s.is_null() || a.is_null() || b.is_null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let core=unsafe{(*s).0.clone()};
        let Ok(pair)=VulkanPair::new(core,command,unsafe{*a},unsafe{*b})else{return ptr::null_mut();};
        unsafe{pair.analyse();}
        Box::into_raw(Box::new(pair))
    })).unwrap_or(ptr::null_mut())
}
/// Begin a GPU-only resident pair directly from converted MediaCodec jobs.
/// Same queue/device and geometry are checked. The caller must record and
/// submit on that queue after the converter submissions. Each job remains
/// pinned until the pair fence retires (or an unsubmitted pair is canceled).
#[cfg(target_os="android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_begin_ahb(s:*const VulkanResident,command:vk::CommandBuffer,
    queue:vk::Queue,a:*const crate::ahb_vulkan::Job,b:*const crate::ahb_vulkan::Job)->*mut VulkanPair{
    if s.is_null() || a.is_null() || b.is_null() || queue==vk::Queue::null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let core=unsafe{(*s).0.clone()};
        let convert=|p:*const crate::ahb_vulkan::Job|{
            let (device,q,w,h,view)=unsafe{(&*p).picture()};
            if unsafe{(&*p).high_depth()}!=(core.mode==3){return None;}
            if device!=core.device.handle() || q!=queue || (w,h)!=(core.width,core.height){return None;}
            Some(VulkanPicture{y:view,u:vk::ImageView::null(),v:vk::ImageView::null(),nv12:2,chroma_location:0})
        };
        let (Some(pa),Some(pb))=(convert(a),convert(b))else{return ptr::null_mut();};
        let Ok(mut pair)=VulkanPair::new(core,command,pa,pb)else{return ptr::null_mut();};
        unsafe{(&*a).retain_consumer();(&*b).retain_consumer();}
        pair.ahb_inputs=Some([a,b]);
        unsafe{pair.analyse();}
        Box::into_raw(Box::new(pair))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_render(pair:*mut VulkanPair,phase:f32,matrix:u32,output:vk::ImageView)->i32{
    if pair.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{(&mut *pair).render(phase,matrix,output)}.map_or(-1,|_|0))).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_render_raw(pair:*mut VulkanPair,phase:f32,outputs:*const vk::ImageView,count:u32)->i32{
    if pair.is_null() || outputs.is_null() || !(2..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{(&mut *pair).render_raw(phase,std::slice::from_raw_parts(outputs,count as usize))}
        .map_or(-1,|_|0))).unwrap_or(-1)
}
// Record metadata statistics immediately after the selected RGB phase.
// Only the pair's recorded view is sampled, not an arbitrary image.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_stats(pair:*mut VulkanPair,phase:u32,
    windows:*const HdrWindow,count:u32)->i32{
    if pair.is_null()||windows.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&mut *pair};let i=phase as usize;
        if p.fence!=vk::Fence::null()||i>=p.phases||p.hdr_jobs[i].is_some()||
            p.rgb_outputs[i]==vk::ImageView::null(){return -1;}
        let windows=unsafe{std::slice::from_raw_parts(windows,count as usize)};
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,None)else{return -1;};
        unsafe{job.record(p.command,p.core.width,p.core.height);}
        p.hdr_jobs[i]=Some(job);0
    })).unwrap_or(-1)
}
// Read compact metadata only once the real submission fence signals.
// Never read GPU-produced data before completion or after pair release.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_read(pair:*const VulkanPair,phase:u32,
    results:*mut HdrResult,count:u32)->i32{
    if pair.is_null()||results.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&*pair};let i=phase as usize;
        if p.fence==vk::Fence::null()||i>=p.phases{return -1;}
        match unsafe{p.core.device.get_fence_status(p.fence)}{Ok(true)=>{},Ok(false)=>return 0,Err(_)=>return -1}
        let Some(job)=p.hdr_jobs[i].as_ref()else{return -1;};
        job.read(unsafe{std::slice::from_raw_parts_mut(results,count as usize)},None).map_or(-1,|_|1)
    })).unwrap_or(-1)
}
// Separate optional ABI preserves the existing stats structure layout.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_stats_track(pair:*mut VulkanPair,phase:u32,
    windows:*const HdrWindow,tracks:*const HdrTrack,t:f32,count:u32)->i32{
    if pair.is_null()||windows.is_null()||tracks.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&mut *pair};let i=phase as usize;
        if p.fence!=vk::Fence::null()||i>=p.phases||p.hdr_jobs[i].is_some()||
            p.rgb_outputs[i]==vk::ImageView::null(){return -1;}
        let windows=unsafe{std::slice::from_raw_parts(windows,count as usize)};
        let tracks=unsafe{std::slice::from_raw_parts(tracks,count as usize)};
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,Some((tracks,t)))else{return -1;};
        unsafe{job.record(p.command,p.core.width,p.core.height);}
        p.hdr_jobs[i]=Some(job);0
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_read_track(pair:*const VulkanPair,phase:u32,
    results:*mut HdrResult,shifts:*mut [i32;2],count:u32)->i32{
    if pair.is_null()||results.is_null()||shifts.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&*pair};let i=phase as usize;
        if p.fence==vk::Fence::null()||i>=p.phases{return -1;}
        match unsafe{p.core.device.get_fence_status(p.fence)}{Ok(true)=>{},Ok(false)=>return 0,Err(_)=>return -1}
        let Some(job)=p.hdr_jobs[i].as_ref()else{return -1;};
        job.read(unsafe{std::slice::from_raw_parts_mut(results,count as usize)},
            Some(unsafe{std::slice::from_raw_parts_mut(shifts,count as usize)})).map_or(-1,|_|1)
    })).unwrap_or(-1)
}
// The caller must pass the fence of the successful submission containing
// this exact command buffer. The fence is borrowed and must not be reset
// or destroyed before release. Never free in-flight descriptors/images.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_seal(pair:*mut VulkanPair,fence:vk::Fence)->i32{
    if pair.is_null() || fence==vk::Fence::null(){return -1;}
    let p=unsafe{&mut *pair};
    if p.fence!=vk::Fence::null() || p.phases==0{return -1;}
    p.fence=fence;0
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_release(pair:*mut VulkanPair)->i32{
    if pair.is_null(){return -1;}
    let p=unsafe{&*pair};
    if p.fence==vk::Fence::null(){return -1;}
    match unsafe{p.core.device.get_fence_status(p.fence)}{
        Ok(true)=>{let mut p=unsafe{Box::from_raw(pair)};p.recycle();drop(p);1}
        Ok(false)=>0,
        // A confirmed lost device no longer executes the submission.
        Err(vk::Result::ERROR_DEVICE_LOST)=>{p.core.lost.store(true,Ordering::Release);unsafe{drop(Box::from_raw(pair))};1}
        Err(_)=>-1,
    }
}
// Only after discarding/resetting the UNSUBMITTED command buffer. Never
// cancel a submitted pair. Device-lost teardown is explicit at the caller.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_cancel(pair:*mut VulkanPair)->i32{
    if pair.is_null() || unsafe{(*pair).fence}!=vk::Fence::null(){return -1;}
    let mut p=unsafe{Box::from_raw(pair)};p.recycle();drop(p);0
}

#[cfg(test)]
mod tests{
    #[test]
    fn resident_native_shaders_compile_to_spirv(){
        let m=super::motion_spirv().unwrap();
        let s=super::synthesis_spirv().unwrap();
        assert_eq!(m[0],0x07230203);assert_eq!(s[0],0x07230203);
        assert!(m.len()>100 && s.len()>100);
        for mode in [1,2,3]{
            assert_eq!(super::high_motion_spirv(mode).unwrap()[0],0x07230203);
            assert_eq!(super::high_synthesis_spirv(mode).unwrap()[0],0x07230203);
        }
        for mode in [4,5]{
            assert_eq!(super::high_motion_spirv(mode).unwrap()[0],0x07230203);
            assert_eq!(super::raw_synthesis_spirv(mode).unwrap()[0],0x07230203);
        }
    }
}

#[cfg(test)]
mod gpu_tests{
    use super::*;
    struct Image{image:vk::Image,view:vk::ImageView,memory:vk::DeviceMemory}
    fn memory_index(props:&vk::PhysicalDeviceMemoryProperties,bits:u32,flags:vk::MemoryPropertyFlags)->u32{
        (0..props.memory_type_count).find(|&i|bits&(1<<i)!=0&&props.memory_types[i as usize].property_flags.contains(flags)).unwrap()
    }
    unsafe fn image(d:&ash::Device,props:&vk::PhysicalDeviceMemoryProperties,w:u32,h:u32,format:vk::Format,usage:vk::ImageUsageFlags)->Image{
        let image=unsafe{d.create_image(&vk::ImageCreateInfo::default().image_type(vk::ImageType::TYPE_2D)
            .format(format).extent(vk::Extent3D{width:w,height:h,depth:1}).mip_levels(1).array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1).tiling(vk::ImageTiling::OPTIMAL)
            .usage(usage).sharing_mode(vk::SharingMode::EXCLUSIVE).initial_layout(vk::ImageLayout::UNDEFINED),None)}.unwrap();
        let req=unsafe{d.get_image_memory_requirements(image)};
        let memory=unsafe{d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size)
            .memory_type_index(memory_index(props,req.memory_type_bits,vk::MemoryPropertyFlags::DEVICE_LOCAL)),None)}.unwrap();
        unsafe{d.bind_image_memory(image,memory,0)}.unwrap();
        let view=unsafe{d.create_image_view(&vk::ImageViewCreateInfo::default().image(image).view_type(vk::ImageViewType::TYPE_2D)
            .format(format).subresource_range(range()),None)}.unwrap();
        Image{image,view,memory}
    }
    fn range()->vk::ImageSubresourceRange{
        vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR).level_count(1).layer_count(1)
    }
    unsafe fn transition(d:&ash::Device,cmd:vk::CommandBuffer,img:vk::Image,
        old:vk::ImageLayout,new:vk::ImageLayout,src:vk::AccessFlags,dst:vk::AccessFlags,
        stage_src:vk::PipelineStageFlags,stage_dst:vk::PipelineStageFlags){
        let barriers=[vk::ImageMemoryBarrier::default().image(img).old_layout(old).new_layout(new)
            .src_access_mask(src).dst_access_mask(dst)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .subresource_range(range())];
        unsafe{d.cmd_pipeline_barrier(cmd,stage_src,stage_dst,vk::DependencyFlags::empty(),&[],&[],&barriers)}
    }
    #[test]
    #[ignore="requires Vulkan, including Mesa software Vulkan"]
    fn native_resident_commands_keep_images_on_shared_device(){
        unsafe{
            let entry=ash::Entry::load().unwrap();
            let app=vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_1);
            let instance=entry.create_instance(&vk::InstanceCreateInfo::default().application_info(&app),None).unwrap();
            let (physical,family)=instance.enumerate_physical_devices().unwrap().into_iter()
                .find_map(|p|instance.get_physical_device_queue_family_properties(p).iter().position(|q|
                    q.queue_count>0&&q.queue_flags.contains(vk::QueueFlags::COMPUTE)).map(|i|(p,i as u32))).unwrap();
            let priorities=[1.0];
            let queues=[vk::DeviceQueueCreateInfo::default().queue_family_index(family).queue_priorities(&priorities)];
            let extended=instance.get_physical_device_features(physical).shader_storage_image_extended_formats!=0;
            let features=vk::PhysicalDeviceFeatures::default().shader_storage_image_extended_formats(extended);
            let d=instance.create_device(physical,&vk::DeviceCreateInfo::default().queue_create_infos(&queues).enabled_features(&features),None).unwrap();
            let queue=d.get_device_queue(family,0);
            let props=instance.get_physical_device_memory_properties(physical);
            let (w,h)=(66u32,34u32);
            let engine=ambient_svp_vk_create(instance.handle(),physical,d.handle(),
                Some(entry.static_fn().get_instance_proc_addr),w,h);
            assert!(!engine.is_null(),"native resident initialization failed");
            let pool=d.create_command_pool(&vk::CommandPoolCreateInfo::default().queue_family_index(family)
                .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),None).unwrap();
            let cmd=d.allocate_command_buffers(&vk::CommandBufferAllocateInfo::default().command_pool(pool)
                .level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1)).unwrap()[0];
            let src=vk::ImageUsageFlags::SAMPLED|vk::ImageUsageFlags::TRANSFER_DST;
            let out=vk::ImageUsageFlags::STORAGE|vk::ImageUsageFlags::SAMPLED|vk::ImageUsageFlags::TRANSFER_SRC;
            let images=[
                image(&d,&props,w,h,vk::Format::R8_UNORM,src),
                image(&d,&props,w,h,vk::Format::R8_UNORM,src),
                image(&d,&props,w/2,h/2,vk::Format::R8G8_UNORM,src),
                image(&d,&props,w/2,h/2,vk::Format::R8_UNORM,src),
                image(&d,&props,w/2,h/2,vk::Format::R8_UNORM,src),
                image(&d,&props,w,h,vk::Format::R8G8B8A8_UNORM,out),
                image(&d,&props,w,h,vk::Format::R8G8B8A8_UNORM,out),
                image(&d,&props,w,h,vk::Format::R8G8B8A8_UNORM,out),
            ];
            let bytes=u64::from(w*h*4);
            let readback=d.create_buffer(&vk::BufferCreateInfo::default().size(bytes*3)
                .usage(vk::BufferUsageFlags::TRANSFER_DST).sharing_mode(vk::SharingMode::EXCLUSIVE),None).unwrap();
            let req=d.get_buffer_memory_requirements(readback);
            let readback_memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size)
                .memory_type_index(memory_index(&props,req.memory_type_bits,
                    vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)),None).unwrap();
            d.bind_buffer_memory(readback,readback_memory,0).unwrap();
            let fence=d.create_fence(&Default::default(),None).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            for (i,img) in images.iter().enumerate(){
                if i<5{
                    transition(&d,cmd,img.image,vk::ImageLayout::UNDEFINED,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::empty(),vk::AccessFlags::TRANSFER_WRITE,
                        vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::TRANSFER);
                    let value=[64.0,96.0,128.0,128.0,128.0][i]/255.0;
                    d.cmd_clear_color_image(cmd,img.image,vk::ImageLayout::GENERAL,
                        &vk::ClearColorValue{float32:[value,value,0.0,1.0]},&[range()]);
                    transition(&d,cmd,img.image,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::SHADER_READ,
                        vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                }else{
                    transition(&d,cmd,img.image,vk::ImageLayout::UNDEFINED,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::empty(),vk::AccessFlags::SHADER_WRITE,
                        vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::COMPUTE_SHADER);
                }
            }
            let a=VulkanPicture{y:images[0].view,u:images[2].view,v:vk::ImageView::null(),nv12:1,chroma_location:1};
            let b=VulkanPicture{y:images[1].view,u:images[3].view,v:images[4].view,nv12:0,chroma_location:2};
            let pair=ambient_svp_vk_begin(engine,cmd,&a,&b);
            assert!(!pair.is_null());
            assert_eq!(ambient_svp_vk_release(pair),-1);
            assert_eq!(ambient_svp_vk_render(pair,0.5,3,a.y),-1);
            assert_eq!(ambient_svp_vk_render(pair,f32::NAN,3,images[5].view),-1);
            for (i,phase) in [0.0,0.5,1.0].into_iter().enumerate(){
                assert_eq!(ambient_svp_vk_render(pair,phase,3,images[5+i].view),0);
            }
            // Readback belongs exclusively to this test. Production API
            // has no pixel upload, buffer map, copy or queue wait.
            for (i,img) in images[5..].iter().enumerate(){
                transition(&d,cmd,img.image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                    vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
                let region=[vk::BufferImageCopy::default().buffer_offset(bytes*i as u64)
                    .image_subresource(vk::ImageSubresourceLayers::default().aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1))
                    .image_extent(vk::Extent3D{width:w,height:h,depth:1})];
                d.cmd_copy_image_to_buffer(cmd,img.image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
            }
            let host=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            assert_eq!(ambient_svp_vk_seal(pair,fence),0);
            assert_eq!(ambient_svp_vk_cancel(pair),-1);
            assert_eq!(ambient_svp_vk_render(pair,0.25,3,images[5].view),-1);
            // Destroy the session handle before completion: pair Arc
            // retains pipelines until the actual fence is signaled.
            ambient_svp_vk_destroy(engine);
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            assert_eq!(ambient_svp_vk_release(pair),1);
            let mapped=d.map_memory(readback_memory,0,bytes*3,vk::MemoryMapFlags::empty()).unwrap();
            let data=std::slice::from_raw_parts(mapped.cast::<u8>(),(bytes*3) as usize);
            for (i,expected) in [64u8,80,96].into_iter().enumerate(){
                for pixel in data[i*bytes as usize..(i+1)*bytes as usize].chunks_exact(4){
                    assert!(pixel[..3].iter().all(|&v|v.abs_diff(expected)<=1)&&pixel[3]==255,
                        "unexpected resident output phase {i}: {pixel:?}");
                }
            }
            d.unmap_memory(readback_memory);
            // Simulate the RGB textures produced by Android external-format
            // conversion, without uploading or mapping any source pixels.
            // Reuse the two GPU endpoint outputs as the next input pair.
            let engine=ambient_svp_vk_create(instance.handle(),physical,d.handle(),
                Some(entry.static_fn().get_instance_proc_addr),w,h);
            assert!(!engine.is_null());
            d.reset_fences(&[fence]).unwrap();
            d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            for (i,img) in images[5..].iter().enumerate(){
                transition(&d,cmd,img.image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,vk::ImageLayout::GENERAL,
                    vk::AccessFlags::TRANSFER_READ,if i==1{vk::AccessFlags::SHADER_WRITE}else{vk::AccessFlags::SHADER_READ},
                    vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
            }
            let rgb=|view|VulkanPicture{y:view,u:vk::ImageView::null(),v:vk::ImageView::null(),nv12:2,chroma_location:0};
            let a=rgb(images[5].view);let b=rgb(images[7].view);
            let pair=ambient_svp_vk_begin(engine,cmd,&a,&b);
            assert!(!pair.is_null());
            let handles=((&*pair).field,(&*pair).pool,(&*pair).sets.clone());
            // Matrix=limited BT601 intentionally: RGB must not be converted again.
            assert_eq!(ambient_svp_vk_render(pair,0.5,0,images[6].view),0);
            transition(&d,cmd,images[6].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
            let region=[vk::BufferImageCopy::default().image_subresource(vk::ImageSubresourceLayers::default()
                .aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1)).image_extent(vk::Extent3D{width:w,height:h,depth:1})];
            d.cmd_copy_image_to_buffer(cmd,images[6].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            assert_eq!(ambient_svp_vk_seal(pair,fence),0);
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            assert_eq!(ambient_svp_vk_release(pair),1);
            let mapped=d.map_memory(readback_memory,0,bytes,vk::MemoryMapFlags::empty()).unwrap();
            for pixel in std::slice::from_raw_parts(mapped.cast::<u8>(),bytes as usize).chunks_exact(4){
                assert!(pixel[..3].iter().all(|&v|v.abs_diff(80)<=1)&&pixel[3]==255,"RGB resident result {pixel:?}");
            }
            d.unmap_memory(readback_memory);
            // The completed pair's entire bundle is reusable, but no
            // in-flight bundle can be borrowed by another pair.
            assert_eq!((&*engine).0.idle.lock().unwrap().len(),1);
            d.reset_fences(&[fence]).unwrap();
            d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            transition(&d,cmd,images[6].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,vk::ImageLayout::GENERAL,
                vk::AccessFlags::TRANSFER_READ,vk::AccessFlags::SHADER_WRITE,
                vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
            let stationary=ambient_svp_vk_begin(engine,cmd,&a,&a);
            assert!(!stationary.is_null());
            assert_eq!(((&*stationary).field,(&*stationary).pool,&(&*stationary).sets),(handles.0,handles.1,&handles.2));
            assert_eq!((&*engine).0.idle.lock().unwrap().len(),0);
            assert_eq!(ambient_svp_vk_render(stationary,0.5,0,images[6].view),0);
            transition(&d,cmd,images[6].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
            d.cmd_copy_image_to_buffer(cmd,images[6].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            assert_eq!(ambient_svp_vk_seal(stationary,fence),0);
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            assert_eq!(ambient_svp_vk_release(stationary),1);
            let mapped=d.map_memory(readback_memory,0,bytes,vk::MemoryMapFlags::empty()).unwrap();
            for pixel in std::slice::from_raw_parts(mapped.cast::<u8>(),bytes as usize).chunks_exact(4){
                assert!(pixel[..3].iter().all(|&v|v.abs_diff(64)<=1)&&pixel[3]==255,"reused resident result {pixel:?}");
            }
            d.unmap_memory(readback_memory);
            ambient_svp_vk_destroy(engine);
            // Actual R16 plane / RGBA16F Vulkan execution. Adjacent
            // 10-bit luma codes must stay distinguishable at source ticks.
            for mode in [1,2,3]{
                let engine=ambient_svp_vk_create_format(instance.handle(),physical,d.handle(),
                    Some(entry.static_fn().get_instance_proc_addr),w,h,mode);
                assert!(!engine.is_null(),"high depth resident mode {mode}");
                let input_format=if mode==3{vk::Format::R16G16B16A16_SFLOAT}else{vk::Format::R16_UNORM};
                let high=[
                    image(&d,&props,w,h,input_format,src),
                    image(&d,&props,w,h,input_format,src),
                    image(&d,&props,w/2,h/2,if mode==1{vk::Format::R16G16_UNORM}else{vk::Format::R16_UNORM},src),
                    image(&d,&props,w/2,h/2,vk::Format::R16_UNORM,src),
                    image(&d,&props,w,h,vk::Format::R16G16B16A16_SFLOAT,out),
                ];
                let pic=|y|if mode==3{rgb(y)}else{VulkanPicture{y,u:high[2].view,v:if mode==1{vk::ImageView::null()}else{high[3].view},
                    nv12:if mode==1{1}else{0},chroma_location:1}};
                let a=pic(high[0].view);let b=pic(high[1].view);
                let mut observed=[0u16;2];
                for (i,phase) in [0.0,1.0].into_iter().enumerate(){
                    d.reset_fences(&[fence]).unwrap();
                    d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
                    d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
                    if i==0{
                        for (j,img) in high[..4].iter().enumerate(){
                            transition(&d,cmd,img.image,vk::ImageLayout::UNDEFINED,vk::ImageLayout::GENERAL,
                                vk::AccessFlags::empty(),vk::AccessFlags::TRANSFER_WRITE,
                                vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::TRANSFER);
                            let code=[502.0,503.0,512.0,512.0][j];
                            let value=if mode==3 && j<2{(code-64.0)/876.0}else{code*if mode==1{64.0/65535.0}else{1.0/65535.0}};
                            d.cmd_clear_color_image(cmd,img.image,vk::ImageLayout::GENERAL,
                                &vk::ClearColorValue{float32:[value,value,if mode==3 && j<2{value}else{0.0},1.0]},&[range()]);
                            transition(&d,cmd,img.image,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                                vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::SHADER_READ,
                                vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                        }
                    }
                    transition(&d,cmd,high[4].image,if i==0{vk::ImageLayout::UNDEFINED}else{vk::ImageLayout::TRANSFER_SRC_OPTIMAL},
                        vk::ImageLayout::GENERAL,if i==0{vk::AccessFlags::empty()}else{vk::AccessFlags::TRANSFER_READ},
                        vk::AccessFlags::SHADER_WRITE,if i==0{vk::PipelineStageFlags::TOP_OF_PIPE}else{vk::PipelineStageFlags::TRANSFER},
                        vk::PipelineStageFlags::COMPUTE_SHADER);
                    let pair=ambient_svp_vk_begin(engine,cmd,&a,&b);
                    assert!(!pair.is_null());
                    assert_eq!(ambient_svp_vk_render(pair,phase,4,high[4].view),0);
                    let windows=[
                        HdrWindow{rect:[0,0,w,h],count:3,percentages:[1,50,99,0,0,0,0,0,0,0,0,0,0,0,0]},
                        HdrWindow{rect:[0,0,w/2,h/2],count:1,percentages:[99,0,0,0,0,0,0,0,0,0,0,0,0,0,0]},
                        HdrWindow{rect:[w/3,h/3,w,h],count:2,percentages:[50,100,0,0,0,0,0,0,0,0,0,0,0,0,0]},
                    ];
                    let mut stats=[HdrResult::default();3];
                    if mode==3{
                        assert_eq!(ambient_svp_vk_hdr_stats(pair,0,windows.as_ptr(),3),0);
                        assert_eq!(ambient_svp_vk_hdr_read(pair,0,stats.as_mut_ptr(),3),-1);
                    }
                    transition(&d,cmd,high[4].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                        vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                        vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
                    d.cmd_copy_image_to_buffer(cmd,high[4].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
                    d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                        vk::DependencyFlags::empty(),&host,&[],&[]);
                    d.end_command_buffer(cmd).unwrap();
                    d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
                    assert_eq!(ambient_svp_vk_seal(pair,fence),0);
                    d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
                    if mode==3{
                        assert_eq!(ambient_svp_vk_hdr_read(pair,0,stats.as_mut_ptr(),3),1);
                        for (r,window) in stats.iter().zip(windows){
                            assert_eq!(r.pixels,(window.rect[2]-window.rect[0])*(window.rect[3]-window.rect[1]));
                            assert_eq!(r.brightest,r.pixels);
                            assert_eq!(r.maxscl,[r.average;3]);
                            assert!(r.average>800 && r.average<1100,"PQ luminance {:?}",r);
                            for &pct in &r.percentiles[..window.count as usize]{assert_eq!(pct,r.average);}
                        }
                    }
                    assert_eq!(ambient_svp_vk_release(pair),1);
                    let mapped=d.map_memory(readback_memory,0,bytes*2,vk::MemoryMapFlags::empty()).unwrap();
                    let data=std::slice::from_raw_parts(mapped.cast::<u8>(),(bytes*2) as usize);
                    let expected=if i==0{0x3800u16}else{0x3802u16};
                    for px in data.chunks_exact(8){
                        let value=|j:usize|u16::from_ne_bytes([px[j*2],px[j*2+1]]);
                        assert!(value(0).abs_diff(expected)<=1 && value(1)==value(0) && value(2)==value(0) && value(3)==0x3c00,
                            "high-depth output mode {mode}, phase {phase}: {px:?}");
                    }
                    observed[i]=u16::from_ne_bytes([data[0],data[1]]);
                    d.unmap_memory(readback_memory);
                }
                assert!(observed[1]>observed[0],"10-bit codes collapsed");
                ambient_svp_vk_destroy(engine);
                for img in high{d.destroy_image_view(img.view,None);d.destroy_image(img.image,None);d.free_memory(img.memory,None);}
            }
            // Raw YUV transport for a downstream Dolby Vision reshaper:
            // verify exact endpoint packing and a fractional chroma/luma
            // midpoint without applying any RGB matrix.
            if extended{
                for mode in [4,5]{
                    let engine=ambient_svp_vk_create_format(instance.handle(),physical,d.handle(),
                        Some(entry.static_fn().get_instance_proc_addr),w,h,mode);
                    assert!(!engine.is_null(),"raw resident mode {mode}");
                    let planes=raw_planes(mode);
                    let mut raw=Vec::new();
                    for image_index in 0..3{
                        for plane in 0..planes{
                            let (pw,ph)=if plane==0{(w,h)}else{(w/2,h/2)};
                            let format=if mode==4&&plane==1{vk::Format::R16G16_UNORM}else{vk::Format::R16_UNORM};
                            raw.push(image(&d,&props,pw,ph,format,if image_index==2{out}else{src}));
                        }
                    }
                    let make=|i:usize|VulkanPicture{y:raw[i*planes].view,u:raw[i*planes+1].view,
                        v:if planes==3{raw[i*planes+2].view}else{vk::ImageView::null()},nv12:if mode==4{1}else{0},chroma_location:1};
                    let a=make(0);let b=make(1);
                    let views=raw[2*planes..].iter().map(|img|img.view).collect::<Vec<_>>();
                    for (i,phase) in [0.0,0.5,1.0].into_iter().enumerate(){
                        d.reset_fences(&[fence]).unwrap();
                        d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
                        d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
                        if i==0{
                            for (j,img) in raw[..2*planes].iter().enumerate(){
                                transition(&d,cmd,img.image,vk::ImageLayout::UNDEFINED,vk::ImageLayout::GENERAL,
                                    vk::AccessFlags::empty(),vk::AccessFlags::TRANSFER_WRITE,
                                    vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::TRANSFER);
                                let n=(j/planes) as f32;
                                let (x,y)=match j%planes{0=>(400.0+4.0*n,0.0),1=>(511.0+2.0*n,514.0+4.0*n),_=>(514.0+4.0*n,0.0)};
                                let scale=if mode==4{64.0/65535.0}else{1.0/65535.0};
                                d.cmd_clear_color_image(cmd,img.image,vk::ImageLayout::GENERAL,
                                    &vk::ClearColorValue{float32:[x*scale,y*scale,0.0,1.0]},&[range()]);
                                transition(&d,cmd,img.image,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                                    vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::SHADER_READ,
                                    vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                            }
                        }
                        for img in &raw[2*planes..]{
                            transition(&d,cmd,img.image,if i==0{vk::ImageLayout::UNDEFINED}else{vk::ImageLayout::TRANSFER_SRC_OPTIMAL},
                                vk::ImageLayout::GENERAL,if i==0{vk::AccessFlags::empty()}else{vk::AccessFlags::TRANSFER_READ},
                                vk::AccessFlags::SHADER_WRITE,if i==0{vk::PipelineStageFlags::TOP_OF_PIPE}else{vk::PipelineStageFlags::TRANSFER},
                                vk::PipelineStageFlags::COMPUTE_SHADER);
                        }
                        let pair=ambient_svp_vk_begin(engine,cmd,&a,&b);
                        assert!(!pair.is_null());
                        assert_eq!(ambient_svp_vk_render(pair,phase,0,views[0]),-1);
                        assert_eq!(ambient_svp_vk_render_raw(pair,phase,views.as_ptr(),1),-1);
                        assert_eq!(ambient_svp_vk_render_raw(pair,phase,views.as_ptr(),planes as u32),0);
                        for (p,img) in raw[2*planes..].iter().enumerate(){
                            transition(&d,cmd,img.image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                                vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                                vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
                            let (pw,ph)=if p==0{(w,h)}else{(w/2,h/2)};
                            let region=[vk::BufferImageCopy::default().buffer_offset(bytes*p as u64)
                                .image_subresource(vk::ImageSubresourceLayers::default().aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1))
                                .image_extent(vk::Extent3D{width:pw,height:ph,depth:1})];
                            d.cmd_copy_image_to_buffer(cmd,img.image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
                        }
                        d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                            vk::DependencyFlags::empty(),&host,&[],&[]);
                        d.end_command_buffer(cmd).unwrap();
                        d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
                        assert_eq!(ambient_svp_vk_seal(pair,fence),0);
                        d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
                        assert_eq!(ambient_svp_vk_release(pair),1);
                        let mapped=d.map_memory(readback_memory,0,bytes*3,vk::MemoryMapFlags::empty()).unwrap();
                        let data=std::slice::from_raw_parts(mapped.cast::<u8>(),(bytes*3) as usize);
                        let shift=if mode==4{6}else{0};
                        for p in 0..planes{
                            let count=if p==0{w*h}else{w*h/4};
                            let components=if mode==4&&p==1{2}else{1};
                            let start=bytes as usize*p;
                            for (j,pixel) in data[start..start+count as usize*components*2].chunks_exact(2).enumerate(){
                                let code=match p{0=>400+2*i,1 if components==2&&j%2==1=>514+2*i,1=>511+i,_=>514+2*i};
                                assert_eq!(u16::from_ne_bytes([pixel[0],pixel[1]]),(code as u16)<<shift,
                                    "raw mode {mode} plane {p} phase {phase}");
                            }
                        }
                        d.unmap_memory(readback_memory);
                    }
                    ambient_svp_vk_destroy(engine);
                    for img in raw{d.destroy_image_view(img.view,None);d.destroy_image(img.image,None);d.free_memory(img.memory,None);}
                }
            }
            d.destroy_fence(fence,None);d.destroy_command_pool(pool,None);
            d.destroy_buffer(readback,None);d.free_memory(readback_memory,None);
            for img in images{
                d.destroy_image_view(img.view,None);d.destroy_image(img.image,None);d.free_memory(img.memory,None);
            }
            d.destroy_device(None);instance.destroy_instance(None);
        }
    }
}
