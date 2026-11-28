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
use hdr_stats::{HdrWindow,HdrResult,HdrTrack,StatsJob,StatsPipelines,HdrScene,SceneResult,TwoPass,LegacySceneResult};

const MAX_PHASES:usize=8;
const MAX_IN_FLIGHT:u32=4;
const MAX_PIXELS:u64=3840*2160;
const HD_PIXELS:u64=1920*1080;
type Result<T>=std::result::Result<T,String>;
fn err(e:vk::Result)->String{format!("Vulkan result {}",e.as_raw())}
fn spirv(source:&str,uniform:&str,entry:&str)->Result<Vec<u32>>{
    if source.matches(uniform).count()!=1{return Err("invalid resident uniform contract".into());}
    let name=if matches!(entry,"analyse"|"scene"|"snapshot"){"geometry"}else{"params"};
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
        if let Some(t)=t{source=tuned_synthesis(&source,t,mode)?;}
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
    if let Some(t)=t{source=tuned_synthesis(&source,t,mode)?;}
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
    if let Some(t)=t{source=tuned_synthesis(&source,t,mode)?;}
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
/// Separate optional ABI: all reference penalty parameters plus the
/// superframe upscaler. Existing tuning structure size is unchanged.
#[repr(C)]
#[derive(Clone,Copy,Debug)]
pub struct ResidentSearchWeights{
    pub filter:u32,pub lambda:u32,pub lsad:u32,pub plevel_q16:u32,
    pub levels:u32,pub level:u32,pub pnew:i32,pub pzero:i32,
    pub pglobal:i32,pub pnbour:i32,pub preverse:i32,pub sad_gate:u32,
}
impl ResidentSearchWeights{
    fn legacy(t:ResidentTuning)->Self{
        Self{filter:0,lambda:t.lambda*t.pel*t.pel,lsad:t.block*t.block*250,
            plevel_q16:65536,levels:1,level:0,pnew:t.pnew as i32,pzero:100,
            pglobal:50,pnbour:50,preverse:0,sad_gate:0}
    }
    fn valid(self)->bool{
        self.filter<=2&&self.lambda<=1_000_000&&(1..=1_000_000).contains(&self.lsad)&&
        (65536..=655360).contains(&self.plevel_q16)&&(1..=16).contains(&self.levels)&&
        self.level<self.levels&&self.sad_gate<=1&&
        [self.pnew,self.pzero,self.pglobal,self.pnbour,self.preverse].iter().all(|p|(-256..=256).contains(p))
    }
    fn scaled_lambda(self,pel:u32,mode:u32)->u32{
        let scale=(f64::from(self.plevel_q16)/65536.0).powi((self.levels-self.level-1) as i32);
        let depth=if mode==0{1.0}else{4.0};
        (f64::from(self.lambda)*depth/scale/f64::from(pel*pel)).min(21_474_836.0) as u32
    }
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
#[cfg(test)]
fn quality_spirv(mode:u32,t:ResidentTuning,entry:&str)->Result<Vec<u32>>{
    quality_spirv_weighted(mode,t,ResidentSearchWeights::legacy(t),entry)
}
fn quality_spirv_weighted(mode:u32,t:ResidentTuning,w:ResidentSearchWeights,entry:&str)->Result<Vec<u32>>{
    spirv(&quality_source(mode,t,w)?,"@group(0) @binding(7) var<uniform> geometry:vec4u;",entry)
}
fn quality_source(mode:u32,t:ResidentTuning,w:ResidentSearchWeights)->Result<String>{
    if !t.valid()||!w.valid(){return Err("invalid weighted search configuration".into());}
    let scale=match mode{0=>"255.0",1|4=>"65535.0/64.0",2|5=>"65535.0",3=>"1023.0",_=>return Err("unknown quality format".into())};
    let mut source=include_str!("resident_quality.wgsl").to_owned();
    for (name,value) in [("SVP_BLOCK",t.block.to_string()),("SVP_STEP",t.step().to_string()),
        ("SVP_PEL",t.pel.to_string()),("SVP_RADIUS",t.radius.to_string()),("SVP_SCALE",scale.into()),
        ("SVP_CODE_MAX",if mode==0{"255"}else{"1023"}.into()),("SVP_SATD",(t.satd!=0).to_string()),
        ("SVP_CHROMA",(t.chroma!=0).to_string()),("SVP_LAMBDA",format!("{}u",w.scaled_lambda(t.pel,mode))),
        ("SVP_PNEW",w.pnew.to_string()),("SVP_SCENE",format!("{}u",t.scene)),
        ("SVP_FILTER",w.filter.to_string()),("SVP_LSAD",format!("{}u",w.lsad*if mode==0{1}else{4})),
        ("SVP_PZERO",w.pzero.to_string()),("SVP_PGLOBAL",w.pglobal.to_string()),
        ("SVP_PNBOUR",w.pnbour.to_string()),("SVP_PREVERSE",w.preverse.to_string()),
        ("SVP_SAD_GATE",(w.sad_gate!=0).to_string())]{
        if source.matches(name).count()!=1{return Err(format!("quality shader placeholder {name}"));}
        source=source.replace(name,&value);
    }
    Ok(source)
}
fn mask_source(mode:u32,t:ResidentTuning)->String{
    include_str!("resident_masks.wgsl").replace("SVP_BLOCK",&t.block.to_string())
        .replace("SVP_STEP",&t.step().to_string())
        .replace("SVP_SAD_AREA",match t.artifacts{2=>"50",3=>"100",_=>"0"})
        .replace("SVP_DEPTH_SCALE",if mode==0{"1"}else{"4"})
}
fn mask_spirv(mode:u32,t:ResidentTuning,entry:&str)->Result<Vec<u32>>{
    spirv(&mask_source(mode,t),"@group(0) @binding(7) var<uniform> params:vec4u;",entry)
}
fn tuned_synthesis(source:&str,t:ResidentTuning,mode:u32)->Result<String>{
    let family=include_str!("resident_synthesis_family.wgsl")
        .replace("SVP_STEP",&t.step().to_string())
        .replace("SVP_ORIGIN",&(t.overlap/2).to_string())
        .replace("SVP_PEL",&t.pel.to_string())
        .replace("SVP_SDR8",if mode==0{"true"}else{"false"})
        .replace("SVP_AREA_BLEND",if t.artifacts==3{"0.7"}else{"0.4"});
    let entry=source.find("@compute @workgroup_size(8,8)").ok_or("tuned synthesis entry")?;
    let threshold=match t.artifacts{1=>64,2=>48,3=>32,_=>48};
    let mismatch=match t.artifacts{1=>8,2=>6,3=>4,_=>6};
    let helper=format!(r#"
struct BlendGrid {{ indices:vec4u, weights:vec4f, }}
struct Warp {{ ap:vec2f, bp:vec2f, blend:f32, }}
fn decode_mv(v:vec4i)->vec4f{{
    return vec4f(vec2f(v.xy)/f32(max(v.w,1)),f32(v.z),1.0);
}}
fn overlap_grid(p:vec2u,grid:vec2u)->BlendGrid{{
    let cell=min(p/{step}u,grid-vec2u(1));
    let prev=cell-min(cell,vec2u(1));
    let frac=clamp((vec2f(p-cell*{step}u)+vec2f(0.5))/f32(max({overlap}u,1u)),vec2f(0),vec2f(1));
    let rise=vec2f(0.5)-0.5*cos(3.141592653589793*frac);
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
    result.push_str(&family);
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
    let algo=(params.y>>8u)&31u;
    if algo!=0u{
        let motion=family_motion(p,grid,phase);
        textureStore(dy,vec2i(id.xy),pack10(family_plane(ay,by,p,motion,1.0,visible,algo)));
        if all((id.xy&vec2u(1))==vec2u(0)){
            let cp=vec2i(id.xy/2u);
            textureStore(du,cp,pack10(family_plane(au,bu,p*0.5,motion,0.5,visible/2u,algo)));
            SVP_FAMILY_V
        }
        return;
    }
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
        result=result.replace("SVP_FAMILY_V",if source.contains("var dv:texture_storage"){"textureStore(dv,cp,pack10(family_plane(av,bv,p*0.5,motion,0.5,visible/2u,algo)));"}else{""})
            .replace("SVP_STORE_V",if source.contains("var dv:texture_storage"){"textureStore(dv,cp,pack10(v));"}else{""})
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
    // Dynamic capture holds the preceding source through a GPU-detected
    // cut, exactly matching the known-boundary replay path.
    if (params.y&8192u)!=0u&&(phase==0.0||fields[2u*grid.x*grid.y].x!=0){
        let p=vec2f(id.xy);
        let value=sample_warp(Warp(p,p,0.0),visible);
        textureStore(dst,vec2i(id.xy),vec4f(select(rgb(value),value,(params.y&64u)!=0u),1.0));
        return;
    }
    let algo=(params.y>>8u)&31u;
    if algo!=0u{
        let p=vec2f(id.xy);
        let m=family_motion(p,grid,phase);
        var pixel:vec3f;
        if (params.y&64u)!=0u{
            pixel=family_plane(ay,by,p,m,1.0,visible,algo).rgb;
        }else{
            let ap=p+m.a;let bp=p+m.b;
            let f=family_yuv(ay,au,av,ap,(params.y&1u)!=0u,(params.y>>2u)&3u,visible);
            let b=family_yuv(by,bu,bv,bp,(params.y&2u)!=0u,(params.y>>4u)&3u,visible);
            let f0=family_yuv(ay,au,av,p,(params.y&1u)!=0u,(params.y>>2u)&3u,visible);
            let b0=family_yuv(by,bu,bv,p,(params.y&2u)!=0u,(params.y>>4u)&3u,visible);
            var ff=f;var bb=b;
            if algo==23u{
                ff=family_yuv(ay,au,av,p+m.ext_a,(params.y&1u)!=0u,(params.y>>2u)&3u,visible);
                bb=family_yuv(by,bu,bv,p+m.ext_b,(params.y&2u)!=0u,(params.y>>4u)&3u,visible);
            }
            var value=family_blend(vec4f(f,1),vec4f(b,1),vec4f(f0,1),vec4f(b0,1),
                vec4f(ff,1),vec4f(bb,1),m,algo).rgb;
            if FAMILY_SDR8{value.x=family_encode(value.x);}
            pixel=rgb(value);
        }
        textureStore(dst,vec2i(id.xy),vec4f(pixel,1.0));
        return;
    }
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
struct HeldCommands{
    device:ash::Device,family:u32,pool:vk::CommandPool,cmd:vk::CommandBuffer,fence:vk::Fence,
}
impl Drop for HeldCommands{
    fn drop(&mut self){unsafe{
        self.device.destroy_command_pool(self.pool,None);
        self.device.destroy_fence(self.fence,None);
    }}
}
impl HeldCommands{
    fn acquire(core:&Core,family:u32)->Result<Self>{
        let mut idle=core.held_idle.lock().unwrap_or_else(|e|e.into_inner());
        if let Some(i)=idle.iter().position(|x|x.family==family){
            let cmd=idle.swap_remove(i);drop(idle);
            unsafe{
                cmd.device.reset_command_pool(cmd.pool,vk::CommandPoolResetFlags::empty()).map_err(err)?;
                cmd.device.reset_fences(&[cmd.fence]).map_err(err)?;
            }
            return Ok(cmd);
        }
        drop(idle);
        let mut x=Self{device:core.device.clone(),family,pool:vk::CommandPool::null(),
            cmd:vk::CommandBuffer::null(),fence:vk::Fence::null()};
        unsafe{
            x.pool=x.device.create_command_pool(&vk::CommandPoolCreateInfo::default().queue_family_index(family)
                .flags(vk::CommandPoolCreateFlags::TRANSIENT),None).map_err(err)?;
            x.cmd=x.device.allocate_command_buffers(&vk::CommandBufferAllocateInfo::default()
                .command_pool(x.pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1)).map_err(err)?[0];
            x.fence=x.device.create_fence(&vk::FenceCreateInfo::default(),None).map_err(err)?;
        }
        Ok(x)
    }
    fn recycle(self,core:&Core){
        if core.lost.load(Ordering::Acquire){return;}
        let mut idle=core.held_idle.lock().unwrap_or_else(|e|e.into_inner());
        if idle.len()<MAX_IN_FLIGHT as usize{idle.push(self);}
    }
}
// Immutable GPU snapshot of analysis, before phase-specific masks.
// A cache belongs to one queue for its entire life; a caller-provided
// generation changes before any source texture is overwritten.
#[derive(Clone,Copy,PartialEq,Eq)]
struct HeldKey{generation:u64,images:[vk::Image;4],count:u32,algorithm:u32}
struct HeldField{
    device:ash::Device,queue:vk::Queue,buffer:vk::Buffer,memory:vk::DeviceMemory,
    key:Option<HeldKey>,extended:bool,
}
impl Drop for HeldField{
    fn drop(&mut self){unsafe{
        self.device.destroy_buffer(self.buffer,None);
        self.device.free_memory(self.memory,None);
    }}
}
impl HeldField{
    fn new(c:&Core,queue:vk::Queue)->Result<Self>{
        let mut s=Self{device:c.device.clone(),queue,buffer:vk::Buffer::null(),memory:vk::DeviceMemory::null(),key:None,extended:false};
        unsafe{
            s.buffer=s.device.create_buffer(&vk::BufferCreateInfo::default().size(c.field_size)
                .usage(vk::BufferUsageFlags::TRANSFER_SRC|vk::BufferUsageFlags::TRANSFER_DST).sharing_mode(vk::SharingMode::EXCLUSIVE),None).map_err(err)?;
            let req=s.device.get_buffer_memory_requirements(s.buffer);
            let ty=(0..c.memory.memory_type_count).find(|&i|req.memory_type_bits&(1<<i)!=0&&
                c.memory.memory_types[i as usize].property_flags.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL))
                .ok_or("held field memory")?;
            s.memory=s.device.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size).memory_type_index(ty),None).map_err(err)?;
            s.device.bind_buffer_memory(s.buffer,s.memory,0).map_err(err)?;
        }
        Ok(s)
    }
    unsafe fn copy(&self,p:&VulkanPair,restore:bool){
        let d=&self.device;let cmd=p.command;
        let (src,dst)=if restore{(self.buffer,p.field)}else{(p.field,self.buffer)};
        let barrier=|buffer,src_access,dst_access|vk::BufferMemoryBarrier::default()
            .buffer(buffer).offset(0).size(p.core.field_size)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .src_access_mask(src_access).dst_access_mask(dst_access);
        let before=[
            barrier(src,vk::AccessFlags::SHADER_WRITE|vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::TRANSFER_READ),
            barrier(dst,vk::AccessFlags::SHADER_READ|vk::AccessFlags::SHADER_WRITE|vk::AccessFlags::TRANSFER_READ|vk::AccessFlags::TRANSFER_WRITE,
                vk::AccessFlags::TRANSFER_WRITE)];
        let after=[barrier(dst,vk::AccessFlags::TRANSFER_WRITE,
            vk::AccessFlags::SHADER_READ|vk::AccessFlags::SHADER_WRITE|vk::AccessFlags::TRANSFER_READ)];
        unsafe{
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER|vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::TRANSFER,vk::DependencyFlags::empty(),&[],&before,&[]);
            // The final vectors/cut flag and selected SADs are immutable.
            // Coarse predictors, scatter and phase masks are scratch; never
            // copy them between phases. Extended vectors/cuts are optional.
            let n=u64::from(p.core.gw)*u64::from(p.core.gh);
            let range=|offset:u64,words:u64|vk::BufferCopy::default().src_offset(offset*16).dst_offset(offset*16).size(words*16);
            let regions=[range(0,2*n+1),range(4*n+3,2*n),range(8*n+3,2*n+2)];
            let extended=if restore{self.extended}else{p.extended.len()==2};
            d.cmd_copy_buffer(cmd,src,dst,&regions[..if extended{3}else{2}]);
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER|vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),&[],&after,&[]);
        }
    }
}
struct AdjacentEntry{field:HeldField,key:Option<[u64;2]>}
#[derive(Default)]
struct AdjacentCache{entries:Vec<AdjacentEntry>,recording:bool}
struct Core{
    device:ash::Device,
    memory:vk::PhysicalDeviceMemoryProperties,
    motion:Pipeline,synthesis:Pipeline,
    quality:Option<(Pipeline,Pipeline,Pipeline)>,tuning:Option<ResidentTuning>,
    masks:Option<[Pipeline;3]>,algorithm:AtomicU32,
    width:u32,height:u32,gw:u32,gh:u32,field_size:u64,mode:u32,
    in_flight:AtomicU32,aux_in_flight:AtomicU32,
    idle:Mutex<Vec<PairResources>>,
    held_idle:Mutex<Vec<HeldCommands>>,held_field:Mutex<Vec<HeldField>>,
    adjacent:Mutex<AdjacentCache>,held_source_ids:Mutex<Option<[u64;4]>>,
    #[cfg(test)]
    held_hits:AtomicU32,
    #[cfg(test)]
    adjacent_hits:AtomicU32,
    lost:AtomicBool,
    stats_supported:bool,
    stats:Mutex<Option<Arc<StatsPipelines>>>,stats_idle:Mutex<Vec<StatsJob>>,
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
    extended:Vec<VulkanPair>,auxiliary:bool,cached_extended:bool,
    adjacent_publish:Option<[(usize,[u64;2]);3]>,
    dynamic_scene:bool,
    rgb_outputs:[vk::ImageView;MAX_PHASES],
    hdr_jobs:[Option<StatsJob>;MAX_PHASES],
    fence:vk::Fence,
    #[cfg(target_os="android")]
    ahb_inputs:Option<Vec<*const crate::ahb_vulkan::Job>>,
}
impl Drop for VulkanPair{
    fn drop(&mut self){unsafe{
        if self.adjacent_publish.take().is_some(){
            self.core.adjacent.lock().unwrap_or_else(|e|e.into_inner()).recording=false;
        }
        let d=&self.core.device;
        d.destroy_descriptor_pool(self.pool,None);
        d.destroy_buffer(self.field,None);
        d.free_memory(self.memory,None);
        #[cfg(target_os="android")]
        if let Some(inputs)=self.ahb_inputs.take(){for p in inputs{(&*p).release_consumer();}}
        let counter=if self.auxiliary{&self.core.aux_in_flight}else{&self.core.in_flight};
        counter.fetch_sub(1,Ordering::AcqRel);
    }}
}
impl VulkanPair{
    fn new(core:Arc<Core>,command:vk::CommandBuffer,a:VulkanPicture,b:VulkanPicture)->Result<Self>{
        Self::new_kind(core,command,a,b,false)
    }
    fn new_kind(core:Arc<Core>,command:vk::CommandBuffer,a:VulkanPicture,b:VulkanPicture,auxiliary:bool)->Result<Self>{
        if core.lost.load(Ordering::Acquire) || command==vk::CommandBuffer::null() || !a.valid() || !b.valid() || a.rgb()!=b.rgb()
            || (matches!(core.mode,1|4)&&(a.nv12!=1||b.nv12!=1)) || (matches!(core.mode,2|5)&&(a.nv12!=0||b.nv12!=0)) || (core.mode==3&&!a.rgb())
            || (core.mode>=4&&a.chroma_location!=b.chroma_location)
        {return Err("invalid resident pictures".into());}
        let counter=if auxiliary{&core.aux_in_flight}else{&core.in_flight};
        let max=if auxiliary{2*MAX_IN_FLIGHT}else{MAX_IN_FLIGHT};
        counter.fetch_update(Ordering::AcqRel,Ordering::Acquire,|n|
            (n<max).then_some(n+1)).map_err(|_|"resident in-flight budget")?;
        let mut p=Self{core,pool:vk::DescriptorPool::null(),field:vk::Buffer::null(),
            memory:vk::DeviceMemory::null(),sets:Vec::new(),command,a,b,phases:0,extended:Vec::new(),auxiliary,cached_extended:false,adjacent_publish:None,dynamic_scene:false,
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
                .usage(vk::BufferUsageFlags::STORAGE_BUFFER|vk::BufferUsageFlags::TRANSFER_SRC|vk::BufferUsageFlags::TRANSFER_DST).sharing_mode(vk::SharingMode::EXCLUSIVE),None).map_err(err)?;
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
            if let Some((q,_,_))=&c.quality{layouts.push(q.set);}
            p.sets=d.allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(p.pool)
                .set_layouts(&layouts)).map_err(err)?;
        }
        Ok(p)
    }
    // Called only after successful fence retirement or explicit cancellation
    // of a discarded command buffer. The next pair rewrites every used set.
    fn recycle(&mut self){
        if self.core.lost.load(Ordering::Acquire){return;}
        for aux in &mut self.extended{aux.recycle();}
        self.extended.clear();
        {
            let mut idle=self.core.stats_idle.lock().unwrap_or_else(|e|e.into_inner());
            for slot in &mut self.hdr_jobs{
                if let Some(job)=slot.take(){if idle.len()<MAX_PHASES{idle.push(job);}}
            }
        }
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
            if let Some((quality,scene,snapshot))=&c.quality{
                let set=self.sets[MAX_PHASES+1];
                let [ay,au,av]=self.a.views();let [by,bu,bv]=self.b.views();
                let sampled=vk::DescriptorType::SAMPLED_IMAGE;
                self.descriptors(set,&[(0,ay,sampled),(1,au,sampled),(2,av,sampled),
                    (3,by,sampled),(4,bu,sampled),(5,bv,sampled)],6);
                let flags=c.gh|((self.a.nv12==1) as u32)<<16|((self.b.nv12==1) as u32)<<17|
                    ((self.a.rgb()) as u32)<<18;
                snapshot.dispatch(self.command,set,[c.width,c.height,c.gw,flags],2,1);
                c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
                for wave in 0..c.gw+2*(c.gh-1){
                    quality.dispatch(self.command,set,[c.width,c.height,c.gw,flags|(wave<<19)],c.gh,2);
                    c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                        vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
                }
                scene.dispatch(self.command,set,[c.width,c.height,c.gw,flags],1,1);
                c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
            }
        }
    }
    unsafe fn prepare_masks(&self,phase:f32)->u32{
        let c=&self.core;
        let algo=c.algorithm.load(Ordering::Acquire);
        if algo==0||phase==0.0||phase==1.0{return 0;}
        let Some(masks)=&c.masks else{return 0;};
        let set=self.sets[MAX_PHASES+1];
        let n=c.gw*c.gh;
        let words=[c.gw,c.gh,(phase*256.0).round() as u32,0];
        let barrier=[vk::BufferMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_READ|vk::AccessFlags::SHADER_WRITE)
            .dst_access_mask(vk::AccessFlags::SHADER_READ|vk::AccessFlags::SHADER_WRITE)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .buffer(self.field).offset(0).size(c.field_size)];
        unsafe{
            // A previous output phase may still be reading these masks.
            for (p,groups) in masks.iter().zip([n.div_ceil(64),(2*n).div_ceil(64),n.div_ceil(64)]){
                c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
                p.dispatch(self.command,set,words,groups,1);
            }
            c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
        }
        // Algorithm 23 requires adjacent source-pair fields; the
        // reference selects 21 when extended vectors are unavailable.
        (if algo==23&&self.extended.len()!=2&&!self.cached_extended{21}else{algo})<<8
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
        let phase=if c.algorithm.load(Ordering::Acquire)!=0{(phase*256.0).round()/256.0}else{phase};
        let flags=if self.a.rgb(){64}else{self.a.nv12|(self.b.nv12<<1)|(self.a.chroma_location<<2)|(self.b.chroma_location<<4)};
        let algo=unsafe{self.prepare_masks(phase)};
        let scene=if self.dynamic_scene{8192}else{0};
        unsafe{c.synthesis.dispatch(self.command,set,[phase.to_bits(),flags|algo|scene,matrix,c.width|(c.height<<16)],
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
        let phase=if c.algorithm.load(Ordering::Acquire)!=0{(phase*256.0).round()/256.0}else{phase};
        let algo=unsafe{self.prepare_masks(phase)};
        unsafe{c.synthesis.dispatch(self.command,set,[phase.to_bits(),algo,0,c.width|(c.height<<16)],
            c.width.div_ceil(8),c.height.div_ceil(8));}
        self.phases+=1;
        Ok(())
    }
}

fn new_core(instance:&ash::Instance,physical:vk::PhysicalDevice,device:ash::Device,width:u32,height:u32,mode:u32,tuning:Option<ResidentTuning>,weights:Option<ResidentSearchWeights>)->Result<VulkanResident>{
    if tuning.is_some_and(|t|!t.valid())||weights.is_some_and(|w|!w.valid())||weights.is_some()&&tuning.is_none(){return Err("invalid resident tuning".into());}
    if width<32 || height<32 || width>3840 || height>2160 || (width|height)&1!=0 || u64::from(width)*u64::from(height)>MAX_PIXELS{
        return Err("unsupported resident dimensions".into());
    }
    let properties=unsafe{instance.get_physical_device_properties(physical)};
    let memory=unsafe{instance.get_physical_device_memory_properties(physical)};
    let limits=properties.limits;
    let step=tuning.map_or(16,ResidentTuning::step);
    let (gw,gh)=(width.div_ceil(step),height.div_ceil(step));
    // UHD is opt-in via the high-depth path; preserve the bounded motion-grid budget.
    if u64::from(width)*u64::from(height)>HD_PIXELS && !matches!(mode,1..=3){return Err("UHD resident HDR formats only".into());}
    if tuning.is_some() && u64::from(gw)*u64::from(gh)>32768{return Err("resident quality block budget".into());}
    // Keep the first two directional fields and scene word ABI stable;
    // append immutable coarse snapshots and two global predictors.
    let n=u64::from(gw)*u64::from(gh);
    let field_size=n*32+if tuning.is_some(){n*128+80}else{0};
    if width>limits.max_image_dimension2_d || height>limits.max_image_dimension2_d
        || limits.max_per_stage_descriptor_sampled_images<6 || limits.max_per_stage_descriptor_storage_images<raw_planes(mode) as u32
        || limits.max_per_stage_descriptor_storage_buffers<1 || limits.max_push_constants_size<16
        || limits.max_compute_work_group_invocations<64 || limits.max_compute_work_group_size[0]<64
        || limits.max_compute_work_group_size[1]<8 || gw>limits.max_compute_work_group_count[0]
        || gh>limits.max_compute_work_group_count[0] || tuning.is_some()&&limits.max_compute_shared_memory_size<8192
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
        if let Some(t)=tuning{spirv(&tuned_synthesis(SYNTHESIS,t,0)?,"@group(0) @binding(8) var<uniform> params:vec4u;","synthesize")?}
        else{synthesis_spirv()?}
    }else if mode>=4{raw_synthesis_config(mode,tuning)?}else{high_synthesis_config(mode,tuning)?};
    let synthesis=Pipeline::new(&device,&types,&synthesis_words,c"synthesize")?;
    let quality=if let Some(t)=tuning{
        let mut types=vec![vk::DescriptorType::SAMPLED_IMAGE;6];types.push(vk::DescriptorType::STORAGE_BUFFER);
        let w=weights.unwrap_or_else(||ResidentSearchWeights::legacy(t));
        Some((Pipeline::new(&device,&types,&quality_spirv_weighted(mode,t,w,"analyse")?,c"analyse")?,
            Pipeline::new(&device,&types,&quality_spirv_weighted(mode,t,w,"scene")?,c"scene")?,
            Pipeline::new(&device,&types,&quality_spirv_weighted(mode,t,w,"snapshot")?,c"snapshot")?))
    }else{None};
    let masks=if let Some(t)=tuning{
        let mut types=vec![vk::DescriptorType::SAMPLED_IMAGE;6];types.push(vk::DescriptorType::STORAGE_BUFFER);
        Some([Pipeline::new(&device,&types,&mask_spirv(mode,t,"clear")?,c"clear")?,
            Pipeline::new(&device,&types,&mask_spirv(mode,t,"scatter")?,c"scatter")?,
            Pipeline::new(&device,&types,&mask_spirv(mode,t,"finish")?,c"finish")?])
    }else{None};
    Ok(VulkanResident(Arc::new(Core{device,memory,motion,synthesis,quality,tuning,masks,algorithm:AtomicU32::new(0),width,height,gw,gh,field_size,mode,in_flight:AtomicU32::new(0),aux_in_flight:AtomicU32::new(0),idle:Mutex::new(Vec::new()),held_idle:Mutex::new(Vec::new()),held_field:Mutex::new(Vec::new()),adjacent:Mutex::new(AdjacentCache::default()),held_source_ids:Mutex::new(None),lost:AtomicBool::new(false),
        stats_supported:matches!(mode,1..=3)&&limits.max_per_stage_descriptor_storage_buffers>=4&&
            limits.max_storage_buffer_range>=100001*4*3&&
            u64::from(limits.max_storage_buffer_range)>=4*(3*100001+u64::from(width.div_ceil(5))*u64::from(height.div_ceil(5))),
        stats:Mutex::new(None),stats_idle:Mutex::new(Vec::new()),
        #[cfg(test)]
        held_hits:AtomicU32::new(0),
        #[cfg(test)]
        adjacent_hits:AtomicU32::new(0)})))
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
        new_core(&instance,physical,device,width,height,mode,None,None).map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s)))
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
        new_core(&instance,physical,device,width,height,mode,Some(unsafe{*tuning}),None)
            .map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s)))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_create_weighted(instance:vk::Instance,physical:vk::PhysicalDevice,device:vk::Device,
    get_proc:Option<vk::PFN_vkGetInstanceProcAddr>,width:u32,height:u32,mode:u32,
    tuning:*const ResidentTuning,weights:*const ResidentSearchWeights)->*mut VulkanResident{
    if instance==vk::Instance::null()||physical==vk::PhysicalDevice::null()||device==vk::Device::null()||
        get_proc.is_none()||mode>5||tuning.is_null()||weights.is_null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let f=ash::StaticFn{get_instance_proc_addr:get_proc.unwrap()};
        let instance=unsafe{ash::Instance::load(&f,instance)};
        let device=unsafe{ash::Device::load(instance.fp_v1_0(),device)};
        new_core(&instance,physical,device,width,height,mode,Some(unsafe{*tuning}),Some(unsafe{*weights}))
            .map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s)))
    })).unwrap_or(ptr::null_mut())
}
/// Configure the reference synthesis family before the first pair.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_set_synthesis(s:*const VulkanResident,algorithm:u32)->i32{
    if s.is_null()||!matches!(algorithm,1|2|11|13|21|22|23){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let c=unsafe{&(*s).0};
        if c.tuning.is_none()||c.in_flight.load(Ordering::Acquire)!=0{return -1;}
        let previous=c.algorithm.swap(algorithm,Ordering::AcqRel);
        if previous!=algorithm{
            let mut cache=c.adjacent.lock().unwrap_or_else(|e|e.into_inner());
            for entry in &mut cache.entries{entry.key=None;}
        }
        0
    })).unwrap_or(-1)
}
/// Player settings shared by all transports, without depending on
/// the player's own options representation or Vulkan header version.
#[repr(C)]
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)]
pub struct ResidentOptions{
    pub preset:u32,pub block:u32,pub overlap:u32,pub pel:u32,
    pub search:u32,pub artifacts:u32,pub scene:u32,pub algorithm:u32,
}
impl ResidentOptions{
    fn resolve(self,width:u32,height:u32)->Option<(ResidentTuning,ResidentSearchWeights,u32)>{
        if self.preset>4||self.overlap>4||self.search>3||self.artifacts>3||self.scene>3{return None;}
        let preset=if matches!(self.preset,0|4){1}else{self.preset};
        let block=if self.block!=0{self.block}else if preset==1||u64::from(width)*u64::from(height)>HD_PIXELS{32}else{16};
        if !matches!(block,8|16|32)||width<block||height<block{return None;}
        let mut overlap=if self.overlap!=0{self.overlap-1}else if preset==1{0}else{2};
        if block==8&&overlap==1{overlap=2;}
        let overlap=match overlap{1=>block/8,2=>block/4,3=>block/2,_=>0};
        let pel=if self.pel!=0{self.pel}else if preset==3{2}else{1};
        if !matches!(pel,1|2|4){return None;}
        let radius=match self.search{1=>1,2=>2,3=>4,_=>(pel*2).min(4)};
        let t=ResidentTuning{block,overlap,pel,radius,satd:1,chroma:1,lambda:block*block*4/(pel*pel),
            pnew:50,scene:match self.scene{1=>30,3=>70,_=>50},artifacts:self.artifacts};
        if !t.valid(){return None;}
        let step=t.step();
        let covered=|x:u32|((x-overlap)/step)*step+overlap;
        let mut levels=0;
        while levels<16&&(covered(width)>>levels)>=block&&(covered(height)>>levels)>=block{levels+=1;}
        let w=ResidentSearchWeights{filter:0,lambda:block*block*10000/32,lsad:block*block*250,
            plevel_q16:98304,levels,level:0,pnew:50,pzero:100,pglobal:50,pnbour:50,preverse:0,sad_gate:0};
        let algo=if self.algorithm!=0{self.algorithm}else if preset==3{23}else{13};
        if !w.valid()||!matches!(algo,1|2|11|13|21|22|23){return None;}
        Some((t,w,algo))
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_create_quality(instance:vk::Instance,physical:vk::PhysicalDevice,device:vk::Device,
    get_proc:Option<vk::PFN_vkGetInstanceProcAddr>,width:u32,height:u32,mode:u32,
    options:*const ResidentOptions)->*mut VulkanResident{
    if options.is_null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let Some((t,w,algo))=unsafe{*options}.resolve(width,height)else{return ptr::null_mut();};
        let p=unsafe{ambient_svp_vk_create_weighted(instance,physical,device,get_proc,width,height,mode,&t,&w)};
        if !p.is_null()&&unsafe{ambient_svp_vk_set_synthesis(p,algo)}!=0{
            unsafe{ambient_svp_vk_destroy(p)};return ptr::null_mut();
        }
        p
    })).unwrap_or(ptr::null_mut())
}
/// A submitted held-image job owns the native command resources until its
/// actual fence retires. The caller retains the images and semaphores.
pub struct HeldSubmission {
    core:Arc<Core>,commands:Option<HeldCommands>,
    views:Vec<vk::ImageView>,pair:*mut VulkanPair,
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_retire_held(job:*mut HeldSubmission,wait:u32)->i32{
    if wait>1{return -1;}
    let rc=unsafe{retire_held_impl(job,wait,ptr::null_mut(),0)};
    if rc==3{1}else{rc}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_retire_held_status(job:*mut HeldSubmission,wait:u32)->i32{
    unsafe{retire_held_impl(job,wait,ptr::null_mut(),0)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_retire_held_capture_group(job:*mut HeldSubmission,wait:u32,pass:*mut TwoPass,index:u64,end:u64)->i32{
    if job.is_null()||pass.is_null()||wait>2{return -1;}
    unsafe{retire_held_with(job,wait,|p|ambient_svp_hdr_pass_capture_group(pass,p,0,index,end)==1)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_retire_held_capture(job:*mut HeldSubmission,wait:u32,pass:*mut TwoPass,index:u64)->i32{
    if pass.is_null(){return -1;}
    unsafe{retire_held_impl(job,wait,pass,index)}
}
unsafe fn retire_held_impl(job:*mut HeldSubmission,wait:u32,pass:*mut TwoPass,index:u64)->i32{
    if job.is_null()||wait>2{return -1;}
    unsafe{retire_held_with(job,wait,|p|pass.is_null()||ambient_svp_hdr_pass_capture(pass,p,0,index)==1)}
}
unsafe fn retire_held_with(job:*mut HeldSubmission,wait:u32,consume:impl FnOnce(*const VulkanPair)->bool)->i32{
    let j=unsafe{&*job};
    let d=&j.core.device;
    let fence=j.commands.as_ref().unwrap().fence;
    let lost=loop{
        let status=if wait!=0{unsafe{d.wait_for_fences(&[fence],true,if wait==2{250_000_000}else{u64::MAX})}.map(|_|true)}
            else{unsafe{d.get_fence_status(fence)}};
        match status{
            Ok(true)=>break false,
            Ok(false)=>return 0,
            Err(vk::Result::ERROR_DEVICE_LOST)=>break true,
            Err(vk::Result::TIMEOUT)=>if wait!=1{return 0;},
            Err(_)=>if wait!=1{return -1;}else{std::thread::sleep(std::time::Duration::from_millis(10));},
        }
    };
    let j=unsafe{Box::from_raw(job)};
    let captured=!lost&&catch_unwind(AssertUnwindSafe(||consume(j.pair))).unwrap_or(false);
    if lost{j.core.lost.store(true,Ordering::Release);}
    unsafe{
        if lost||ambient_svp_vk_release(j.pair)!=1{drop(Box::from_raw(j.pair));}
        for view in j.views{j.core.device.destroy_image_view(view,None);}
        if let Some(commands)=j.commands{commands.recycle(&j.core);}
    }
    if lost{3}else if captured{1}else{2}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_submit_held(s:*const VulkanResident,queue:vk::Queue,family:u32,
    images:*const vk::Image,count:u32,waits:*const vk::Semaphore,signal:vk::Semaphore,
    value:u64,phase:f32,job:*mut *mut HeldSubmission)->i32{
    if job.is_null(){return -1;}
    unsafe{*job=ptr::null_mut();}
    unsafe{render_held_impl(s,queue,family,images,count,waits,signal,value,phase,
        ptr::null(),ptr::null(),ptr::null(),ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),
        0,ptr::null_mut(),0,job,0)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_submit_held_cached(s:*const VulkanResident,queue:vk::Queue,family:u32,
    images:*const vk::Image,count:u32,waits:*const vk::Semaphore,signal:vk::Semaphore,
    value:u64,phase:f32,job:*mut *mut HeldSubmission,generation:u64)->i32{
    if job.is_null(){return -1;}
    unsafe{*job=ptr::null_mut();}
    unsafe{render_held_impl(s,queue,family,images,count,waits,signal,value,phase,
        ptr::null(),ptr::null(),ptr::null(),ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),
        0,ptr::null_mut(),0,job,generation)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_submit_held_capture(s:*const VulkanResident,queue:vk::Queue,family:u32,
    images:*const vk::Image,count:u32,waits:*const vk::Semaphore,signal:vk::Semaphore,
    value:u64,phase:f32,windows:*const HdrWindow,tracks:*const HdrTrack,axes:*const [u32;3],
    n:u32,pass:*mut TwoPass,index:u64,job:*mut *mut HeldSubmission,generation:u64)->i32{
    if job.is_null(){return -1;}
    unsafe{*job=ptr::null_mut();}
    if pass.is_null()||unsafe{(&*pass).mode()}!=1{return -1;}
    unsafe{render_held_impl(s,queue,family,images,count,waits,signal,value,phase,
        windows,tracks,axes,ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),
        n,pass,index,job,generation)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_submit_held_stats(s:*const VulkanResident,queue:vk::Queue,family:u32,
    images:*const vk::Image,count:u32,waits:*const vk::Semaphore,signal:vk::Semaphore,
    value:u64,phase:f32,windows:*const HdrWindow,tracks:*const HdrTrack,axes:*const [u32;3],
    n:u32,pass:*mut TwoPass,index:u64,job:*mut *mut HeldSubmission,generation:u64)->i32{
    if job.is_null(){return -1;}
    unsafe{*job=ptr::null_mut();}
    if n==0||n>3||!pass.is_null()&&unsafe{((&*pass).mode(),(&*pass).version())}!=(2,0){return -1;}
    unsafe{render_held_impl(s,queue,family,images,count,waits,signal,value,phase,
        windows,tracks,axes,ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),
        n,pass,index,job,generation)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_collect_held_stats(job:*mut HeldSubmission,wait:u32,
    results:*mut HdrResult,rects:*mut [u32;4],ellipses:*mut [u32;3],shifts:*mut [i32;2],n:u32,pass:*mut TwoPass,index:u64)->i32{
    if job.is_null()||wait>2||n==0||n>3||results.is_null()||rects.is_null()||ellipses.is_null()||shifts.is_null(){return -1;}
    unsafe{retire_held_with(job,wait,|p|{
        let rc=if n==1{ambient_svp_vk_hdr_read(p,0,results,n)}
            else{ambient_svp_vk_hdr_read_track(p,0,results,shifts,n)};
        rc==1&&(n==1||ambient_svp_vk_hdr_read_rectangles(p,0,rects,n)==1&&
            ambient_svp_vk_hdr_read_ellipses(p,0,ellipses,n)==1)&&
            (pass.is_null()||ambient_svp_hdr_pass_verify_geometry(pass,p,0,index)==1)
    })}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_set_held_source_ids(s:*const VulkanResident,ids:*const [u64;4])->i32{
    if s.is_null(){return -1;}
    let c=unsafe{&(*s).0};
    *c.held_source_ids.lock().unwrap_or_else(|e|e.into_inner())=if ids.is_null(){None}else{Some(unsafe{*ids})};
    0
}
/// Renderer bridge for libplacebo-held, canonical RGBA16F textures.
/// Caller owns queue serialization and image hold/release. Waits are
/// binary hold semaphores; signal is a caller-owned timeline semaphore.
/// This bounded synchronous submission never maps or copies pixels.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_render_held(s:*const VulkanResident,queue:vk::Queue,family:u32,
    images:*const vk::Image,count:u32,waits:*const vk::Semaphore,signal:vk::Semaphore,
    value:u64,phase:f32)->i32{
    unsafe{ambient_svp_vk_render_held_hdr(s,queue,family,images,count,waits,signal,value,phase,
        ptr::null(),ptr::null(),ptr::null(),ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),ptr::null_mut(),0)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_render_held_hdr(s:*const VulkanResident,queue:vk::Queue,family:u32,
    images:*const vk::Image,count:u32,waits:*const vk::Semaphore,signal:vk::Semaphore,
    value:u64,phase:f32,windows:*const HdrWindow,tracks:*const HdrTrack,axes:*const [u32;3],
    results:*mut HdrResult,rects:*mut [u32;4],ellipses:*mut [u32;3],shifts:*mut [i32;2],n:u32)->i32{
    unsafe{ambient_svp_vk_render_held_pass(s,queue,family,images,count,waits,signal,value,phase,
        windows,tracks,axes,results,rects,ellipses,shifts,n,ptr::null_mut(),0)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_render_held_pass(s:*const VulkanResident,queue:vk::Queue,family:u32,
    images:*const vk::Image,count:u32,waits:*const vk::Semaphore,signal:vk::Semaphore,
    value:u64,phase:f32,windows:*const HdrWindow,tracks:*const HdrTrack,axes:*const [u32;3],
    results:*mut HdrResult,rects:*mut [u32;4],ellipses:*mut [u32;3],shifts:*mut [i32;2],n:u32,pass:*mut TwoPass,index:u64)->i32{
    unsafe{render_held_impl(s,queue,family,images,count,waits,signal,value,phase,
        windows,tracks,axes,results,rects,ellipses,shifts,n,pass,index,ptr::null_mut(),0)}
}
unsafe fn render_held_impl(s:*const VulkanResident,queue:vk::Queue,family:u32,
    images:*const vk::Image,count:u32,waits:*const vk::Semaphore,signal:vk::Semaphore,
    value:u64,phase:f32,windows:*const HdrWindow,tracks:*const HdrTrack,axes:*const [u32;3],
    results:*mut HdrResult,rects:*mut [u32;4],ellipses:*mut [u32;3],shifts:*mut [i32;2],
    n:u32,pass:*mut TwoPass,index:u64,async_job:*mut *mut HeldSubmission,generation:u64)->i32{
    if s.is_null()||queue==vk::Queue::null()||images.is_null()||waits.is_null()||
        !matches!(count,2|3|5)||count==2&&phase!=0.0||signal==vk::Semaphore::null()||value==0||
        !phase.is_finite()||!(0.0..=1.0).contains(&phase){return -1;}
    if !pass.is_null()&&(n==0||n>3){return -1;}
    if n>3||n>0&&(windows.is_null()||async_job.is_null()&&(results.is_null()||rects.is_null()||ellipses.is_null()||shifts.is_null())||n>1&&(tracks.is_null()||axes.is_null())){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let core=&(*s).0;
        if core.mode!=3||core.tuning.is_none()||core.lost.load(Ordering::Acquire){return -1;}
        let images=std::slice::from_raw_parts(images,count as usize);
        let waits=std::slice::from_raw_parts(waits,count as usize);
        if images.iter().enumerate().any(|(i,x)|*x==vk::Image::null()||images[..i].contains(x))||
            waits.iter().enumerate().any(|(i,x)|*x==vk::Semaphore::null()||*x==signal||waits[..i].contains(x)){return -1;}
        let d=&core.device;
        let mut commands=match HeldCommands::acquire(core,family){Ok(x)=>Some(x),Err(_)=>return -1};
        let cmd=commands.as_ref().unwrap().cmd;
        let fence=commands.as_ref().unwrap().fence;
        let mut views=Vec::with_capacity(count as usize);
        let mut pair=ptr::null_mut();
        let mut submitted=false;
        let mut fence_retired=false;
        let mut device_lost=false;
        // Allocate the ticket before submitting: publishing a successful
        // async submission must not need any fallible allocation.
        let mut ticket=(!async_job.is_null()).then(||Box::new(HeldSubmission{
            core:core.clone(),commands:None,
            views:Vec::new(),pair:ptr::null_mut(),
        }));
        let mut deferred=false;
        let source_ids=core.held_source_ids.lock().unwrap_or_else(|e|e.into_inner()).take();
        let mut cache=core.held_field.lock().unwrap_or_else(|e|e.into_inner());
        let mut key=HeldKey{generation,images:[vk::Image::null();4],count,algorithm:core.algorithm.load(Ordering::Acquire)};
        key.images[..count as usize-1].copy_from_slice(&images[..count as usize-1]);
        // A small same-queue cache. Never destroy a snapshot while a
        // queued command may still read it; evict by overwriting in queue order.
        let mut slot=None;
        if generation!=0&&(cache.is_empty()||cache[0].queue==queue){
            slot=cache.iter().position(|x|x.key==Some(key));
            if slot.is_none(){
                slot=cache.iter().position(|x|x.key.is_none());
                if slot.is_none()&&cache.len()<3{
                    if let Ok(field)=HeldField::new(core,queue){cache.push(field);slot=Some(cache.len()-1);}
                }
                if slot.is_none()&&!cache.is_empty(){slot=Some(0);}
            }
        }
        let mut publish_cache=false;
        let result=catch_unwind(AssertUnwindSafe(||->Result<()>{
            for &image in images{
                views.push(d.create_image_view(&vk::ImageViewCreateInfo::default().image(image)
                    .view_type(vk::ImageViewType::TYPE_2D).format(vk::Format::R16G16B16A16_SFLOAT)
                    .subresource_range(vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR)
                        .level_count(1).layer_count(1)),None).map_err(err)?);
            }
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default()
                .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).map_err(err)?;
            let picture=|i:usize|VulkanPicture{y:views[i],u:vk::ImageView::null(),v:vk::ImageView::null(),nv12:2,chroma_location:0};
            let a=picture(0);let b=picture(if count==2{0}else{1});
            let cached=match slot{Some(i)=>Some(&mut cache[i]),None=>None};
            if let Some(x)=cached.as_ref().filter(|x|x.key==Some(key)){
                let mut p=VulkanPair::new(core.clone(),cmd,a,b)?;
                p.cached_extended=x.extended;
                // Restore descriptors used by the phase-specific mask kernels.
                let [ay,au,av]=a.views();let [by,bu,bv]=b.views();
                let sampled=vk::DescriptorType::SAMPLED_IMAGE;
                p.descriptors(p.sets[MAX_PHASES+1],&[(0,ay,sampled),(1,au,sampled),(2,av,sampled),
                    (3,by,sampled),(4,bu,sampled),(5,bv,sampled)],6);
                x.copy(&p,true);
                #[cfg(test)]
                core.held_hits.fetch_add(1,Ordering::Relaxed);
                pair=Box::into_raw(Box::new(p));
            }else{
                if count==5{pair=begin_extended_impl(s,cmd,&picture(2),&a,&b,&picture(3),queue,source_ids.map(|i|[i[2],i[0],i[1],i[3]]));}
                if pair.is_null(){pair=ambient_svp_vk_begin(s,cmd,&a,&b);}
                if !pair.is_null(){
                    if let Some(x)=cached{
                        // Do not cache a downgraded extended analysis.
                        if count!=5||(*pair).extended.len()==2{
                            x.copy(&*pair,false);publish_cache=true;
                        }
                    }
                }
            }
            if !pair.is_null()&&!pass.is_null()&&(&*pass).dynamic()&&(&*pass).mode()==1{
                if ambient_svp_vk_enable_dynamic_scene(pair)!=0{return Err("dynamic HDR requires tuned synthesis".into());}
            }
            if pair.is_null()||ambient_svp_vk_render(pair,phase,3,views[count as usize-1])!=0{
                return Err("held renderer pair rejected".into());
            }
            if n>0{
                let rc=if !pass.is_null()&&(&*pass).version()==0{ambient_svp_vk_hdr_stats_scene_legacy_tracked(pair,0,windows,tracks,axes,phase,n)}
                    else if !pass.is_null(){ambient_svp_vk_hdr_stats_scene(pair,0,windows,n)}
                    else if n==1{ambient_svp_vk_hdr_stats(pair,0,windows,n)}
                    else{ambient_svp_vk_hdr_stats_ellipse(pair,0,windows,tracks,axes,phase,n)};
                if rc!=0{return Err("held HDR stats rejected".into());}
            }
            d.end_command_buffer(cmd).map_err(err)?;
            let stages=vec![vk::PipelineStageFlags::ALL_COMMANDS;count as usize];
            let wait_values=vec![0u64;count as usize];let signals=[signal];let values=[value];let submit_commands=[cmd];
            let mut timeline=vk::TimelineSemaphoreSubmitInfo::default()
                .wait_semaphore_values(&wait_values).signal_semaphore_values(&values);
            let submit=vk::SubmitInfo::default().push_next(&mut timeline).wait_semaphores(waits)
                .wait_dst_stage_mask(&stages).command_buffers(&submit_commands).signal_semaphores(&signals);
            if let Err(e)=d.queue_submit(queue,&[submit],fence){
                device_lost=e==vk::Result::ERROR_DEVICE_LOST;
                return Err(err(e));
            }
            submitted=true;
            if let Some(i)=slot{
                if publish_cache{cache[i].key=Some(key);cache[i].extended=(*pair).extended.len()==2;}
                // Move the used snapshot to the MRU end without freeing GPU storage.
                if cache[i].key==Some(key){let entry=cache.remove(i);cache.push(entry);}
            }
            let sealed=ambient_svp_vk_seal(pair,fence)==0;
            if let Some(mut job)=ticket.take(){
                // Even a rejected seal cannot revoke a successful queue
                // submit. Publish ownership; the fence is authoritative.
                job.commands=commands.take();job.views=std::mem::take(&mut views);job.pair=pair;
                *async_job=Box::into_raw(job);
                deferred=true;
                return if sealed{Ok(())}else{Err("submitted pair seal failed".into())};
            }
            match d.wait_for_fences(&[fence],true,u64::MAX){
                Ok(())=>fence_retired=true,
                Err(e)=>{device_lost=e==vk::Result::ERROR_DEVICE_LOST;return Err(err(e));}
            }
            if !sealed{return Err("held renderer seal failed".into());}
            if n>0&&(pass.is_null()||(&*pass).version()==0){
                let rc=if n==1{ambient_svp_vk_hdr_read(pair,0,results,n)}
                    else{ambient_svp_vk_hdr_read_track(pair,0,results,shifts,n)};
                if rc!=1||n>1&&(ambient_svp_vk_hdr_read_rectangles(pair,0,rects,n)!=1||
                    ambient_svp_vk_hdr_read_ellipses(pair,0,ellipses,n)!=1){
                    return Err("held HDR stats readback failed".into());
                }
            }
            if !pass.is_null()&&(&*pass).mode()==1&&ambient_svp_hdr_pass_capture(pass,pair,0,index)!=1{return Err("scene pass capture failed".into());}
            if !pass.is_null()&&(&*pass).mode()==2&&(&*pass).version()==0&&
                ambient_svp_hdr_pass_verify_geometry(pass,pair,0,index)!=1{return Err("scene replay geometry mismatch".into());}
            Ok(())
        })).unwrap_or_else(|_|Err("held submission panic".into()));
        if deferred{return if result.is_ok(){0}else{1};}
        if submitted{
            // A panic after queue_submit must not force a live renderer to
            // wait for queue idle. Preserve all ownership in its ticket.
            if let Some(mut job)=ticket.take(){
                job.commands=commands.take();job.views=std::mem::take(&mut views);job.pair=pair;
                *async_job=Box::into_raw(job);
                return 1;
            }
            // A failed wait may only release resources after confirmed
            // retirement/device loss. Retry queue idle before cleanup.
            let mut lost=device_lost;
            if !fence_retired&&!lost{
                // Keep the caller's held textures alive until retirement
                // is proven. Unexpected host allocation errors are retried.
                loop{match d.queue_wait_idle(queue){
                    Ok(())=>break,
                    Err(vk::Result::ERROR_DEVICE_LOST)=>{lost=true;break;},
                    Err(_)=>std::thread::yield_now(),
                }}
            }
            if lost{core.lost.store(true,Ordering::Release);}
            // The seal contract is satisfied by this valid fence and
            // rendered pair. On confirmed loss drop without pooling.
            if lost{drop(Box::from_raw(pair));}
            else if ambient_svp_vk_release(pair)!=1{
                // Queue idle proves retirement even if a fence status
                // query fails. Discard rather than reuse the bundle.
                drop(Box::from_raw(pair));
            }
            for view in views{d.destroy_image_view(view,None);}
            if let Some(commands)=commands.take(){commands.recycle(core);}
            return if lost{-1}else if result.is_err(){1}else{0};
        }
        if device_lost{core.lost.store(true,Ordering::Release);}
        // Destroy the unsubmitted command buffer before releasing descriptors.
        drop(commands.take());
        if !submitted&&!pair.is_null(){ambient_svp_vk_cancel(pair);}
        for view in views{d.destroy_image_view(view,None);}
        if result.is_ok(){0}else{-1}
    })).unwrap_or(-2)
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
/// Four consecutive source pictures, all transitioned and retained by
/// the caller until the shared submission fence retires. Compute actual
/// neighbouring pairs before the current phase and copy only their fields.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_begin_extended(s:*const VulkanResident,command:vk::CommandBuffer,
    previous:*const VulkanPicture,a:*const VulkanPicture,b:*const VulkanPicture,next:*const VulkanPicture)->*mut VulkanPair{
    unsafe{begin_extended_impl(s,command,previous,a,b,next,vk::Queue::null(),None)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_begin_extended_cached(s:*const VulkanResident,command:vk::CommandBuffer,
    previous:*const VulkanPicture,a:*const VulkanPicture,b:*const VulkanPicture,next:*const VulkanPicture,
    queue:vk::Queue,ids:*const [u64;4])->*mut VulkanPair{
    if ids.is_null(){return ptr::null_mut();}
    unsafe{begin_extended_impl(s,command,previous,a,b,next,queue,Some(*ids))}
}
unsafe fn begin_extended_impl(s:*const VulkanResident,command:vk::CommandBuffer,
    previous:*const VulkanPicture,a:*const VulkanPicture,b:*const VulkanPicture,next:*const VulkanPicture,
    queue:vk::Queue,ids:Option<[u64;4]>)->*mut VulkanPair{
    if s.is_null()||previous.is_null()||a.is_null()||b.is_null()||next.is_null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let core=unsafe{(*s).0.clone()};
        if core.tuning.is_none()||core.algorithm.load(Ordering::Acquire)!=23{return ptr::null_mut();}
        let (p,a,b,n)=unsafe{(*previous,*a,*b,*next)};
        // Allocate all three pairs BEFORE recording. Failure leaves the
        // caller's command buffer untouched and permits the two-frame path.
        let Ok(mut center)=VulkanPair::new(core.clone(),command,a,b)else{return ptr::null_mut();};
        let Ok(left)=VulkanPair::new_kind(core.clone(),command,p,a,true)else{return ptr::null_mut();};
        let Ok(right)=VulkanPair::new_kind(core.clone(),command,b,n,true)else{return ptr::null_mut();};
        let mut reused=false;
        if let Some(ids)=ids.filter(|ids|queue!=vk::Queue::null()&&ids.iter().enumerate().all(|(i,id)|*id!=0&&!ids[..i].contains(id))){
            let keys=[[ids[1],ids[2]],[ids[0],ids[1]],[ids[2],ids[3]]];
            let mut cache=core.adjacent.lock().unwrap_or_else(|e|e.into_inner());
            if !cache.recording&&(cache.entries.is_empty()||cache.entries[0].field.queue==queue){
                while cache.entries.len()<3{
                    let Ok(field)=HeldField::new(&core,queue)else{break;};
                    cache.entries.push(AdjacentEntry{field,key:None});
                }
                if cache.entries.len()==3{
                    let mut slots=[usize::MAX;3];
                    let mut hits=[false;3];
                    for i in 0..3{
                        if let Some(j)=cache.entries.iter().position(|x|x.key==Some(keys[i])){
                            slots[i]=j;hits[i]=true;
                        }
                    }
                    for i in 0..3{
                        if slots[i]==usize::MAX{
                            slots[i]=(0..3).find(|j|!slots.contains(j)).unwrap();
                            // Never expose overwritten storage on a failed or
                            // abandoned recording, even before submission.
                            cache.entries[slots[i]].key=None;
                        }
                    }
                    cache.recording=true;
                    center.adjacent_publish=Some(std::array::from_fn(|i|(slots[i],keys[i])));
                    for (i,p) in [&center,&left,&right].into_iter().enumerate(){
                        let field=&cache.entries[slots[i]].field;
                        if hits[i]{
                            let [ay,au,av]=p.a.views();let [by,bu,bv]=p.b.views();
                            let sampled=vk::DescriptorType::SAMPLED_IMAGE;
                            p.descriptors(p.sets[MAX_PHASES+1],&[(0,ay,sampled),(1,au,sampled),(2,av,sampled),
                                (3,by,sampled),(4,bu,sampled),(5,bv,sampled)],6);
                            unsafe{field.copy(p,true);}
                            #[cfg(test)]
                            core.adjacent_hits.fetch_add(1,Ordering::Relaxed);
                        }else{unsafe{p.analyse();field.copy(p,false);}}
                    }
                    reused=true;
                }
            }
        }
        unsafe{
            if !reused{center.analyse();left.analyse();right.analyse();}
            let d=&core.device;
            let before=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE|vk::AccessFlags::TRANSFER_WRITE|vk::AccessFlags::TRANSFER_READ)
                .dst_access_mask(vk::AccessFlags::TRANSFER_READ|vk::AccessFlags::TRANSFER_WRITE)];
            d.cmd_pipeline_barrier(command,vk::PipelineStageFlags::COMPUTE_SHADER|vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::TRANSFER,vk::DependencyFlags::empty(),&before,&[],&[]);
            let n=u64::from(core.gw)*u64::from(core.gh);
            // Upstream's previous forward/backward naming is opposite
            // the resident source-to-destination naming. The previous
            // pair's B->A and next pair's A->B are the extended vectors.
            d.cmd_copy_buffer(command,left.field,center.field,
                &[vk::BufferCopy::default().src_offset(n*16).dst_offset((8*n+3)*16).size(n*16),
                  vk::BufferCopy::default().src_offset(2*n*16).dst_offset((10*n+3)*16).size(16)]);
            d.cmd_copy_buffer(command,right.field,center.field,
                &[vk::BufferCopy::default().src_offset(0).dst_offset((9*n+3)*16).size(n*16),
                  vk::BufferCopy::default().src_offset(2*n*16).dst_offset((10*n+4)*16).size(16)]);
            let after=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)];
            d.cmd_pipeline_barrier(command,vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&after,&[],&[]);
        }
        center.extended=vec![left,right];
        Box::into_raw(Box::new(center))
    })).unwrap_or(ptr::null_mut())
}

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
        pair.ahb_inputs=Some(vec![a,b]);
        unsafe{pair.analyse();}
        Box::into_raw(Box::new(pair))
    })).unwrap_or(ptr::null_mut())
}
#[cfg(target_os="android")]
pub(crate) unsafe fn begin_ahb_extended(s:*const VulkanResident,command:vk::CommandBuffer,queue:vk::Queue,
    past:*const crate::ahb_vulkan::Job,a:*const crate::ahb_vulkan::Job,b:*const crate::ahb_vulkan::Job,
    next:*const crate::ahb_vulkan::Job)->*mut VulkanPair{
    if s.is_null()||queue==vk::Queue::null()||[past,a,b,next].iter().any(|p|p.is_null()){return ptr::null_mut();}
    let core=unsafe{&(*s).0};
    let convert=|p:*const crate::ahb_vulkan::Job|{
        let (device,q,w,h,view)=unsafe{(&*p).picture()};
        if unsafe{(&*p).high_depth()}!=(core.mode==3)||device!=core.device.handle()||
            q!=queue||(w,h)!=(core.width,core.height){return None;}
        Some(VulkanPicture{y:view,u:vk::ImageView::null(),v:vk::ImageView::null(),nv12:2,chroma_location:0})
    };
    let (Some(pp),Some(pa),Some(pb),Some(pn))=(convert(past),convert(a),convert(b),convert(next))
        else{return ptr::null_mut();};
    let pair=unsafe{ambient_svp_vk_begin_extended(s,command,&pp,&pa,&pb,&pn)};
    if !pair.is_null(){
        let inputs=vec![past,a,b,next];
        for &p in &inputs{unsafe{(&*p).retain_consumer();}}
        unsafe{(*pair).ahb_inputs=Some(inputs);}
    }
    pair
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_enable_dynamic_scene(pair:*mut VulkanPair)->i32{
    if pair.is_null(){return -1;}
    let p=unsafe{&mut *pair};
    if p.phases!=0||p.core.tuning.is_none()||p.core.mode==0||p.core.mode>=4||p.fence!=vk::Fence::null(){return -1;}
    p.dynamic_scene=true;0
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
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,None,None,false)else{return -1;};
        unsafe{job.record(p.command,p.core.width,p.core.height);}
        p.hdr_jobs[i]=Some(job);0
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_stats_scene(pair:*mut VulkanPair,phase:u32,
    windows:*const HdrWindow,count:u32)->i32{
    if pair.is_null()||windows.is_null()||count!=1{return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&mut *pair};let i=phase as usize;
        if p.fence!=vk::Fence::null()||i>=p.phases||p.hdr_jobs[i].is_some()||
            p.rgb_outputs[i]==vk::ImageView::null(){return -1;}
        let windows=unsafe{std::slice::from_raw_parts(windows,count as usize)};
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,None,None,true)else{return -1;};
        unsafe{
            job.record(p.command,p.core.width,p.core.height);
            if p.dynamic_scene{job.record_cut(p.command,p.field,2*u64::from(p.core.gw)*u64::from(p.core.gh)*16);}
        }
        p.hdr_jobs[i]=Some(job);0
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_stats_scene_legacy(pair:*mut VulkanPair,phase:u32,
    windows:*const HdrWindow,tracks:*const HdrTrack,axes:*const [u32;3],count:u32)->i32{
    if pair.is_null()||windows.is_null()||!(1..=3).contains(&count)||count>1&&(tracks.is_null()||axes.is_null()){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&mut *pair};let i=phase as usize;
        if p.fence!=vk::Fence::null()||i>=p.phases||p.hdr_jobs[i].is_some()||
            p.rgb_outputs[i]==vk::ImageView::null(){return -1;}
        let windows=unsafe{std::slice::from_raw_parts(windows,count as usize)};
        let tracking=if count>1{Some((unsafe{std::slice::from_raw_parts(tracks,count as usize)},0.0,true))}else{None};
        let ellipses=if count>1{Some(unsafe{std::slice::from_raw_parts(axes,count as usize)})}else{None};
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,tracking,ellipses,true)else{return -1;};
        unsafe{job.record(p.command,p.core.width,p.core.height);}
        p.hdr_jobs[i]=Some(job);0
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_stats_scene_legacy_tracked(pair:*mut VulkanPair,phase:u32,
    windows:*const HdrWindow,tracks:*const HdrTrack,axes:*const [u32;3],fraction:f32,count:u32)->i32{
    if !fraction.is_finite()||!(0.0..=1.0).contains(&fraction)||pair.is_null()||windows.is_null()||!(1..=3).contains(&count)||count>1&&(tracks.is_null()||axes.is_null()){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&mut *pair};let i=phase as usize;
        if p.fence!=vk::Fence::null()||i>=p.phases||p.hdr_jobs[i].is_some()||
            p.rgb_outputs[i]==vk::ImageView::null(){return -1;}
        let windows=unsafe{std::slice::from_raw_parts(windows,count as usize)};
        let tracking=if count>1{Some((unsafe{std::slice::from_raw_parts(tracks,count as usize)},fraction,true))}else{None};
        let ellipses=if count>1{Some(unsafe{std::slice::from_raw_parts(axes,count as usize)})}else{None};
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,tracking,ellipses,true)else{return -1;};
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
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,Some((tracks,t,false)),None,false)else{return -1;};
        unsafe{job.record(p.command,p.core.width,p.core.height);}
        p.hdr_jobs[i]=Some(job);0
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_stats_affine(pair:*mut VulkanPair,phase:u32,
    windows:*const HdrWindow,tracks:*const HdrTrack,t:f32,count:u32)->i32{
    if pair.is_null()||windows.is_null()||tracks.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&mut *pair};let i=phase as usize;
        if p.fence!=vk::Fence::null()||i>=p.phases||p.hdr_jobs[i].is_some()||
            p.rgb_outputs[i]==vk::ImageView::null(){return -1;}
        let windows=unsafe{std::slice::from_raw_parts(windows,count as usize)};
        let tracks=unsafe{std::slice::from_raw_parts(tracks,count as usize)};
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,Some((tracks,t,true)),None,false)else{return -1;};
        unsafe{job.record(p.command,p.core.width,p.core.height);}
        p.hdr_jobs[i]=Some(job);0
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_stats_ellipse(pair:*mut VulkanPair,phase:u32,
    windows:*const HdrWindow,tracks:*const HdrTrack,ellipses:*const [u32;3],t:f32,count:u32)->i32{
    if pair.is_null()||windows.is_null()||tracks.is_null()||ellipses.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&mut *pair};let i=phase as usize;
        if p.fence!=vk::Fence::null()||i>=p.phases||p.hdr_jobs[i].is_some()||
            p.rgb_outputs[i]==vk::ImageView::null(){return -1;}
        let windows=unsafe{std::slice::from_raw_parts(windows,count as usize)};
        let tracks=unsafe{std::slice::from_raw_parts(tracks,count as usize)};
        let Ok(job)=StatsJob::new(&p.core,p.rgb_outputs[i],p.field,windows,Some((tracks,t,true)),Some(unsafe{std::slice::from_raw_parts(ellipses,count as usize)}),false)else{return -1;};
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
// Optional metadata-only affine readback, compatible with the legacy tracker.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_read_affine(pair:*const VulkanPair,phase:u32,
    gradients:*mut [f32;4],count:u32)->i32{
    if pair.is_null()||gradients.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&*pair};let i=phase as usize;
        if p.fence==vk::Fence::null()||i>=p.phases{return -1;}
        match unsafe{p.core.device.get_fence_status(p.fence)}{Ok(true)=>{},Ok(false)=>return 0,Err(_)=>return -1}
        let Some(job)=p.hdr_jobs[i].as_ref()else{return -1;};
        job.gradients(unsafe{std::slice::from_raw_parts_mut(gradients,count as usize)}).map_or(-1,|_|1)
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_read_rectangles(pair:*const VulkanPair,phase:u32,
    rectangles:*mut [u32;4],count:u32)->i32{
    if pair.is_null()||rectangles.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&*pair};let i=phase as usize;
        if p.fence==vk::Fence::null()||i>=p.phases{return -1;}
        match unsafe{p.core.device.get_fence_status(p.fence)}{Ok(true)=>{},Ok(false)=>return 0,Err(_)=>return -1}
        let Some(job)=p.hdr_jobs[i].as_ref()else{return -1;};
        job.rectangles(unsafe{std::slice::from_raw_parts_mut(rectangles,count as usize)}).map_or(-1,|_|1)
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_hdr_read_ellipses(pair:*const VulkanPair,phase:u32,
    ellipses:*mut [u32;3],count:u32)->i32{
    if pair.is_null()||ellipses.is_null()||!(1..=3).contains(&count){return -1;}
    catch_unwind(AssertUnwindSafe(||{
        let p=unsafe{&*pair};let i=phase as usize;
        if p.fence==vk::Fence::null()||i>=p.phases{return -1;}
        match unsafe{p.core.device.get_fence_status(p.fence)}{Ok(true)=>{},Ok(false)=>return 0,Err(_)=>return -1}
        let Some(job)=p.hdr_jobs[i].as_ref()else{return -1;};
        job.ellipses(unsafe{std::slice::from_raw_parts_mut(ellipses,count as usize)}).map_or(-1,|_|1)
    })).unwrap_or(-1)
}
// Offline/two-pass scene capture. The caller supplies authoritative
// boundaries and indices of ALL output frames, including originals.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_scene_create(first:u64,end:u64,width:u32,height:u32,
    percentages:*const [u32;9])->*mut HdrScene{
    if percentages.is_null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||HdrScene::new(first,end,width,height,unsafe{*percentages})
        .map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s))))).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_scene_push(scene:*mut HdrScene,pair:*const VulkanPair,phase:u32,index:u64)->i32{
    if scene.is_null()||pair.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let p=&*pair;let i=phase as usize;
        if p.fence==vk::Fence::null()||i>=p.phases{return -1;}
        match p.core.device.get_fence_status(p.fence){Ok(true)=>{},Ok(false)=>return 0,Err(_)=>return -1}
        let Some(job)=p.hdr_jobs[i].as_ref()else{return -1;};
        let Ok(frame)=job.scene_frame()else{return -1;};
        (&mut *scene).push(index,frame).map_or(-1,|_|1)
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_scene_finish(scene:*const HdrScene,out:*mut SceneResult)->i32{
    if scene.is_null()||out.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let Ok(r)=(&*scene).finish()else{return -1;};
        *out=r;1
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_scene_destroy(scene:*mut HdrScene){
    if !scene.is_null(){let _=catch_unwind(AssertUnwindSafe(||unsafe{drop(Box::from_raw(scene))}));}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_scene_validate(first:u64,end:u64,measured:*const SceneResult,
    authored:*const SceneResult,count:u32)->i32{
    if measured.is_null()||authored.is_null()||count==0||count>1_000_000{return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        i32::from(hdr_stats::validate_scene_sequence(first,end,
            std::slice::from_raw_parts(measured,count as usize),
            std::slice::from_raw_parts(authored,count as usize)))
    })).unwrap_or(-1)
}
// The per-playback session is serialized, including all accesses from
// scheduler/display threads. The caller retains the handle until retirement.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_open(mode:u32,plan:*const std::ffi::c_char,
    cache:*const std::ffi::c_char,key:*const std::ffi::c_char,profile:*const std::ffi::c_char,w:u32,h:u32)->*mut TwoPass{
    if [plan,cache,key,profile].iter().any(|p|p.is_null()){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let (Ok(plan),Ok(cache),Ok(key),Ok(profile))=(std::ffi::CStr::from_ptr(plan).to_str(),
            std::ffi::CStr::from_ptr(cache).to_str(),std::ffi::CStr::from_ptr(key).to_str(),std::ffi::CStr::from_ptr(profile).to_str())else{return ptr::null_mut();};
        TwoPass::open(mode,std::path::Path::new(plan),std::path::Path::new(cache),key,profile,w,h)
            .map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s)))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_open_version(mode:u32,version:u32,plan:*const std::ffi::c_char,
    cache:*const std::ffi::c_char,key:*const std::ffi::c_char,profile:*const std::ffi::c_char,w:u32,h:u32)->*mut TwoPass{
    if [plan,cache,key,profile].iter().any(|p|p.is_null()){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let (Ok(plan),Ok(cache),Ok(key),Ok(profile))=(std::ffi::CStr::from_ptr(plan).to_str(),
            std::ffi::CStr::from_ptr(cache).to_str(),std::ffi::CStr::from_ptr(key).to_str(),std::ffi::CStr::from_ptr(profile).to_str())else{return ptr::null_mut();};
        TwoPass::open_version(mode,version,std::path::Path::new(plan),std::path::Path::new(cache),key,profile,w,h)
            .map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s)))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_interval(pass:*const TwoPass,index:u64,end:*mut u64)->i32{
    if pass.is_null()||end.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let Ok((_,boundary))=(&*pass).interval(index)else{return -1;};*end=boundary;1
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_capture(pass:*mut TwoPass,pair:*const VulkanPair,phase:u32,index:u64)->i32{
    unsafe{ambient_svp_hdr_pass_capture_group(pass,pair,phase,index,0)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_is_dynamic(pass:*const TwoPass)->i32{
    if pass.is_null(){return 0;}
    i32::from(unsafe{(&*pass).dynamic()})
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_capture_group(pass:*mut TwoPass,pair:*const VulkanPair,phase:u32,index:u64,group_end:u64)->i32{
    if pass.is_null()||pair.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let p=&*pair;let i=phase as usize;
        if p.fence==vk::Fence::null()||i>=p.phases{return -1;}
        match p.core.device.get_fence_status(p.fence){Ok(true)=>{},Ok(false)=>return 0,Err(_)=>return -1}
        let Some(job)=p.hdr_jobs[i].as_ref()else{return -1;};
        if (&*pass).version()==0{
            let Ok(frames)=job.scene_frames()else{return -1;};
            let Ok(signature)=job.geometry_signature()else{return -1;};
            return (&mut *pass).capture_legacy_geometry(index,frames,signature).map_or(-1,|_|1);
        }
        let Ok(mut frames)=job.scene_frames()else{return -1;};
        if frames.len()!=1||frames[0].count!=9{return -1;}
        let frame=frames.remove(0);
        let schema:[u32;9]=frame.percentages[..9].try_into().unwrap();
        if (&*pass).dynamic(){
            if !p.dynamic_scene||group_end<=index{(&mut *pass).abort();return -1;}
            let Ok(cut)=job.cut_flag()else{(&mut *pass).abort();return -1;};
            if (&mut *pass).discover_cut(index,group_end,cut).is_err(){(&mut *pass).abort();return -1;}
        }
        (&mut *pass).capture_v1(index,frame.frame,schema).map_or(-1,|_|1)
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_verify_geometry(pass:*mut TwoPass,pair:*const VulkanPair,phase:u32,index:u64)->i32{
    if pass.is_null()||pair.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let p=&*pair;let i=phase as usize;
        if p.fence==vk::Fence::null()||i>=p.phases{return -1;}
        match p.core.device.get_fence_status(p.fence){Ok(true)=>{},Ok(false)=>return 0,Err(_)=>return -1}
        let Some(job)=p.hdr_jobs[i].as_ref()else{return -1;};
        let Ok(signature)=job.geometry_signature()else{return -1;};
        (&mut *pass).verify_geometry(index,signature).map_or(-1,|_|1)
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_lookup(pass:*mut TwoPass,index:u64,out:*mut SceneResult)->i32{
    if pass.is_null()||out.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let Ok(result)=(&mut *pass).lookup(index)else{return -1;};*out=result;1
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_lookup_v1(pass:*mut TwoPass,index:u64,schema:*const [u32;9],out:*mut SceneResult)->i32{
    if pass.is_null()||schema.is_null()||out.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let Ok(result)=(&mut *pass).lookup_v1(index,*schema)else{return -1;};*out=result;1
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_lookup_legacy(pass:*mut TwoPass,index:u64,out:*mut LegacySceneResult)->i32{
    if pass.is_null()||out.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{
        let Ok(result)=(&mut *pass).lookup_legacy(index)else{return -1;};*out=result;1
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_finish(pass:*mut TwoPass)->i32{
    if pass.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{(&mut *pass).finish().map_or(-1,|_|1)})).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_hdr_pass_destroy(pass:*mut TwoPass){
    if !pass.is_null(){let _=catch_unwind(AssertUnwindSafe(||unsafe{drop(Box::from_raw(pass))}));}
}
// The caller must pass the fence of the successful submission containing
// this exact command buffer. The fence is borrowed and must not be reset
// or destroyed before release. Never free in-flight descriptors/images.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_seal(pair:*mut VulkanPair,fence:vk::Fence)->i32{
    if pair.is_null() || fence==vk::Fence::null(){return -1;}
    let p=unsafe{&mut *pair};
    if p.fence!=vk::Fence::null() || p.phases==0{return -1;}
    p.fence=fence;
    if let Some(keys)=p.adjacent_publish.take(){
        let mut cache=p.core.adjacent.lock().unwrap_or_else(|e|e.into_inner());
        for (i,key) in keys{cache.entries[i].key=Some(key);}
        cache.recording=false;
    }
    0
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
// Recovery for a caller that successfully submitted but could not seal.
// The actual submit fence must belong to this pair's submission. Never
// recycle an unsealed pair; destroy it only on proven completion/loss.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_release_unsealed(pair:*mut VulkanPair,fence:vk::Fence)->i32{
    if pair.is_null()||fence==vk::Fence::null(){return -1;}
    let p=unsafe{&*pair};
    if p.fence!=vk::Fence::null()&&p.fence!=fence{return -1;}
    match unsafe{p.core.device.get_fence_status(fence)}{
        Ok(true)=>{unsafe{drop(Box::from_raw(pair))};1}
        Ok(false)=>0,
        Err(vk::Result::ERROR_DEVICE_LOST)=>{p.core.lost.store(true,Ordering::Release);unsafe{drop(Box::from_raw(pair))};1}
        Err(_)=>-1,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_cancel(pair:*mut VulkanPair)->i32{
    if pair.is_null() || unsafe{(*pair).fence}!=vk::Fence::null(){return -1;}
    let mut p=unsafe{Box::from_raw(pair)};p.recycle();drop(p);0
}

#[cfg(test)]
#[path="resident_synthesis_tests.rs"]
mod synthesis_tests;

#[cfg(test)]
mod tests{
    #[test]
    fn common_player_quality_resolves_presets_and_rejects_invalid_options(){
        use super::ResidentOptions;
        for preset in 1..=4{
            let (uhd,_,_)=ResidentOptions{preset,..Default::default()}.resolve(3840,2160).unwrap();
            assert_eq!(uhd.block,32);
            assert!(3840u32.div_ceil(uhd.step())*2160u32.div_ceil(uhd.step())<=32768);
        }
        let (t,w,algo)=ResidentOptions::default().resolve(1920,1080).unwrap();
        assert_eq!((t.block,t.overlap,t.pel,algo),(32,0,1,13));
        assert_eq!((w.filter,w.lambda,w.lsad,w.pnew,w.pzero,w.pglobal,w.pnbour,w.preverse),
            (0,320000,256000,50,100,50,50,0));
        let q=ResidentOptions{preset:3,artifacts:3,..Default::default()};
        let (t,_,algo)=q.resolve(1920,1080).unwrap();
        assert_eq!((t.block,t.overlap,t.pel,t.artifacts,algo),(16,4,2,3,23));
        for algo in [1,2,11,13,21,22,23]{
            let (_,_,selected)=ResidentOptions{algorithm:algo,..q}.resolve(1920,1080).unwrap();
            assert_eq!(selected,algo);
        }
        assert!(ResidentOptions{algorithm:12,..q}.resolve(1920,1080).is_none());
        assert!(ResidentOptions{pel:3,..q}.resolve(1920,1080).is_none());
        assert!(ResidentOptions{block:32,..q}.resolve(16,16).is_none());
    }
    #[test]
    fn resident_native_shaders_compile_to_spirv(){
        let t=super::ResidentTuning{block:16,overlap:4,pel:4,radius:2,satd:1,chroma:1,lambda:64,pnew:50,scene:50,artifacts:2};
        assert!(t.valid());
        for mode in 0..=5{
            assert_eq!(super::tuned_motion_spirv(mode,t).unwrap()[0],0x07230203);
            assert_eq!(super::quality_spirv(mode,t,"analyse").unwrap()[0],0x07230203);
            assert_eq!(super::quality_spirv(mode,t,"scene").unwrap()[0],0x07230203);
            assert_eq!(super::quality_spirv(mode,t,"snapshot").unwrap()[0],0x07230203);
            for entry in ["clear","scatter","finish"]{assert_eq!(super::mask_spirv(mode,t,entry).unwrap()[0],0x07230203);}
            let out=if mode==0{super::spirv(&super::tuned_synthesis(super::SYNTHESIS,t,0).unwrap(),
                "@group(0) @binding(8) var<uniform> params:vec4u;","synthesize")}
                else if mode>=4{super::raw_synthesis_config(mode,Some(t))}else{super::high_synthesis_config(mode,Some(t))};
            assert_eq!(out.unwrap()[0],0x07230203);
        }
        for filter in 1..=2{
            let mut w=super::ResidentSearchWeights::legacy(t);w.filter=filter;
            assert_eq!(super::quality_spirv_weighted(0,t,w,"analyse").unwrap()[0],0x07230203);
        }
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
    #[test]
    #[ignore="requires Vulkan, including Mesa software Vulkan"]
    fn resident_upstream_subpixel_filters_match_phase_planes(){
        use wgpu::util::DeviceExt;
        let instance=wgpu::Instance::new(&wgpu::InstanceDescriptor{
            backends:wgpu::Backends::VULKAN,..Default::default()
        });
        let adapter=pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()))
            .expect("Vulkan adapter required");
        let (device,queue)=pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor{
            label:Some("SVP exact native filter phase probe"),required_features:wgpu::Features::empty(),
            required_limits:wgpu::Limits::default().using_resolution(adapter.limits()),
            memory_hints:wgpu::MemoryHints::MemoryUsage,
        },None)).unwrap();
        let (width,height)=(64u32,48u32);
        let mut input=vec![0u8;(width*height*3/2) as usize];
        for (i,p) in input.iter_mut().enumerate(){*p=((i*73+i/13*17+i/64*37)%256) as u8;}
        let texture=device.create_texture(&wgpu::TextureDescriptor{
            label:Some("phase probe input"),size:wgpu::Extent3d{width,height,depth_or_array_layers:1},
            mip_level_count:1,sample_count:1,dimension:wgpu::TextureDimension::D2,
            format:wgpu::TextureFormat::R8Unorm,view_formats:&[],
            usage:wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::COPY_DST,
        });
        queue.write_texture(wgpu::TexelCopyTextureInfo{texture:&texture,mip_level:0,
            origin:wgpu::Origin3d::ZERO,aspect:wgpu::TextureAspect::All},
            &input[..(width*height) as usize],wgpu::TexelCopyBufferLayout{
                offset:0,bytes_per_row:Some(width),rows_per_image:Some(height)},
            wgpu::Extent3d{width,height,depth_or_array_layers:1});
        let view=texture.create_view(&Default::default());
        let points=[(0u32,0u32),(4,4),(7,5),(21,19),(31,26),(58,42)];
        // Exclude the last phase-storage row/column and SIMD spill
        // bytes: these are layout-specific upstream canvas behavior.
        for pel in [2u32,4]{
            for filter in 0..=2{
                let t=super::ResidentTuning{block:16,overlap:4,pel,radius:2,satd:1,chroma:1,lambda:64,pnew:50,scene:50,artifacts:2};
                let mut weights=super::ResidentSearchWeights::legacy(t);weights.filter=filter;
                let builder=svpflow1_vs::SuperBuilder::with_options(width as i32,height as i32,
                    &format!("{{pel:{pel},gpu:0,scale:{{up:{filter}}}}}")).unwrap();
                let reference=builder.build(&input).unwrap();
                let plane=reference.planes()[0];
                // Probe the same sampler compiled in the native kernel,
                // using a small storage output rather than pixel transfers
                // in the actual player path.
                let mut source=super::quality_source(0,t,weights).unwrap();
                source.push_str(r#"
@compute @workgroup_size(1)
fn sample_probe(@builtin(global_invocation_id) id:vec3u){
    let hi=vec2i(geometry.xy)-vec2i(1);
    let point=vec2i(i32(id.x),i32(id.y));
    let phase=vec2i(i32(id.z)%PEL,i32(id.z)/PEL);
    let value=sample_plane(ay,point*PEL+phase,hi,0u);
    let offset=id.x+id.y*geometry.x+id.z*geometry.x*geometry.y;
    fields[offset]=vec4i(value,0,0,0);
}
"#);
                let module=device.create_shader_module(wgpu::ShaderModuleDescriptor{
                    label:Some("upstream phase probe"),source:wgpu::ShaderSource::Wgsl(source.into())});
                let pipeline=device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor{
                    label:None,layout:None,module:&module,entry_point:Some("sample_probe"),
                    compilation_options:Default::default(),cache:None});
                let size=u64::from(width*height*pel*pel)*16;
                let output=device.create_buffer(&wgpu::BufferDescriptor{label:None,size,
                    usage:wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_SRC,mapped_at_creation:false});
                let read=device.create_buffer(&wgpu::BufferDescriptor{label:None,size,
                    usage:wgpu::BufferUsages::MAP_READ|wgpu::BufferUsages::COPY_DST,mapped_at_creation:false});
                let words:Vec<u8>=[width,height,0,0].into_iter().flat_map(u32::to_le_bytes).collect();
                let uniform=device.create_buffer_init(&wgpu::util::BufferInitDescriptor{
                    label:None,contents:&words,usage:wgpu::BufferUsages::UNIFORM});
                let bind=device.create_bind_group(&wgpu::BindGroupDescriptor{label:None,
                    layout:&pipeline.get_bind_group_layout(0),entries:&[
                        wgpu::BindGroupEntry{binding:0,resource:wgpu::BindingResource::TextureView(&view)},
                        wgpu::BindGroupEntry{binding:6,resource:output.as_entire_binding()},
                        wgpu::BindGroupEntry{binding:7,resource:uniform.as_entire_binding()}]});
                let mut encoder=device.create_command_encoder(&Default::default());
                {
                    let mut pass=encoder.begin_compute_pass(&Default::default());
                    pass.set_pipeline(&pipeline);pass.set_bind_group(0,&bind,&[]);
                    pass.dispatch_workgroups(width,height,pel*pel);
                }
                encoder.copy_buffer_to_buffer(&output,0,&read,0,size);
                queue.submit([encoder.finish()]);
                let slice=read.slice(..);
                let (tx,rx)=std::sync::mpsc::channel();
                slice.map_async(wgpu::MapMode::Read,move |r|{let _=tx.send(r);});
                let _=device.poll(wgpu::Maintain::Wait);
                rx.recv_timeout(std::time::Duration::from_secs(30)).unwrap().unwrap();
                let data=slice.get_mapped_range();
                for phase in 0..pel*pel{
                    for (x,y) in points{
                        let index=(x+y*width+phase*width*height) as usize;
                        let actual=i32::from_le_bytes(data[index*16..index*16+4].try_into().unwrap());
                        assert_eq!(actual,i32::from(plane[index]),"pel={pel} filter={filter} phase={phase} ({x},{y})");
                    }
                    // Every interior sample, including negative fractional
                    // motion represented by its preceding integer base.
                    for y in 4..height-5{for x in 4..width-5{
                        let index=(x+y*width+phase*width*height) as usize;
                        let actual=i32::from_le_bytes(data[index*16..index*16+4].try_into().unwrap());
                        assert_eq!(actual,i32::from(plane[index]),"pel={pel} filter={filter} phase={phase} ({x},{y})");
                    }}
                }
                drop(data);read.unmap();
            }
        }

        // Match the reference f32 division followed by f64 multiplication
        // and truncation, including near-zero SAD and signed penalties.
        let t=super::ResidentTuning{block:16,overlap:4,pel:2,radius:2,satd:1,chroma:1,lambda:64,pnew:50,scene:50,artifacts:2};
        let mut weights=super::ResidentSearchWeights::legacy(t);
        weights.lambda=80000;weights.lsad=64000;
        weights.pzero=-100;weights.pglobal=50;weights.pnbour=-32;weights.preverse=0;
        weights.plevel_q16=98304;weights.levels=4;
        let mut source=super::quality_source(0,t,weights).unwrap();
        source.push_str(r#"
@compute @workgroup_size(64)
fn weight_probe(@builtin(global_invocation_id) id:vec3u){
    let sad=select(id.x*1531u,id.x,id.x<1024u);
    fields[id.x]=vec4i(i32(adapt_lambda(sad)),i32(penalized(sad,PZERO)),
        i32(penalized(sad,PGLOBAL)),i32(penalized(sad,PNBOUR)));
}
"#);
        let module=device.create_shader_module(wgpu::ShaderModuleDescriptor{
            label:Some("upstream weight probe"),source:wgpu::ShaderSource::Wgsl(source.into())});
        let pipeline=device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor{
            label:None,layout:None,module:&module,entry_point:Some("weight_probe"),
            compilation_options:Default::default(),cache:None});
        let size=4096*16;
        let output=device.create_buffer(&wgpu::BufferDescriptor{label:None,size,
            usage:wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_SRC,mapped_at_creation:false});
        let read=device.create_buffer(&wgpu::BufferDescriptor{label:None,size,
            usage:wgpu::BufferUsages::MAP_READ|wgpu::BufferUsages::COPY_DST,mapped_at_creation:false});
        let bind=device.create_bind_group(&wgpu::BindGroupDescriptor{label:None,
            layout:&pipeline.get_bind_group_layout(0),entries:&[
                wgpu::BindGroupEntry{binding:6,resource:output.as_entire_binding()}]});
        let mut encoder=device.create_command_encoder(&Default::default());
        {
            let mut pass=encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&pipeline);pass.set_bind_group(0,&bind,&[]);
            pass.dispatch_workgroups(64,1,1);
        }
        encoder.copy_buffer_to_buffer(&output,0,&read,0,size);
        queue.submit([encoder.finish()]);
        let slice=read.slice(..);
        let (tx,rx)=std::sync::mpsc::channel();
        slice.map_async(wgpu::MapMode::Read,move |r|{let _=tx.send(r);});
        let _=device.poll(wgpu::Maintain::Wait);
        rx.recv_timeout(std::time::Duration::from_secs(30)).unwrap().unwrap();
        let data=slice.get_mapped_range();
        let lambda=weights.scaled_lambda(t.pel,0);
        for i in 0..4096u32{
            let sad=if i<1024{i}else{i*1531};
            let ratio=weights.lsad as f32/(weights.lsad+(sad>>1)) as f32;
            let k=f64::from(ratio)*f64::from(ratio);
            let penalty=|p:i32| sad as i32+((p*sad as i32)>>8);
            let expected=[(f64::from(lambda)*k) as i32,penalty(weights.pzero),
                penalty(weights.pglobal),penalty(weights.pnbour)];
            let row=&data[i as usize*16..(i as usize+1)*16];
            let actual: [i32;4]=std::array::from_fn(|j|i32::from_le_bytes(row[j*4..j*4+4].try_into().unwrap()));
            assert_eq!(actual,expected,"weight probe SAD {sad}");
        }
        drop(data);read.unmap();
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
    // Same device, inputs, quality and phases for all modes. The
    // renderer-independent workload measures submit and complete time
    // separately; it is not a full player throughput benchmark.
    #[test]
    #[ignore="requires a real Vulkan device or Mesa"]
    fn held_cache_and_submission_benchmark(){
        unsafe{
            let dim=|name:&str,default:u32|std::env::var(name).ok().and_then(|v|v.parse::<u32>().ok()).unwrap_or(default);
            let (w,h,runs)=(dim("AMBIENT_SVP_BENCH_WIDTH",66),dim("AMBIENT_SVP_BENCH_HEIGHT",34),dim("AMBIENT_SVP_BENCH_RUNS",4));
            assert!(w>=32&&h>=32&&w<=3840&&h<=2160&&(w|h)&1==0&&(2..=64).contains(&runs));
            let algorithm=dim("AMBIENT_SVP_BENCH_ALGORITHM",11);
            assert!(matches!(algorithm,11|23));
            let entry=ash::Entry::load().unwrap();
            let app=vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_2);
            let instance=entry.create_instance(&vk::InstanceCreateInfo::default().application_info(&app),None).unwrap();
            let require_hw=std::env::var("AMBIENT_SVP_REQUIRE_HARDWARE").as_deref()==Ok("1");
            let (physical,family)=instance.enumerate_physical_devices().unwrap().into_iter()
                .filter(|&p|{let v=instance.get_physical_device_properties(p);
                    v.api_version>=vk::API_VERSION_1_2&&(!require_hw||v.device_type!=vk::PhysicalDeviceType::CPU)})
                .find_map(|p|instance.get_physical_device_queue_family_properties(p).iter().position(|q|
                    q.queue_count>0&&q.queue_flags.contains(vk::QueueFlags::COMPUTE)).map(|i|(p,i as u32))).expect("no qualified Vulkan device");
            let properties=instance.get_physical_device_properties(physical);
            let name=std::ffi::CStr::from_ptr(properties.device_name.as_ptr()).to_string_lossy();
            println!("SVP_DEVICE name={name} vendor={} device={} driver={} width={w} height={h} runs={runs}",
                properties.vendor_id,properties.device_id,properties.driver_version);
            let priorities=[1.0];
            let queues=[vk::DeviceQueueCreateInfo::default().queue_family_index(family).queue_priorities(&priorities)];
            let mut timeline_feature=vk::PhysicalDeviceTimelineSemaphoreFeatures::default().timeline_semaphore(true);
            let d=instance.create_device(physical,&vk::DeviceCreateInfo::default().queue_create_infos(&queues).push_next(&mut timeline_feature),None).unwrap();
            let queue=d.get_device_queue(family,0);
            let props=instance.get_physical_device_memory_properties(physical);
            let quality=ResidentOptions{preset:2,algorithm,..Default::default()};
            let engine=ambient_svp_vk_create_quality(instance.handle(),physical,d.handle(),
                Some(entry.static_fn().get_instance_proc_addr),w,h,3,&quality);
            assert!(!engine.is_null());
            let src=vk::ImageUsageFlags::SAMPLED|vk::ImageUsageFlags::TRANSFER_DST;
            let out=vk::ImageUsageFlags::STORAGE|vk::ImageUsageFlags::TRANSFER_SRC;
            let images=std::array::from_fn::<_,7,_>(|i|image(&d,&props,w,h,vk::Format::R16G16B16A16_SFLOAT,if i<2||i>=5{src}else{out}));
            let pool=d.create_command_pool(&vk::CommandPoolCreateInfo::default().queue_family_index(family)
                .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),None).unwrap();
            let cmd=d.allocate_command_buffers(&vk::CommandBufferAllocateInfo::default().command_pool(pool)
                .level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1)).unwrap()[0];
            let fence=d.create_fence(&Default::default(),None).unwrap();
            let hold=std::array::from_fn::<_,3,_>(|_|d.create_semaphore(&vk::SemaphoreCreateInfo::default(),None).unwrap());
            let pipeline_holds=std::array::from_fn::<_,4,_>(|_|std::array::from_fn::<_,5,_>(|_|d.create_semaphore(&vk::SemaphoreCreateInfo::default(),None).unwrap()));
            let mut ty=vk::SemaphoreTypeCreateInfo::default().semaphore_type(vk::SemaphoreType::TIMELINE);
            let done=d.create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut ty),None).unwrap();
            let source_bytes=u64::from(w)*u64::from(h)*8;
            let upload=d.create_buffer(&vk::BufferCreateInfo::default().size(source_bytes*4)
                .usage(vk::BufferUsageFlags::TRANSFER_SRC),None).unwrap();
            let req=d.get_buffer_memory_requirements(upload);
            let upload_memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size).memory_type_index(
                memory_index(&props,req.memory_type_bits,vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)),None).unwrap();
            d.bind_buffer_memory(upload,upload_memory,0).unwrap();
            let raw=d.map_memory(upload_memory,0,source_bytes*4,vk::MemoryMapFlags::empty()).unwrap();
            let pixels=std::slice::from_raw_parts_mut(raw.cast::<u16>(),(source_bytes*2) as usize);
            let order=[1u32,2,0,3];
            for(frame,&shift)in order.iter().enumerate(){
                for y in 0..h{for x in 0..w{
                    let xx=x.wrapping_add(w).wrapping_sub(shift*2)%w;
                    let pattern=((xx/5+y/7)^(xx/17+y/13))&3;
                    let code=[0x3000u16,0x3400,0x3800,0x3a00][pattern as usize];
                    let j=(frame*w as usize*h as usize+y as usize*w as usize+x as usize)*4;
                    pixels[j..j+4].copy_from_slice(&[code,code,code,0x3c00]);
                }}
            }
            d.unmap_memory(upload_memory);
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default()).unwrap();
            for(i,img)in images.iter().enumerate(){
                transition(&d,cmd,img.image,vk::ImageLayout::UNDEFINED,vk::ImageLayout::GENERAL,
                    vk::AccessFlags::empty(),if i<2||i>=5{vk::AccessFlags::TRANSFER_WRITE}else{vk::AccessFlags::SHADER_WRITE},
                    vk::PipelineStageFlags::TOP_OF_PIPE,if i<2||i>=5{vk::PipelineStageFlags::TRANSFER}else{vk::PipelineStageFlags::COMPUTE_SHADER});
                if i<2||i>=5{
                    let frame=if i<2{i}else{i-3};
                    let region=[vk::BufferImageCopy::default().buffer_offset(source_bytes*frame as u64)
                        .image_subresource(vk::ImageSubresourceLayers::default().aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1))
                        .image_extent(vk::Extent3D{width:w,height:h,depth:1})];
                    d.cmd_copy_buffer_to_image(cmd,upload,img.image,vk::ImageLayout::GENERAL,&region);
                    transition(&d,cmd,img.image,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::SHADER_READ,
                        vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                }
            }
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            d.wait_for_fences(&[fence],true,u64::MAX).unwrap();
            let mut value=0u64;
            d.destroy_buffer(upload,None);d.free_memory(upload_memory,None);
            let count=if algorithm==23{5}else{3};
            for mode in 0..3{
                let mut host_us=0u128;
                let mut tickets:Vec<*mut HeldSubmission>=Vec::new();
                let mut pool_ids=Vec::new();
                let mut seen_reuse=false;
                let mut measured_start=std::time::Instant::now();
                for i in 0..runs+2{
                    if i==2{
                        for job in tickets.drain(..){assert_eq!(ambient_svp_vk_retire_held(job,1),1);}
                        measured_start=std::time::Instant::now();
                    }
                    if tickets.len()==4{
                        assert_eq!(ambient_svp_vk_retire_held(tickets.remove(0),1),1);
                    }
                    let waits=&pipeline_holds[(i%4) as usize][..count];
                    d.queue_submit(queue,&[vk::SubmitInfo::default().signal_semaphores(waits)],vk::Fence::null()).unwrap();
                    let handles=if algorithm==23{[images[0].image,images[1].image,images[5].image,images[6].image,images[mode+2].image]}
                        else{[images[0].image,images[1].image,images[mode+2].image,vk::Image::null(),vk::Image::null()]};
                    value+=1;
                    let phase=if i+1==runs+2{0.5}else{0.25+(i%3) as f32*0.125};
                    let start=std::time::Instant::now();
                    let mut job=ptr::null_mut();
                    let rc=match mode{
                        0=>ambient_svp_vk_render_held(engine,queue,family,handles.as_ptr(),count as u32,waits.as_ptr(),done,value,phase),
                        1=>ambient_svp_vk_submit_held(engine,queue,family,handles.as_ptr(),count as u32,waits.as_ptr(),done,value,phase,&mut job),
                        _=>ambient_svp_vk_submit_held_cached(engine,queue,family,handles.as_ptr(),count as u32,waits.as_ptr(),done,value,phase,&mut job,1),
                    };
                    assert_eq!(rc,0);
                    if i>=2{host_us+=start.elapsed().as_micros();}
                    if mode!=0{
                        assert!(!job.is_null());
                        let p=(*job).commands.as_ref().unwrap().pool;
                        if pool_ids.contains(&p){seen_reuse=true;}else{pool_ids.push(p);}
                        tickets.push(job);
                    }
                }
                for job in tickets.drain(..){assert_eq!(ambient_svp_vk_retire_held(job,1),1);}
                if mode!=0{assert!(seen_reuse,"command resources were never reused");}
                assert_eq!(d.get_semaphore_counter_value(done).unwrap(),value);
                println!("SVP_BENCH mode={} algorithm={algorithm} host_submit_mean_us={} complete_mean_us={}",
                    ["sync_uncached","async_uncached","async_cached"][mode],host_us/runs as u128,measured_start.elapsed().as_micros()/runs as u128);
            }
            assert_eq!((&(*engine).0).held_hits.load(Ordering::Relaxed),runs+1,"motion snapshot was not reused for each phase");
            // Independently read each final output and compare all FP16 bytes.
            let bytes=u64::from(w)*u64::from(h)*8;
            let buf=d.create_buffer(&vk::BufferCreateInfo::default().size(bytes*3).usage(vk::BufferUsageFlags::TRANSFER_DST),None).unwrap();
            let req=d.get_buffer_memory_requirements(buf);
            let memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size).memory_type_index(
                memory_index(&props,req.memory_type_bits,vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)),None).unwrap();
            d.bind_buffer_memory(buf,memory,0).unwrap();
            d.reset_fences(&[fence]).unwrap();d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default()).unwrap();
            for i in 0..3{
                transition(&d,cmd,images[2+i].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                    vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
                let region=[vk::BufferImageCopy::default().buffer_offset(bytes*i as u64)
                    .image_subresource(vk::ImageSubresourceLayers::default().aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1))
                    .image_extent(vk::Extent3D{width:w,height:h,depth:1})];
                d.cmd_copy_image_to_buffer(cmd,images[2+i].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,buf,&region);
            }
            let host=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::TRANSFER_WRITE).dst_access_mask(vk::AccessFlags::HOST_READ)];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            d.wait_for_fences(&[fence],true,u64::MAX).unwrap();
            let mapped=d.map_memory(memory,0,bytes*3,vk::MemoryMapFlags::empty()).unwrap();
            let data=std::slice::from_raw_parts(mapped.cast::<u8>(),(bytes*3) as usize);
            let b=bytes as usize;
            assert_eq!(&data[..b],&data[b..2*b]);assert_eq!(&data[..b],&data[2*b..]);
            d.unmap_memory(memory);d.destroy_buffer(buf,None);d.free_memory(memory,None);
            println!("SVP_PARITY exact");
            if (w,h)==(66,34){
                d.reset_fences(&[fence]).unwrap();d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
                d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default()).unwrap();
                for img in &images[2..5]{
                    transition(&d,cmd,img.image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::TRANSFER_READ,vk::AccessFlags::SHADER_WRITE,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                }
                transition(&d,cmd,images[0].image,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                    vk::AccessFlags::SHADER_READ,vk::AccessFlags::TRANSFER_WRITE,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
                d.cmd_clear_color_image(cmd,images[0].image,vk::ImageLayout::GENERAL,
                    &vk::ClearColorValue{float32:[0.5,0.5,0.5,1.0]},&[range()]);
                transition(&d,cmd,images[0].image,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                    vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::SHADER_READ,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                d.end_command_buffer(cmd).unwrap();
                d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
                d.wait_for_fences(&[fence],true,u64::MAX).unwrap();
                if algorithm==23{
                    let core=&(*engine).0;
                    let hits=core.adjacent_hits.load(Ordering::Relaxed);
                    // A/B/P/N then B/N/A/P: two adjacent ordered pairs
                    // retain exactly the same IDs and source image content.
                    for (ids,handles) in [
                        ([11u64,12,10,13],[images[0].image,images[1].image,images[5].image,images[6].image,images[2].image]),
                        ([12,13,11,14],[images[1].image,images[6].image,images[0].image,images[5].image,images[2].image]),
                    ]{
                        let waits=&pipeline_holds[0];
                        d.queue_submit(queue,&[vk::SubmitInfo::default().signal_semaphores(waits)],vk::Fence::null()).unwrap();
                        value+=1;
                        let mut job=ptr::null_mut();
                        assert_eq!(ambient_svp_vk_set_held_source_ids(engine,&ids),0);
                        assert_eq!(ambient_svp_vk_submit_held(engine,queue,family,handles.as_ptr(),5,waits.as_ptr(),done,value,0.5,&mut job),0);
                        assert_eq!(ambient_svp_vk_retire_held(job,1),1);
                    }
                    assert_eq!(core.adjacent_hits.load(Ordering::Relaxed),hits+2,"adjacent source pairs must be reused");
                    // Discard an unsubmitted recording. Its newly assigned
                    // cache entries must not become visible to the next job.
                    d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
                    d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default()).unwrap();
                    let rgb=|i:usize|VulkanPicture{y:images[i].view,u:vk::ImageView::null(),v:vk::ImageView::null(),nv12:2,chroma_location:0};
                    let pair=ambient_svp_vk_begin_extended_cached(engine,cmd,&rgb(5),&rgb(0),&rgb(1),&rgb(6),queue,&[20,21,22,23]);
                    assert!(!pair.is_null());
                    d.end_command_buffer(cmd).unwrap();
                    d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
                    assert_eq!(ambient_svp_vk_cancel(pair),0);
                    let cache=core.adjacent.lock().unwrap();
                    assert!(!cache.recording&&cache.entries.iter().all(|e|e.key.is_none()));
                }
                let before=(&(*engine).0).held_hits.load(Ordering::Relaxed);
                for (i,generation) in [2u64,2,3,3].into_iter().enumerate(){
                    let waits=&pipeline_holds[0][..count];
                    d.queue_submit(queue,&[vk::SubmitInfo::default().signal_semaphores(waits)],vk::Fence::null()).unwrap();
                    let handles=if algorithm==23{[images[0].image,images[1].image,images[5].image,images[6].image,images[2].image]}
                        else{[images[0].image,images[1].image,images[2].image,vk::Image::null(),vk::Image::null()]};
                    value+=1;
                    let mut job=ptr::null_mut();
                    assert_eq!(ambient_svp_vk_submit_held_cached(engine,queue,family,handles.as_ptr(),count as u32,waits.as_ptr(),done,value,0.5,&mut job,generation),0);
                    assert_eq!(ambient_svp_vk_retire_held(job,1),1);
                    assert_eq!((&(*engine).0).held_hits.load(Ordering::Relaxed),before+(i as u32+1)/2);
                }
            }
            // Exercise the new capture/readback ABI on the small CI fixture
            // outside the measured workload. Two captures may be submitted
            // before either readback, but the cache must publish in tick order.
            if (w,h)==(66,34)&&algorithm==11{
                let directory=std::env::temp_dir().join(format!("ambient-held-scene-{}-{}",
                    std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
                std::fs::create_dir(&directory).unwrap();
                let plan=directory.join("plan");let cache=directory.join("cache");
                let key="a".repeat(64);
                std::fs::write(&plan,format!("AMBIENTSVP_SCENE_PLAN_1\n{key}\n2\n")).unwrap();
                let schema=[1,5,10,25,50,75,90,95,99];
                let mut window=HdrWindow{rect:[0,0,w,h],count:9,percentages:[0;15]};
                window.percentages[..9].copy_from_slice(&schema);
                let mut capture=TwoPass::open(1,&plan,&cache,&key,"held-test",w,h).unwrap();
                let second=std::array::from_fn::<_,3,_>(|_|d.create_semaphore(&vk::SemaphoreCreateInfo::default(),None).unwrap());
                let mut jobs=[ptr::null_mut();2];
                for(i,waits)in [&hold,&second].into_iter().enumerate(){
                    d.queue_submit(queue,&[vk::SubmitInfo::default().signal_semaphores(waits)],vk::Fence::null()).unwrap();
                    let handles=[images[0].image,images[1].image,images[2+i].image];
                    value+=1;
                    assert_eq!(ambient_svp_vk_submit_held_capture(engine,queue,family,handles.as_ptr(),3,waits.as_ptr(),
                        done,value,0.5,&window,ptr::null(),ptr::null(),1,&mut capture,i as u64,&mut jobs[i],3),0);
                    assert!(!jobs[i].is_null());
                }
                assert!(!cache.exists());
                for(i,job)in jobs.into_iter().enumerate(){assert_eq!(ambient_svp_vk_retire_held_capture(job,1,&mut capture,i as u64),1);}
                capture.finish().unwrap();
                let mut replay=TwoPass::open(2,&plan,&cache,&key,"held-test",w,h).unwrap();
                let a=replay.lookup_v1(0,schema).unwrap();let b=replay.lookup_v1(1,schema).unwrap();
                assert_eq!(a.maxscl,b.maxscl);assert_eq!(a.average,b.average);assert_eq!(a.percentiles,b.percentiles);
                replay.finish().unwrap();
                let canceled=directory.join("canceled");
                let mut aborted=TwoPass::open(1,&plan,&canceled,&key,"held-test",w,h).unwrap();
                d.queue_submit(queue,&[vk::SubmitInfo::default().signal_semaphores(&hold)],vk::Fence::null()).unwrap();
                let handles=[images[0].image,images[1].image,images[2].image];
                value+=1;
                let mut job=ptr::null_mut();
                assert_eq!(ambient_svp_vk_submit_held_capture(engine,queue,family,handles.as_ptr(),3,hold.as_ptr(),done,value,0.5,&window,ptr::null(),ptr::null(),1,&mut aborted,0,&mut job,3),0);
                aborted.abort();drop(aborted);
                assert_eq!(ambient_svp_vk_retire_held(job,1),1);
                assert!(!canceled.exists());
                for sem in second{d.destroy_semaphore(sem,None);}
                std::fs::remove_dir_all(&directory).unwrap();
                // Live per-frame readback must consume its own ticket.
                d.queue_submit(queue,&[vk::SubmitInfo::default().signal_semaphores(&hold)],vk::Fence::null()).unwrap();
                let handles=[images[0].image,images[1].image,images[4].image];
                value+=1;
                let mut job=ptr::null_mut();
                assert_eq!(ambient_svp_vk_submit_held_stats(engine,queue,family,handles.as_ptr(),3,hold.as_ptr(),
                    done,value,0.5,&window,ptr::null(),ptr::null(),1,ptr::null_mut(),0,&mut job,3),0);
                assert!(!job.is_null());
                let mut result=HdrResult::default();let mut rect=[[0u32;4]];let mut ellipse=[[0u32;3]];let mut shift=[[0i32;2]];
                assert_eq!(ambient_svp_vk_collect_held_stats(job,1,&mut result,rect.as_mut_ptr(),ellipse.as_mut_ptr(),
                    shift.as_mut_ptr(),1,ptr::null_mut(),0),1);
                assert_eq!(result.pixels,w*h);
                // GPU-driven cut capture must hold the preceding source
                // even at phase .75, then discover the boundary at group end.
                d.reset_fences(&[fence]).unwrap();d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
                d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default()).unwrap();
                for (i,pixel) in [0.2f32,0.9].into_iter().enumerate(){
                    let img=images[i].image;
                    transition(&d,cmd,img,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::SHADER_READ,vk::AccessFlags::TRANSFER_WRITE,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
                    d.cmd_clear_color_image(cmd,img,vk::ImageLayout::GENERAL,&vk::ClearColorValue{float32:[pixel,pixel,pixel,1.0]},&[range()]);
                    transition(&d,cmd,img,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::SHADER_READ,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                }
                d.end_command_buffer(cmd).unwrap();d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
                d.wait_for_fences(&[fence],true,u64::MAX).unwrap();
                std::fs::create_dir(&directory).unwrap();
                std::fs::write(&plan,format!("AMBIENTSVP_SCENE_PLAN_2\n{key}\n4\n")).unwrap();
                let mut pass=TwoPass::open(1,&plan,&cache,&key,"dynamic-test",w,h).unwrap();
                for i in 0..4u64{
                    let (handles,count)=if i<2{([images[0].image,images[1].image,images[2].image],3)}
                        else{([images[1].image,images[2].image,vk::Image::null()],2)};
                    let waits=&hold[..count];
                    d.queue_submit(queue,&[vk::SubmitInfo::default().signal_semaphores(waits)],vk::Fence::null()).unwrap();
                    value+=1;
                    let mut job=ptr::null_mut();
                    let phase=if i<2&&i==1{0.75}else{0.0};
                    assert_eq!(ambient_svp_vk_submit_held_capture(engine,queue,family,handles.as_ptr(),count as u32,waits.as_ptr(),
                        done,value,phase,&window,ptr::null(),ptr::null(),1,&mut pass,i,&mut job,4),0);
                    assert_eq!(ambient_svp_vk_retire_held_capture_group(job,1,&mut pass,i,if i<2{2}else{4}),1);
                }
                assert_eq!(pass.interval(0).unwrap(),(0,2));pass.finish().unwrap();
                let mut replay=TwoPass::open(2,&plan,&cache,&key,"dynamic-test",w,h).unwrap();
                let dark=replay.lookup(0).unwrap();assert_eq!(dark,replay.lookup(1).unwrap());
                let bright=replay.lookup(2).unwrap();assert_eq!(bright,replay.lookup(3).unwrap());
                assert!(dark.maxscl[0]<bright.maxscl[0]&&dark.end==2&&bright.first==2);
                // Each scene must be constant, not a phase-dependent blend.
                assert_eq!(dark.average,dark.maxscl[0]);assert_eq!(bright.average,bright.maxscl[0]);
                replay.finish().unwrap();std::fs::remove_dir_all(directory).unwrap();
            }
            ambient_svp_vk_destroy(engine);
            for s in hold{d.destroy_semaphore(s,None);}for group in pipeline_holds{for s in group{d.destroy_semaphore(s,None);}}d.destroy_semaphore(done,None);
            for img in images{d.destroy_image_view(img.view,None);d.destroy_image(img.image,None);d.free_memory(img.memory,None);}
            d.destroy_fence(fence,None);d.destroy_command_pool(pool,None);d.destroy_device(None);instance.destroy_instance(None);
        }
    }
    #[test]
    #[ignore="requires Vulkan, including Mesa software Vulkan"]
    fn native_resident_commands_keep_images_on_shared_device(){
        unsafe{
            let entry=ash::Entry::load().unwrap();
            let app=vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_2);
            let instance=entry.create_instance(&vk::InstanceCreateInfo::default().application_info(&app),None).unwrap();
            let (physical,family)=instance.enumerate_physical_devices().unwrap().into_iter()
                .find_map(|p|instance.get_physical_device_queue_family_properties(p).iter().position(|q|
                    q.queue_count>0&&q.queue_flags.contains(vk::QueueFlags::COMPUTE)).map(|i|(p,i as u32))).unwrap();
            let priorities=[1.0];
            let queues=[vk::DeviceQueueCreateInfo::default().queue_family_index(family).queue_priorities(&priorities)];
            let extended=instance.get_physical_device_features(physical).shader_storage_image_extended_formats!=0;
            let features=vk::PhysicalDeviceFeatures::default().shader_storage_image_extended_formats(extended);
            let mut timeline_feature=vk::PhysicalDeviceTimelineSemaphoreFeatures::default().timeline_semaphore(true);
            let d=instance.create_device(physical,&vk::DeviceCreateInfo::default().queue_create_infos(&queues).enabled_features(&features).push_next(&mut timeline_feature),None).unwrap();
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
                .usage(vk::BufferUsageFlags::TRANSFER_DST|vk::BufferUsageFlags::TRANSFER_SRC).sharing_mode(vk::SharingMode::EXCLUSIVE),None).unwrap();
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
            // Exercise the actual four-picture GPU path and retained
            // auxiliary field lifetime, not just the scalar blend probe.
            let tune=ResidentTuning{block:16,overlap:4,pel:2,radius:1,satd:0,chroma:1,lambda:64,pnew:50,scene:50,artifacts:1};
            let engine=ambient_svp_vk_create_config(instance.handle(),physical,d.handle(),
                Some(entry.static_fn().get_instance_proc_addr),w,h,0,&tune);
            assert!(!engine.is_null());
            assert_eq!(ambient_svp_vk_set_synthesis(engine,23),0);
            d.reset_fences(&[fence]).unwrap();
            d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            transition(&d,cmd,images[6].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,vk::ImageLayout::GENERAL,
                vk::AccessFlags::TRANSFER_READ,vk::AccessFlags::SHADER_WRITE,
                vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
            let pair=ambient_svp_vk_begin_extended(engine,cmd,&a,&a,&b,&b);
            assert!(!pair.is_null());
            assert_eq!((&*pair).extended.len(),2);
            assert_eq!((&*engine).0.aux_in_flight.load(Ordering::Acquire),2);
            assert_eq!(ambient_svp_vk_set_synthesis(engine,11),-1);
            assert_eq!(ambient_svp_vk_render(pair,0.5,0,images[6].view),0);
            transition(&d,cmd,images[6].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
            d.cmd_copy_image_to_buffer(cmd,images[6].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            assert_eq!(ambient_svp_vk_seal(pair,fence),0);
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            assert_eq!(ambient_svp_vk_release(pair),1);
            assert_eq!((&*engine).0.aux_in_flight.load(Ordering::Acquire),0);
            assert_eq!((&*engine).0.in_flight.load(Ordering::Acquire),0);
            let mapped=d.map_memory(readback_memory,0,bytes,vk::MemoryMapFlags::empty()).unwrap();
            for pixel in std::slice::from_raw_parts(mapped.cast::<u8>(),bytes as usize).chunks_exact(4){
                assert!(pixel[..3].iter().all(|&v|v.abs_diff(80)<=1)&&pixel[3]==255,"extended result {pixel:?}");
            }
            d.unmap_memory(readback_memory);
            ambient_svp_vk_destroy(engine);
            // Actual R16 plane / RGBA16F Vulkan execution. Adjacent
            // 10-bit luma codes must stay distinguishable at source ticks.
            for (mode,tuned) in [(1,false),(2,false),(3,false),(3,true)]{
                let tune=ResidentTuning{block:16,overlap:4,pel:4,radius:1,satd:1,chroma:1,lambda:64,pnew:50,scene:50,artifacts:2};
                let engine=if tuned{ambient_svp_vk_create_config(instance.handle(),physical,d.handle(),
                    Some(entry.static_fn().get_instance_proc_addr),w,h,mode,&tune)}else{ambient_svp_vk_create_format(instance.handle(),physical,d.handle(),
                    Some(entry.static_fn().get_instance_proc_addr),w,h,mode)};
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
                    let mut legacy_quantized=[0u32;3];
                    let mut legacy_mean=[0u32;3];
                    let scene_p=[1u32,5,10,25,50,75,90,95,99];
                    let scene=if mode==3&&!tuned{ambient_svp_hdr_scene_create(0,1,w,h,&scene_p)}else{ptr::null_mut()};
                    if mode==3{
                        if tuned{
                            let tracks=windows.map(|window|HdrTrack{a:window.rect,b:window.rect,ellipse:[window.rect[0],window.rect[1]]});
                            let axes=if i==0{[[0,0,0],[4,2,0],[4,2,90]]}else{[[0,0,0],[4,2,45],[4,2,73]]};
                            if i==0{assert_eq!(ambient_svp_vk_hdr_stats_ellipse(pair,0,windows.as_ptr(),tracks.as_ptr(),axes.as_ptr(),phase,3),0);}
                            else{assert_eq!(ambient_svp_vk_hdr_stats_scene_legacy_tracked(pair,0,windows.as_ptr(),tracks.as_ptr(),axes.as_ptr(),phase,3),0);}
                        }else{
                            assert!(!scene.is_null());
                            let mut global=windows[0];global.count=9;global.percentages[..9].copy_from_slice(&scene_p);
                            assert_eq!(ambient_svp_vk_hdr_stats_scene(pair,0,&global,1),0);
                            assert_eq!(ambient_svp_hdr_scene_push(scene,pair,0,0),-1);
                        }
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
                    d.wait_for_fences(&[fence],true,20_000_000_000)
                        .unwrap_or_else(|e|panic!("high-depth fence mode={mode} tuned={tuned} phase={phase}: {e:?}"));
                    if mode==3{
                        if tuned&&i==1{
                            let job=(&*pair).hdr_jobs[0].as_ref().unwrap();
                            let frames=job.scene_frames().unwrap();
                            assert_eq!(frames.len(),3);
                            for (j,f) in frames.iter().enumerate(){
                                let n=f.frame.bins.iter().map(|&v|u64::from(v)).sum::<u64>();
                                assert_eq!(n,match j{0=>u64::from(w)*u64::from(h),1=>13,2=>11,_=>unreachable!()});
                                // Independent Decimal ST 2084 fixture for FP16 0x3802;
                                // allow adjacent half-float rounding in the synthesis.
                                assert!(f.frame.maxscl.iter().all(|&x|[927,932,936].contains(&x)));
                                legacy_quantized[j]=f.frame.maxscl[0];
                                legacy_mean[j]=((f.frame.linear_sum+n*5000)/(n*10000)) as u32;
                                assert_eq!(legacy_mean[j],legacy_quantized[j]);
                                assert_eq!(f.frame.bins[legacy_quantized[j] as usize],n as u32);
                            }
                        }else if tuned{
                            let mut shifts=[[0i32;2];3];
                            assert_eq!(ambient_svp_vk_hdr_read_track(pair,0,stats.as_mut_ptr(),shifts.as_mut_ptr(),3),1);
                            assert_eq!(shifts,[[0,0];3]);
                            let mut boxes=[[0u32;4];3];
                            assert_eq!(ambient_svp_vk_hdr_read_rectangles(pair,0,boxes.as_mut_ptr(),3),1);
                            assert_eq!(boxes,windows.map(|window|window.rect));
                        }else{
                            assert_eq!(ambient_svp_vk_hdr_read(pair,0,stats.as_mut_ptr(),1),1);
                            let mut measured=SceneResult::default();
                            assert_eq!(ambient_svp_hdr_scene_finish(scene,&mut measured),-1);
                            assert_eq!(ambient_svp_hdr_scene_push(scene,pair,0,0),1);
                            assert_eq!(ambient_svp_hdr_scene_push(scene,pair,0,0),-1);
                            assert_eq!(ambient_svp_hdr_scene_finish(scene,&mut measured),1);
                            assert_eq!(measured.fraction,1000);
                            assert_eq!(measured.average,stats[0].average);
                            assert_eq!(measured.maxscl,stats[0].maxscl);
                            assert_eq!(measured.percentiles,[measured.average,0,255,measured.average,measured.average,measured.average,measured.average,measured.average,measured.average]);
                            assert_eq!(ambient_svp_hdr_scene_validate(0,1,&measured,&measured,1),1);
                            ambient_svp_hdr_scene_destroy(scene);
                        }
                        for (index,(r,window)) in stats.iter().zip(windows).take(if tuned&&i==0{3}else{0}).enumerate(){
                            let area=(window.rect[2]-window.rect[0])*(window.rect[3]-window.rect[1]);
                            if tuned&&index>0{
                                let (major,minor)=if index==1{(4i32,2i32)}else{(2i32,4i32)};
                                let mut expected=0;
                                for y in 0..(window.rect[3]-window.rect[1]) as i32{
                                    for x in 0..(window.rect[2]-window.rect[0]) as i32{
                                        if x*x*minor*minor+y*y*major*major<=major*major*minor*minor{expected+=1;}
                                    }
                                }
                                assert_eq!(r.pixels,expected);
                            }else{assert_eq!(r.pixels,area);}
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
                    if mode==3&&tuned&&i==1{
                        // Exact comparison against Decimal reference generated from
                        // the actual output half-float code, not shader self-comparison.
                        let expected=match observed[i]{0x3801=>927,0x3802=>932,0x3803=>936,_=>panic!("unexpected PQ fixture code")};
                        assert_eq!(legacy_quantized,[expected;3]);assert_eq!(legacy_mean,[expected;3]);
                    }
                    d.unmap_memory(readback_memory);
                }
                assert!(observed[1]>observed[0],"10-bit codes collapsed");
                if mode==3&&tuned{
                    // Nonuniform two-frame reference. Exact Fraction/Decimal
                    // oracle in hdr10plus_reference.py uses 66x34 RGB stripes
                    // with a boundary crossing the nonintegral 5:1 proxy grid.
                    let schema=[1,5,10,25,50,75,90,95,99];
                    let scene=ambient_svp_hdr_scene_create(0,2,w,h,&schema);
                    assert!(!scene.is_null());
                    for frame in 0..2u64{
                        let mapped=d.map_memory(readback_memory,0,bytes*2,vk::MemoryMapFlags::empty()).unwrap();
                        let data=std::slice::from_raw_parts_mut(mapped.cast::<u16>(),(bytes*2/2) as usize);
                        for y in 0..h{
                            for x in 0..w{
                                let rgb=if frame==0{
                                    if x<23{[0x3c00,0,0,0x3c00]}else{[0,0x3c00,0,0x3c00]}
                                }else if x<23{[0,0x3bfc,0,0x3c00]}else{[0,0x3c00,0,0x3c00]};
                                let i=((y*w+x)*4) as usize;data[i..i+4].copy_from_slice(&rgb);
                            }
                        }
                        d.unmap_memory(readback_memory);
                        d.reset_fences(&[fence]).unwrap();
                        d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
                        d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
                        transition(&d,cmd,high[0].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                            vk::AccessFlags::SHADER_READ,vk::AccessFlags::TRANSFER_WRITE,
                            vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
                        d.cmd_copy_buffer_to_image(cmd,readback,high[0].image,vk::ImageLayout::TRANSFER_DST_OPTIMAL,&region);
                        transition(&d,cmd,high[0].image,vk::ImageLayout::TRANSFER_DST_OPTIMAL,vk::ImageLayout::GENERAL,
                            vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::SHADER_READ,
                            vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                        transition(&d,cmd,high[4].image,if frame==0{vk::ImageLayout::TRANSFER_SRC_OPTIMAL}else{vk::ImageLayout::GENERAL},
                            vk::ImageLayout::GENERAL,if frame==0{vk::AccessFlags::TRANSFER_READ}else{vk::AccessFlags::SHADER_READ|vk::AccessFlags::SHADER_WRITE},
                            vk::AccessFlags::SHADER_WRITE,if frame==0{vk::PipelineStageFlags::TRANSFER}else{vk::PipelineStageFlags::COMPUTE_SHADER},
                            vk::PipelineStageFlags::COMPUTE_SHADER);
                        let pair=ambient_svp_vk_begin(engine,cmd,&a,&a);
                        assert!(!pair.is_null());
                        assert_eq!(ambient_svp_vk_render(pair,0.0,4,high[4].view),0);
                        let mut global=HdrWindow{rect:[0,0,w,h],count:9,percentages:[0;15]};
                        global.percentages[..9].copy_from_slice(&schema);
                        assert_eq!(ambient_svp_vk_hdr_stats_scene(pair,0,&global,1),0);
                        d.end_command_buffer(cmd).unwrap();
                        d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
                        assert_eq!(ambient_svp_vk_seal(pair,fence),0);
                        d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
                        assert_eq!(ambient_svp_hdr_scene_push(scene,pair,0,frame),1);
                        let f=(&*pair).hdr_jobs[0].as_ref().unwrap().scene_frame().unwrap();
                        assert_eq!(f.maxscl,if frame==0{[100000,100000,0]}else{[0,100000,0]});
                        assert_eq!((f.fraction*1000.0).round() as u32,if frame==0{643}else{811});
                        assert_eq!(ambient_svp_vk_release(pair),1);
                    }
                    let mut measured=SceneResult::default();
                    assert_eq!(ambient_svp_hdr_scene_finish(scene,&mut measured),1);
                    assert_eq!(measured.maxscl,[100000,100000,0]);
                    assert_eq!(measured.average,99678);
                    assert_eq!(measured.percentiles,[98152,0,255,100000,100000,100000,100000,100000,100000]);
                    assert_eq!(measured.brightest_frame,1);assert_eq!(measured.fraction,811);
                    ambient_svp_hdr_scene_destroy(scene);
                }
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
            // Exercise the exact libplacebo held-image ABI with binary
            // producer semaphores and timeline publication of the result.
            let quality=ResidentOptions{preset:2,algorithm:11,..Default::default()};
            let engine=ambient_svp_vk_create_quality(instance.handle(),physical,d.handle(),
                Some(entry.static_fn().get_instance_proc_addr),w,h,3,&quality);
            assert!(!engine.is_null());
            let held=[
                image(&d,&props,w,h,vk::Format::R16G16B16A16_SFLOAT,src),
                image(&d,&props,w,h,vk::Format::R16G16B16A16_SFLOAT,src),
                image(&d,&props,w,h,vk::Format::R16G16B16A16_SFLOAT,out),
            ];
            let hold=std::array::from_fn::<_,3,_>(|_|d.create_semaphore(&vk::SemaphoreCreateInfo::default(),None).unwrap());
            let mut ty=vk::SemaphoreTypeCreateInfo::default().semaphore_type(vk::SemaphoreType::TIMELINE);
            let done=d.create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut ty),None).unwrap();
            let gate=d.create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut ty),None).unwrap();
            d.reset_fences(&[fence]).unwrap();
            d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            for (i,img) in held.iter().enumerate(){
                transition(&d,cmd,img.image,vk::ImageLayout::UNDEFINED,vk::ImageLayout::GENERAL,
                    vk::AccessFlags::empty(),if i<2{vk::AccessFlags::TRANSFER_WRITE}else{vk::AccessFlags::SHADER_WRITE},
                    vk::PipelineStageFlags::TOP_OF_PIPE,if i<2{vk::PipelineStageFlags::TRANSFER}else{vk::PipelineStageFlags::COMPUTE_SHADER});
                if i<2{d.cmd_clear_color_image(cmd,img.image,vk::ImageLayout::GENERAL,
                    &vk::ClearColorValue{float32:[0.5,0.5,0.5,1.0]},&[range()]);}
            }
            d.end_command_buffer(cmd).unwrap();
            let gate_wait=[gate];let gate_value=[1u64];let gate_stage=[vk::PipelineStageFlags::ALL_COMMANDS];
            let hold_values=[0u64;3];let producer_commands=[cmd];
            let mut gate_info=vk::TimelineSemaphoreSubmitInfo::default().wait_semaphore_values(&gate_value).signal_semaphore_values(&hold_values);
            let producer=vk::SubmitInfo::default().push_next(&mut gate_info).wait_semaphores(&gate_wait)
                .wait_dst_stage_mask(&gate_stage).command_buffers(&producer_commands).signal_semaphores(&hold);
            d.queue_submit(queue,&[producer],fence).unwrap();
            let handles=held.each_ref().map(|img|img.image);
            let mut job=ptr::null_mut();
            // The producer gate is opened by a watchdog even if a future
            // regression blocks the submit call, turning a hang into a failure.
            std::thread::scope(|scope|{
                let (release,blocked)=std::sync::mpsc::channel::<()>();
                let dev=&d;
                let watchdog=scope.spawn(move||{
                    let early=blocked.recv_timeout(std::time::Duration::from_secs(30)).is_ok();
                    unsafe{dev.signal_semaphore(&vk::SemaphoreSignalInfo::default().semaphore(gate).value(1))}.unwrap();
                    early
                });
                let rc=ambient_svp_vk_submit_held(engine,queue,family,handles.as_ptr(),3,hold.as_ptr(),done,1,0.5,&mut job);
                let pending=if job.is_null(){-1}else{ambient_svp_vk_retire_held(job,0)};
                let counter=d.get_semaphore_counter_value(done).unwrap();
                let _=release.send(());
                assert!(watchdog.join().unwrap(),"held submit blocked on the GPU producer");
                assert_eq!(rc,0);
                assert!(!job.is_null());
                assert_eq!(pending,0);
                assert_eq!(counter,0);
            });
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            d.reset_fences(&[fence]).unwrap();
            d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            transition(&d,cmd,held[2].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
            d.cmd_copy_image_to_buffer(cmd,held[2].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            let values=[1u64];let sems=[done];let stages=[vk::PipelineStageFlags::TRANSFER];let commands=[cmd];
            let mut timeline=vk::TimelineSemaphoreSubmitInfo::default().wait_semaphore_values(&values);
            let submit=vk::SubmitInfo::default().push_next(&mut timeline).wait_semaphores(&sems).wait_dst_stage_mask(&stages).command_buffers(&commands);
            d.queue_submit(queue,&[submit],fence).unwrap();
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            let mapped=d.map_memory(readback_memory,0,bytes*2,vk::MemoryMapFlags::empty()).unwrap();
            for pixel in std::slice::from_raw_parts(mapped.cast::<u8>(),(bytes*2) as usize).chunks_exact(8){
                for channel in pixel[..6].chunks_exact(2){assert_eq!(u16::from_ne_bytes(channel.try_into().unwrap()),0x3800);}
                assert_eq!(u16::from_ne_bytes(pixel[6..8].try_into().unwrap()),0x3c00);
            }
            d.unmap_memory(readback_memory);
            assert_eq!(ambient_svp_vk_retire_held(job,1),1);
            assert_eq!(d.get_semaphore_counter_value(done).unwrap(),1);
            ambient_svp_vk_destroy(engine);
            for sem in hold{d.destroy_semaphore(sem,None);}d.destroy_semaphore(done,None);d.destroy_semaphore(gate,None);
            for img in held{d.destroy_image_view(img.view,None);d.destroy_image(img.image,None);d.free_memory(img.memory,None);}
            d.destroy_fence(fence,None);d.destroy_command_pool(pool,None);
            d.destroy_buffer(readback,None);d.free_memory(readback_memory,None);
            for img in images{
                d.destroy_image_view(img.view,None);d.destroy_image(img.image,None);d.free_memory(img.memory,None);
            }
            d.destroy_device(None);instance.destroy_instance(None);
        }
    }
}
