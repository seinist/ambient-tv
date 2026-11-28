//! Experimental bounded coarse-to-fine Vulkan motion search.
//! This is an independently orchestrated search, NOT upstream search parity.
//! Only the explicit native benchmark/test entry point may enable it.
use crate::motion::{Candidate,MotionCostKernel,SuperLevel,reference_super_cost};
use crate::motion_packet::{FinalVector,PacketLayout};
use crate::motion_select::{Choice,ScoredCandidate,Search,SelectionKernel};
use std::{collections::BTreeMap,time::{Duration,Instant}};
use svpflow1_vs::{SuperBuilder,SuperFrame};

const MAX_BATCH:usize=32_768;
const MAX_LEVELS:u32=4;
const MAX_PIXELS:u64=1280*720;
const MAX_BLOCKS:usize=16_384;
const MAX_TOTAL_CANDIDATES:usize=1_000_000;
const MAX_CACHED_TEXTURE_BYTES:u64=32*1024*1024;

#[derive(Clone,Copy,Debug)]
struct Grid{w:u32,h:u32,sx:u32,sy:u32,bw:u32,bh:u32}
impl Grid{
    fn new(w:u32,h:u32,bw:u32,bh:u32,ox:u32,oy:u32)->Option<Self>{
        if w<bw || h<bh || ox>=bw || oy>=bh{return None;}
        let (sx,sy)=(bw-ox,bh-oy);
        if sx%2!=0 || sy%2!=0{return None;}
        let (w,h)=((w-ox)/sx,(h-oy)/sy);
        if w==0 || h==0 || u64::from(w)*u64::from(h)>MAX_BLOCKS as u64{return None;}
        Some(Self{w,h,sx,sy,bw,bh})
    }
    fn count(self)->usize{(self.w*self.h) as usize}
    fn origin(self,i:usize)->(u32,u32){(i as u32%self.w*self.sx,i as u32/self.w*self.sy)}
    // x+2*y places left, top and top-right strictly before the
    // current block. Both directions can share a wave's GPU submission.
    fn wave(self,n:u32)->Vec<usize>{
        (0..self.h).filter_map(|y|n.checked_sub(2*y).filter(|&x|x<self.w)
            .map(|x|(y*self.w+x) as usize)).collect()
    }
    fn causal(self,i:usize,choices:&[Choice],coarse:[i32;2])->([i32;2],bool){
        let x=i as u32%self.w;let y=i as u32/self.w;
        let mut vectors=[coarse;3];let mut high=false;
        for (slot,cell) in [(0,x.checked_sub(1).map(|x|(x,y))),
            (1,y.checked_sub(1).map(|y|(x,y))),
            (2,y.checked_sub(1).filter(|_|x+1<self.w).map(|y|(x+1,y)))]{
            if let Some((x,y))=cell{
                let c=choices[(y*self.w+x) as usize];
                if c.winner>=0{
                    vectors[slot]=[c.dx,c.dy];
                    high|=c.sad>(self.bw*self.bh*12) as i32;
                }
            }
        }
        let median=|axis:usize|{let mut v=vectors.map(|v|v[axis]);v.sort_unstable();v[1]};
        ([median(0),median(1)],high)
    }
}
struct LevelField{grid:Grid,choices:Vec<Choice>}
impl LevelField{
    fn predict(&self,x:u32,y:u32,pel:u32)->[i32;2]{
        // Match upstream's four-neighbour interpolation topology and
        // overlap-dependent weights. Scale from coarse integer-pel units
        // into the finer grid's pel units; use wide arithmetic instead of
        // allowing signed 16-bit intermediate overflow.
        let g=self.grid;
        let i=(x/g.sx).min(2*g.w-1);let j=(y/g.sy).min(2*g.h-1);
        let (cx,cy)=(i/2,j/2);
        let (ox,oy)=(g.bw-g.sx,g.bh-g.sy);
        let nx=if i==0||i==2*g.w-1{cx}else if i%2==0{cx-1}else{cx+1};
        let ny=if j==0||j==2*g.h-1{cy}else if j%2==0{cy-1}else{cy+1};
        let at=|x:u32,y:u32|self.choices[(y*g.w+x) as usize];
        let v=[at(cx,cy),at(nx,cy),at(cx,ny),at(nx,ny)];
        let axis=|block:u32,overlap:u32,step:u32,odd:bool|{
            let first=3*i64::from(block)-i64::from(overlap)*if odd{2}else{4};
            let total=4*i64::from(step);
            (first.clamp(0,total),total)
        };
        let (ax,tx)=axis(g.bw,ox,g.sx,i%2!=0);
        let (ay,ty)=axis(g.bh,oy,g.sy,j%2!=0);
        let weights=[ax*ay,(tx-ax)*ay,ax*(ty-ay),(tx-ax)*(ty-ay)];
        let denom=tx*ty;
        let blend=|component:fn(Choice)->i32|{
            let n=v.iter().zip(weights).map(|(&c,w)|i64::from(component(c))*w).sum::<i64>()*2*i64::from(pel);
            // Arithmetic floor agrees with signed shifts for flat grids.
            n.div_euclid(denom).clamp(i64::from(i32::MIN),i64::from(i32::MAX)) as i32
        };
        [blend(|c|c.dx),blend(|c|c.dy)]
    }

    /// A dominant low-residual translation from the preceding level.
    /// Require support in at least a quarter of the grid; a single
    /// arbitrary border vector must not dictate a finer search.
    fn global(&self,pel:u32)->Option<[i32;2]>{
        let min_sad=self.choices.iter().map(|c|c.sad).min()?;
        let limit=min_sad.saturating_add((self.grid.bw*self.grid.bh*2) as i32);
        let mut votes:BTreeMap<(i32,i32),(usize,i64)>=BTreeMap::new();
        for c in self.choices.iter().filter(|c|c.sad<=limit){
            let vote=votes.entry((c.dx,c.dy)).or_default();
            vote.0+=1;
            vote.1+=i64::from(c.sad);
        }
        let (&(dx,dy),&(support,_))=votes.iter().max_by_key(|(mv,(n,sad))|
            (*n,-*sad,-(i64::from(mv.0).abs()+i64::from(mv.1).abs())))?;
        if support*4<self.choices.len(){return None;}
        let scale=2*pel as i32;
        Some([dx.saturating_mul(scale),dy.saturating_mul(scale)])
    }
}

/// Find a strictly majority displacement supported by zero full-block
/// residuals. Unlike the coarse predictor histogram, this is a strong
/// final-level consistency check, not a heuristic low-SAD vote.
fn exact_majority(field:&LevelField)->Option<[i32;2]>{
    let mut votes=BTreeMap::new();
    for c in field.choices.iter().filter(|c|c.sad==0){
        *votes.entry([c.dx,c.dy]).or_insert(0usize)+=1;
    }
    votes.into_iter().find(|(_,n)|*n>field.choices.len()/2).map(|(mv,_)|mv)
}

/// The pinned upstream search includes a centered half-block near image
/// boundaries. Full-block-only candidate geometry cannot recover a pan
/// that crosses the outer edge even when every visible central pixel
/// agrees. Recover only independently corroborated reciprocal motion,
/// and only after verifying a zero residual against actual source pixels.
/// Keep luma from the original full block for serialization.
fn recover_exact_translation(a:&SuperLevel<'_>,b:&SuperLevel<'_>,
                             fields:&mut [LevelField;2],deadline:Instant)->Result<usize,String>{
    let Some(f)=exact_majority(&fields[0])else{return Ok(0);};
    let Some(r)=exact_majority(&fields[1])else{return Ok(0);};
    if f!=[-r[0],-r[1]] || f==[0,0]{return Ok(0);}
    let mut recovered=0;
    for (direction,field) in fields.iter_mut().enumerate(){
        let mv=[f,r][direction];
        let grid=field.grid;
        for (i,c) in field.choices.iter_mut().enumerate(){
            if c.sad==0{continue;}
            if Instant::now()>=deadline{return Err("experimental motion deadline".into());}
            let (x,y)=grid.origin(i);
            let mut probe=Candidate{x,y,dx:mv[0],dy:mv[1],width:grid.bw,height:grid.bh,
                reverse:direction==0,satd:false};
            if !probe.valid(a.width,a.height,a.pel){
                // Centered half-blocks are supported for 16/32 layouts.
                // Do not sample beyond any phase or chroma plane.
                if grid.bw<16 || grid.bh<16{continue;}
                probe.x+=grid.bw/4;
                probe.y+=grid.bh/4;
                probe.width/=2;
                probe.height/=2;
            }
            if reference_super_cost(a,b,probe).is_some_and(|cost|cost.weighted(true)==0){
                *c=Choice{dx:mv[0],dy:mv[1],sad:0,cost:0,raw_sad:-1,winner:-1};
                recovered+=1;
            }
        }
    }
    Ok(recovered)
}

fn clamp_predictor(pred:[i32;2],x:u32,y:u32,w:u32,h:u32,bw:u32,bh:u32,pel:u32)->[i32;2]{
    let p=pel as i32;
    [pred[0].clamp(-(x as i32)*p,((w-x-bw) as i32)*p),
     pred[1].clamp(-(y as i32)*p,((h-y-bh) as i32)*p)]
}

fn candidate_offsets(out:&mut Vec<[i32;2]>,pred:[i32;2],global:Option<[i32;2]>,radius:i32,pel:u32,finest:bool){
    out.clear();
    out.push([0,0]);
    for dy in -radius..=radius{
        for dx in -radius..=radius{
            let v=[pred[0]+dx,pred[1]+dy];
            if v!=[0,0]{out.push(v);}
        }
    }
    let p=pel as i32;
    for dy in -2..=2{
        for dx in -2..=2{
            let v=[dx*p,dy*p];
            if v!=[0,0] && ((v[0]-pred[0]).abs()>radius || (v[1]-pred[1]).abs()>radius){out.push(v);}
        }
    }
    if let Some(v)=global{
        if !out.contains(&v){out.push(v);}
    }
    // A clipped top-level field can fail to predict a moderate pan.
    // Eight whole-pixel axial/diagonal anchors recover a four-pixel
    // displacement without a dense additional search window.
    if finest{
        for dy in [-4,0,4]{
            for dx in [-4,0,4]{
                let v=[dx*p,dy*p];
                if !out.contains(&v){out.push(v);}
            }
        }
    }
}

pub struct MotionSearch{
    metric:MotionCostKernel,
    selection:SelectionKernel,
    cached_index:Option<i32>,
    cached_next:BTreeMap<u32,wgpu::Texture>,
    readback:Option<wgpu::Buffer>,
    // Bounded by MAX_BATCH; reuse host-side descriptors between pairs.
    metrics:Vec<Candidate>,
    candidates:Vec<ScoredCandidate>,
    searches:Vec<Search>,
    offsets:Vec<[i32;2]>,
    #[cfg(test)]
    separate_directions:bool,
    #[cfg(test)]
    submissions:u64,
    #[cfg(test)]
    cache_hits:u64,
}
impl MotionSearch{
    pub fn new(device:&wgpu::Device)->Self{
        Self{metric:MotionCostKernel::new(device),selection:SelectionKernel::new(device),
            cached_index:None,cached_next:BTreeMap::new(),readback:None,
            metrics:Vec::new(),candidates:Vec::new(),searches:Vec::new(),offsets:Vec::new(),
            #[cfg(test)] separate_directions:false,#[cfg(test)] submissions:0,#[cfg(test)] cache_hits:0}
    }

    pub fn clear_sources(&mut self){
        self.cached_index=None;
        self.cached_next.clear();
    }

    pub fn supported(header:&[i32])->bool{
        PacketLayout::from_header(header).is_ok() && header[1]==3
            && matches!(header[13],0|1) && u64::from(header[7] as u32)*u64::from(header[8] as u32)<=MAX_PIXELS
            && Grid::new(header[7] as u32,header[8] as u32,header[2] as u32,header[3] as u32,
                header[9] as u32,header[10] as u32).is_some_and(|g|g.w==header[11] as u32 && g.h==header[12] as u32)
    }

    /// The frame pair is already prepared by the pinned SuperBuilder.
    /// Search coarse grids first, propagate the chosen displacement to
    /// the finer grid, and test a bounded local window plus a zero window.
    /// All costs and candidate reduction stay on the GPU; only winners
    /// cross the host boundary between levels/chunks. Each level uploads
    /// both sources once and reuses them for both directions.
    pub fn analyse(&mut self,device:&wgpu::Device,queue:&wgpu::Queue,builder:&SuperBuilder,
                   current:&SuperFrame,next:&SuperFrame,header:&[i32],
                   timeout:Duration,source_index:Option<i32>)->Result<Vec<u8>,String>{
        // Move out the previous cache: on every error the partial next
        // cache is dropped, rather than reused after a rejected input.
        let mut cached=std::mem::take(&mut self.cached_next);
        if source_index.is_none() || self.cached_index.take()!=source_index{cached.clear();}
        self.cached_index=None;
        let mut next_cache=BTreeMap::new();
        let mut cache_bytes=0u64;
        if !Self::supported(header){return Err("unsupported experimental search geometry".into());}
        let deadline=Instant::now()+timeout;
        let layout=PacketLayout::from_header(header)?;
        let (bw,bh,ox,oy)=(header[2] as u32,header[3] as u32,header[9] as u32,header[10] as u32);
        let mut levels=Vec::new();
        for level in 0..(builder.levels() as u32).min(MAX_LEVELS){
            let a=SuperLevel::new(builder,current,level).ok_or("invalid current super level")?;
            let b=SuperLevel::new(builder,next,level).ok_or("invalid next super level")?;
            let Some(grid)=Grid::new(a.width,a.height,bw,bh,ox,oy)else{break;};
            levels.push((level,a,b,grid));
        }
        if levels.is_empty(){return Err("no supported motion levels".into());}
        // Reject oversized jobs before uploading any frame or submitting
        // compute, rather than doing partial work and falling back late.
        let max_candidates=levels.iter().rev().enumerate().try_fold(0usize,|sum,(index,(_,_,_,grid))|
            sum.checked_add(grid.count().checked_mul(if index==0 {234}else{172})?))
            .ok_or("experimental motion candidate estimate overflow")?;
        if max_candidates>MAX_TOTAL_CANDIDATES{return Err("experimental motion candidate budget".into());}
        let mut previous:[Option<LevelField>;2]=[None,None];
        let mut total=0usize;
        for (level,a,b,grid) in levels.into_iter().rev(){
            if Instant::now()>=deadline{return Err("experimental motion deadline".into());}
            let mut encoder=device.create_command_encoder(&Default::default());
            let at=match cached.remove(&level){
                Some(t) if t.width()==a.width && t.height()==a.height*3/2
                    && t.depth_or_array_layers()==a.pel*a.pel=>{
                    #[cfg(test)] {self.cache_hits+=1;}
                    t
                }
                _=>a.upload(device,&mut encoder)?,
            };
            let bt=b.upload(device,&mut encoder)?;
            // Record the uploads in the first metric batch's encoder, so
            // upload and compute execute in order in one queue submission.
            let mut first_encoder=Some(encoder);
            // Causal predictors are finalized by the previous wave.
            // Both directional grids remain independent of one another.
            let count=grid.count();
            let radius=if previous[0].is_none(){4}else{2};
            let global:[Option<[i32;2]>;2]=std::array::from_fn(|d|previous[d].as_ref().and_then(|f|f.global(a.pel)));
            let max_radius=if radius==4{4}else{3};
            let max_per=((2*max_radius+1)*(2*max_radius+1)+36) as usize;
            let batch_limit=MAX_BATCH.min(device.limits().max_compute_workgroups_per_dimension as usize)
                .min((device.limits().max_storage_buffer_binding_size/32) as usize);
            if batch_limit<max_per{return Err("insufficient motion dispatch capacity".into());}
            let blocks=batch_limit/max_per;
            let offsets=&mut self.offsets;
            let metrics=&mut self.metrics;
            let candidates=&mut self.candidates;
            let searches=&mut self.searches;
            let unset=Choice{dx:0,dy:0,sad:0,cost:i32::MAX,raw_sad:-1,winner:-1};
            let mut choices:[Vec<Choice>;2]=std::array::from_fn(|_|vec![unset;count]);
            for wave in 0..grid.w+2*(grid.h-1){
            let indices=grid.wave(wave);
            let width=indices.len();
            let mut start=0;
            while start<2*width{
                let end=(start+blocks).min(2*width);
                #[cfg(test)]
                let end=if self.separate_directions && start<width{end.min(width)}else{end};
                metrics.clear();
                candidates.clear();
                searches.clear();
                metrics.reserve((end-start)*max_per);
                candidates.reserve((end-start)*max_per);
                searches.reserve(end-start);
                for i in start..end{
                    let direction=i/width;
                    let index=indices[i%width];
                    let field=&previous[direction];
                    let (x,y)=grid.origin(index);
                    let coarse=clamp_predictor(field.as_ref().map_or([0,0],|f|f.predict(x,y,a.pel)),
                        x,y,a.width,a.height,bw,bh,a.pel);
                    let (causal,high)=grid.causal(index,&choices[direction],coarse);
                    let pred=clamp_predictor(causal,x,y,a.width,a.height,bw,bh,a.pel);
                    let radius=if radius==2&&high{3}else{radius};
                    let global=global[direction].map(|v|clamp_predictor(v,x,y,a.width,a.height,bw,bh,a.pel));
                    let from=candidates.len() as u32;
                    candidate_offsets(offsets,pred,global,radius,a.pel,level==0);
                    if !offsets.contains(&coarse){offsets.push(coarse);}
                    for &[dx,dy] in offsets.iter(){
                        let c=Candidate{x,y,dx,dy,width:bw,height:bh,reverse:direction==0,satd:false};
                        if !c.valid(a.width,a.height,a.pel){continue;}
                        let index=metrics.len() as u32;
                        metrics.push(c);
                        candidates.push(ScoredCandidate{dx,dy,metric:index,raw:index});
                    }
                    let n=candidates.len() as u32-from;
                    if n==0{return Err("empty motion block".into());}
                    searches.push(Search{start:from,count:n,predictor:pred,lambda:(32/(a.pel*a.pel)) as i32,pnew:0,chroma:true,sad_gate:false,
                        initial:Choice{dx:0,dy:0,sad:0,cost:i32::MAX,raw_sad:-1,winner:-1}});
                }
                total=total.checked_add(metrics.len()).filter(|&n|n<=MAX_TOTAL_CANDIDATES)
                    .ok_or("experimental motion candidate budget")?;
                let mut encoder=first_encoder.take().unwrap_or_else(||device.create_command_encoder(&Default::default()));
                let costs=self.metric.encode(device,&mut encoder,&at,&bt,a.width,a.height,metrics)?;
                let selected=self.selection.encode(device,&mut encoder,&costs,searches,candidates)?;
                let batch=read_choices(device,queue,encoder,&selected,deadline,&mut self.readback)?;
                #[cfg(test)] {self.submissions+=1;}
                if batch.len()!=end-start || batch.iter().zip(searches.iter()).any(|(c,s)|{
                    if c.winner<s.start as i32 || c.winner>=(s.start+s.count) as i32
                        || c.sad<0 || c.sad>3*(bw*bh*255) as i32{return true;}
                    let selected=candidates[c.winner as usize];
                    c.dx!=selected.dx || c.dy!=selected.dy || c.raw_sad != -1
                }){
                    return Err("invalid GPU motion winner".into());
                }
                for (i,c) in batch.into_iter().enumerate(){
                    let job=start+i;
                    choices[job/width][indices[job%width]]=c;
                }
                start=end;
            }
            }
            if choices.iter().flatten().any(|c|c.winner<0){return Err("incomplete causal wavefront".into());}
            previous=choices.map(|choices|Some(LevelField{grid,choices}));
            // Source index belongs to the validated input timeline. Keep
            // only the next picture, and bound total retained texture bytes.
            let bytes=u64::from(bt.width())*u64::from(bt.height())*u64::from(bt.depth_or_array_layers());
            if source_index.is_some() && cache_bytes+bytes<=MAX_CACHED_TEXTURE_BYTES{
                cache_bytes+=bytes;
                next_cache.insert(level,bt);
            }
        }
        let [forward,backward]=previous;
        let forward=forward.ok_or("missing previous field")?;
        let backward=backward.ok_or("missing current field")?;
        let a=SuperLevel::new(builder,current,0).ok_or("invalid current final level")?;
        let b=SuperLevel::new(builder,next,0).ok_or("invalid next final level")?;
        let mut fields=[forward,backward];
        recover_exact_translation(&a,&b,&mut fields,deadline)?;
        let [forward,backward]=fields;
        let finish=|field:LevelField,frame:&SuperFrame|->Result<Vec<FinalVector>,String>{
            if field.grid.count()!=layout.count(){return Err("incomplete final grid".into());}
            let level=SuperLevel::new(builder,frame,0).ok_or("invalid final level")?;
            field.choices.into_iter().enumerate().map(|(i,choice)|{
                let (x,y)=field.grid.origin(i);
                let luma=level.block_luma(x,y,bw,bh).ok_or("invalid final block")?;
                Ok(FinalVector{choice,luma})
            }).collect()
        };
        let previous=finish(forward,next)?;
        let current=finish(backward,current)?;
        let packet=layout.pack(Some(&previous),&current)?;
        if let Some(index)=source_index.and_then(|i|i.checked_add(1)){
            self.cached_index=Some(index);
            self.cached_next=next_cache;
        }
        Ok(packet)
    }
}

fn read_choices(device:&wgpu::Device,queue:&wgpu::Queue,mut encoder:wgpu::CommandEncoder,
                output:&wgpu::Buffer,deadline:Instant,slot:&mut Option<wgpu::Buffer>)->Result<Vec<Choice>,String>{
    if Instant::now()>=deadline{return Err("experimental motion deadline".into());}
    // Retain one unmapped buffer across batches. On timeout/map error
    // ownership stays local so a possibly busy buffer is never reused.
    let readback=slot.take().filter(|b|b.size()>=output.size()).unwrap_or_else(||device.create_buffer(&wgpu::BufferDescriptor{
        label:Some("SVP motion winners readback"),size:output.size(),
        usage:wgpu::BufferUsages::COPY_DST|wgpu::BufferUsages::MAP_READ,mapped_at_creation:false,
    }));
    encoder.copy_buffer_to_buffer(output,0,&readback,0,output.size());
    queue.submit([encoder.finish()]);
    let (tx,rx)=std::sync::mpsc::sync_channel(1);
    readback.slice(..output.size()).map_async(wgpu::MapMode::Read,move|r|{let _=tx.send(r);});
    loop{
        device.poll(wgpu::Maintain::Poll);
        match rx.try_recv(){
            Ok(r)=>{r.map_err(|e|e.to_string())?;break;}
            Err(std::sync::mpsc::TryRecvError::Disconnected)=>return Err("motion map disconnected".into()),
            Err(_) if Instant::now()>=deadline=>{readback.unmap();return Err("experimental motion deadline".into());}
            Err(_)=>std::thread::sleep(Duration::from_micros(200)),
        }
    }
    let bytes=readback.slice(..output.size()).get_mapped_range();
    let result=bytes.chunks_exact(32).map(|raw|{
        let word=|i:usize|i32::from_le_bytes(raw[i*4..i*4+4].try_into().unwrap());
        Choice{dx:word(0),dy:word(1),sad:word(2),cost:word(3),raw_sad:word(4),winner:word(5)}
    }).collect();
    drop(bytes);readback.unmap();
    *slot=Some(readback);
    Ok(result)
}


#[cfg(test)]
mod tests{
    use super::*;
    use svpflow_core::metadata::{self,VectorRecord};
    use svpflow1_vs::Analyser;

    #[test]
    fn hierarchical_grid_and_predictor_bounds(){
        let g=Grid::new(96,66,16,16,4,4).unwrap();
        assert_eq!((g.w,g.h),(7,5));
        assert_eq!(g.origin(g.count()-1),(72,48));
        let choice=Choice{dx:3,dy:-2,sad:0,cost:0,raw_sad:-1,winner:0};
        let f=LevelField{grid:g,choices:vec![choice;g.count()]};
        assert_eq!(f.predict(90,64,2),[12,-8]);
        assert!(Grid::new(96,66,16,16,3,4).is_none());
        assert_eq!(f.global(2),Some([12,-8]));
        assert_eq!(clamp_predictor([-16,20],0,48,96,66,16,16,2),[0,4]);
        for pel in [1,2,4]{
            let mut offsets=Vec::new();
            candidate_offsets(&mut offsets,[0,0],Some([5,1]),2,pel,true);
            assert!(offsets.contains(&[-4*pel as i32,0]));
            assert!(offsets.contains(&[4*pel as i32,0]));
            assert!(offsets.contains(&[5,1]));
            assert_eq!(offsets[0],[0,0]);
            assert_eq!(offsets.len(),offsets.iter().collect::<std::collections::BTreeSet<_>>().len());
            assert!(offsets.len()<=60);
        }
    }

    #[test]
    fn weighted_coarse_predictor_respects_flat_and_overlapped_edges(){
        let choice=|dx,dy|Choice{dx,dy,sad:0,cost:0,raw_sad:-1,winner:0};
        for overlap in [0,4,8]{
            let g=Grid{w:2,h:2,sx:16-overlap,sy:16-overlap,bw:16,bh:16};
            let field=LevelField{grid:g,choices:vec![choice(0,0),choice(8,-8),choice(16,-16),choice(24,-24)]};
            assert_eq!(field.predict(0,0,1),[0,0]);
            assert_eq!(field.predict(g.sx*3,g.sy*3,1),[48,-48]);
            let p=field.predict(g.sx,g.sy,1);
            // At half-block overlap the odd-cell weight is exactly 100%.
            let expected=match overlap{0=>12,4=>8,8=>0,_=>unreachable!()};
            assert_eq!(p,[expected,-expected]);
        }
    }

    #[test]
    fn causal_wavefront_orders_every_predictor_and_adapts_to_residual(){
        let g=Grid::new(96,66,16,16,0,0).unwrap();
        let unset=Choice{dx:0,dy:0,sad:0,cost:i32::MAX,raw_sad:-1,winner:-1};
        let mut choices=vec![unset;g.count()];
        let mut seen=0;
        for wave in 0..g.w+2*(g.h-1){
            for i in g.wave(wave){
                let x=i as u32%g.w;let y=i as u32/g.w;
                if x>0{assert!(choices[i-1].winner>=0);}
                if y>0{
                    assert!(choices[i-g.w as usize].winner>=0);
                    if x+1<g.w{assert!(choices[i-g.w as usize+1].winner>=0);}
                }
                let (_,high)=g.causal(i,&choices,[4,-2]);
                if x>0||y>0{assert!(high);}
                choices[i]=Choice{dx:4,dy:-2,sad:4096,cost:4096,raw_sad:-1,winner:0};
                seen+=1;
            }
        }
        assert_eq!(seen,g.count());
    }

    #[test]
    fn exact_reciprocal_translation_recovers_only_verified_edge_blocks(){
        let builder=SuperBuilder::new(96,66,1).unwrap();
        let make=|shift:usize|{
            let mut raw=vec![128;builder.source_len()];
            for y in 0..66{for x in 0..96{
                let xx=(x+shift)%96;
                raw[y*96+x]=((xx*17+y*31+xx*y*3)%256) as u8;
            }}
            raw
        };
        let a=builder.build(&make(0)).unwrap();
        let b=builder.build(&make(4)).unwrap();
        let a=SuperLevel::new(&builder,&a,0).unwrap();
        let b=SuperLevel::new(&builder,&b,0).unwrap();
        let grid=Grid::new(96,66,16,16,0,0).unwrap();
        let choice=|dx|Choice{dx,dy:0,sad:0,cost:0,raw_sad:-1,winner:0};
        let bad=Choice{sad:500,cost:500,..choice(0)};
        let mut fields=[LevelField{grid,choices:vec![choice(4);grid.count()]},
            LevelField{grid,choices:vec![choice(-4);grid.count()]}];
        fields[1].choices[0]=bad;
        assert_eq!(recover_exact_translation(&a,&b,&mut fields,Instant::now()+Duration::from_secs(1)).unwrap(),1);
        assert_eq!((fields[1].choices[0].dx,fields[1].choices[0].sad),(-4,0));
        // No independently supported reciprocal shift: preserve result.
        fields[0].choices.fill(choice(3));
        fields[1].choices[0]=bad;
        assert_eq!(recover_exact_translation(&a,&b,&mut fields,Instant::now()+Duration::from_secs(1)).unwrap(),0);
        assert_eq!(fields[1].choices[0],bad);
    }

    #[test]
    #[ignore="requires Vulkan, including software Vulkan"]
    fn hierarchical_search_produces_complete_valid_fields(){
        let instance=wgpu::Instance::new(&wgpu::InstanceDescriptor{backends:wgpu::Backends::VULKAN,..Default::default()});
        let adapter=pollster::block_on(instance.request_adapter(&Default::default())).unwrap();
        let (device,queue)=pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor{
            label:Some("SVP hierarchical search regression"),required_features:wgpu::Features::empty(),
            required_limits:wgpu::Limits::default().using_resolution(adapter.limits()),
            memory_hints:wgpu::MemoryHints::MemoryUsage,
        },None)).unwrap();
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let mut search=MotionSearch::new(&device);
        for pel in [1,2,4]{
            let builder=SuperBuilder::new(192,128,pel).unwrap();
            let analyser=Analyser::new(&builder,16,16,2,3).unwrap();
            let header=analyser.vector_header();
            assert!(MotionSearch::supported(&header));
            let make=|shift:usize|{
                let mut raw=vec![128;builder.source_len()];
                for y in 0..128{for x in 0..192{
                    let xx=(x+shift)%192;
                    raw[y*192+x]=((xx*17+y*31+xx*y*3)%256) as u8;
                }}
                raw
            };
            let a=make(0);
            let b=make(2);
            let asf=builder.build(&a).unwrap();
            let bsf=builder.build(&b).unwrap();
            let start=search.submissions;
            let packet=search.analyse(&device,&queue,&builder,&asf,&bsf,&header,Duration::from_secs(20),None).unwrap();
            let combined=search.submissions-start;
            search.separate_directions=true;
            let start=search.submissions;
            let second=search.analyse(&device,&queue,&builder,&asf,&bsf,&header,Duration::from_secs(20),None).unwrap();
            let separated=search.submissions-start;
            search.separate_directions=false;
            assert_eq!(packet,second,"combined vs separate directions pel={pel}");
            assert!(combined<separated,"expected fewer GPU readbacks: {combined} vs {separated}");
            assert!(search.readback.is_some());
            // Adjacent source pairs may reuse the prior next image only
            // under the same timeline; repeat with an uncached reference.
            let cached=search.analyse(&device,&queue,&builder,&asf,&bsf,&header,Duration::from_secs(20),Some(0)).unwrap();
            assert_eq!(cached,packet);
            let hits=search.cache_hits;
            let reverse_cached=search.analyse(&device,&queue,&builder,&bsf,&asf,&header,Duration::from_secs(20),Some(1)).unwrap();
            assert!(search.cache_hits>hits);
            search.clear_sources();
            let reverse_uncached=search.analyse(&device,&queue,&builder,&bsf,&asf,&header,Duration::from_secs(20),None).unwrap();
            assert_eq!(reverse_cached,reverse_uncached);
            assert!(search.cached_next.is_empty());
            let bytes:Vec<u8>=header.iter().flat_map(|x|x.to_le_bytes()).collect();
            let VectorRecord::Ready(data)=metadata::vector_data(&bytes)else{panic!("header");};
            let decoded=metadata::decode_vectors(&packet,3,data.grid,3).unwrap();
            assert_eq!(decoded.previous.as_ref().unwrap().len(),(data.grid.width*data.grid.height) as usize);
            assert_eq!(decoded.current.as_ref().unwrap().len(),(data.grid.width*data.grid.height) as usize);
            assert!(decoded.current.as_ref().unwrap().iter().any(|v|v.dx!=0||v.dy!=0));
            let layout=PacketLayout::from_header(&header).unwrap();
            assert_eq!(packet.len(),72+layout.count()*16);
            assert!(search.analyse(&device,&queue,&builder,&asf,&bsf,&header,Duration::ZERO,None).is_err());
        }
        assert!(pollster::block_on(device.pop_error_scope()).is_none());
    }
}
