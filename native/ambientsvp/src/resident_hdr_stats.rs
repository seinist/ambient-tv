//! Optional metadata-only readback. The histogram and all pixels remain
//! GPU resident. The owner pair's actual submission fence governs lifetime.
use super::{Core,Pipeline,Result,err,spirv};
use ash::vk;
use std::sync::Arc;
const BINS:u64=100001;
const STRIDE:usize=56;
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
pub(super) struct StatsPipelines{collect:Pipeline,reduce:Pipeline,track:Pipeline}
impl StatsPipelines{
    pub(super) fn new(core:&Core)->Result<Self>{
        let types=[vk::DescriptorType::SAMPLED_IMAGE,vk::DescriptorType::STORAGE_BUFFER,vk::DescriptorType::STORAGE_BUFFER,vk::DescriptorType::STORAGE_BUFFER];
        Ok(Self{collect:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"collect")?,c"collect")?,
            reduce:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"summarize")?,c"summarize")?,
            track:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"track")?,c"track")?})
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
    hist:Buffer,summary:Buffer,windows:Vec<HdrWindow>,
    pipelines:Arc<StatsPipelines>,set:vk::DescriptorSet,
    tracking:bool,width:u32,height:u32,
}
impl Drop for StatsJob{
    fn drop(&mut self){unsafe{self.device.destroy_descriptor_pool(self.pool,None);}}
}
impl StatsJob{
    pub(super) fn new(core:&Core,view:vk::ImageView,field:vk::Buffer,windows:&[HdrWindow],
        tracking:Option<(&[HdrTrack],f32)>)->Result<Self>{
        if !core.stats_supported || !(1..=3).contains(&windows.len()) ||
            windows[0].rect!=[0,0,core.width,core.height] ||
            windows.iter().any(|w|w.rect[0]>=w.rect[2]||w.rect[1]>=w.rect[3]||
                w.rect[2]>core.width||w.rect[3]>core.height||w.count>15||
                w.percentages[..w.count.min(15) as usize].iter().any(|&p|p>100)||
                w.percentages[..w.count.min(15) as usize].windows(2).any(|p|p[0]>=p[1])){
            return Err("unsupported HDR statistics geometry or percentile list".into());
        }
        let valid_rect=|r:[u32;4]|r[0]<r[2]&&r[1]<r[3]&&r[2]<=core.width&&r[3]<=core.height;
        if let Some((tracks,phase))=tracking{
            if tracks.len()!=windows.len()||!phase.is_finite()||!(0.0..=1.0).contains(&phase)||
                tracks.iter().skip(1).any(|t|!valid_rect(t.a)||!valid_rect(t.b)||
                    t.ellipse[0]>=core.width||t.ellipse[1]>=core.height){return Err("invalid HDR tracking geometry".into());}
        }
        let pipelines={
            let mut slot=core.stats.lock().unwrap_or_else(|e|e.into_inner());
            if slot.is_none(){*slot=Some(Arc::new(StatsPipelines::new(core)?));}
            slot.as_ref().unwrap().clone()
        };
        let hist=Buffer::new(core,BINS*4*windows.len() as u64,
            vk::BufferUsageFlags::STORAGE_BUFFER|vk::BufferUsageFlags::TRANSFER_DST,vk::MemoryPropertyFlags::DEVICE_LOCAL)?;
        let summary=Buffer::new(core,(STRIDE*4*windows.len()) as u64,vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)?;
        let mut s=Self{device:core.device.clone(),pool:vk::DescriptorPool::null(),hist,summary,windows:windows.to_vec(),
            pipelines,set:vk::DescriptorSet::null(),tracking:tracking.is_some(),width:core.width,height:core.height};
        let mut config=vec![0u32;STRIDE*windows.len()];
        for (i,w) in windows.iter().enumerate(){
            let row=&mut config[i*STRIDE..(i+1)*STRIDE];
            row[..4].copy_from_slice(&w.rect);row[4]=w.count;row[5..20].copy_from_slice(&w.percentages);
            if let Some((tracks,phase))=tracking{if i>0{
                let t=tracks[i];row[43]=phase.to_bits();row[44..48].copy_from_slice(&t.a);
                row[48..52].copy_from_slice(&t.b);row[52..54].copy_from_slice(&t.ellipse);row[54]=1;
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
        let params=[w,h,self.windows.len() as u32,0];
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
            let after=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE).dst_access_mask(vk::AccessFlags::HOST_READ)];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&after,&[],&[]);
        }
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
                if expected<0||expected>i64::from(if k%2==0{self.width}else{self.height})||expected!=i64::from(row[k]){
                    return Err("invalid GPU HDR tracked rectangle".into());
                }
            }
            if let Some(s)=shifts.as_deref_mut(){s[i]=shift;}
            let pixels=(w.rect[2]-w.rect[0])*(w.rect[3]-w.rect[1]);
            if row[39]!=pixels||row[40]>pixels||row[40]==0||row[20..39].iter().any(|&x|x>100000){
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
    for entry in ["collect","summarize","track"]{assert_eq!(spirv(SHADER,UNIFORM,entry).unwrap()[0],0x07230203);}
}
