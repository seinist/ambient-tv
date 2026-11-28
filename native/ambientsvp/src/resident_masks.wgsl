// Coverage and SAD masks, adapted from the pinned Open-SVPFlow
// VectorField::cover_mask and ::sad_mask. Device-local scatter sums.
// SPDX-License-Identifier: Apache-2.0
@group(0) @binding(6) var<storage,read_write> field:array<atomic<i32>>;
@group(0) @binding(7) var<uniform> params:vec4u;
const BLOCK:i32=SVP_BLOCK;
const STEP:i32=SVP_STEP;
const AREA:i32=BLOCK*BLOCK;
const SAD_AREA:i32=SVP_SAD_AREA;
const DEPTH_SCALE:i32=SVP_DEPTH_SCALE;
fn count()->u32{return params.x*params.y;}
fn offset(i:u32)->u32{return i*4u;}
fn sad_base(n:u32)->u32{return 4u*n+3u;}
fn scatter_base(n:u32)->u32{return 6u*n+3u;}
fn mask_base(n:u32)->u32{return 7u*n+3u;}
fn floor_div(a:i32,b:i32)->i32{
    return (a-select(0,b-1,a<0))/b;
}
@compute @workgroup_size(64)
fn clear(@builtin(global_invocation_id) id:vec3u){
    let n=count();
    if id.x>=n{return;}
    let i=offset(scatter_base(n)+id.x);
    atomicStore(&field[i],0);atomicStore(&field[i+1u],0);
}
fn add_cell(cell:vec2i,dir:u32,value:i32){
    if any(cell<vec2i(0))||any(cell>=vec2i(params.xy)){return;}
    let n=count();
    let i=offset(scatter_base(n)+u32(cell.x)+u32(cell.y)*params.x)+dir;
    atomicAdd(&field[i],value);
}
@compute @workgroup_size(64)
fn scatter(@builtin(global_invocation_id) id:vec3u){
    let n=count();
    if id.x>=2u*n{return;}
    let dir=id.x/n;let j=id.x%n;
    let base=offset(id.x);
    let pel=max(atomicLoad(&field[base+3u]),1);
    let time=i32(select(params.z,256u-params.z,dir!=0u));
    let vx=atomicLoad(&field[base]);let vy=atomicLoad(&field[base+1u]);
    // Signed truncation toward zero is intentional, then floor cell division.
    let tx=time*vx/(pel*256);let ty=time*vy/(pel*256);
    let qx=floor_div(tx,STEP);let qy=floor_div(ty,STEP);
    let rx=STEP*(qx+1)-tx;let ry=STEP*(qy+1)-ty;
    let lx=BLOCK-rx;let ly=BLOCK-ry;
    let cell=vec2i(i32(j%params.x)+qx,i32(j/params.x)+qy);
    add_cell(cell,dir,rx*ry);
    add_cell(cell+vec2i(1,0),dir,lx*ry);
    add_cell(cell+vec2i(1,1),dir,lx*ly);
    add_cell(cell+vec2i(0,1),dir,rx*ly);
}
fn coverage(cell:vec2i,dir:u32)->i32{
    var total=0;
    for(var y=-1;y<=1;y++){for(var x=-1;x<=1;x++){
        let q=cell+vec2i(x,y);
        if all(q>=vec2i(0))&&all(q<vec2i(params.xy)){
            let i=offset(scatter_base(count())+u32(q.x)+u32(q.y)*params.x)+dir;
            total+=atomicLoad(&field[i]);
        }
    }}
    let covered=min(total>>3,AREA);
    return clamp((AREA-covered)*256/AREA,0,255);
}
fn sad_mask(index:u32)->i32{
    // area_sharp=1, scale=area/10000 for 8-bit. The high-depth
    // resident path extends the reference 8-bit metric in code units.
    let sad=max(0,atomicLoad(&field[offset(sad_base(count())+index)]));
    if SAD_AREA==0{return 0;}
    // App policies use 50 or 100; cancel the factor 4*area/10000
    // before multiplying to keep the arithmetic in signed 32 bits.
    let den=AREA*DEPTH_SCALE*select(50,25,SAD_AREA==100);
    if sad>=den{return 255;}
    return clamp(sad*255/den,0,255);
}
@compute @workgroup_size(64)
fn finish(@builtin(global_invocation_id) id:vec3u){
    let n=count();
    if id.x>=n{return;}
    let cell=vec2i(i32(id.x%params.x),i32(id.x/params.x));
    let m=offset(mask_base(n)+id.x);
    atomicStore(&field[m],sad_mask(id.x));
    atomicStore(&field[m+1u],coverage(cell,0u));
    atomicStore(&field[m+2u],coverage(cell,1u));
    atomicStore(&field[m+3u],sad_mask(id.x+n));
}
