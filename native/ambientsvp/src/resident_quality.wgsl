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
const PNEW:i32=SVP_PNEW;
const SCENE:u32=SVP_SCENE;
const LSAD:u32=SVP_LSAD;
const PZERO:i32=SVP_PZERO;
const PGLOBAL:i32=SVP_PGLOBAL;
const PNBOUR:i32=SVP_PNBOUR;
const PREVERSE:i32=SVP_PREVERSE;
const SAD_GATE:bool=SVP_SAD_GATE;
var<workgroup> scene_bad:array<u32,64>;
// At most 17x17 candidates. Each lane computes an independent metric;
// lane zero consumes the results in reference raster order.
const MAX_CANDIDATES:u32=289u;
var<workgroup> metrics:array<vec4u,289>;
var<workgroup> candidates:array<vec2i,8>;
var<workgroup> enabled:array<u32,8>;
var<workgroup> predictor:vec2i;
var<workgroup> best_vec:vec2i;
var<workgroup> best_sad:u32;
var<workgroup> best_raw:u32;
var<workgroup> best_cost:u32;
var<workgroup> block_lambda:u32;
var<workgroup> freq_x:array<atomic<u32>,129>;
var<workgroup> freq_y:array<atomic<u32>,129>;
var<workgroup> global_mode:vec2i;
var<workgroup> global_sum:array<vec3i,64>;
fn texel(t:texture_2d<f32>,p:vec2i,hi:vec2i,channel:u32)->i32{
    return i32(round(clamp(textureLoad(t,clamp(p,vec2i(0),hi),0)[channel]*SCALE,0.0,f32(CODE_MAX))));
}
// The pinned SuperBuilder stores rounded half-pel phase planes. Quarter
// phases are then averages of two already rounded half-pel samples.
// In particular the bilinear diagonal is avg(A,D), not avg(A,B,C,D).
const FILTER:i32=SVP_FILTER;
fn avg(a:i32,b:i32)->i32{return (a+b+1)>>1;}
fn half_axis(t:texture_2d<f32>,p:vec2i,hi:vec2i,channel:u32,axis:vec2i)->i32{
    let a=texel(t,p,hi,channel);let b=texel(t,p+axis,hi,channel);
    let at=p.x*axis.x+p.y*axis.y;
    let last=hi.x*axis.x+hi.y*axis.y;
    if FILTER==1&&at>=1&&at<last-2{
        let m=texel(t,p-axis,hi,channel);let n=texel(t,p+2*axis,hi,channel);
        return clamp((-m+9*a+9*b-n+8)>>4,0,CODE_MAX);
    }
    if FILTER==2&&at>=2&&at<last-3{
        let m2=texel(t,p-2*axis,hi,channel);let m1=texel(t,p-axis,hi,channel);
        let p2=texel(t,p+2*axis,hi,channel);let p3=texel(t,p+3*axis,hi,channel);
        return clamp((m2-5*m1+20*a+20*b-5*p2+p3+16)>>5,0,CODE_MAX);
    }
    return avg(a,b);
}
fn vertical(t:texture_2d<f32>,p:vec2i,hi:vec2i,channel:u32)->i32{
    // Upstream bilinear first-row convention.
    if FILTER==0&&p.y==0{return texel(t,p,hi,channel);}
    return half_axis(t,p,hi,channel,vec2i(0,1));
}
fn diagonal(t:texture_2d<f32>,p:vec2i,hi:vec2i,channel:u32)->i32{
    if FILTER==0{
        if p.y==0{return texel(t,p+vec2i(1,0),hi,channel);}
        return avg(texel(t,p,hi,channel),texel(t,p+vec2i(1),hi,channel));
    }
    // Upstream applies horizontal FIR to the already rounded/clipped
    // vertical phase, rather than a single two-dimensional convolution.
    let x=vec2i(1,0);
    let a=vertical(t,p,hi,channel);let b=vertical(t,p+x,hi,channel);
    if FILTER==1&&p.x>=1&&p.x<hi.x-2{
        return clamp((-vertical(t,p-x,hi,channel)+9*a+9*b-vertical(t,p+2*x,hi,channel)+8)>>4,0,CODE_MAX);
    }
    if FILTER==2&&p.x>=2&&p.x<hi.x-3{
        return clamp((vertical(t,p-2*x,hi,channel)-5*vertical(t,p-x,hi,channel)+20*a+20*b-
            5*vertical(t,p+2*x,hi,channel)+vertical(t,p+3*x,hi,channel)+16)>>5,0,CODE_MAX);
    }
    return avg(a,b);
}
fn half_sample(t:texture_2d<f32>,q:vec2i,hi:vec2i,channel:u32)->i32{
    let p=q>>vec2u(1);
    let f=q&vec2i(1);
    if all(f==vec2i(0)){return texel(t,p,hi,channel);}
    if f.y==0{return half_axis(t,p,hi,channel,vec2i(1,0));}
    if f.x==0{return vertical(t,p,hi,channel);}
    return diagonal(t,p,hi,channel);
}
fn vertical_quarter(t:texture_2d<f32>,q:vec2i,yodd:bool,hi:vec2i,channel:u32)->i32{
    let a=half_sample(t,q,hi,channel);
    if !yodd{return a;}
    return avg(a,half_sample(t,q+vec2i(0,1),hi,channel));
}
fn sample_plane(t:texture_2d<f32>,p:vec2i,hi:vec2i,channel:u32)->i32{
    // Bounds are deliberately visible-frame safe. The legacy upstream
    // packed canvas can spill across physical phase rows at the edge;
    // those storage-specific bytes are not reproduced from an image view.
    let q=clamp(p,vec2i(0),hi*PEL);
    if PEL==1{return texel(t,q,hi,channel);}
    if PEL==2{return half_sample(t,q,hi,channel);}
    let h=q>>vec2u(1);let f=q&vec2i(1);
    let a=vertical_quarter(t,h,f.y!=0,hi,channel);
    if f.x==0{return a;}
    return avg(a,vertical_quarter(t,h+vec2i(1,0),f.y!=0,hi,channel));
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
// Both SAD and SATD retain the separate chroma term. Upstream
// check_predictor applies its penalty to the combined metric; a new
// candidate applies pnew separately to luma and weighted chroma.
fn costs(origin:vec2i,mv:vec2i,reverse:bool,satd:bool)->vec4u{
    let y=plane_metric(origin,mv,0u,reverse,satd);
    var uv=0u;
    if CHROMA{
        uv=4u*(plane_metric(origin,mv,1u,reverse,satd).x+
            plane_metric(origin,mv,2u,reverse,satd).x);
    }
    return vec4u(y.x,uv,y.y,1u);
}
fn penalized(sad:u32,penalty:i32)->u32{
    return u32(max(0,i32(sad)+((penalty*i32(sad))>>8)));
}
fn motion_cost(mv:vec2i)->u32{
    let d=mv-predictor;
    let norm=u32(d.x*d.x+d.y*d.y);
    // Equivalent to (lambda*norm)>>8 when representable. Keep
    // extreme distances expensive instead of wrapping to a false win.
    let hi=norm>>8u;let lo=norm&255u;
    if hi>0u&&block_lambda>0x7fffffffu/hi{return 0x7fffffffu;}
    let whole=hi*block_lambda;
    let fraction=lo*(block_lambda>>8u)+(lo*(block_lambda&255u)>>8u);
    return min(0x7fffffffu-whole,fraction)+whole;
}
// Exact 32x32 -> 64 bit product using 16-bit limbs. No shaderInt64 or
// shaderFloat64 device feature is required.
fn wide_mul(a:u32,b:u32)->vec2u{
    let a0=a&65535u;let a1=a>>16u;
    let b0=b&65535u;let b1=b>>16u;
    let w=a0*b0;
    let t=a1*b0+(w>>16u);
    let z=a0*b1+(t&65535u);
    return vec2u((z<<16u)|(w&65535u),a1*b1+(t>>16u)+(z>>16u));
}
// Reference computes a rounded f32 ratio, squares that ratio in f64,
// multiplies by lambda in f64, and truncates. Reproduce the rounding
// using integer mantissas so mobile Vulkan does not require Float64.
fn adapt_lambda(sad:u32)->u32{
    if LAMBDA==0u{return 0u;}
    let denominator=LSAD+(sad>>1u);
    if denominator==LSAD{return LAMBDA;}
    var n=LSAD;
    var exponent=127u;
    // The bounded SAD and LSAD are exactly representable f32 integers.
    // Normalize the rational and generate 23 fractional mantissa bits.
    while n<denominator{n*=2u;exponent-=1u;}
    var rem=n-denominator;
    var mantissa=1u<<23u;
    for(var bit=23u;bit>0u;bit-=1u){
        rem*=2u;
        if rem>=denominator{
            rem-=denominator;mantissa|=1u<<(bit-1u);
        }
    }
    let twice=rem*2u;
    if twice>denominator||(twice==denominator&&(mantissa&1u)!=0u){mantissa+=1u;}
    if mantissa==(1u<<24u){mantissa>>=1u;exponent+=1u;}
    // 48-bit exact square, then multiply by the bounded lambda.
    let square=wide_mul(mantissa,mantissa);
    let low=wide_mul(square.x,LAMBDA);
    let high=wide_mul(square.y,LAMBDA);
    var product=vec3u(low.x,low.y+high.x,high.y);
    if product.y<low.y{product.z+=1u;}
    // f64 rounding discards at most 23 low bits for this product.
    // Add half-minus-one plus the retained LSB (round ties to even).
    var length=32u-countLeadingZeros(product.x);
    if product.y!=0u{length=64u-countLeadingZeros(product.y);}
    if product.z!=0u{length=96u-countLeadingZeros(product.z);}
    if length>53u{
        let drop=length-53u;
        let add=(1u<<(drop-1u))-1u+((product.x>>drop)&1u);
        let before=product.x;product.x+=add;
        if product.x<before{product.y+=1u;if product.y==0u{product.z+=1u;}}
    }
    let shift=2u*(150u-exponent);
    if shift>=96u{return 0u;}
    if shift>=64u{return product.z>>(shift-64u);}
    // shift >= 46 for ratio <= 1, so only middle/high limbs matter.
    let offset=shift-32u;
    return (product.y>>offset)|(product.z<<(32u-offset));
}
fn clamp_mv(v:vec2i)->vec2i{return clamp(v,vec2i(-64*PEL),vec2i(64*PEL));}
fn snapshot_base(n:u32)->u32{return 2u*n+1u;}
fn global_base(n:u32)->u32{return 4u*n+1u;}
@compute @workgroup_size(64)
fn snapshot(@builtin(workgroup_id) g:vec3u,@builtin(local_invocation_index) lane:u32){
    let n=geometry.z*(geometry.w&0xffffu);
    let offset=g.x*n;
    for(var i=lane;i<129u;i+=64u){
        atomicStore(&freq_x[i],0u);atomicStore(&freq_y[i],0u);
    }
    workgroupBarrier();
    for(var i=lane;i<n;i+=64u){
        let f=fields[offset+i];
        fields[snapshot_base(n)+offset+i]=f;
        let v=clamp(f.xy,vec2i(-64),vec2i(64))+vec2i(64);
        atomicAdd(&freq_x[u32(v.x)],1u);atomicAdd(&freq_y[u32(v.y)],1u);
    }
    workgroupBarrier();
    if lane==0u{
        var ix=0u;var iy=0u;
        for(var i=1u;i<129u;i++){
            if atomicLoad(&freq_x[i])>atomicLoad(&freq_x[ix]){ix=i;}
            if atomicLoad(&freq_y[i])>atomicLoad(&freq_y[iy]){iy=i;}
        }
        global_mode=vec2i(i32(ix)-64,i32(iy)-64);
    }
    workgroupBarrier();
    var sum=vec3i(0);
    for(var i=lane;i<n;i+=64u){
        let v=fields[offset+i].xy;
        if all(abs(v-global_mode)<vec2i(6)){sum+=vec3i(v,1);}
    }
    global_sum[lane]=sum;
    workgroupBarrier();
    for(var stride=32u;stride>0u;stride/=2u){
        if lane<stride{global_sum[lane]+=global_sum[lane+stride];}
        workgroupBarrier();
    }
    if lane==0u{
        let total=global_sum[0];
        // Coarse field is already expressed in this level's pixels,
        // so do not double a second time as in inter-level promotion.
        let v=select(global_mode,total.xy/max(total.z,1),total.z>0);
        fields[global_base(n)+g.x]=vec4i(v*PEL,0,PEL);
    }
}
fn pick_predictors(origin:vec2i,cell:vec2u,index:u32,reverse:bool,lane:u32){
    let gh=geometry.w&0xffffu;let gw=geometry.z;let n=gw*gh;
    let base=select(0u,n,reverse);
    let seed=fields[snapshot_base(n)+index];
    if lane==0u{
        predictor=clamp_mv(seed.xy*PEL);
        candidates[0]=predictor;enabled[0]=1u;
        // Reciprocal prediction comes from the immutable opposite
        // coarse field at the displaced block, never concurrent writes.
        let other=vec2u(clamp(vec2i(cell)+seed.xy/STEP,vec2i(0),vec2i(i32(gw)-1,i32(gh)-1)));
        let opposite=fields[snapshot_base(n)+select(n,0u,reverse)+other.x+other.y*gw];
        candidates[1]=clamp_mv(-opposite.xy*PEL);enabled[1]=select(0u,1u,opposite.z>0);
        candidates[2]=clamp_mv(fields[global_base(n)+select(0u,1u,reverse)].xy);enabled[2]=1u;
        candidates[3]=vec2i(0);enabled[3]=1u;
        // Same-level neighbours in upstream row-major order. All
        // precede the current x+2*y wave and have finalized pel units.
        for(var j=0u;j<4u;j++){
            let d=vec2i(i32(j)-1,-1);
            let off=select(d,vec2i(-1,0),j==3u);
            let p=vec2i(cell)+off;
            enabled[j+4u]=0u;candidates[j+4u]=vec2i(0);
            if all(p>=vec2i(0))&&p.x<i32(gw)&&p.y<i32(gh){
                let v=fields[base+u32(p.x)+u32(p.y)*gw];
                candidates[j+4u]=clamp_mv(v.xy);enabled[j+4u]=1u;
            }
        }
    }
    workgroupBarrier();
    if lane<8u{
        metrics[lane]=vec4u(0,0,0,0);
        if enabled[lane]!=0u{metrics[lane]=costs(origin,candidates[lane],reverse,SATD);}
    }
    workgroupBarrier();
    if lane==0u{
        let initial=metrics[0];
        block_lambda=adapt_lambda(initial.x+initial.y);
        best_vec=predictor;best_sad=initial.x+initial.y;best_raw=initial.z;best_cost=best_sad;
        for(var j=1u;j<8u;j++){
            let m=metrics[j];
            if m.w==0u{continue;}
            var penalty=PNBOUR;
            if j==1u{penalty=PREVERSE;}else if j==2u{penalty=PGLOBAL;}else if j==3u{penalty=PZERO;}
            let sad=m.x+m.y;let cost=penalized(sad,penalty);
            if cost<best_cost{
                best_vec=candidates[j];best_sad=sad;best_raw=m.z;best_cost=cost;
                if j==1u{predictor=best_vec;}
            }
        }
        // Upstream resets the search bound to the selected raw metric
        // after comparing the weighted predictor candidates.
        best_cost=best_sad;
    }
    workgroupBarrier();
}
fn search(origin:vec2i,reverse:bool,lane:u32,radius:i32,step:i32,satd:bool){
    let center=best_vec;
    if lane==0u{
        let m=costs(origin,center,reverse,satd);
        best_sad=m.x+m.y;best_raw=m.z;best_cost=best_sad;
    }
    workgroupBarrier();
    let side=2*radius+1;
    let count=u32(side*side);
    let initial=best_cost;
    for(var j=lane;j<count;j+=64u){
        let off=vec2i(i32(j)%side-radius,i32(j)/side-radius);
        let mv=clamp_mv(center+off*step);
        var m=vec4u(0);
        if any(off!=vec2i(0))&&initial>0u&&motion_cost(mv)<initial{
            m=costs(origin,mv,reverse,satd);
        }
        metrics[j]=m;
    }
    workgroupBarrier();
    if lane==0u{
        for(var j=0u;j<count;j++){
            let m=metrics[j];if m.w==0u{continue;}
            let off=vec2i(i32(j)%side-radius,i32(j)/side-radius);
            let mv=clamp_mv(center+off*step);
            let motion=motion_cost(mv);
            if motion>=best_cost{continue;}
            if SAD_GATE&&satd&&m.z*4u>best_raw*5u{continue;}
            let y=penalized(m.x,PNEW);
            if y>=best_cost-motion{continue;}
            let uv=penalized(m.y,PNEW);
            let cost=motion+y+uv;
            if cost<best_cost{
                best_vec=mv;best_sad=m.x+m.y;best_cost=cost;best_raw=m.z;
            }
        }
    }
    workgroupBarrier();
}
@compute @workgroup_size(64)
fn analyse(@builtin(workgroup_id) g:vec3u,@builtin(local_invocation_index) lane:u32){
    let gh=geometry.w&0xffffu;
    let wave=(geometry.w>>19u)&4095u;
    let gy=g.x;
    if gy>=gh||wave<2u*gy{return;}
    let gx=wave-2u*gy;
    if gx>=geometry.z{return;}
    let reverse=g.y!=0u;
    let index=gx+gy*geometry.z+select(0u,geometry.z*gh,reverse);
    let origin=vec2i(i32(gx),i32(gy))*STEP;
    pick_predictors(origin,vec2u(gx,gy),index,reverse,lane);
    search(origin,reverse,lane,RADIUS,PEL,SATD);
    // Fine SAD search uses the exact rounded subpixel phase samples.
    search(origin,reverse,lane,PEL,1,false);
    if lane==0u{
        let size=min(vec2i(BLOCK),vec2i(geometry.xy)-origin);
        let n=u32(max(size.x*size.y,1));
        let normalized=(best_raw*64u/u32(select(1,4,CODE_MAX==1023)))/n;
        fields[index]=vec4i(best_vec,i32(normalized),PEL);
        // Preserve the complete selected luma + weighted chroma metric
        // for the artifact mask; the legacy confidence word stays luma-only.
        fields[4u*geometry.z*gh+3u+index]=vec4i(i32(best_sad),0,0,0);
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
