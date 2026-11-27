// SPDX-License-Identifier: Apache-2.0
// Adapted from Open-SVPFlow crates/svpflow-wasm/src/smoother.rs,
// revision badc0debd33fe4034d0557bc736b9e0b7cfaf784.
// See OPEN-SVPFLOW-LICENSE and NOTICE.txt.
// Modified to borrow native streaming source storage and defer CPU-only
// subpixel expansion until necessary. Motion planning/rendering remains
// in the pinned upstream Engine and Job implementation.
use std::{collections::BTreeMap, ops::Range, sync::Arc};
use rayon::prelude::*;
use svpflow_core::smooth::{Frame,FrameMut,Plane,PlaneMut};
use svpflow_core::smooth_engine::{Engine,Job,Output,interleave_pel};
use svpflow_core::smooth_options::SourceInfo;
use svpflow1_vs::{SuperBuilder,SuperFrame};
use crate::Source;

type Expanded = Arc<[Vec<u8>;3]>;

pub(super) struct Smoother {
    engine: Engine,
    width: usize,
    height: usize,
    last: i32,
    vectors: BTreeMap<i32,Arc<Vec<u8>>>,
    // CPU-only interleaved pel data. Raw planes are borrowed from the
    // native queue instead of copied to a second source map.
    expanded: BTreeMap<i32,Expanded>,
}

impl Smoother {
    pub fn new(width:i32,height:i32,fps_num:i32,fps_den:i32,source_frames:i32,
               options:&str,vector_header:&[i32])->Result<Self,String>{
        if width<=0 || height<=0 || width%2!=0 || height%2!=0
            || fps_num<=0 || fps_den<=0 || source_frames<=0 {
            return Err("invalid source geometry/timing".into());
        }
        let header:Vec<u8>=vector_header.iter().flat_map(|v|v.to_le_bytes()).collect();
        let source=SourceInfo{fps_num:i64::from(fps_num),fps_den:i64::from(fps_den),width,height};
        Ok(Self{engine:Engine::new(options,&source,&header)?,
            width:width as usize,height:height as usize,last:source_frames-1,
            vectors:BTreeMap::new(),expanded:BTreeMap::new()})
    }

    pub fn plan(&self,frame:i32)->[i32;4]{
        let (n,vectors)=self.engine.plan(frame);
        let clamp=|k:i32|k.clamp(0,self.last);
        [clamp(n),clamp(n+1),clamp(vectors.start),
         if vectors.is_empty(){clamp(vectors.start)}else{clamp(vectors.end-1)+1}]
    }

    pub fn set_vectors(&mut self,k:i32,payload:Arc<Vec<u8>>){
        self.vectors.insert(k,payload);
    }

    pub fn uses_super(&self)->bool{self.engine.uses_super()}

    pub fn set_super(&mut self,k:i32,frame:&SuperFrame)->Result<(),String>{
        if !self.uses_super(){return Ok(());}
        let [y,u,v]=frame.planes();
        let pel=usize::try_from(self.engine.pel()).map_err(|_|"invalid pel")?;
        let (w,h)=(self.width,self.height);
        let expand=|data:&[u8],w,h|interleave_pel(data,w,h,pel)
            .ok_or_else(||String::from("super frame does not match vectors"));
        // Use the existing session pool on HD frames; no additional threads.
        let planes=if w*h>=320*240 && rayon::current_num_threads()>1 {
            let (y,(u,v))=rayon::join(||expand(y,w,h),
                ||rayon::join(||expand(u,w/2,h/2),||expand(v,w/2,h/2)));
            [y?,u?,v?]
        }else{[expand(y,w,h)?,expand(u,w/2,h/2)?,expand(v,w/2,h/2)?]};
        self.expanded.insert(k,Arc::new(planes));
        Ok(())
    }

    pub fn repeat_super(&mut self,k:i32,frame:&SuperFrame)->Result<(),String>{
        if let Some(previous)=self.expanded.get(&(k-1)).cloned(){
            self.expanded.insert(k,previous);
            Ok(())
        }else{self.set_super(k,frame)}
    }

    pub fn forget_before(&mut self,k:i32){
        self.vectors.retain(|&index,_|index>=k);
        self.expanded.retain(|&index,_|index>=k);
    }

    #[cfg(feature="vulkan")]
    pub fn prepare_gpu(&mut self,frame:i32)->Output{
        let last=self.last;
        let vectors=&self.vectors;
        match self.engine.prepare_gpu(frame,|k|vectors.get(&k.clamp(0,last)).map(|data|data.as_slice())){
            Output::Copy(k)=>Output::Copy(k.clamp(0,last)),
            output=>output,
        }
    }

    fn source<'a>(&self,frames:&'a BTreeMap<i32,Source>,k:i32)->Result<&'a [u8],String>{
        frames.get(&k.clamp(0,self.last)).map(|source|source.data.as_slice())
            .ok_or_else(||format!("source frame {} is missing",k.clamp(0,self.last)))
    }

    fn ensure_super(&mut self,frames:&BTreeMap<i32,Source>,builder:&SuperBuilder,k:i32)->Result<(),String>{
        let k=k.clamp(0,self.last);
        if self.uses_super() && !self.expanded.contains_key(&k){
            // GPU failure may require older source pairs. Rebuild exactly
            // from retained raw pictures, not an approximated subpixel image.
            let superframe=builder.build(self.source(frames,k)?)?;
            self.set_super(k,&superframe)?;
        }
        Ok(())
    }

    fn view<'a>(&'a self,frames:&'a BTreeMap<i32,Source>,k:i32)->Result<Frame<'a>,String>{
        let k=k.clamp(0,self.last);
        let (planes,scale)=if let Some(expanded)=self.expanded.get(&k){
            ([expanded[0].as_slice(),expanded[1].as_slice(),expanded[2].as_slice()],self.engine.pel() as usize)
        }else{
            if self.uses_super(){return Err("missing expanded source".into());}
            let raw=self.source(frames,k)?;
            let luma=self.width*self.height;
            if raw.len()!=luma*3/2{return Err("invalid source buffer length".into());}
            let (y,chroma)=raw.split_at(luma);
            let (u,v)=chroma.split_at(luma/4);
            ([y,u,v],1)
        };
        let plane=|data:&'a [u8],width:usize|Plane{data,pitch:width*scale,slack:0};
        Ok(Frame{y:plane(planes[0],self.width),u:plane(planes[1],self.width/2),v:plane(planes[2],self.width/2)})
    }

    pub fn render_into(&mut self,frame:i32,out:&mut [u8],frames:&BTreeMap<i32,Source>,
                       builder:&SuperBuilder)->Result<(),String>{
        if out.len()!=self.width*self.height*3/2{return Err("invalid output buffer length".into());}
        if !self.engine.cpu_supported(){return Err("overlap must be even with CPU rendering".into());}
        let last=self.last;
        let vectors=&self.vectors;
        match self.engine.prepare(frame,|k|vectors.get(&k.clamp(0,last)).map(|data|data.as_slice())){
            Output::Copy(k)=>{out.copy_from_slice(self.source(frames,k)?);Ok(())}
            Output::Render(job)=>{
                self.ensure_super(frames,builder,job.n)?;
                self.ensure_super(frames,builder,job.n+1)?;
                self.render_job(&job,self.view(frames,job.n)?,self.view(frames,job.n+1)?,out);
                Ok(())
            }
            Output::Gpu(_)=>Err("unexpected GPU job".into()),
        }
    }

    fn render_job(&self, job: &Job, current: Frame<'_>, next: Frame<'_>, out: &mut [u8]) {
        let blocks = job.blocks();
        let total = usize::try_from(blocks.end - blocks.start)
            .unwrap_or(1)
            .max(1);
        let parts = (rayon::current_num_threads() * 2).clamp(1, total);
        let bands: Vec<Range<i32>> = (0..parts)
            .map(|i| {
                let at = |i: usize| blocks.start + i32::try_from(i * total / parts).unwrap_or(0);
                at(i)..at(i + 1)
            })
            .filter(|band| !band.is_empty())
            .collect();
        let luma = self.width * self.height;
        let (y, chroma) = out.split_at_mut(luma);
        let (u, v) = chroma.split_at_mut(luma / 4);
        let mut rest = [y, u, v];
        let mut offsets = [0usize; 3];
        let pitches = [self.width, self.width / 2, self.width / 2];
        let mut work = Vec::with_capacity(bands.len());
        for band in bands {
            let [luma_rows, chroma_rows] = job.band_rows(band.clone());
            let rows = [&luma_rows, &chroma_rows, &chroma_rows];
            let slices: [&mut [u8]; 3] = std::array::from_fn(|p| {
                let start = rows[p].start * pitches[p];
                let end = (rows[p].end * pitches[p]).max(start);
                let plane = std::mem::take(&mut rest[p]);
                let (_, tail) =
                    plane.split_at_mut(start.saturating_sub(offsets[p]).min(plane.len()));
                let (band, after) = tail.split_at_mut((end - start).min(tail.len()));
                rest[p] = after;
                offsets[p] = end.max(offsets[p]);
                band
            });
            work.push((band, slices));
        }
        work.into_par_iter().for_each(|(band, [y, u, v])| {
            let mut dst = FrameMut {
                y: PlaneMut {
                    data: y,
                    pitch: pitches[0],
                },
                u: PlaneMut {
                    data: u,
                    pitch: pitches[1],
                },
                v: PlaneMut {
                    data: v,
                    pitch: pitches[2],
                },
            };
            job.render_band(&mut dst, current, next, band);
        });
    }
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn borrowed_and_lazy_sources_match_pinned_upstream_smoother(){
        use svpflow1_vs::Analyser;
        let pool=rayon::ThreadPoolBuilder::new().num_threads(2).build().unwrap();
        pool.install(||{
        for pel in [1,2,4]{
            let builder=SuperBuilder::new(96,66,pel).unwrap();
            let analyser=Analyser::new(&builder,16,16,2,3).unwrap();
            for algo in [13,21,23]{
                let options=format!("{{rate:{{abs:true,num:60,den:1}},algo:{algo},scene:{{mode:0,limits:{{zero:0,blocks:50}}}},gpu:false}}");
                let header=analyser.vector_header();
                let mut reference=svpflow_wasm::Smoother::new(96,66,24,1,8,&options,&header).unwrap();
                let mut lazy=Smoother::new(96,66,24,1,8,&options,&header).unwrap();
                let mut eager=Smoother::new(96,66,24,1,8,&options,&header).unwrap();
                let mut frames=BTreeMap::new();
                let mut previous=None;
                for n in 0..8{
                    let mut data=vec![128;builder.source_len()];
                    for y in 0..66{for x in 0..96{
                        data[y*96+x]=if (x+n as usize*3)%96<32{180}else{40};
                    }}
                    let sf=builder.build(&data).unwrap();
                    if let Some(prev)=previous.as_ref(){
                        let payload=analyser.analyse(prev,&sf).unwrap();
                        reference.set_vectors(n-1,&payload);
                        let payload=Arc::new(payload);
                        lazy.set_vectors(n-1,payload.clone());
                        eager.set_vectors(n-1,payload);
                    }
                    if reference.uses_super(){reference.set_super(n,&sf).unwrap();}
                    else{reference.set_source(n,&data).unwrap();}
                    eager.set_super(n,&sf).unwrap();
                    frames.insert(n,Source{data,pts:n as i64*1_000_000/24});
                    previous=Some(sf);
                }
                assert!(lazy.expanded.is_empty());
                let (mut a,mut b,mut c)=(vec![0;builder.source_len()],vec![0;builder.source_len()],vec![0;builder.source_len()]);
                for n in 0..reference.output_frames(){
                    assert_eq!(lazy.plan(n),reference.plan(n));
                    reference.render_into(n,&mut a).unwrap();
                    lazy.render_into(n,&mut b,&frames,&builder).unwrap();
                    eager.render_into(n,&mut c,&frames,&builder).unwrap();
                    assert_eq!(a,b,"lazy pel={pel} algo={algo} output={n}");
                    assert_eq!(a,c,"eager pel={pel} algo={algo} output={n}");
                }
                if pel==1{assert!(lazy.expanded.is_empty());}
                lazy.forget_before(8);
                assert!(lazy.expanded.is_empty()&&lazy.vectors.is_empty());
            }
        }
        });
    }

    #[test]
    fn repeat_super_shares_expanded_planes_and_vector_payload(){
        let builder=SuperBuilder::new(96,66,2).unwrap();
        let analyser=svpflow1_vs::Analyser::new(&builder,16,16,2,3).unwrap();
        let mut smoother=Smoother::new(96,66,24,1,8,
            "{rate:{abs:true,num:60,den:1},algo:23,gpu:false}",&analyser.vector_header()).unwrap();
        assert!(smoother.uses_super());
        let data=vec![128;builder.source_len()];
        let frame=builder.build(&data).unwrap();
        smoother.set_super(0,&frame).unwrap();
        smoother.repeat_super(1,&frame).unwrap();
        assert!(Arc::ptr_eq(&smoother.expanded[&0],&smoother.expanded[&1]));
        let payload=Arc::new(analyser.analyse(&frame,&frame).unwrap());
        smoother.set_vectors(0,payload.clone());
        smoother.set_vectors(1,payload);
        assert!(Arc::ptr_eq(&smoother.vectors[&0],&smoother.vectors[&1]));
        smoother.forget_before(1);
        assert_eq!(smoother.expanded.len(),1);
        assert_eq!(smoother.vectors.len(),1);
    }
}
