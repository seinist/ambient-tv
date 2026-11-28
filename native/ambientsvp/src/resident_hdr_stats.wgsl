// PQ RGB statistics on the actual synthesized frame. All histograms
// stay on the GPU; only compact results are mapped by the caller.
// 100001 bins preserve the ST 2094-40 1e-5 quantization.
@group(0) @binding(0) var source:texture_2d<f32>;
@group(0) @binding(1) var<storage,read_write> hist:array<atomic<u32>>;
@group(0) @binding(2) var<storage,read_write> words:array<atomic<u32>>;
@group(0) @binding(3) var<uniform> params:vec4u;
const BINS:u32=100001u;
const STRIDE:u32=64u;
@group(0) @binding(3) var<storage,read> motion:array<vec4i>;
fn pq(v:vec3f)->vec3f{
    let p=pow(clamp(v,vec3f(0.0),vec3f(1.0)),vec3f(1.0/78.84375));
    return pow(max(p-vec3f(0.8359375),vec3f(0.0))/max(vec3f(18.8515625)-18.6875*p,vec3f(0.000001)),vec3f(1.0/0.1593017578125));
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
        let moved=vec4u(vec4i(rect)+vec4i(shift,shift));
        for(var k=0u;k<4u;k++){atomicStore(&words[o+k],moved[k]);}
        atomicStore(&words[o+41u],bitcast<u32>(shift.x));
        atomicStore(&words[o+42u],bitcast<u32>(shift.y));
    }
}
@compute @workgroup_size(8,8)
fn collect(@builtin(global_invocation_id) id:vec3u){
    if any(id.xy>=params.xy){return;}
    let c=vec3u(round(clamp(pq(textureLoad(source,vec2i(id.xy),0).rgb),vec3f(0.0),vec3f(1.0))*100000.0));
    let bin=max(c.x,max(c.y,c.z));
    for(var w=0u;w<params.z;w++){
        let o=w*STRIDE;
        let lo=vec2u(atomicLoad(&words[o]),atomicLoad(&words[o+1u]));
        let hi=vec2u(atomicLoad(&words[o+2u]),atomicLoad(&words[o+3u]));
        if all(id.xy>=lo)&&all(id.xy<hi){
            atomicMax(&words[o+20u],c.x);
            atomicMax(&words[o+21u],c.y);
            atomicMax(&words[o+22u],c.z);
            atomicAdd(&hist[w*BINS+bin],1u);
        }
    }
}
var<workgroup> totals:array<u32,64>;
var<workgroup> starts:array<u32,64>;
var<workgroup> sums:array<f32,64>;
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
    var n=0u;var sum=0.0;var peak=0u;
    for(var i=begin;i<end;i++){
        let count=atomicLoad(&hist[base+i]);
        n+=count;sum+=f32(i)*f32(count);
        if count>0u{peak=i;}
    }
    totals[lane]=n;sums[lane]=sum;peaks[lane]=peak;
    workgroupBarrier();
    if lane==0u{
        var all=0u;var acc=0.0;var highest=0u;
        for(var i=0u;i<64u;i++){
            starts[i]=all;all+=totals[i];acc+=sums[i];highest=max(highest,peaks[i]);
        }
        pixels=all;
        atomicStore(&words[o+23u],u32(round(acc/f32(max(all,1u)))));
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
