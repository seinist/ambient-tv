//! Optional metadata-only readback. The histogram and all pixels remain
//! GPU resident. The owner pair's actual submission fence governs lifetime.
use super::{Core,Pipeline,Result,err,spirv};
use ash::vk;
use std::sync::Arc;
const BINS:u64=100001;
const STRIDE:usize=72;
const SHADER:&str=include_str!("resident_hdr_stats.wgsl");
const UNIFORM:&str="@group(0) @binding(3) var<uniform> params:vec4u;";
#[repr(C)]
#[derive(Clone,Copy,Debug,Default)]
pub struct HdrWindow{
    pub rect:[u32;4],pub count:u32,pub percentages:[u32;15],
}
#[repr(C)]
#[derive(Clone,Copy,Debug,Default)]
pub struct HdrResult{
    pub maxscl:[u32;3],pub average:u32,pub percentiles:[u32;15],
    pub pixels:u32,pub brightest:u32,
}
#[repr(C)]
#[derive(Clone,Copy,Debug,Default)]
pub struct HdrTrack{pub a:[u32;4],pub b:[u32;4],pub ellipse:[u32;2]}
pub(super) struct StatsPipelines{collect:Pipeline,reduce:Pipeline,track:Pipeline,proxy:Pipeline,bright:Pipeline}
impl StatsPipelines{
    pub(super) fn new(core:&Core)->Result<Self>{
        let types=[vk::DescriptorType::SAMPLED_IMAGE,vk::DescriptorType::STORAGE_BUFFER,vk::DescriptorType::STORAGE_BUFFER,vk::DescriptorType::STORAGE_BUFFER];
        Ok(Self{collect:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"collect")?,c"collect")?,
            reduce:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"summarize")?,c"summarize")?,
            track:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"track")?,c"track")?,
            proxy:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"bright_proxy")?,c"bright_proxy")?,
            bright:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"bright_reduce")?,c"bright_reduce")?})
    }
}
struct Buffer{device:ash::Device,buffer:vk::Buffer,memory:vk::DeviceMemory,size:u64}
impl Drop for Buffer{
    fn drop(&mut self){unsafe{
        self.device.destroy_buffer(self.buffer,None);
        self.device.free_memory(self.memory,None);
    }}
}
impl Buffer{
    fn new(core:&Core,size:u64,usage:vk::BufferUsageFlags,flags:vk::MemoryPropertyFlags)->Result<Self>{
        let d=&core.device;
        let mut b=Self{device:d.clone(),buffer:vk::Buffer::null(),memory:vk::DeviceMemory::null(),size};
        unsafe{
            b.buffer=d.create_buffer(&vk::BufferCreateInfo::default().size(size).usage(usage)
                .sharing_mode(vk::SharingMode::EXCLUSIVE),None).map_err(err)?;
            let req=d.get_buffer_memory_requirements(b.buffer);
            let index=(0..core.memory.memory_type_count).find(|&i|req.memory_type_bits&(1<<i)!=0 &&
                core.memory.memory_types[i as usize].property_flags.contains(flags)).ok_or("HDR statistics memory type")?;
            b.memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size).memory_type_index(index),None).map_err(err)?;
            d.bind_buffer_memory(b.buffer,b.memory,0).map_err(err)?;
        }
        Ok(b)
    }
    fn map(&self)->Result<*mut u32>{unsafe{self.device.map_memory(self.memory,0,self.size,vk::MemoryMapFlags::empty()).map(|p|p.cast()).map_err(err)}}
}
pub(super) struct StatsJob{
    device:ash::Device,pool:vk::DescriptorPool,
    hist:Buffer,summary:Buffer,scene_hist:Option<Buffer>,windows:Vec<HdrWindow>,
    pipelines:Arc<StatsPipelines>,set:vk::DescriptorSet,
    tracking:bool,affine:bool,width:u32,height:u32,step:u32,
}
impl Drop for StatsJob{
    fn drop(&mut self){unsafe{self.device.destroy_descriptor_pool(self.pool,None);}}
}
impl StatsJob{
    pub(super) fn new(core:&Core,view:vk::ImageView,field:vk::Buffer,windows:&[HdrWindow],
        tracking:Option<(&[HdrTrack],f32,bool)>, ellipses:Option<&[[u32;3]]>, scene:bool)->Result<Self>{
        if !core.stats_supported || !(1..=3).contains(&windows.len()) ||
            windows[0].rect!=[0,0,core.width,core.height] ||
            windows.iter().any(|w|w.rect[0]>=w.rect[2]||w.rect[1]>=w.rect[3]||
                w.rect[2]>core.width||w.rect[3]>core.height||w.count>15||
                w.percentages[..w.count.min(15) as usize].iter().any(|&p|p>100)||
                w.percentages[..w.count.min(15) as usize].windows(2).any(|p|p[0]>=p[1])){
            return Err("unsupported HDR statistics geometry or percentile list".into());
        }
        let valid_rect=|r:[u32;4]|r[0]<r[2]&&r[1]<r[3]&&r[2]<=core.width&&r[3]<=core.height;
        if let Some((tracks,phase,_))=tracking{
            if tracks.len()!=windows.len()||!phase.is_finite()||!(0.0..=1.0).contains(&phase)||
                tracks.iter().skip(1).any(|t|!valid_rect(t.a)||!valid_rect(t.b)||
                    t.ellipse[0]>=core.width||t.ellipse[1]>=core.height){return Err("invalid HDR tracking geometry".into());}
        }
        if let Some(e)=ellipses{
            if !tracking.is_some_and(|t|t.2)||e.len()!=windows.len()||
                e.iter().skip(1).any(|v|v[0]==0||v[0]>65535||v[1]==0||v[1]>v[0]||v[2]>180){
                return Err("invalid HDR ellipse axes".into());
            }
        }
        let pipelines={
            let mut slot=core.stats.lock().unwrap_or_else(|e|e.into_inner());
            if slot.is_none(){*slot=Some(Arc::new(StatsPipelines::new(core)?));}
            slot.as_ref().unwrap().clone()
        };
        if scene&&windows.len()!=1{return Err("scene statistics require version 1 global window".into());}
        let proxy_count=if scene{u64::from(core.width.div_ceil(5))*u64::from(core.height.div_ceil(5))}else{0};
        let hist=Buffer::new(core,(BINS*windows.len() as u64+proxy_count)*4,
            vk::BufferUsageFlags::STORAGE_BUFFER|vk::BufferUsageFlags::TRANSFER_DST|vk::BufferUsageFlags::TRANSFER_SRC,vk::MemoryPropertyFlags::DEVICE_LOCAL)?;
        let scene_hist=if scene{Some(Buffer::new(core,BINS*4,vk::BufferUsageFlags::TRANSFER_DST,
            vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)?)}else{None};
        let summary=Buffer::new(core,(STRIDE*4*windows.len()) as u64,vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)?;
        let mut s=Self{device:core.device.clone(),pool:vk::DescriptorPool::null(),hist,summary,scene_hist,windows:windows.to_vec(),
            pipelines,set:vk::DescriptorSet::null(),tracking:tracking.is_some(),affine:tracking.is_some_and(|t|t.2),width:core.width,height:core.height,step:core.tuning.map_or(16,super::ResidentTuning::step)};
        let mut config=vec![0u32;STRIDE*windows.len()];
        for (i,w) in windows.iter().enumerate(){
            let row=&mut config[i*STRIDE..(i+1)*STRIDE];
            row[..4].copy_from_slice(&w.rect);row[4]=w.count;row[5..20].copy_from_slice(&w.percentages);
            if let Some((tracks,phase,_))=tracking{if i>0{
                let t=tracks[i];row[43]=phase.to_bits();row[44..48].copy_from_slice(&t.a);
                row[48..52].copy_from_slice(&t.b);row[52..54].copy_from_slice(&t.ellipse);row[54]=1;row[55]=u32::from(s.affine);
                if let Some(e)=ellipses{row[64..67].copy_from_slice(&e[i]);row[67]=1;row[68..71].copy_from_slice(&e[i]);}
            }}
        }
        unsafe{
            let mapped=s.summary.map()?;
            std::ptr::copy_nonoverlapping(config.as_ptr(),mapped,config.len());
            s.device.unmap_memory(s.summary.memory);
            let sizes=[
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::SAMPLED_IMAGE).descriptor_count(1),
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::STORAGE_BUFFER).descriptor_count(3)];
            s.pool=s.device.create_descriptor_pool(&vk::DescriptorPoolCreateInfo::default().max_sets(1).pool_sizes(&sizes),None).map_err(err)?;
            s.set=s.device.allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(s.pool)
                .set_layouts(&[s.pipelines.collect.set])).map_err(err)?[0];
            let image=[vk::DescriptorImageInfo::default().image_view(view).image_layout(vk::ImageLayout::GENERAL)];
            let h=[vk::DescriptorBufferInfo::default().buffer(s.hist.buffer).range(s.hist.size)];
            let o=[vk::DescriptorBufferInfo::default().buffer(s.summary.buffer).range(s.summary.size)];
            let motion=[vk::DescriptorBufferInfo::default().buffer(field).range(core.field_size)];
            s.device.update_descriptor_sets(&[
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(0).descriptor_type(vk::DescriptorType::SAMPLED_IMAGE).image_info(&image),
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(1).descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&h),
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(2).descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&o),
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(3).descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&motion),
            ],&[]);
        }
        Ok(s)
    }
    pub(super) unsafe fn record(&self,cmd:vk::CommandBuffer,w:u32,h:u32){
        let d=&self.device;
        let params=[w,h,self.windows.len() as u32,self.step];
        unsafe{
            d.cmd_fill_buffer(cmd,self.hist.buffer,0,self.hist.size,0);
            let read=vk::AccessFlags::SHADER_READ|vk::AccessFlags::SHADER_WRITE;
            // Image storage writes from synthesis, transfer clear and
            // host-written window configuration must all be visible.
            let before=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE|
                vk::AccessFlags::TRANSFER_WRITE|vk::AccessFlags::HOST_WRITE).dst_access_mask(read)];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER|vk::PipelineStageFlags::TRANSFER|
                vk::PipelineStageFlags::HOST,vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&before,&[],&[]);
            let between=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE).dst_access_mask(read)];
            if self.tracking{
                self.pipelines.track.dispatch(cmd,self.set,params,self.windows.len() as u32,1);
                d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),&between,&[],&[]);
            }
            self.pipelines.collect.dispatch(cmd,self.set,params,w.div_ceil(8),h.div_ceil(8));
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),&between,&[],&[]);
            self.pipelines.reduce.dispatch(cmd,self.set,params,self.windows.len() as u32,1);
            if let Some(hist)=&self.scene_hist{
                self.pipelines.proxy.dispatch(cmd,self.set,params,w.div_ceil(5).div_ceil(8),h.div_ceil(5).div_ceil(8));
                d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),&between,&[],&[]);
                self.pipelines.bright.dispatch(cmd,self.set,params,1,1);
                let transfer=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE)
                    .dst_access_mask(vk::AccessFlags::TRANSFER_READ)];
                d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER,
                    vk::DependencyFlags::empty(),&transfer,&[],&[]);
                d.cmd_copy_buffer(cmd,self.hist.buffer,hist.buffer,&[vk::BufferCopy::default().size(BINS*4)]);
                let host=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::TRANSFER_WRITE).dst_access_mask(vk::AccessFlags::HOST_READ)];
                d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                    vk::DependencyFlags::empty(),&host,&[],&[]);
            }
            let after=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE).dst_access_mask(vk::AccessFlags::HOST_READ)];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&after,&[],&[]);
        }
    }
    pub(super) fn scene_frame(&self)->Result<SceneFrame>{
        let hist=self.scene_hist.as_ref().ok_or("scene capture not recorded")?;
        let mut row=[0u32;STRIDE];
        let mut bins=vec![0u32;BINS as usize];
        unsafe{
            let mapped=self.summary.map()?;
            std::ptr::copy_nonoverlapping(mapped,row.as_mut_ptr(),row.len());
            self.device.unmap_memory(self.summary.memory);
            let mapped=hist.map()?;
            std::ptr::copy_nonoverlapping(mapped,bins.as_mut_ptr(),bins.len());
            self.device.unmap_memory(hist.memory);
        }
        let average=f32::from_bits(row[61]);let fraction=f32::from_bits(row[62]);
        if row[63]!=self.width.div_ceil(5)*self.height.div_ceil(5)||
            !average.is_finite()||!(0.0..=1.0).contains(&average)||
            !fraction.is_finite()||!(0.0..=1.0).contains(&fraction)||
            row[39]!=self.width*self.height||bins.iter().map(|&v|u64::from(v)).sum::<u64>()!=u64::from(row[39])||
            row[20..23].iter().any(|&v|v>100000){
            return Err("invalid scene frame statistics".into());
        }
        Ok(SceneFrame{width:self.width,height:self.height,maxscl:[row[20],row[21],row[22]],bins,average,fraction})
    }
    pub(super) fn ellipses(&self,out:&mut[[u32;3]])->Result<()>{
        if !self.affine||out.len()!=self.windows.len(){return Err("HDR ellipse read contract".into());}
        let mut data=vec![0u32;STRIDE*out.len()];
        unsafe{
            let mapped=self.summary.map()?;
            std::ptr::copy_nonoverlapping(mapped,data.as_mut_ptr(),data.len());
            self.device.unmap_memory(self.summary.memory);
        }
        for (i,e) in out.iter_mut().enumerate(){
            let row=&data[i*STRIDE..(i+1)*STRIDE];
            *e=[0;3];
            if i==0{continue;}
            if row[67]!=1||row[68]==0||row[68]>65535||row[69]==0||row[69]>row[68]||row[70]>180{return Err("invalid measured ellipse".into());}
            e.copy_from_slice(&row[68..71]);
        }
        Ok(())
    }
    pub(super) fn rectangles(&self,out:&mut[[u32;4]])->Result<()>{
        if !self.affine||out.len()!=self.windows.len(){return Err("HDR affine rectangle contract".into());}
        let mut data=vec![0u32;STRIDE*out.len()];
        unsafe{
            let mapped=self.summary.map()?;
            std::ptr::copy_nonoverlapping(mapped,data.as_mut_ptr(),data.len());
            self.device.unmap_memory(self.summary.memory);
        }
        for (i,r) in out.iter_mut().enumerate(){
            let row=&data[i*STRIDE..(i+1)*STRIDE];
            r.copy_from_slice(&row[..4]);
            if r[0]>=r[2]||r[1]>=r[3]||r[2]>self.width||r[3]>self.height||
                i==0&&*r!=[0,0,self.width,self.height]||
                row[39]==0||row[39]>(r[2]-r[0])*(r[3]-r[1]){return Err("invalid affine rectangle".into());}
        }
        Ok(())
    }
    pub(super) fn gradients(&self,out:&mut[[f32;4]])->Result<()>{
        if !self.tracking||out.len()!=self.windows.len(){return Err("HDR affine read contract".into());}
        let mut data=vec![0u32;STRIDE*out.len()];
        unsafe{
            let mapped=self.summary.map()?;
            std::ptr::copy_nonoverlapping(mapped,data.as_mut_ptr(),data.len());
            self.device.unmap_memory(self.summary.memory);
        }
        for (i,g) in out.iter_mut().enumerate(){
            let row=&data[i*STRIDE..(i+1)*STRIDE];
            *g=[0.0;4];
            if row[60]>1||i==0&&row[60]!=0{return Err("invalid HDR affine flag".into());}
            if row[60]==1{
                for k in 0..4{g[k]=f32::from_bits(row[56+k]);}
                if g.iter().any(|v|!v.is_finite()||v.abs()>0.5){return Err("invalid HDR affine gradient".into());}
            }
        }
        Ok(())
    }
    pub(super) fn read(&self,out:&mut[HdrResult],mut shifts:Option<&mut[[i32;2]]>)->Result<()>{
        if shifts.as_ref().is_some_and(|s|s.len()!=out.len())||self.tracking!=shifts.is_some(){return Err("HDR tracking read contract".into());}
        if out.len()!=self.windows.len(){return Err("HDR statistics count mismatch".into());}
        let mut data=vec![0u32;STRIDE*out.len()];
        unsafe{
            let mapped=self.summary.map()?;
            std::ptr::copy_nonoverlapping(mapped,data.as_mut_ptr(),data.len());
            self.device.unmap_memory(self.summary.memory);
        }
        for (i,result) in out.iter_mut().enumerate(){
            let row=&data[i*STRIDE..(i+1)*STRIDE];let w=&self.windows[i];
            let shift=[row[41] as i32,row[42] as i32];
            if shift.iter().any(|&v|!(-16..=16).contains(&v))||(!self.tracking||i==0)&&shift!=[0,0]{return Err("invalid GPU HDR shift".into());}
            for k in 0..4{
                let expected=i64::from(w.rect[k])+i64::from(shift[k%2]);
                if expected<0||expected>i64::from(if k%2==0{self.width}else{self.height})||(!self.affine||i==0)&&expected!=i64::from(row[k]){
                    return Err("invalid GPU HDR tracked rectangle".into());
                }
            }
            if let Some(s)=shifts.as_deref_mut(){s[i]=shift;}
            if row[0]>=row[2]||row[1]>=row[3]||row[2]>self.width||row[3]>self.height{return Err("invalid measured HDR rectangle".into());}
            let area=(row[2]-row[0])*(row[3]-row[1]);
            let pixels=row[39];
            if pixels==0||pixels>area||(row[67]==0&&pixels!=area)||row[40]>pixels||row[40]==0||row[20..39].iter().any(|&x|x>100000){
                return Err("invalid GPU HDR statistics".into());
            }
            result.maxscl.copy_from_slice(&row[20..23]);result.average=row[23];
            result.percentiles.copy_from_slice(&row[24..39]);result.pixels=pixels;result.brightest=row[40];
        }
        Ok(())
    }
}
#[cfg(test)]
#[test]
fn hdr_statistics_shaders_compile(){
    for entry in ["collect","summarize","track","bright_proxy","bright_reduce"]{assert_eq!(spirv(SHADER,UNIFORM,entry).unwrap()[0],0x07230203);}
}

// ST 2094-40:2020 section 10.4. Scene-level reducer. A caller must
// supply every frame's complete 5:1 linear BT.2020 proxy in display
// order and finalize only at the actual scene boundary. No partial
// scene value may be published as a measured FractionBrightPixels.
#[derive(Default)]
pub(crate) struct BrightScene {
    best:Option<(f64,u32)>,
    frames:u64,
}
impl BrightScene {
    pub(crate) fn push_proxy(&mut self,proxy:&[f32])->Result<()> {
        if proxy.is_empty()||proxy.iter().any(|v|!v.is_finite()||!(0.0..=1.0).contains(v)){
            return Err("invalid luminance proxy".into());
        }
        let average=proxy.iter().map(|&v|f64::from(v)).sum::<f64>()/proxy.len() as f64;
        if self.best.is_none_or(|(a,_)|average>=a){
            let peak=proxy.iter().copied().fold(0.0f32,f32::max);
            let weight=proxy.iter().map(|&v|((5.0-255.0*(f64::from(peak)-f64::from(v)))/4.0).clamp(0.0,1.0)).sum::<f64>();
            let value=(1000.0*weight/proxy.len() as f64).round().clamp(1.0,1000.0) as u32;
            self.best=Some((average,value));
        }
        self.frames=self.frames.checked_add(1).ok_or("scene frame overflow")?;
        Ok(())
    }
    pub(crate) fn finish(self)->Option<u32>{self.best.map(|(_,v)|v)}
}
#[cfg(test)]
#[test]
fn bright_scene_selects_latest_highest_average_and_weighted_band(){
    let mut scene=BrightScene::default();
    scene.push_proxy(&[0.9,0.0,0.0,0.0]).unwrap();
    scene.push_proxy(&[0.5,0.5,0.5,0.5]).unwrap();
    assert_eq!(scene.finish(),Some(1000));
    let mut scene=BrightScene::default();
    scene.push_proxy(&[0.5,0.5]).unwrap();
    scene.push_proxy(&[0.75,0.25]).unwrap();
    assert_eq!(scene.finish(),Some(500));
    let mut scene=BrightScene::default();
    scene.push_proxy(&[1.0,1.0-3.0/255.0,0.0]).unwrap();
    assert_eq!(scene.finish(),Some(500));
    assert_eq!(BrightScene::default().finish(),None);
}

pub(super) struct SceneFrame{
    pub width:u32,pub height:u32,pub maxscl:[u32;3],
    pub bins:Vec<u32>,pub average:f32,pub fraction:f32,
}

// Explicit complete-scene contract. The caller supplies authoritative
// frame boundaries, not guesses from motion, PTS gaps or metadata changes.
// The accumulator stores one histogram, never a queue of decoded frames.
#[repr(C)]
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)]
pub struct SceneResult{
    pub first:u64,pub end:u64,pub brightest_frame:u64,
    pub maxscl:[u32;3],pub average:u32,pub percentiles:[u32;9],pub fraction:u32,
}
pub struct HdrScene{
    first:u64,end:u64,next:u64,width:u32,height:u32,
    percentages:[u32;9],bins:Vec<u64>,maxscl:[u32;3],best:Option<(f32,u64,f32)>,
}
impl HdrScene{
    pub(super) fn new(first:u64,end:u64,width:u32,height:u32,percentages:[u32;9])->Result<Self>{
        if first>=end||width==0||height==0||width>3840||height>2160||u64::from(width)*u64::from(height)>3840*2160||
            (end-first)>u64::MAX/(u64::from(width)*u64::from(height))/100000||
            percentages.iter().any(|&v|v>100)||percentages.windows(2).any(|p|p[0]>=p[1]){
            return Err("invalid scene interval or distribution".into());
        }
        Ok(Self{first,end,next:first,width,height,percentages,bins:vec![0;BINS as usize],maxscl:[0;3],best:None})
    }
    pub(super) fn push(&mut self,index:u64,frame:SceneFrame)->Result<()>{
        if index!=self.next||index>=self.end||frame.width!=self.width||frame.height!=self.height||
            frame.bins.len()!=self.bins.len()||frame.bins.iter().map(|&v|u64::from(v)).sum::<u64>()!=u64::from(self.width)*u64::from(self.height)||
            !frame.average.is_finite()||!(0.0..=1.0).contains(&frame.average)||
            !frame.fraction.is_finite()||!(0.0..=1.0).contains(&frame.fraction)||
            frame.maxscl.iter().any(|&v|v>100000){
            return Err("incompatible or out-of-order scene frame".into());
        }
        for (sum,&v) in self.bins.iter_mut().zip(&frame.bins){*sum+=u64::from(v);}
        for (peak,v) in self.maxscl.iter_mut().zip(frame.maxscl){*peak=(*peak).max(v);}
        if self.best.is_none_or(|(a,_,_)|frame.average>=a){self.best=Some((frame.average,index,frame.fraction));}
        self.next+=1;
        Ok(())
    }
    pub(super) fn finish(&self)->Result<SceneResult>{
        if self.next!=self.end{return Err("incomplete scene".into());}
        let (_,brightest_frame,fraction)=self.best.ok_or("empty scene")?;
        let n=self.bins.iter().sum::<u64>();
        let sum=self.bins.iter().enumerate().map(|(i,&v)|i as u128*u128::from(v)).sum::<u128>();
        let mut result=SceneResult{first:self.first,end:self.end,brightest_frame,maxscl:self.maxscl,
            average:((sum+u128::from(n)/2)/u128::from(n)) as u32,
            fraction:(f64::from(fraction)*1000.0).round().clamp(1.0,1000.0) as u32,
            ..Default::default()};
        let reserved=self.percentages[1]==5&&self.percentages[2]==10;
        for (j,&pct) in self.percentages.iter().enumerate(){
            if reserved&&(j==1||j==2){result.percentiles[j]=if j==1{0}else{255};continue;}
            let rank=if j==8&&pct==99{n-n/5000}else{((u128::from(n)*u128::from(pct)+99)/100) as u64}.max(1);
            let mut cumulative=0u64;
            for (i,&count) in self.bins.iter().enumerate(){
                cumulative+=count;
                if cumulative>=rank{result.percentiles[j]=i as u32;break;}
            }
        }
        Ok(result)
    }
}
#[cfg(test)]
mod scene_tests{
    use super::*;
    const P:[u32;9]=[1,5,10,25,50,75,90,95,99];
    fn frame(a:u32,b:u32,avg:f32,fraction:f32)->SceneFrame{
        let mut bins=vec![0;BINS as usize];bins[a as usize]+=1;bins[b as usize]+=1;
        SceneFrame{width:2,height:1,maxscl:[a.max(b);3],bins,average:avg,fraction}
    }
    #[test]
    fn complete_scene_histogram_and_latest_tie(){
        let mut s=HdrScene::new(10,12,2,1,P).unwrap();
        assert!(s.push(11,frame(0,100,0.2,0.5)).is_err());
        s.push(10,frame(100,300,0.2,1.0)).unwrap();
        assert!(s.finish().is_err());
        assert!(s.push(10,frame(0,100,0.2,0.5)).is_err());
        s.push(11,frame(200,400,0.2,0.5)).unwrap();
        let r=s.finish().unwrap();
        assert_eq!(r.maxscl,[400;3]);assert_eq!(r.average,250);
        assert_eq!(r.percentiles,[100,0,255,100,200,300,400,400,400]);
        assert_eq!(r.brightest_frame,11);assert_eq!(r.fraction,500);
        assert!(s.push(12,frame(100,100,0.3,1.0)).is_err());
    }
    #[test]
    fn scene_boundaries_and_percentiles(){
        assert!(HdrScene::new(1,1,2,1,P).is_err());
        assert!(HdrScene::new(0,1,2,1,[1;9]).is_err());
        let mut s=HdrScene::new(0,1,2,1,P).unwrap();
        s.push(0,frame(100,100,0.1,0.00001)).unwrap();
        assert_eq!(s.finish().unwrap().fraction,1);
    }
}

// Verify the measured version-1 metadata against a contiguous authoring
// manifest. Zero FBP/MaxSCL may mean unmeasured, but are not accepted as
// a verified measurement here (except genuine measured black MaxSCL).
// Caller supplies the same percentile list to each scene accumulator;
// a match here is not a certification of the complete ST 2094 stream.
pub(super) fn validate_scene_sequence(first:u64,end:u64,measured:&[SceneResult],authored:&[SceneResult])->bool{
    if first>=end||measured.is_empty()||measured.len()!=authored.len(){return false;}
    let mut next=first;
    for (m,a) in measured.iter().zip(authored){
        if m.first!=next||m.end<=m.first||m.end>end||
            m.brightest_frame<m.first||m.brightest_frame>=m.end||
            a.first!=m.first||a.end!=m.end||m.maxscl!=a.maxscl||m.average!=a.average||
            m.percentiles!=a.percentiles||m.fraction!=a.fraction||
            !(1..=1000).contains(&m.fraction)||m.maxscl.iter().any(|&v|v>100000)||
            m.average>*m.maxscl.iter().max().unwrap_or(&0)||m.percentiles.iter().any(|&v|v>100000){
            return false;
        }
        next=m.end;
    }
    next==end
}
#[cfg(test)]
#[test]
fn validate_scene_sequence_rejects_gaps_and_metadata_changes(){
    let a=SceneResult{first:0,end:2,brightest_frame:1,maxscl:[100;3],average:50,fraction:500,..Default::default()};
    let b=SceneResult{first:2,end:4,brightest_frame:2,..a};
    assert!(validate_scene_sequence(0,4,&[a,b],&[a,b]));
    assert!(!validate_scene_sequence(0,5,&[a,b],&[a,b]));
    assert!(!validate_scene_sequence(0,4,&[a,SceneResult{first:3,..b}],&[a,b]));
    assert!(!validate_scene_sequence(0,4,&[a,b],&[a,SceneResult{fraction:501,..b}]));
    assert!(!validate_scene_sequence(0,4,&[a,b],&[a,SceneResult{percentiles:[1;9],..b}]));
}

// Two-pass cache: explicitly supplied authoritative scene boundaries and
// content fingerprint, renderer/configuration fingerprint, fixed geometry.
// All frames must be captured in presentation order. The cache is create-only
// and is published atomically only on successful explicit completion.
const PASS_P:[u32;9]=[1,5,10,25,50,75,90,95,99];
const MAX_SCENES:usize=100_000;
pub(super) struct TwoPass{
    mode:u32,key:String,profile:String,width:u32,height:u32,ends:Vec<u64>,
    path:std::path::PathBuf,next:u64,results:Vec<SceneResult>,current:Option<HdrScene>,failed:bool,
}
fn read_bounded(path:&std::path::Path,max:u64)->Result<String>{
    use std::io::Read;
    if !std::fs::symlink_metadata(path).map_err(|e|e.to_string())?.file_type().is_file(){return Err("not a regular scene file".into());}
    let f=std::fs::File::open(path).map_err(|e|e.to_string())?;
    if !f.metadata().map_err(|e|e.to_string())?.is_file(){return Err("not a regular scene file".into());}
    let mut s=String::new();
    f.take(max+1).read_to_string(&mut s).map_err(|e|e.to_string())?;
    if s.len() as u64>max{return Err("scene file too large".into());}
    Ok(s)
}
fn parse_num<T:std::str::FromStr>(s:Option<&str>)->Result<T>{
    s.ok_or("missing scene field")?.parse().map_err(|_|"invalid scene field".into())
}
impl TwoPass{
    pub(super) fn open(mode:u32,plan:&std::path::Path,cache:&std::path::Path,key:&str,profile:&str,w:u32,h:u32)->Result<Self>{
        if !matches!(mode,1|2)||key.len()!=64||!key.bytes().all(|v|v.is_ascii_hexdigit())||
            profile.is_empty()||profile.len()>1024||profile.bytes().any(|v|!v.is_ascii_graphic())||
            w==0||h==0||w>3840||h>2160||u64::from(w)*u64::from(h)>3840*2160||plan==cache{
            return Err("invalid two-pass identity or geometry".into());
        }
        let manifest=read_bounded(plan,2_000_000)?;
        let mut lines=manifest.lines();
        if lines.next()!=Some("AMBIENTSVP_SCENE_PLAN_1")||lines.next()!=Some(key){
            return Err("scene plan identity mismatch".into());
        }
        let mut ends=Vec::new();let mut first=0;
        for l in lines{
            let end:u64=parse_num(Some(l))?;
            if ends.len()>=MAX_SCENES||end<=first||end>1_000_000_000{return Err("invalid scene interval".into());}
            ends.push(end);first=end;
        }
        if ends.is_empty(){return Err("empty scene plan".into());}
        let mut s=Self{mode,key:key.into(),profile:profile.into(),width:w,height:h,ends,path:cache.into(),
            next:0,results:Vec::new(),current:None,failed:false};
        if mode==2{s.read_cache()?;}
        else if cache.exists(){return Err("scene cache already exists".into());}
        Ok(s)
    }
    fn header(&self)->String{
        format!("AMBIENTSVP_SCENE_CACHE_1\n{}\n{}\n{} {}\n",self.key,self.profile,self.width,self.height)
    }
    fn read_cache(&mut self)->Result<()>{
        let contents=read_bounded(&self.path,32_000_000)?;
        let header=self.header();
        let body=contents.strip_prefix(&header).ok_or("scene cache identity mismatch")?;
        for l in body.lines(){
            if self.results.len()>=self.ends.len(){return Err("extra scene result".into());}
            let mut fields=l.split_ascii_whitespace();
            let first=parse_num(fields.next())?;let end=parse_num(fields.next())?;
            let brightest_frame=parse_num(fields.next())?;
            let mut maxscl=[0;3];for v in &mut maxscl{*v=parse_num(fields.next())?;}
            let average=parse_num(fields.next())?;
            let mut percentiles=[0;9];for v in &mut percentiles{*v=parse_num(fields.next())?;}
            let fraction=parse_num(fields.next())?;
            if fields.next().is_some()||end!=self.ends[self.results.len()]{return Err("scene cache plan mismatch".into());}
            self.results.push(SceneResult{first,end,brightest_frame,maxscl,average,percentiles,fraction});
        }
        if self.results.iter().any(|r|{
            let peak=*r.maxscl.iter().max().unwrap_or(&0);
            let actual=[r.percentiles[0],r.percentiles[3],r.percentiles[4],r.percentiles[5],r.percentiles[6],r.percentiles[7],r.percentiles[8]];
            r.percentiles[1]!=0||r.percentiles[2]!=255||actual.iter().any(|&v|v>peak)||actual.windows(2).any(|p|p[0]>p[1])
        }){return Err("invalid cached version-1 distribution".into());}
        if self.results.len()!=self.ends.len()||!validate_scene_sequence(0,*self.ends.last().unwrap(),&self.results,&self.results){
            return Err("incomplete or invalid scene cache".into());
        }
        Ok(())
    }
    pub(super) fn interval(&self,index:u64)->Result<(u64,u64)>{
        if self.failed||index>=*self.ends.last().unwrap(){return Err("scene frame outside plan".into());}
        let i=self.ends.partition_point(|&v|v<=index);
        Ok((if i==0{0}else{self.ends[i-1]},self.ends[i]))
    }
    pub(super) fn abort(&mut self){self.failed=true;}
    pub(super) fn capture(&mut self,index:u64,frame:SceneFrame)->Result<()>{
        if self.mode!=1||self.failed||index!=self.next||self.results.len()>=self.ends.len(){
            self.abort();return Err("out of order scene capture".into());
        }
        let end=self.ends[self.results.len()];
        if self.current.is_none(){self.current=Some(HdrScene::new(index,end,self.width,self.height,PASS_P)?);}
        if let Err(e)=self.current.as_mut().unwrap().push(index,frame){self.abort();return Err(e);}
        self.next+=1;
        if self.next==end{
            self.results.push(self.current.take().unwrap().finish()?);
        }
        Ok(())
    }
    pub(super) fn lookup(&mut self,index:u64)->Result<SceneResult>{
        if self.mode!=2||self.failed||index!=self.next||index>=*self.ends.last().unwrap(){
            self.abort();return Err("out of order second pass".into());
        }
        let i=self.ends.partition_point(|&v|v<=index);
        let result=*self.results.get(i).ok_or("missing scene result")?;
        self.next+=1;
        Ok(result)
    }
    pub(super) fn finish(&mut self)->Result<()>{
        use std::io::Write;
        if self.failed||self.next!=*self.ends.last().unwrap()||self.results.len()!=self.ends.len()||self.current.is_some(){
            return Err("incomplete two-pass run".into());
        }
        if self.mode==2{return Ok(());}
        if self.mode!=1{return Err("already finalized".into());}
        let mut data=self.header();
        for r in &self.results{
            use std::fmt::Write as _;
            write!(&mut data,"{} {} {}",r.first,r.end,r.brightest_frame).map_err(|_|"cache formatting")?;
            for v in r.maxscl{write!(&mut data," {}",v).map_err(|_|"cache formatting")?;}
            write!(&mut data," {}",r.average).map_err(|_|"cache formatting")?;
            for v in r.percentiles{write!(&mut data," {}",v).map_err(|_|"cache formatting")?;}
            writeln!(&mut data," {}",r.fraction).map_err(|_|"cache formatting")?;
        }
        // Unique create-new sibling; hard-link publication never overwrites
        // an existing cache and never exposes a partially written file.
        static SERIAL:std::sync::atomic::AtomicU64=std::sync::atomic::AtomicU64::new(0);
        let mut tmp=self.path.as_os_str().to_os_string();
        tmp.push(format!(".tmp-{}-{}",std::process::id(),SERIAL.fetch_add(1,std::sync::atomic::Ordering::Relaxed)));
        let tmp=std::path::PathBuf::from(tmp);
        let mut opts=std::fs::OpenOptions::new();opts.write(true).create_new(true);
        #[cfg(unix)] {use std::os::unix::fs::OpenOptionsExt;opts.mode(0o600);}
        let mut f=opts.open(&tmp).map_err(|e|e.to_string())?;
        let result=(||->Result<()>{
            f.write_all(data.as_bytes()).map_err(|e|e.to_string())?;
            f.sync_all().map_err(|e|e.to_string())?;
            std::fs::hard_link(&tmp,&self.path).map_err(|e|e.to_string())?;
            Ok(())
        })();
        drop(f);let _=std::fs::remove_file(tmp);
        result?;self.mode=0;Ok(())
    }
}
#[cfg(test)]
#[test]
fn two_pass_cache_identity_completeness_and_replay(){
    let root=std::env::temp_dir().join(format!("ambient-svp-two-pass-{}",std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let plan=root.join("plan");let cache=root.join("cache");
    let _=std::fs::remove_file(&cache);
    let key="a".repeat(64);
    std::fs::write(&plan,format!("AMBIENTSVP_SCENE_PLAN_1\n{key}\n1\n2\n")).unwrap();
    let mut p=TwoPass::open(1,&plan,&cache,&key,"mpv:fixture",2,1).unwrap();
    assert!(p.finish().is_err());assert!(!cache.exists());
    for i in 0..2{
        let mut bins=vec![0;BINS as usize];bins[100+i as usize]=2;
        p.capture(i,SceneFrame{width:2,height:1,maxscl:[100+i as u32;3],bins,average:0.1,fraction:1.0}).unwrap();
    }
    p.finish().unwrap();
    assert!(TwoPass::open(2,&plan,&cache,&key,"vlc:fixture",2,1).is_err());
    let mut q=TwoPass::open(2,&plan,&cache,&key,"mpv:fixture",2,1).unwrap();
    assert_eq!(q.interval(0).unwrap(),(0,1));assert_eq!(q.interval(1).unwrap(),(1,2));
    assert!(q.interval(2).is_err());
    assert_eq!(q.lookup(0).unwrap().average,100);
    assert_eq!(q.lookup(1).unwrap().average,101);
    q.finish().unwrap();
    let mut q=TwoPass::open(2,&plan,&cache,&key,"mpv:fixture",2,1).unwrap();
    assert!(q.lookup(1).is_err());assert!(q.lookup(0).is_err());
    let _=std::fs::remove_dir_all(root);
}
