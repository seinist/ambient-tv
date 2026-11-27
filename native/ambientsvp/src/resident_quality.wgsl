// Native high-quality local refinement. Compile-time layout/precision
// constants are generated from the validated resident tuning contract.
// Workgroups are independent, reading only their own integer seed.
@group(0) @binding(0) var ay:texture_2d<f32>;
@group(0) @binding(1) var au:texture_2d<f32>;
@group(0) @binding(2) var av:texture_2d<f32>;
@group(0) @binding(3) var by:texture_2d<f32>;
@group(0) @binding(4) var bu:texture_2d<f32>;
@group(0) @binding(5) var bv:texture_2d<f32>;
@group(0) @binding(6) var<storage,read_write> fields:array<vec4i>;
@group(0) @binding(7) var<uniform> geometry:vec4u;
const BLOCK:i32=SVP_BLOCK;
const STEP:i32=SVP_STEP;
const PEL:i32=SVP_PEL;
const RADIUS:i32=SVP_RADIUS;
const SCALE:f32=SVP_SCALE;
const CODE_MAX:i32=SVP_CODE_MAX;
const SATD:bool=SVP_SATD;
const CHROMA:bool=SVP_CHROMA;
const LAMBDA:u32=SVP_LAMBDA;
const PNEW:u32=SVP_PNEW;
const SCENE:u32=SVP_SCENE;
var<workgroup> scene_bad:array<u32,64>;
var<workgroup> scores:array<u32,64>;
var<workgroup> raws:array<u32,64>;
var<workgroup> ranks:array<u32,64>;
var<workgroup> vectors:array<vec2i,64>;
var<workgroup> seed_score:u32;
var<workgroup> seed_raw:u32;

fn texel(t:texture_2d<f32>,p:vec2i,hi:vec2i,channel:u32)->i32{
    return i32(round(clamp(textureLoad(t,clamp(p,vec2i(0),hi),0)[channel]*SCALE,0.0,f32(CODE_MAX))));
}
// Use integer bilinear weights so negative displacement and phase
// rounding are deterministic. Exact endpoints require only one fetch.
fn sample_plane(t:texture_2d<f32>,p:vec2i,hi:vec2i,channel:u32)->i32{
    let base=(p-(p%PEL+vec2i(PEL))%PEL)/PEL;
    let f=p-base*PEL;
    let a=texel(t,base,hi,channel);
    if all(f==vec2i(0)){return a;}
    let b=texel(t,base+vec2i(1,0),hi,channel);
    let c=texel(t,base+vec2i(0,1),hi,channel);
    let d=texel(t,base+vec2i(1),hi,channel);
    let x=PEL-f.x;let y=PEL-f.y;
    return (a*x*y+b*f.x*y+c*x*f.y+d*f.x*f.y+PEL*PEL/2)/(PEL*PEL);
}
fn rgb_code(t:texture_2d<f32>,p:vec2i,hi:vec2i,plane:u32)->i32{
    let r=sample_plane(t,p,hi,0u);
    let g=sample_plane(t,p,hi,1u);
    let b=sample_plane(t,p,hi,2u);
    let y=(54*r+183*g+19*b+128)/256;
    // Signed color differences, not independent RGB luminance weights.
    if plane==1u{return (b-y)/2;}
    if plane==2u{return (r-y)/2;}
    return y;
}
fn code(p:vec2i,plane:u32,second:bool)->i32{
    let chroma=plane!=0u;
    let rgb=(geometry.w&0x40000u)!=0u;
    let hi=vec2i(geometry.xy)/select(1,2,chroma)-vec2i(1);
    if rgb{
        let full_hi=vec2i(geometry.xy)-vec2i(1);
        let full_p=p*select(1,2,chroma);
        if second{return rgb_code(by,full_p,full_hi,plane);}
        return rgb_code(ay,full_p,full_hi,plane);
    }
    let nv=select((geometry.w&0x10000u)!=0u,(geometry.w&0x20000u)!=0u,second);
    if second{
        if plane==0u{return sample_plane(by,p,hi,0u);}
        if plane==1u{return sample_plane(bu,p,hi,0u);}
        if nv{return sample_plane(bu,p,hi,1u);}
        return sample_plane(bv,p,hi,0u);
    }
    if plane==0u{return sample_plane(ay,p,hi,0u);}
    if plane==1u{return sample_plane(au,p,hi,0u);}
    if nv{return sample_plane(au,p,hi,1u);}
    return sample_plane(av,p,hi,0u);
}
fn diff(origin:vec2i,offset:vec2i,mv:vec2i,plane:u32,reverse:bool)->i32{
    let p=origin+offset;
    let size=vec2i(geometry.xy)/select(1,2,plane!=0u);
    if any(p>=size){return 0;}
    // Edge extension matches the declared visible frame, never padded
    // texels outside the image. Chroma uses signed floor displacement.
    let v=select(mv,mv>>vec2u(1u),plane!=0u);
    return code(p*PEL,plane,reverse)-code(p*PEL+v,plane,!reverse);
}
fn had(v:vec4i)->vec4i{
    let a=v.x+v.y;let b=v.z+v.w;let c=v.x-v.y;let d=v.z-v.w;
    return vec4i(a+b,a-b,c+d,c-d);
}
fn tile(origin:vec2i,offset:vec2i,mv:vec2i,plane:u32,reverse:bool)->vec2u{
    var rows:array<vec4i,4>;var raw=0u;
    for(var y=0;y<4;y++){
        var row=vec4i(0);
        for(var x=0;x<4;x++){row[x]=diff(origin,offset+vec2i(x,y),mv,plane,reverse);}
        raw+=u32((abs(row.x)+abs(row.y)+abs(row.z)+abs(row.w)));
        rows[y]=had(row);
    }
    var value=0u;
    for(var x=0;x<4;x++){
        let col=had(vec4i(rows[0][x],rows[1][x],rows[2][x],rows[3][x]));
        value+=u32((abs(col.x)+abs(col.y)+abs(col.z)+abs(col.w)));
    }
    return vec2u(value,raw);
}
fn plane_metric(origin:vec2i,mv:vec2i,plane:u32,reverse:bool,satd:bool)->vec2u{
    let block=BLOCK/select(1,2,plane!=0u);
    let start=origin/select(1,2,plane!=0u);
    var result=vec2u(0);
    if satd{
        for(var y=0;y<block;y+=4){for(var x=0;x<block;x+=4){
            result+=tile(start,vec2i(x,y),mv,plane,reverse);
        }}
        result.x/=2u;
    }else{
        for(var y=0;y<block;y++){for(var x=0;x<block;x++){
            result.x+=u32(abs(diff(start,vec2i(x,y),mv,plane,reverse)));
        }}
        result.y=result.x;
    }
    return result;
}
fn metric(origin:vec2i,mv:vec2i,seed:vec2i,reverse:bool,satd:bool,bound:u32,gate:u32)->vec2u{
    let d=mv-seed;
    let motion=LAMBDA*u32((d.x*d.x+d.y*d.y))/256u;
    if motion>=bound{return vec2u(0xffffffffu);}
    let y=plane_metric(origin,mv,0u,reverse,satd);
    if gate!=0xffffffffu && y.y*4u>gate*5u{return vec2u(0xffffffffu);}
    let yc=y.x+PNEW*y.x/256u;
    if motion+yc>=bound{return vec2u(0xffffffffu);}
    var uv=0u;
    if CHROMA{
        uv=4u*(plane_metric(origin,mv,1u,reverse,satd).x+plane_metric(origin,mv,2u,reverse,satd).x);
        uv+=PNEW*uv/256u;
    }
    return vec2u(motion+yc+uv,y.y);
}
fn choose(lane:u32,mv:vec2i,m:vec2u,rank:u32){
    if m.x<scores[lane]||(m.x==scores[lane]&&rank<ranks[lane]){
        scores[lane]=m.x;raws[lane]=m.y;ranks[lane]=rank;vectors[lane]=mv;
    }
}
fn reduce(lane:u32){
    workgroupBarrier();
    for(var stride=32u;stride>0u;stride/=2u){
        if lane<stride{
            choose(lane,vectors[lane+stride],vec2u(scores[lane+stride],raws[lane+stride]),ranks[lane+stride]);
        }
        workgroupBarrier();
    }
}
fn search(origin:vec2i,seed:vec2i,center:vec2i,reverse:bool,lane:u32,radius:i32,step:i32,satd:bool){
    if lane==0u{
        let m=metric(origin,center,seed,reverse,satd,0xffffffffu,0xffffffffu);
        seed_score=m.x;seed_raw=m.y;
    }
    workgroupBarrier();
    let bound=seed_score;
    let gate=select(0xffffffffu,seed_raw,satd);
    scores[lane]=0xffffffffu;raws[lane]=0xffffffffu;ranks[lane]=0xffffffffu;vectors[lane]=center;
    if lane==0u{choose(lane,center,vec2u(bound,seed_raw),0u);}
    let side=2*radius+1;
    if bound!=0u{
        for(var n=lane;n<u32(side*side);n+=64u){
            let offset=vec2i(i32(n)%side-radius,i32(n)/side-radius);
            if all(offset==vec2i(0)){continue;}
            let mv=clamp(center+offset*step,vec2i(-64*PEL),vec2i(64*PEL));
            let m=metric(origin,mv,seed,reverse,satd,min(bound,scores[lane]),gate);
            choose(lane,mv,m,n+1u);
        }
    }
    reduce(lane);
}
@compute @workgroup_size(64)
fn analyse(@builtin(workgroup_id) g:vec3u,@builtin(local_invocation_index) lane:u32){
    let gh=geometry.w&0xffffu;
    let reverse=g.y>=gh;
    let gy=g.y%gh;
    let index=g.x+gy*geometry.z+select(0u,geometry.z*gh,reverse);
    let origin=vec2i(i32(g.x),i32(gy))*STEP;
    let seed=fields[index].xy*PEL;
    search(origin,seed,seed,reverse,lane,RADIUS,PEL,SATD);
    let center=vectors[0];
    workgroupBarrier();
    // Fine SAD search in native pel units. No interpolation of the
    // integer motion vector itself substitutes for matching subpixels.
    search(origin,seed,center,reverse,lane,PEL,1,false);
    if lane==0u{
        let size=min(vec2i(BLOCK),vec2i(geometry.xy)-origin);
        let n=u32(max(size.x*size.y,1));
        let raw=raws[0];
        let normalized=(raw*64u/u32(select(1,4,CODE_MAX==1023)))/n;
        fields[index]=vec4i(vectors[0],i32(normalized),PEL);
    }
}

// Global scene gate on device. Both directional residuals must be
// considered before synthesizing; no field or image readback.
@compute @workgroup_size(64)
fn scene(@builtin(local_invocation_index) lane:u32){
    let n=geometry.z*(geometry.w&0xffffu);
    var bad=0u;
    for(var i=lane;i<n;i+=64u){
        if fields[i].z>64*48||fields[i+n].z>64*48{bad+=1u;}
    }
    scene_bad[lane]=bad;
    workgroupBarrier();
    for(var stride=32u;stride>0u;stride/=2u){
        if lane<stride{scene_bad[lane]+=scene_bad[lane+stride];}
        workgroupBarrier();
    }
    if lane==0u{fields[2u*n]=vec4i(select(0,1,scene_bad[0]*100u>=n*SCENE),i32(scene_bad[0]),i32(n),0);}
}
