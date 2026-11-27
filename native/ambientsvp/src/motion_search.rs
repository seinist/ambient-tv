//! Experimental bounded coarse-to-fine Vulkan motion search.
//! This is an independently orchestrated search, NOT upstream search parity.
//! Only the explicit native benchmark/test entry point may enable it.
use crate::motion::{Candidate,MotionCostKernel,SuperLevel};
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
    fn nearest(self,x:u32,y:u32)->usize{
        let bx=(x/self.sx).min(self.w-1);
        let by=(y/self.sy).min(self.h-1);
        (by*self.w+bx) as usize
    }
}
struct LevelField{grid:Grid,choices:Vec<Choice>}
impl LevelField{
    fn predict(&self,x:u32,y:u32,pel:u32)->[i32;2]{
        let i=self.grid.nearest(x/2,y/2);
        let c=self.choices[i];
        let scale=2*pel as i32;
        [c.dx.saturating_mul(scale),c.dy.saturating_mul(scale)]
    }
}

pub struct MotionSearch{
    metric:MotionCostKernel,
    selection:SelectionKernel,
    cached_index:Option<i32>,
    cached_next:BTreeMap<u32,wgpu::Texture>,
    #[cfg(test)]
    cache_hits:u64,
}
impl MotionSearch{
    pub fn new(device:&wgpu::Device)->Self{
        Self{metric:MotionCostKernel::new(device),selection:SelectionKernel::new(device),
            cached_index:None,cached_next:BTreeMap::new(),#[cfg(test)] cache_hits:0}
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
            sum.checked_add(grid.count().checked_mul(if index==0 {212}else{100})?))
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
            queue.submit([encoder.finish()]);
            for (direction,field) in previous.iter_mut().enumerate(){
                let reverse=direction==0; // upstream previous region: next -> current
                let mut choices=Vec::with_capacity(grid.count());
                // Each block contributes at most 81 + 25 candidate metrics.
                let radius=if field.is_none(){4}else{2};
                let max_per=((2*radius+1)*(2*radius+1)+25) as usize;
                let batch_limit=MAX_BATCH.min(device.limits().max_compute_workgroups_per_dimension as usize)
                    .min((device.limits().max_storage_buffer_binding_size/32) as usize);
                if batch_limit<max_per{return Err("insufficient motion dispatch capacity".into());}
                let blocks=batch_limit/max_per;
                for start in (0..grid.count()).step_by(blocks){
                    let end=(start+blocks).min(grid.count());
                    let mut metrics=Vec::with_capacity((end-start)*max_per);
                    let mut candidates=Vec::with_capacity(metrics.capacity());
                    let mut searches=Vec::with_capacity(end-start);
                    for i in start..end{
                        let (x,y)=grid.origin(i);
                        let pred=field.as_ref().map_or([0,0],|f|f.predict(x,y,a.pel));
                        let from=candidates.len() as u32;
                        let mut add=|dx:i32,dy:i32|{
                            let c=Candidate{x,y,dx,dy,width:bw,height:bh,reverse,satd:false};
                            if !c.valid(a.width,a.height,a.pel) {return;}
                            let index=metrics.len() as u32;
                            metrics.push(c);
                            candidates.push(ScoredCandidate{dx,dy,metric:index,raw:index});
                        };
                        // Zero candidate first for deterministic ties; then
                        // the propagated predictor and its bounded neighborhood.
                        add(0,0);
                        for dy in -radius..=radius{
                            for dx in -radius..=radius{
                                let (vx,vy)=(pred[0]+dx,pred[1]+dy);
                                if (vx,vy)!=(0,0){add(vx,vy);}
                            }
                        }
                        // At pel=2/4, the local radius is fractional.
                        // Also consider integer-phase anchors at +/-2
                        // pixels around zero, not just +/-2 pel units.
                        if pred!=[0,0] || a.pel>1{
                            for dy in -2..=2{
                                for dx in -2..=2{
                                    let (dx,dy)=(dx*a.pel as i32,dy*a.pel as i32);
                                    if (dx,dy)!=(0,0) && ((dx-pred[0]).abs()>radius || (dy-pred[1]).abs()>radius){add(dx,dy);}
                                }
                            }
                        }
                        let count=candidates.len() as u32-from;
                        if count==0{return Err("empty motion block".into());}
                        searches.push(Search{start:from,count,predictor:pred,lambda:32,pnew:0,chroma:true,sad_gate:false,
                            initial:Choice{dx:0,dy:0,sad:0,cost:i32::MAX,raw_sad:-1,winner:-1}});
                    }
                    total=total.checked_add(metrics.len()).filter(|&n|n<=MAX_TOTAL_CANDIDATES)
                        .ok_or("experimental motion candidate budget")?;
                    let mut encoder=device.create_command_encoder(&Default::default());
                    let costs=self.metric.encode(device,&mut encoder,&at,&bt,a.width,a.height,&metrics)?;
                    let selected=self.selection.encode(device,&mut encoder,&costs,&searches,&candidates)?;
                    let batch=read_choices(device,queue,encoder,&selected,deadline)?;
                    if batch.len()!=end-start || batch.iter().zip(&searches).any(|(c,s)|{
                        if c.winner<s.start as i32 || c.winner>=(s.start+s.count) as i32
                            || c.sad<0 || c.sad>3*(bw*bh*255) as i32{return true;}
                        let selected=candidates[c.winner as usize];
                        c.dx!=selected.dx || c.dy!=selected.dy || c.raw_sad != -1
                    }){
                        return Err("invalid GPU motion winner".into());
                    }
                    choices.extend(batch);
                }
                *field=Some(LevelField{grid,choices});
            }
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
                output:&wgpu::Buffer,deadline:Instant)->Result<Vec<Choice>,String>{
    if Instant::now()>=deadline{return Err("experimental motion deadline".into());}
    let readback=device.create_buffer(&wgpu::BufferDescriptor{
        label:Some("SVP motion winners readback"),size:output.size(),
        usage:wgpu::BufferUsages::COPY_DST|wgpu::BufferUsages::MAP_READ,mapped_at_creation:false,
    });
    encoder.copy_buffer_to_buffer(output,0,&readback,0,output.size());
    queue.submit([encoder.finish()]);
    let (tx,rx)=std::sync::mpsc::sync_channel(1);
    readback.slice(..).map_async(wgpu::MapMode::Read,move|r|{let _=tx.send(r);});
    loop{
        device.poll(wgpu::Maintain::Poll);
        match rx.try_recv(){
            Ok(r)=>{r.map_err(|e|e.to_string())?;break;}
            Err(std::sync::mpsc::TryRecvError::Disconnected)=>return Err("motion map disconnected".into()),
            Err(_) if Instant::now()>=deadline=>{readback.unmap();return Err("experimental motion deadline".into());}
            Err(_)=>std::thread::sleep(Duration::from_micros(200)),
        }
    }
    let bytes=readback.slice(..).get_mapped_range();
    let result=bytes.chunks_exact(32).map(|raw|{
        let word=|i:usize|i32::from_le_bytes(raw[i*4..i*4+4].try_into().unwrap());
        Choice{dx:word(0),dy:word(1),sad:word(2),cost:word(3),raw_sad:word(4),winner:word(5)}
    }).collect();
    drop(bytes);readback.unmap();
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
            let packet=search.analyse(&device,&queue,&builder,&asf,&bsf,&header,Duration::from_secs(20),None).unwrap();
            let second=search.analyse(&device,&queue,&builder,&asf,&bsf,&header,Duration::from_secs(20),None).unwrap();
            assert_eq!(packet,second,"deterministic pel={pel}");
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
