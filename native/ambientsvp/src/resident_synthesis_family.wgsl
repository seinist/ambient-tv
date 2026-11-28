// SPDX-License-Identifier: Apache-2.0
// Algorithm equations adapted from Open-SVPFlow smooth GPU shader.
// Native fields are in signed pel units; mask values in integer 0..255.
const FAMILY_STEP:f32=SVP_STEP.0;
const FAMILY_ORIGIN:f32=SVP_ORIGIN.0;
const AREA_BLEND:f32=SVP_AREA_BLEND;
const FAMILY_PEL:f32=SVP_PEL.0;
const FAMILY_SDR8:bool=SVP_SDR8;
struct FamilyMotion {
    a:vec2f, b:vec2f, ext_a:vec2f, ext_b:vec2f,
    mask:vec4f, time:f32, cut:bool, extended_ok:bool,
}
fn family_field(p:vec2f,grid:vec2u,base:u32,is_mask:bool)->vec4f{
    let q=(p-vec2f(FAMILY_ORIGIN))/FAMILY_STEP-vec2f(0.5);
    let at=vec2i(floor(q));
    let f=vec2i(floor(fract(q)*256.0+vec2f(0.5)));
    let a=vec2i(256)-f;
    let weights=vec4i((a.x*a.y+128)>>8,(f.x*a.y+127)>>8,
        (a.x*f.y+127)>>8,(f.x*f.y+128)>>8);
    let hi=vec2i(grid)-vec2i(1);
    let i=clamp(at,vec2i(0),hi);let j=clamp(at+vec2i(1),vec2i(0),hi);
    let i0=base+u32(i.x)+u32(i.y)*grid.x;
    let i1=base+u32(j.x)+u32(i.y)*grid.x;
    let i2=base+u32(i.x)+u32(j.y)*grid.x;
    let i3=base+u32(j.x)+u32(j.y)*grid.x;
    let sum=fields[i0]*weights.x+fields[i1]*weights.y+fields[i2]*weights.z+fields[i3]*weights.w;
    if is_mask{
        return vec4f((sum*257+vec4i(128))>>vec4u(8))/65535.0;
    }
    let bias=1024*(weights.x+weights.y+weights.z+weights.w);
    let mv=((sum.xy+vec2i(bias+128))>>vec2u(8))-vec2i(1024);
    return vec4f(vec2f(mv)/FAMILY_PEL,0.0,1.0);
}
fn family_motion(p:vec2f,grid:vec2u,phase:f32)->FamilyMotion{
    let n=grid.x*grid.y;
    let a=family_field(p,grid,0u,false);
    let b=family_field(p,grid,n,false);
    let time=floor(phase*256.0+0.5)/256.0;
    // Reference samples A along B->A and B along A->B, not the
    // negated vectors from the other source (different at occlusions).
    let f=b.xy*time;
    let r=a.xy*(1.0-time);
    let mask=clamp(family_field(p,grid,7u*n+3u,true),vec4f(0),vec4f(1));
    // Extended fields occupy a separate optional region. No substitute
    // motion vectors are interpreted as neighbouring pair observations.
    var ff=f;var bb=r;var extended_ok=false;
    if ((params.y>>8u)&31u)==23u{
        extended_ok=fields[10u*n+3u].x==0&&fields[10u*n+4u].x==0;
        let ea=family_field(p,grid,8u*n+3u,false);
        let eb=family_field(p,grid,9u*n+3u,false);
        ff=ea.xy*time;
        bb=eb.xy*(1.0-time);
    }
    return FamilyMotion(f,r,ff,bb,mask,time,fields[2u*n].x!=0,extended_ok);
}
fn family_median(a:vec4f,b:vec4f,c:vec4f)->vec4f{
    return max(min(a,b),min(max(a,b),c));
}
fn family_blend(f:vec4f,b:vec4f,f0:vec4f,b0:vec4f,ff:vec4f,bb:vec4f,
    m:FamilyMotion,algo:u32)->vec4f{
    if m.cut{return select(f0,b0,m.time>=0.5);}
    let sad_blend=select(m.time*AREA_BLEND,1.0-AREA_BLEND*(1.0-m.time),m.time*256.0>126.0);
    var result=mix(f,b,m.time);
    switch algo{
        case 1u:{result=b;}
        case 2u:{result=select(f,b,m.time>0.5-1.0/256.0);}
        case 11u:{}
        case 13u:{result=family_median(f,b,mix(f0,b0,sad_blend));}
        case 21u:{result=mix(mix(f,b,m.mask.y),mix(b,f,m.mask.z),m.time);}
        case 22u:{result=family_median(mix(f,b,m.mask.y),mix(b,f,m.mask.z),mix(f0,b0,m.time));}
        case 23u:{
            if m.extended_ok{result=mix(mix(f,family_median(b,bb,f),m.mask.y),
                mix(b,family_median(b,ff,f),m.mask.z),m.time);}
            else{result=mix(mix(f,b,m.mask.y),mix(b,f,m.mask.z),m.time);}
        }
        default:{}
    }
    if algo==1u{return mix(result,b0,m.mask.x);}
    if algo==2u{return select(mix(result,f0,m.mask.w),mix(result,b0,m.mask.x),m.time>0.5-1.0/256.0);}
    return mix(result,mix(f0,b0,sad_blend),max(m.mask.x,m.mask.w));
}
fn family_decode(v:vec4f)->vec4f{
    return select(pow((v+vec4f(0.099))/1.099,vec4f(1.0/0.45)),v/4.5,v<vec4f(0.081));
}
fn family_encode(v:f32)->f32{
    return select(1.099*pow(max(v,0.0),0.45)-0.099,v*4.5,v<0.018);
}
fn family_sample(t:texture_2d<f32>,p:vec2f,visible:vec2u,linear:bool)->vec4f{
    let hi=vec2i(min(textureDimensions(t),visible))-vec2i(1);
    let q=clamp(p,vec2f(0),vec2f(hi));
    let i=vec2i(floor(q));let j=min(i+vec2i(1),hi);
    let a=textureLoad(t,i,0);
    if all(fract(q)==vec2f(0)){
        if linear{return family_decode(a);}
        return a;
    }
    let b=textureLoad(t,vec2i(j.x,i.y),0);
    let c=textureLoad(t,vec2i(i.x,j.y),0);
    let d=textureLoad(t,j,0);
    let f=vec2i(floor(fract(q)*256.0+vec2f(0.5)));
    let g=vec2i(256)-f;
    let w=vec4i((g.x*g.y+128)>>8,(f.x*g.y+127)>>8,
        (g.x*f.y+127)>>8,(f.x*f.y+128)>>8);
    if linear{
        let wf=vec4f(w)/256.0;
        return ((family_decode(a)*wf.x+family_decode(b)*wf.y)+family_decode(c)*wf.z)+family_decode(d)*wf.w;
    }
    if FAMILY_SDR8{
        let sum=w.x*vec4i(round(a*255.0))*257+w.y*vec4i(round(b*255.0))*257+
            w.z*vec4i(round(c*255.0))*257+w.w*vec4i(round(d*255.0))*257;
        return vec4f((sum+vec4i(128))>>vec4u(8))/65535.0;
    }
    // Preserve native FP16 HDR headroom; never run the SDR OETF here.
    let wf=vec4f(w)/256.0;
    return ((a*wf.x+b*wf.y)+c*wf.z)+d*wf.w;
}
fn family_yuv(y:texture_2d<f32>,u:texture_2d<f32>,v:texture_2d<f32>,p:vec2f,nv:bool,location:u32,visible:vec2u)->vec3f{
    let offset=vec2f(select(0.0,0.5,location==2u),select(0.0,0.5,location!=0u));
    let chroma=(p-offset)*0.5;
    let uv=family_sample(u,chroma,visible/2u,false);
    var vv=uv.y;
    if !nv{vv=family_sample(v,chroma,visible/2u,false).x;}
    return vec3f(family_sample(y,p,visible,FAMILY_SDR8).x,uv.x,vv);
}
fn family_plane(a:texture_2d<f32>,b:texture_2d<f32>,p:vec2f,m:FamilyMotion,
    scale:f32,visible:vec2u,algo:u32)->vec4f{
    let f=family_sample(a,p+m.a*scale,visible,false);
    let r=family_sample(b,p+m.b*scale,visible,false);
    let f0=family_sample(a,p,visible,false);let b0=family_sample(b,p,visible,false);
    var ff=f;var bb=r;
    if algo==23u{
        ff=family_sample(a,p+m.ext_a*scale,visible,false);
        bb=family_sample(b,p+m.ext_b*scale,visible,false);
    }
    return family_blend(f,r,f0,b0,ff,bb,m,algo);
}
