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
