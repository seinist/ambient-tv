// PQ RGB statistics on the actual synthesized frame. All histograms
// stay on the GPU; only compact results are mapped by the caller.
// 100001 bins preserve the ST 2094-40 1e-5 quantization.
@group(0) @binding(0) var source:texture_2d<f32>;
@group(0) @binding(1) var<storage,read_write> hist:array<atomic<u32>>;
@group(0) @binding(2) var<storage,read_write> words:array<atomic<u32>>;
@group(0) @binding(3) var<uniform> params:vec4u;
const BINS:u32=100001u;
const STRIDE:u32=76u;
@group(0) @binding(3) var<storage,read> motion:array<vec4i>;
@group(0) @binding(4) var<storage,read> pq_table:array<vec4u>;
fn pq_index(v:f32)->u32{
    return 2u*min(pack2x16float(vec2f(clamp(v,0.0,1.0),0.0))&65535u,15360u);
}
fn pq(v:vec3f)->vec3f{
    return vec3f(bitcast<f32>(pq_table[pq_index(v.x)].z),bitcast<f32>(pq_table[pq_index(v.y)].z),bitcast<f32>(pq_table[pq_index(v.z)].z));
}
fn pq_bin(v:vec3f)->vec3u{
    return vec3u(pq_table[pq_index(v.x)].x,pq_table[pq_index(v.y)].x,pq_table[pq_index(v.z)].x);
}
fn pq_linear_max(v:vec3f)->u32{
    return max(pq_table[pq_index(v.x)].y,max(pq_table[pq_index(v.y)].y,pq_table[pq_index(v.z)].y));
}
// Window tracking stays on the resident motion field. For each endpoint
// use only blocks whose forward/reverse displacement agrees at the
// displaced coordinate. Blend the two predictions according to their
// support; bounded translation leaves authored window shape intact.
var<workgroup> track_sum:array<vec4i,64>;
var<workgroup> track_count:array<vec2u,64>;
// Normalized spatial moments and cross moments of reciprocal vectors.
// Fit a full 2D affine gradient after removing translation.
var<workgroup> fit_pos:array<vec4f,64>;
var<workgroup> fit_cross:array<vec4f,64>;
var<workgroup> fit_vel:array<vec4f,64>;
fn rectangle(o:u32)->vec4u{
    return vec4u(atomicLoad(&words[o]),atomicLoad(&words[o+1u]),
        atomicLoad(&words[o+2u]),atomicLoad(&words[o+3u]));
}
@compute @workgroup_size(64)
fn track(@builtin(workgroup_id) g:vec3u,@builtin(local_invocation_index) lane:u32){
    let w=g.x;
    if w==0u||w>=params.z{return;}
    let o=w*STRIDE;
    // Phase-zero geometry is authored, not inferred from motion.
    if bitcast<f32>(atomicLoad(&words[o+43u]))==0.0{return;}
    let ar=rectangle(o+44u);
    let br=rectangle(o+48u);
    let step=max(params.w,1u);
    let grid=(params.xy+vec2u(step-1u))/step;
    let n=grid.x*grid.y;
    var sum=vec4i(0);var support=vec2u(0);
    var pos=vec4f(0);var cross=vec4f(0);var vel=vec4f(0);
    for(var direction=0u;direction<2u;direction++){
        let r=select(ar,br,direction==1u);
        let lo=r.xy/step;let hi=min((r.zw+vec2u(step-1u))/step,grid);
        let size=hi-lo;
        for(var i=lane;i<size.x*size.y;i+=64u){
            let cell=lo+vec2u(i%size.x,i/size.x);
            let center=min(cell*step+vec2u(step/2u),params.xy-vec2u(1u));
            // Include partially intersecting blocks, but prefer exact
            // cycle consistency at their displaced sample position.
            let f=motion[direction*n+cell.y*grid.x+cell.x];
            let mv=f.xy*4/max(f.w,1);
            let displaced=vec2i(center)+vec2i(round(vec2f(mv)/4.0));
            if f.z<0||f.z>64*32||any(displaced<vec2i(0))||any(displaced>=vec2i(params.xy)){continue;}
            let other=vec2u(displaced)/step;
            let b=motion[(1u-direction)*n+other.y*grid.x+other.x];
            let back=b.xy*4/max(b.w,1);
            if b.z<0||b.z>64*32||abs(mv.x+back.x)+abs(mv.y+back.y)>16{continue;}
            // Use the forward field only for the fit; reverse vectors
            // independently qualify samples through the cycle check above.
            if direction==0u{
                let xy=(vec2f(center)-(vec2f(ar.xy)+vec2f(ar.zw))*0.5)/f32(step);
                let v=vec2f(mv)/(4.0*f32(step));
                pos+=vec4f(xy,xy*xy);
                cross+=vec4f(xy.x*v,xy.y*v);
                vel+=vec4f(v,xy.x*xy.y,1.0);
            }
            if direction==0u{sum.x+=mv.x;sum.y+=mv.y;support.x+=1u;}
            else{sum.z+=mv.x;sum.w+=mv.y;support.y+=1u;}
        }
    }
    track_sum[lane]=sum;track_count[lane]=support;
    fit_pos[lane]=pos;fit_cross[lane]=cross;fit_vel[lane]=vel;
    workgroupBarrier();
    for(var stride=32u;stride>0u;stride/=2u){
        if lane<stride{
            track_sum[lane]+=track_sum[lane+stride];
            track_count[lane]+=track_count[lane+stride];
            fit_pos[lane]+=fit_pos[lane+stride];
            fit_cross[lane]+=fit_cross[lane+stride];
            fit_vel[lane]+=fit_vel[lane+stride];
        }
        workgroupBarrier();
    }
    if lane==0u{
        let nfit=fit_vel[0].w;
        if nfit>=6.0{
            let p=fit_pos[0];let v=fit_vel[0];let c=fit_cross[0];
            let xx=p.z-p.x*p.x/nfit;
            let yy=p.w-p.y*p.y/nfit;
            let xy=v.z-p.x*p.y/nfit;
            let det=xx*yy-xy*xy;
            // Reject collinear or poorly conditioned windows.
            if xx>0.0&&yy>0.0&&det>0.01*xx*yy{
                let cx=c.xy-p.x*v.xy/nfit;
                let cy=c.zw-p.y*v.xy/nfit;
                let gx=(yy*cx-xy*cy)/det;
                let gy=(xx*cy-xy*cx)/det;
                let gradient=vec4f(gx,gy);
                if all(abs(gradient)<=vec4f(0.5)){
                    for(var k=0u;k<4u;k++){
                        atomicStore(&words[o+56u+k],bitcast<u32>(gradient[k]));
                    }
                    atomicStore(&words[o+60u],1u);
                }
            }
        }
        let counts=track_count[0];let total=counts.x+counts.y;
        if total==0u{return;}
        let rect=rectangle(o);
        let center=(vec2f(rect.xy)+vec2f(rect.zw))*0.5;
        let ac=(vec2f(ar.xy)+vec2f(ar.zw))*0.5;
        let bc=(vec2f(br.xy)+vec2f(br.zw))*0.5;
        let t=bitcast<f32>(atomicLoad(&words[o+43u]));
        let forward=vec2f(track_sum[0].xy)/(4.0*f32(max(counts.x,1u)));
        let reverse=vec2f(track_sum[0].zw)/(4.0*f32(max(counts.y,1u)));
        let aw=(1.0-t)*f32(counts.x);let bw=t*f32(counts.y);
        let prediction=(aw*(ac+t*forward)+bw*(bc+(1.0-t)*reverse))/max(aw+bw,0.00001);
        let fade=4.0*t*(1.0-t);
        let ellipse=vec2i(i32(atomicLoad(&words[o+52u])),i32(atomicLoad(&words[o+53u])));
        let lower=max(max(-vec2i(rect.xy),-ellipse),vec2i(-16));
        let upper=min(min(vec2i(params.xy)-vec2i(rect.zw),vec2i(params.xy)-vec2i(1)-ellipse),vec2i(16));
        let shift=clamp(vec2i(round((prediction-center)*fade)),lower,upper);
        var moved=vec4u(vec4i(rect)+vec4i(shift,shift));
        // New opt-in ABI: transform the four rectangle corners about
        // their center. HDR10+ encodes an axis-aligned bounding box.
        if atomicLoad(&words[o+55u])==1u&&atomicLoad(&words[o+60u])==1u{
            let gx=vec2f(bitcast<f32>(atomicLoad(&words[o+56u])),bitcast<f32>(atomicLoad(&words[o+57u])));
            let gy=vec2f(bitcast<f32>(atomicLoad(&words[o+58u])),bitcast<f32>(atomicLoad(&words[o+59u])));
            let half_size=(vec2f(rect.zw)-vec2f(rect.xy))*0.5;
            let x=vec2f(1.0,0.0)+gx*fade*0.5;
            let y=vec2f(0.0,1.0)+gy*fade*0.5;
            let extent=abs(x)*half_size.x+abs(y)*half_size.y;
            let mid=center+vec2f(shift);
            let lo=vec2u(clamp(floor(mid-extent),vec2f(0.0),vec2f(params.xy)-vec2f(1.0)));
            let hi=vec2u(clamp(ceil(mid+extent),vec2f(lo)+vec2f(1.0),vec2f(params.xy)));
            if all(hi-lo>=vec2u(2u)){moved=vec4u(lo,hi);}
        }
        if atomicLoad(&words[o+67u])==1u{
            let angle=f32(atomicLoad(&words[o+66u]))*0.017453292519943295;
            let u=f32(atomicLoad(&words[o+64u]));let v=f32(atomicLoad(&words[o+65u]));
            let t=bitcast<f32>(atomicLoad(&words[o+43u]));
            let weight=2.0*t*(1.0-t);
            let gx=vec2f(bitcast<f32>(atomicLoad(&words[o+56u])),bitcast<f32>(atomicLoad(&words[o+57u])));
            let gy=vec2f(bitcast<f32>(atomicLoad(&words[o+58u])),bitcast<f32>(atomicLoad(&words[o+59u])));
            let a=vec2f(1.0,0.0)+weight*gx;
            let b=vec2f(0.0,1.0)+weight*gy;
            let x=u*(a*cos(angle)+b*sin(angle));
            let y=v*(-a*sin(angle)+b*cos(angle));
            let xx=x.x*x.x+y.x*y.x;
            let yy=x.y*x.y+y.y*y.y;
            let xy=x.x*x.y+y.x*y.y;
            let disc=length(vec2f(xx-yy,2.0*xy));
            let major=sqrt(max(0.0,(xx+yy+disc)*0.5));
            let minor=sqrt(max(0.0,(xx+yy-disc)*0.5));
            var axes=vec2f(u,v);
            var rotation=f32(atomicLoad(&words[o+66u]));
            if major<=65535.0&&minor>=1.0&&weight>0.0&&any(vec4f(gx,gy)!=vec4f(0.0)){
                axes=floor(vec2f(major,minor)+vec2f(0.5));
                if disc>0.00000001{
                    let degrees=atan2(2.0*xy,xx-yy)*28.64788975654116;
                    rotation=sign(degrees)*floor(abs(degrees)+0.5);
                }
            }

            atomicStore(&words[o+68u],u32(axes.x));
            atomicStore(&words[o+69u],u32(axes.y));
            atomicStore(&words[o+70u],u32((i32(rotation)+180)%180));
        }
        for(var k=0u;k<4u;k++){atomicStore(&words[o+k],moved[k]);}
        atomicStore(&words[o+41u],bitcast<u32>(shift.x));
        atomicStore(&words[o+42u],bitcast<u32>(shift.y));
    }
}
// Q30 rotations use the same coefficient table as the CPU scanline
// selector. All intermediate terms fit 128 bits at the allowed UHD
// dimensions and 16-bit ellipse axes, even without shaderInt64.
fn wide_neg(a:vec2u)->vec2u{return wide_add(~a,vec2u(1u,0u));}
fn signed_product(a:i32,b:i32)->vec2u{
    let p=wide_mul(u32(abs(a)),u32(abs(b)));
    return select(p,wide_neg(p),(a<0)!=(b<0));
}
fn wide_abs(a:vec2u)->vec2u{return select(a,wide_neg(a),(a.y&0x80000000u)!=0u);}
fn add_word(a:u32,b:u32,carry:u32)->vec2u{
    let lo=a+b;let hi=lo+carry;
    return vec2u(hi,select(0u,1u,lo<a||hi<lo));
}
fn quad_add(a:vec4u,b:vec4u)->vec4u{
    let p=add_word(a.x,b.x,0u);
    let q=add_word(a.y,b.y,p.y);
    let r=add_word(a.z,b.z,q.y);
    let s=add_word(a.w,b.w,r.y);
    return vec4u(p.x,q.x,r.x,s.x);
}
fn quad_square(a:vec2u)->vec4u{
    let p=wide_mul(a.x,a.x);let q=wide_mul(a.x,a.y);let r=wide_mul(a.y,a.y);
    let cross=vec4u(0u,q.x,q.y,0u);
    return quad_add(quad_add(vec4u(p,0u,0u),vec4u(0u,0u,r)),quad_add(cross,cross));
}
fn mul_word_carry(a:u32,b:u32,carry:u32)->vec2u{
    let p=wide_mul(a,b);
    let low=p.x+carry;
    return vec2u(low,p.y+select(0u,1u,low<p.x));
}
fn quad_mul_word(a:vec4u,b:u32)->vec4u{
    let p=mul_word_carry(a.x,b,0u);
    let q=mul_word_carry(a.y,b,p.y);
    let r=mul_word_carry(a.z,b,q.y);
    let s=mul_word_carry(a.w,b,r.y);
    return vec4u(p.x,q.x,r.x,s.x);
}
fn quad_le(a:vec4u,b:vec4u)->bool{
    if a.w!=b.w{return a.w<b.w;}
    if a.z!=b.z{return a.z<b.z;}
    if a.y!=b.y{return a.y<b.y;}
    return a.x<=b.x;
}
fn ellipse_center(o:u32)->vec2i{
    return vec2i(i32(atomicLoad(&words[o+52u])),i32(atomicLoad(&words[o+53u])))+
        vec2i(bitcast<i32>(atomicLoad(&words[o+41u])),bitcast<i32>(atomicLoad(&words[o+42u])));
}
fn ellipse_value(delta:vec2i,trig:vec2i,angle:u32,a2:u32,b2:u32)->vec4u{
    // Use an unscaled rational value for exact diagonal rotations and
    // circles. The matching limit below uses the same representation.
    if a2==b2{
        let d=vec2u(abs(delta));
        return vec4u(wide_add(wide_mul(d.x*d.x,a2),wide_mul(d.y*d.y,a2)),0u,0u);
    }
    if angle==45u||angle==135u{
        let u=u32(abs(select(delta.x+delta.y,delta.y-delta.x,angle==135u)));
        let v=u32(abs(select(delta.y-delta.x,delta.x+delta.y,angle==135u)));
        return quad_add(vec4u(wide_mul(u*u,b2),0u,0u),vec4u(wide_mul(v*v,a2),0u,0u));
    }
    let u=wide_abs(wide_add(signed_product(delta.x,trig.x),signed_product(delta.y,trig.y)));
    let v=wide_abs(wide_add(signed_product(-delta.x,trig.y),signed_product(delta.y,trig.x)));
    return quad_add(quad_mul_word(quad_square(u),b2),quad_mul_word(quad_square(v),a2));
}
fn ellipse_contains(id:vec2u,o:u32)->bool{
    let axes=vec2u(atomicLoad(&words[o+68u]),atomicLoad(&words[o+69u]));
    if any(axes==vec2u(0u)){return false;}
    let angle=atomicLoad(&words[o+70u]);
    if angle>180u{return false;}
    let delta=vec2i(id)-ellipse_center(o);
    let a2=axes.x*axes.x;let b2=axes.y*axes.y;
    if angle==0u||angle==90u||angle==180u{
        let d=vec2u(abs(select(delta,delta.yx,angle==90u)));
        let lhs=wide_add(wide_mul(d.x*d.x,b2),wide_mul(d.y*d.y,a2));
        let rhs=wide_mul(a2,b2);
        return lhs.y<rhs.y||(lhs.y==rhs.y&&lhs.x<=rhs.x);
    }
    let trig=bitcast<vec2i>(pq_table[30722u+angle].xy);
    let lhs=ellipse_value(delta,trig,angle,a2,b2);
    var rhs=quad_mul_word(quad_mul_word(vec4u(0u,268435456u,0u,0u),a2),b2);
    if a2==b2||angle==45u||angle==135u{
        rhs=vec4u(wide_mul(a2,b2),0u,0u);
        if a2!=b2{rhs=quad_add(rhs,rhs);}
    }
    return quad_le(lhs,rhs);
}
// Find exact inclusive-exclusive spans after the motion pass. The
// fixed-point ellipse value is a convex quadratic in x, so discrete
// minimum and both membership boundaries can be found by binary search.
// No approximate root or per-pixel transcendental arithmetic is needed.
@compute @workgroup_size(64)
fn ellipse_scanlines(@builtin(global_invocation_id) id:vec3u){
    let w=id.y+1u;let y=id.x;
    if w>=params.z||y>=params.y{return;}
    let o=w*STRIDE;
    let base=atomicLoad(&words[o+71u]);
    if base==0u{return;}
    let dest=base+2u*y;
    atomicStore(&words[dest],0u);atomicStore(&words[dest+1u],0u);
    let rect=rectangle(o);
    if y<rect.y||y>=rect.w||rect.x>=rect.z||rect.z>params.x||rect.w>params.y{return;}
    let axes=vec2u(atomicLoad(&words[o+68u]),atomicLoad(&words[o+69u]));
    let angle=atomicLoad(&words[o+70u]);
    if any(axes==vec2u(0u))||angle>180u{return;}
    let center=ellipse_center(o);
    let trig=bitcast<vec2i>(pq_table[30722u+angle].xy);
    let a2=axes.x*axes.x;let b2=axes.y*axes.y;
    let dy=i32(y)-center.y;
    var left=rect.x;var right=rect.z-1u;
    if angle==0u||angle==90u||angle==180u{
        left=u32(clamp(center.x,i32(left),i32(right)));
    }else{
        for(var step=0u;step<12u&&left<right;step++){
            let mid=left+(right-left)/2u;
            let p=ellipse_value(vec2i(i32(mid)-center.x,dy),trig,angle,a2,b2);
            let q=ellipse_value(vec2i(i32(mid+1u)-center.x,dy),trig,angle,a2,b2);
            if quad_le(p,q){right=mid;}else{left=mid+1u;}
        }
    }
    let middle=left;
    if !ellipse_contains(vec2u(middle,y),o){return;}
    left=rect.x;right=middle;
    for(var step=0u;step<12u&&left<right;step++){
        let mid=left+(right-left)/2u;
        if ellipse_contains(vec2u(mid,y),o){right=mid;}else{left=mid+1u;}
    }
    atomicStore(&words[dest],left);
    left=middle;right=rect.z;
    for(var step=0u;step<12u&&left+1u<right;step++){
        let mid=left+(right-left)/2u;
        if ellipse_contains(vec2u(mid,y),o){left=mid;}else{right=mid;}
    }
    atomicStore(&words[dest+1u],right);
}
@compute @workgroup_size(8,8)
fn collect(@builtin(global_invocation_id) id:vec3u){
    if any(id.xy>=params.xy){return;}
    let c=pq_bin(textureLoad(source,vec2i(id.xy),0).rgb);
    let bin=max(c.x,max(c.y,c.z));
    for(var w=0u;w<params.z;w++){
        let o=w*STRIDE;
        let lo=vec2u(atomicLoad(&words[o]),atomicLoad(&words[o+1u]));
        let hi=vec2u(atomicLoad(&words[o+2u]),atomicLoad(&words[o+3u]));
        var inside=true;
        let span_base=atomicLoad(&words[o+71u]);
        if span_base!=0u{
            let span=span_base+2u*id.y;
            inside=id.x>=atomicLoad(&words[span])&&id.x<atomicLoad(&words[span+1u]);
        }else if atomicLoad(&words[o+67u])==1u{
            // Every ellipse job allocates spans. Fail closed if its contract
            // is broken; never inline the wide selector into pixel kernels.
            inside=false;
        }
        if inside&&all(id.xy>=lo)&&all(id.xy<hi){
            atomicMax(&words[o+20u],c.x);
            atomicMax(&words[o+21u],c.y);
            atomicMax(&words[o+22u],c.z);
            atomicAdd(&hist[w*BINS+bin],1u);
        }
    }
}
var<workgroup> totals:array<u32,64>;
var<workgroup> starts:array<u32,64>;
var<workgroup> peaks:array<u32,64>;
var<workgroup> pixels:u32;
@compute @workgroup_size(64)
fn summarize(@builtin(workgroup_id) g:vec3u,@builtin(local_invocation_index) lane:u32){
    let w=g.x;
    if w>=params.z{return;}
    let o=w*STRIDE;
    let base=w*BINS;
    let begin=lane*1563u;
    let end=min(begin+1563u,BINS);
    var n=0u;var peak=0u;
    for(var i=begin;i<end;i++){
        let count=atomicLoad(&hist[base+i]);
        n+=count;
        if count>0u{peak=i;}
    }
    totals[lane]=n;peaks[lane]=peak;
    workgroupBarrier();
    if lane==0u{
        var all=0u;var highest=0u;
        for(var i=0u;i<64u;i++){
            starts[i]=all;all+=totals[i];highest=max(highest,peaks[i]);
        }
        pixels=all;
        atomicStore(&words[o+39u],all);
        atomicStore(&words[o+40u],atomicLoad(&hist[base+highest]));
    }
    workgroupBarrier();
    let count=atomicLoad(&words[o+4u]);
    for(var j=0u;j<count;j++){
        let pct=atomicLoad(&words[o+5u+j]);
        // ST 2094-40 section 8.5: 99 denotes the 99.98th percentile.
        // Quotient/remainder avoids overflowing the pixel-count product.
        let ordinary=(pixels/100u)*pct+((pixels%100u)*pct+99u)/100u;
        let rank=max(1u,select(ordinary,pixels-pixels/5000u,pct==99u));
        if rank>starts[lane]&&rank<=starts[lane]+n{
            var acc=starts[lane];
            for(var i=begin;i<end;i++){
                acc+=atomicLoad(&hist[base+i]);
                if acc>=rank{atomicStore(&words[o+24u+j],i);break;}
            }
        }
    }
}

// Section 10.4: exact area weights on a 5:1 proxy grid. Luminance
// uses 1e-9 fixed-point per-channel BT.2020 lookup contributions.
// Portable unsigned 64-bit arithmetic uses low/high u32 words.
fn wide_add(a:vec2u,b:vec2u)->vec2u{
    let low=a.x+b.x;
    return vec2u(low,a.y+b.y+select(0u,1u,low<a.x));
}
fn wide_mul(a:u32,b:u32)->vec2u{
    let a0=a&65535u;let a1=a>>16u;let b0=b&65535u;let b1=b>>16u;
    let w0=a0*b0;let t=a1*b0+(w0>>16u);
    let mid=a0*b1+(t&65535u);
    return vec2u((mid<<16u)|(w0&65535u),a1*b1+(t>>16u)+(mid>>16u));
}
// Quotient fits u32; denominator is at most UHD area. The remainder
// never exceeds twice that value, so the bitwise division cannot overflow.
fn wide_div_round(v:vec2u,d:u32)->u32{
    var rem=v.y;var q=0u;
    for(var i=32u;i>0u;i--){
        rem=(rem<<1u)|((v.x>>(i-1u))&1u);
        if rem>=d{rem-=d;q|=1u<<(i-1u);}
    }
    return q+select(0u,1u,rem>=(d+1u)/2u);
}
fn pq_luminance(v:vec3f)->u32{
    return min(1000000000u,pq_table[pq_index(v.x)+1u].x+
        pq_table[pq_index(v.y)+1u].y+pq_table[pq_index(v.z)+1u].z);
}
@compute @workgroup_size(8,8)
fn bright_proxy(@builtin(global_invocation_id) id:vec3u){
    let size=(params.xy+vec2u(4u))/5u;
    if any(id.xy>=size){return;}
    // Rational boundaries expressed in units of proxy dimensions.
    let lo=id.xy*params.xy;
    let hi=(id.xy+vec2u(1u))*params.xy;
    var total=vec2u(0u);
    for(var y=lo.y/size.y;y<min((hi.y+size.y-1u)/size.y,params.y);y++){
        for(var x=lo.x/size.x;x<min((hi.x+size.x-1u)/size.x,params.x);x++){
            let wx=min(hi.x,(x+1u)*size.x)-max(lo.x,x*size.x);
            let wy=min(hi.y,(y+1u)*size.y)-max(lo.y,y*size.y);
            let luminance=pq_luminance(textureLoad(source,vec2i(i32(x),i32(y)),0).rgb);
            total=wide_add(total,wide_mul(luminance,wx*wy));
        }
    }
    let luminance=min(1000000000u,wide_div_round(total,params.x*params.y));
    atomicStore(&hist[params.z*BINS+id.y*size.x+id.x],luminance);
}
var<workgroup> proxy_sums:array<vec2u,64>;
var<workgroup> proxy_peaks:array<u32,64>;
var<workgroup> proxy_peak:u32;
@compute @workgroup_size(64)
fn bright_reduce(@builtin(local_invocation_index) lane:u32){
    let size=(params.xy+vec2u(4u))/5u;
    let n=size.x*size.y;
    let base=params.z*BINS;
    var sum=vec2u(0u);var peak=0u;
    for(var i=lane;i<n;i+=64u){
        let v=atomicLoad(&hist[base+i]);
        sum=wide_add(sum,vec2u(v,0u));peak=max(peak,v);
    }
    proxy_sums[lane]=sum;proxy_peaks[lane]=peak;
    workgroupBarrier();
    if lane==0u{
        var all=vec2u(0u);var highest=0u;
        for(var j=0u;j<64u;j++){all=wide_add(all,proxy_sums[j]);highest=max(highest,proxy_peaks[j]);}
        proxy_peak=highest;
        atomicStore(&words[58u],all.x);atomicStore(&words[59u],all.y);
    }
    workgroupBarrier();
    var weight=vec2u(0u);
    for(var i=lane;i<n;i+=64u){
        let v=atomicLoad(&hist[base+i]);
        let eps=proxy_peak-v;
        // Exact weight numerator over 4e9: full through 1/255,
        // zero from 5/255. The middle expression is modulo 2^32;
        // its true nonnegative result fits u32.
        var w=0u;
        if eps<=3921568u{w=4000000000u;}
        else if eps<19607844u{w=705032704u-eps*255u;}
        weight=wide_add(weight,vec2u(w,0u));
    }
    proxy_sums[lane]=weight;
    workgroupBarrier();
    if lane==0u{
        var all=vec2u(0u);
        for(var j=0u;j<64u;j++){all=wide_add(all,proxy_sums[j]);}
        atomicStore(&words[61u],all.x);atomicStore(&words[62u],all.y);
        atomicStore(&words[63u],n);
    }
}

// Section 8.4: accumulate linear MaxRGB before quantization to the
// 1e-5 metadata grid. One 64-bit fixed-point sum per workgroup,
// represented by two 32-bit words for devices without shaderInt64.
var<workgroup> linear_values:array<u32,64>;
@compute @workgroup_size(8,8)
fn scene_linear_sum(@builtin(global_invocation_id) id:vec3u,@builtin(local_invocation_index) lane:u32){
    let valid=all(id.xy<params.xy);
    var linear=0u;
    if valid{
        linear=pq_linear_max(textureLoad(source,vec2i(id.xy),0).rgb);
    }
    for(var w=0u;w<params.z;w++){
        let o=w*STRIDE;
        let lo=vec2u(atomicLoad(&words[o]),atomicLoad(&words[o+1u]));
        let hi=vec2u(atomicLoad(&words[o+2u]),atomicLoad(&words[o+3u]));
        var value=0u;
        if valid{
        var inside=true;
        let span_base=atomicLoad(&words[o+71u]);
        if span_base!=0u{
            let span=span_base+2u*id.y;
            inside=id.x>=atomicLoad(&words[span])&&id.x<atomicLoad(&words[span+1u]);
        }else if atomicLoad(&words[o+67u])==1u{
            // Every ellipse job allocates spans. Fail closed if its contract
            // is broken; never inline the wide selector into pixel kernels.
            inside=false;
        }
        if inside&&all(id.xy>=lo)&&all(id.xy<hi){value=linear;}

        }
        linear_values[lane]=value;
        workgroupBarrier();
        if lane==0u{
            var low=0u;var high=0u;
            for(var i=0u;i<64u;i++){
                let v=linear_values[i];
                if low>0xffffffffu-v{high+=1u;}
                low+=v;
            }
            let old=atomicAdd(&words[o+72u],low);
            let carry=select(0u,1u,old>0xffffffffu-low);
            atomicAdd(&words[o+73u],high+carry);
        }
        workgroupBarrier();
    }
}
