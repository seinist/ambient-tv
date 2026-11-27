// PQ RGB statistics on the actual synthesized frame. All histograms
// stay on the GPU; only compact results are mapped by the caller.
// 100001 bins preserve the ST 2094-40 1e-5 quantization.
@group(0) @binding(0) var source:texture_2d<f32>;
@group(0) @binding(1) var<storage,read_write> hist:array<atomic<u32>>;
@group(0) @binding(2) var<storage,read_write> words:array<atomic<u32>>;
@group(0) @binding(3) var<uniform> params:vec4u;
const BINS:u32=100001u;
const STRIDE:u32=44u;
fn pq(v:vec3f)->vec3f{
    let p=pow(clamp(v,vec3f(0.0),vec3f(1.0)),vec3f(1.0/78.84375));
    return pow(max(p-vec3f(0.8359375),vec3f(0.0))/max(vec3f(18.8515625)-19.6875*p,vec3f(0.000001)),vec3f(1.0/0.1593017578125));
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
        let rank=max(1u,(pixels*pct+99u)/100u);
        if rank>starts[lane]&&rank<=starts[lane]+n{
            var acc=starts[lane];
            for(var i=begin;i<end;i++){
                acc+=atomicLoad(&hist[base+i]);
                if acc>=rank{atomicStore(&words[o+24u+j],i);break;}
            }
        }
    }
}
