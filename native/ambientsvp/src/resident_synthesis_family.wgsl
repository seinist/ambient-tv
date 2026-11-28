// SPDX-License-Identifier: Apache-2.0
// Algorithm equations adapted from Open-SVPFlow smooth GPU shader.
// Native fields are in signed pel units; mask values in integer 0..255.
const FAMILY_STEP:f32=SVP_STEP.0;
const FAMILY_ORIGIN:f32=SVP_ORIGIN.0;
const AREA_BLEND:f32=SVP_AREA_BLEND;
struct FamilyMotion {
    a:vec2f, b:vec2f, ext_a:vec2f, ext_b:vec2f,
    mask:vec4f, time:f32, cut:bool,
}
fn family_field(p:vec2f,grid:vec2u,base:u32)->vec4f{
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
    return vec4f(fields[i0]*weights.x+fields[i1]*weights.y+
        fields[i2]*weights.z+fields[i3]*weights.w)/256.0;
}
fn family_motion(p:vec2f,grid:vec2u,phase:f32)->FamilyMotion{
    let n=grid.x*grid.y;
    let a=family_field(p,grid,0u);
    let b=family_field(p,grid,n);
    let time=floor(phase*256.0+0.5)/256.0;
    let f=-a.xy/max(a.w,1.0)*time;
    let r=-b.xy/max(b.w,1.0)*(1.0-time);
    let mask=clamp(family_field(p,grid,7u*n+3u)/255.0,vec4f(0),vec4f(1));
    // Extended fields occupy a separate optional region. No substitute
    // motion vectors are interpreted as neighbouring pair observations.
    var ff=f;var bb=r;
    if ((params.y>>8u)&31u)==23u{
        let ea=family_field(p,grid,8u*n+3u);
        let eb=family_field(p,grid,9u*n+3u);
        ff=-ea.xy/max(ea.w,1.0)*time;
        bb=-eb.xy/max(eb.w,1.0)*(1.0-time);
    }
    return FamilyMotion(f,r,ff,bb,mask,time,fields[2u*n].x!=0);
}
fn family_median(a:vec4f,b:vec4f,c:vec4f)->vec4f{
    return max(min(a,b),min(max(a,b),c));
}
fn family_blend(f:vec4f,b:vec4f,f0:vec4f,b0:vec4f,ff:vec4f,bb:vec4f,
    m:FamilyMotion,algo:u32)->vec4f{
    if m.cut{return select(f0,b0,m.time>=0.5);}
    let sad_blend=AREA_BLEND;
    var result=mix(f,b,m.time);
    switch algo{
        case 1u:{result=b;}
        case 2u:{result=f;}
        case 11u:{}
        case 13u:{result=family_median(f,b,mix(f0,b0,sad_blend));}
        case 21u:{result=mix(mix(f,b,m.mask.y),mix(b,f,m.mask.z),m.time);}
        case 22u:{result=family_median(mix(f,b,m.mask.y),mix(b,f,m.mask.z),mix(f0,b0,m.time));}
        case 23u:{result=mix(mix(f,family_median(b,bb,f),m.mask.y),
            mix(b,family_median(b,ff,f),m.mask.z),m.time);}
        default:{}
    }
    if algo==1u{return mix(result,b0,m.mask.x);}
    if algo==2u{return mix(result,f0,m.mask.w);}
    return mix(result,mix(f0,b0,sad_blend),max(m.mask.x,m.mask.w));
}
fn family_plane(a:texture_2d<f32>,b:texture_2d<f32>,p:vec2f,m:FamilyMotion,
    scale:f32,visible:vec2u,algo:u32)->vec4f{
    let f=bilinear(a,p+m.a*scale,visible);
    let r=bilinear(b,p+m.b*scale,visible);
    let f0=bilinear(a,p,visible);let b0=bilinear(b,p,visible);
    var ff=f;var bb=r;
    if algo==23u{
        ff=bilinear(a,p+m.ext_a*scale,visible);
        bb=bilinear(b,p+m.ext_b*scale,visible);
    }
    return family_blend(f,r,f0,b0,ff,bb,m,algo);
}
