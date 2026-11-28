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
// Every positive finite binary16 PQ code through 1.0, evaluated in f64
// once per process. Quantization is performed from the high precision
// linear value rather than from device-specific shader pow() results.
const PQ_CODES:usize=0x3c01;
fn pq_table()->&'static Vec<u32>{
    static TABLE:std::sync::OnceLock<Vec<u32>>=std::sync::OnceLock::new();
    TABLE.get_or_init(||{
        let mut table=Vec::with_capacity(PQ_CODES*8);
        for bits in 0..PQ_CODES{
            let exp=(bits>>10)&31;let mant=(bits&1023) as f64;
            let x=if exp==0{mant*2f64.powi(-24)}else{(1.0+mant/1024.0)*2f64.powi(exp as i32-15)};
            let p=x.powf(32.0/2523.0);
            let linear=((p-3424.0/4096.0).max(0.0)/(2413.0/128.0-2392.0/128.0*p)).powf(16384.0/2610.0).clamp(0.0,1.0);
            table.extend_from_slice(&[(linear*100_000.0).round() as u32,(linear*1_000_000_000.0).round() as u32,
                (linear as f32).to_bits(),0,
                (linear*262_700_000.0).round() as u32,(linear*678_000_000.0).round() as u32,
                (linear*59_300_000.0).round() as u32,0]);
        }
        table
    })
}
pub(super) struct StatsPipelines{collect:Pipeline,reduce:Pipeline,track:Pipeline,proxy:Pipeline,bright:Pipeline,linear:Pipeline,pq:Buffer}
impl StatsPipelines{
    pub(super) fn new(core:&Core)->Result<Self>{
        let types=[vk::DescriptorType::SAMPLED_IMAGE,vk::DescriptorType::STORAGE_BUFFER,vk::DescriptorType::STORAGE_BUFFER,vk::DescriptorType::STORAGE_BUFFER,vk::DescriptorType::STORAGE_BUFFER];
        let table=pq_table();
        let pq=Buffer::new(core,(table.len()*4) as u64,vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)?;
        unsafe{let mapped=pq.map()?;std::ptr::copy_nonoverlapping(table.as_ptr(),mapped,table.len());pq.device.unmap_memory(pq.memory);}
        Ok(Self{pq,collect:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"collect")?,c"collect")?,
            reduce:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"summarize")?,c"summarize")?,
            track:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"track")?,c"track")?,
            proxy:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"bright_proxy")?,c"bright_proxy")?,
            bright:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"bright_reduce")?,c"bright_reduce")?,
            linear:Pipeline::new(&core.device,&types,&spirv(SHADER,UNIFORM,"scene_linear_sum")?,c"scene_linear_sum")?})
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
// For static legacy geometry, precompute each ellipse/rectangle intersection
// as one inclusive-exclusive integer span per scanline. This avoids
// per-pixel GPU trigonometry and f32 uncertainty near the ellipse boundary.
// Coordinates follow section 6.1 (pixel centers, clockwise angle).
fn ellipse_spans(rect:[u32;4],center:[u32;2],axes:[u32;3],height:u32)->Vec<u32>{
    let [a,b,angle]=axes;
    let (sin,cos)=match angle{0|180=>(0.0,1.0),90=>(1.0,0.0),_=>f64::from(angle).to_radians().sin_cos()};
    let (a,b)=(f64::from(a),f64::from(b));
    let inv_a=1.0/(a*a);let inv_b=1.0/(b*b);
    let aa=cos*cos*inv_a+sin*sin*inv_b;
    let ab=sin*cos*(inv_a-inv_b);
    // det = 1/(a²b²), computed directly to avoid cancellation.
    let det=inv_a*inv_b;
    let mut spans=vec![0;2*height as usize];
    let inside=|x:i64,y:i64|{
        let dx=x-i64::from(center[0]);let dy=y-i64::from(center[1]);
        if angle==0||angle==180||angle==90{
            let (dx,dy)=if angle==90{(dy,dx)}else{(dx,dy)};
            let (aa,bb)=((a as i128).pow(2),(b as i128).pow(2));
            return i128::from(dx).pow(2)*bb+i128::from(dy).pow(2)*aa<=aa*bb;
        }
        let (x,y)=(dx as f64,dy as f64);
        let u=(cos*x+sin*y)/a;let v=(-sin*x+cos*y)/b;
        u*u+v*v<=1.0+8.0*f64::EPSILON
    };
    for y in rect[1]..rect[3].min(height){
        let dy=f64::from(y)-f64::from(center[1]);
        let rad=aa-det*dy*dy;
        if rad< -aa*8.0*f64::EPSILON{continue;}
        let shift=-ab*dy/aa+f64::from(center[0]);
        let radius=rad.max(0.0).sqrt()/aa;
        let left=i64::from(rect[0]);let right=i64::from(rect[2]);
        let mut lo=((shift-radius).floor() as i64-1).clamp(left,right);
        let mut hi=((shift+radius).ceil() as i64+2).clamp(left,right);
        while lo<hi&&!inside(lo,i64::from(y)){lo+=1;}
        while lo<hi&&!inside(hi-1,i64::from(y)){hi-=1;}
        spans[2*y as usize]=lo as u32;spans[2*y as usize+1]=hi as u32;
    }
    spans
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
        if scene&&windows.len()>1&&ellipses.is_none(){return Err("local scene windows require ellipse geometry".into());}
        if scene&&tracking.is_some_and(|t|t.1!=0.0){return Err("scene capture requires fixed per-output geometry".into());}
        let proxy_count=if scene{u64::from(core.width.div_ceil(5))*u64::from(core.height.div_ceil(5))}else{0};
        let hist=Buffer::new(core,(BINS*windows.len() as u64+proxy_count)*4,
            vk::BufferUsageFlags::STORAGE_BUFFER|vk::BufferUsageFlags::TRANSFER_DST|vk::BufferUsageFlags::TRANSFER_SRC,vk::MemoryPropertyFlags::DEVICE_LOCAL)?;
        let scene_hist=if scene{Some(Buffer::new(core,BINS*4*windows.len() as u64,vk::BufferUsageFlags::TRANSFER_DST,
            vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)?)}else{None};
        let span_words=if scene&&windows.len()>1{2*core.height as usize*(windows.len()-1)}else{0};
        let summary=Buffer::new(core,((STRIDE*windows.len()+span_words)*4) as u64,vk::BufferUsageFlags::STORAGE_BUFFER,
            vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)?;
        let mut s=Self{device:core.device.clone(),pool:vk::DescriptorPool::null(),hist,summary,scene_hist,windows:windows.to_vec(),
            pipelines,set:vk::DescriptorSet::null(),tracking:tracking.is_some()&&!scene,affine:tracking.is_some_and(|t|t.2),width:core.width,height:core.height,step:core.tuning.map_or(16,super::ResidentTuning::step)};
        let mut config=vec![0u32;STRIDE*windows.len()+span_words];
        for (i,w) in windows.iter().enumerate(){
            let row=&mut config[i*STRIDE..(i+1)*STRIDE];
            row[..4].copy_from_slice(&w.rect);row[4]=w.count;row[5..20].copy_from_slice(&w.percentages);
            if let Some((tracks,phase,_))=tracking{if i>0{
                let t=tracks[i];row[43]=phase.to_bits();row[44..48].copy_from_slice(&t.a);
                row[48..52].copy_from_slice(&t.b);row[52..54].copy_from_slice(&t.ellipse);row[54]=1;row[55]=u32::from(s.affine);
                if let Some(e)=ellipses{row[64..67].copy_from_slice(&e[i]);row[67]=1;row[68..71].copy_from_slice(&e[i]);}
            }}
        }
        if scene&&windows.len()>1{
            let (tracks,_,_)=tracking.ok_or("missing legacy ellipse tracks")?;
            let axes=ellipses.ok_or("missing legacy ellipse axes")?;
            for i in 1..windows.len(){
                let offset=STRIDE*windows.len()+2*core.height as usize*(i-1);
                let spans=ellipse_spans(windows[i].rect,tracks[i].ellipse,axes[i],core.height);
                config[i*STRIDE+71]=offset as u32;
                config[offset..offset+spans.len()].copy_from_slice(&spans);
            }
        }
        unsafe{
            let mapped=s.summary.map()?;
            std::ptr::copy_nonoverlapping(config.as_ptr(),mapped,config.len());
            s.device.unmap_memory(s.summary.memory);
            let sizes=[
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::SAMPLED_IMAGE).descriptor_count(1),
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::STORAGE_BUFFER).descriptor_count(4)];
            s.pool=s.device.create_descriptor_pool(&vk::DescriptorPoolCreateInfo::default().max_sets(1).pool_sizes(&sizes),None).map_err(err)?;
            s.set=s.device.allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(s.pool)
                .set_layouts(&[s.pipelines.collect.set])).map_err(err)?[0];
            let image=[vk::DescriptorImageInfo::default().image_view(view).image_layout(vk::ImageLayout::GENERAL)];
            let h=[vk::DescriptorBufferInfo::default().buffer(s.hist.buffer).range(s.hist.size)];
            let o=[vk::DescriptorBufferInfo::default().buffer(s.summary.buffer).range(s.summary.size)];
            let motion=[vk::DescriptorBufferInfo::default().buffer(field).range(core.field_size)];
            let lut=[vk::DescriptorBufferInfo::default().buffer(s.pipelines.pq.buffer).range(s.pipelines.pq.size)];
            s.device.update_descriptor_sets(&[
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(0).descriptor_type(vk::DescriptorType::SAMPLED_IMAGE).image_info(&image),
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(1).descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&h),
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(2).descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&o),
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(3).descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&motion),
                vk::WriteDescriptorSet::default().dst_set(s.set).dst_binding(4).descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&lut),
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
                self.pipelines.linear.dispatch(cmd,self.set,params,w.div_ceil(8),h.div_ceil(8));
                self.pipelines.proxy.dispatch(cmd,self.set,params,w.div_ceil(5).div_ceil(8),h.div_ceil(5).div_ceil(8));
                d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::DependencyFlags::empty(),&between,&[],&[]);
                self.pipelines.bright.dispatch(cmd,self.set,params,1,1);
                let transfer=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE)
                    .dst_access_mask(vk::AccessFlags::TRANSFER_READ)];
                d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER,
                    vk::DependencyFlags::empty(),&transfer,&[],&[]);
                d.cmd_copy_buffer(cmd,self.hist.buffer,hist.buffer,&[vk::BufferCopy::default().size(BINS*4*self.windows.len() as u64)]);
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
        if self.windows.len()!=1{return Err("expected one global scene window".into());}
        Ok(self.scene_frames()?.remove(0).frame)
    }
    pub(super) fn scene_frames(&self)->Result<Vec<SceneWindowFrame>>{
        let hist=self.scene_hist.as_ref().ok_or("scene capture not recorded")?;
        let mut rows=vec![0u32;STRIDE*self.windows.len()];
        let mut bins=vec![0u32;BINS as usize*self.windows.len()];
        unsafe{
            let mapped=self.summary.map()?;
            std::ptr::copy_nonoverlapping(mapped,rows.as_mut_ptr(),rows.len());
            self.device.unmap_memory(self.summary.memory);
            let mapped=hist.map()?;
            std::ptr::copy_nonoverlapping(mapped,bins.as_mut_ptr(),bins.len());
            self.device.unmap_memory(hist.memory);
        }
        let mut output=Vec::with_capacity(self.windows.len());
        for (i,w) in self.windows.iter().enumerate(){
        let row=&rows[i*STRIDE..(i+1)*STRIDE];
        let bins=bins[i*BINS as usize..(i+1)*BINS as usize].to_vec();
        let proxy_count=u64::from(row[63]);
        let proxy_sum=u64::from(row[58])|(u64::from(row[59])<<32);
        let weight_sum=u64::from(row[61])|(u64::from(row[62])<<32);
        if i==0&&(proxy_count==0||proxy_sum>proxy_count*1_000_000_000||weight_sum>proxy_count*4_000_000_000){
            return Err("invalid integer HDR luminance reduction".into());
        }
        let average=if i==0{proxy_sum as f64/(proxy_count as f64*1_000_000_000.0)}else{0.0};
        let fraction=if i==0{weight_sum as f64/(proxy_count as f64*4_000_000_000.0)}else{0.0};
        let linear_sum=u64::from(row[56])|(u64::from(row[57])<<32);
        if (i==0&&row[63]!=self.width.div_ceil(5)*self.height.div_ceil(5))||
            !average.is_finite()||!(0.0..=1.0).contains(&average)||
            !fraction.is_finite()||!(0.0..=1.0).contains(&fraction)||
            row[39]==0||row[39]>self.width*self.height||i==0&&row[39]!=self.width*self.height||bins.iter().map(|&v|u64::from(v)).sum::<u64>()!=u64::from(row[39])||
            row[20..23].iter().any(|&v|v>100000)||linear_sum>u64::from(row[39])*1_000_000_000{
            return Err("invalid scene frame statistics".into());
        }
        output.push(SceneWindowFrame{count:w.count,percentages:w.percentages,frame:SceneFrame{width:self.width,height:self.height,maxscl:[row[20],row[21],row[22]],bins,average,fraction,linear_sum}});
        }
        Ok(output)
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
    for entry in ["collect","summarize","track","bright_proxy","bright_reduce","scene_linear_sum"]{assert_eq!(spirv(SHADER,UNIFORM,entry).unwrap()[0],0x07230203);}
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

pub(super) struct SceneWindowFrame{pub count:u32,pub percentages:[u32;15],pub frame:SceneFrame}
pub(super) struct SceneFrame{
    pub width:u32,pub height:u32,pub maxscl:[u32;3],
    pub bins:Vec<u32>,pub average:f64,pub fraction:f64,pub linear_sum:u64,
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
    percentages:[u32;9],bins:Vec<u64>,maxscl:[u32;3],linear_sum:u128,best:Option<(f64,u64,f64)>,
}
impl HdrScene{
    pub(super) fn new(first:u64,end:u64,width:u32,height:u32,percentages:[u32;9])->Result<Self>{
        if first>=end||width==0||height==0||width>3840||height>2160||u64::from(width)*u64::from(height)>3840*2160||
            (end-first)>u64::MAX/(u64::from(width)*u64::from(height))/100000||
            percentages.iter().any(|&v|v>100)||percentages.windows(2).any(|p|p[0]>=p[1]){
            return Err("invalid scene interval or distribution".into());
        }
        Ok(Self{first,end,next:first,width,height,percentages,bins:vec![0;BINS as usize],maxscl:[0;3],linear_sum:0,best:None})
    }
    pub(super) fn push(&mut self,index:u64,frame:SceneFrame)->Result<()>{
        if index!=self.next||index>=self.end||frame.width!=self.width||frame.height!=self.height||
            frame.bins.len()!=self.bins.len()||frame.bins.iter().map(|&v|u64::from(v)).sum::<u64>()!=u64::from(self.width)*u64::from(self.height)||
            !frame.average.is_finite()||!(0.0..=1.0).contains(&frame.average)||
            !frame.fraction.is_finite()||!(0.0..=1.0).contains(&frame.fraction)||
            frame.maxscl.iter().any(|&v|v>100000)||frame.linear_sum>u64::from(self.width)*u64::from(self.height)*1_000_000_000{
            return Err("incompatible or out-of-order scene frame".into());
        }
        for (sum,&v) in self.bins.iter_mut().zip(&frame.bins){*sum+=u64::from(v);}
        self.linear_sum+=u128::from(frame.linear_sum);
        for (peak,v) in self.maxscl.iter_mut().zip(frame.maxscl){*peak=(*peak).max(v);}
        if self.best.is_none_or(|(a,_,_)|frame.average>=a){self.best=Some((frame.average,index,frame.fraction));}
        self.next+=1;
        Ok(())
    }
    pub(super) fn finish(&self)->Result<SceneResult>{
        if self.next!=self.end{return Err("incomplete scene".into());}
        let (_,brightest_frame,fraction)=self.best.ok_or("empty scene")?;
        let n=self.bins.iter().sum::<u64>();
        let denominator=u128::from(n)*10_000;
        let mut result=SceneResult{first:self.first,end:self.end,brightest_frame,maxscl:self.maxscl,
            average:((self.linear_sum+denominator/2)/denominator) as u32,
            fraction:(fraction*1000.0).round().clamp(1.0,1000.0) as u32,
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
    fn frame(a:u32,b:u32,avg:f64,fraction:f64)->SceneFrame{
        let mut bins=vec![0;BINS as usize];bins[a as usize]+=1;bins[b as usize]+=1;
        SceneFrame{width:2,height:1,maxscl:[a.max(b);3],bins,average:avg,fraction,linear_sum:(u64::from(a)+u64::from(b))*10_000}
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
    fn uhd_scene_exact_average_and_percentile_rank(){
        // 4K contains enough samples to exercise the 99.98% rank above
        // 32-bit multiplication limits. The mean is derived from the
        // pre-quantization sum, not the rounded histogram bins.
        let w=3840u32;let h=2160u32;let n=u64::from(w)*u64::from(h);
        let mut scene=HdrScene::new(0,1,w,h,P).unwrap();
        let mut bins=vec![0;BINS as usize];
        bins[100]=(n-n/5000) as u32;bins[900]=(n/5000) as u32;
        let linear_sum=n*1_000_000 + n/2 + n/5000*8_000_000;
        scene.push(0,SceneFrame{width:w,height:h,maxscl:[900;3],bins,average:0.1,fraction:0.75,linear_sum}).unwrap();
        let result=scene.finish().unwrap();
        assert_eq!(result.average,100);
        assert_eq!(result.percentiles[8],100);
        assert_eq!(result.fraction,750);
        assert!(HdrScene::new(0,1,4096,2160,P).is_err());
    }
    #[test]
    fn mean_quantizes_once_after_full_scene(){
        let mut scene=HdrScene::new(0,1,2,1,P).unwrap();
        let mut f=frame(100,101,0.2,0.5);
        // Both actual pixels round to these histogram bins, but the
        // unrounded average is 100.1 rather than 100.5 metadata units.
        f.linear_sum=2_002_000;
        scene.push(0,f).unwrap();
        assert_eq!(scene.finish().unwrap().average,100);
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

// Version 0 legacy windows have independent selected-pixel populations.
// Geometry may move between frames, but each output frame must use the
// identical authored ellipse in both passes. A scene's topology and
// percentile indices cannot change within its interval.
#[repr(C)]
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)]
pub struct LegacyWindowResult{
    pub count:u32,pub percentages:[u32;15],pub maxscl:[u32;3],
    pub average:u32,pub percentiles:[u32;15],pub fraction:u32,
}
#[repr(C)]
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)]
pub struct LegacySceneResult{
    pub first:u64,pub end:u64,pub count:u32,pub windows:[LegacyWindowResult;3],
}
struct LegacyWindow{
    count:u32,percentages:[u32;15],bins:Vec<u64>,maxscl:[u32;3],linear_sum:u128,
}
impl LegacyWindow{
    fn new(f:&SceneWindowFrame)->Result<Self>{
        if f.count>15||f.percentages[..f.count.min(15) as usize].iter().any(|&v|v>100)||
            f.percentages[..f.count.min(15) as usize].windows(2).any(|p|p[0]>=p[1]){
            return Err("invalid legacy percentile indices".into());
        }
        Ok(Self{count:f.count,percentages:f.percentages,bins:vec![0;BINS as usize],maxscl:[0;3],linear_sum:0})
    }
    fn push(&mut self,f:SceneWindowFrame,w:u32,h:u32,global:bool)->Result<()>{
        let n=f.frame.bins.iter().map(|&v|u64::from(v)).sum::<u64>();
        let area=u64::from(w)*u64::from(h);
        if f.count!=self.count||f.percentages[..f.count.min(15) as usize]!=self.percentages[..self.count as usize]||
            f.frame.width!=w||f.frame.height!=h||f.frame.bins.len()!=BINS as usize||
            n==0||n>area||global&&n!=area||f.frame.linear_sum>n*1_000_000_000||
            f.frame.maxscl.iter().any(|&v|v>100000){
            return Err("invalid legacy selected pixel population".into());
        }
        for (sum,v) in self.bins.iter_mut().zip(f.frame.bins){*sum=sum.checked_add(u64::from(v)).ok_or("legacy histogram overflow")?;}
        for (peak,v) in self.maxscl.iter_mut().zip(f.frame.maxscl){*peak=(*peak).max(v);}
        self.linear_sum+=u128::from(f.frame.linear_sum);
        Ok(())
    }
    fn finish(self)->Result<LegacyWindowResult>{
        let n=self.bins.iter().sum::<u64>();
        if n==0{return Err("empty legacy window".into());}
        let d=u128::from(n)*10000;
        let mut r=LegacyWindowResult{count:self.count,percentages:self.percentages,maxscl:self.maxscl,
            average:((self.linear_sum+d/2)/d) as u32,..Default::default()};
        for (j,&pct) in self.percentages[..self.count as usize].iter().enumerate(){
            let rank=if pct==99{n-n/5000}else{((u128::from(n)*u128::from(pct)+99)/100) as u64}.max(1);
            let mut total=0;
            for (i,&v) in self.bins.iter().enumerate(){total+=v;if total>=rank{r.percentiles[j]=i as u32;break;}}
        }
        // Section 10.3: newly generated version-0 FBP is not measured.
        if !valid_legacy_window(&r){return Err("invalid legacy scene result".into());}
        Ok(r)
    }
}
fn valid_legacy_window(r:&LegacyWindowResult)->bool{
    if r.count>15||r.fraction!=0||r.maxscl.iter().any(|&v|v>100000){return false;}
    let peak=*r.maxscl.iter().max().unwrap_or(&0);
    let p=&r.percentages[..r.count as usize];let v=&r.percentiles[..r.count as usize];
    r.average<=peak&&p.iter().all(|&x|x<=100)&&p.windows(2).all(|x|x[0]<x[1])&&
        v.iter().all(|&x|x<=peak)&&v.windows(2).all(|x|x[0]<=x[1])
}
struct LegacyScene{first:u64,end:u64,next:u64,windows:Vec<LegacyWindow>}
impl LegacyScene{
    fn new(first:u64,end:u64,frames:&[SceneWindowFrame])->Result<Self>{
        if first>=end||!(1..=3).contains(&frames.len()){return Err("invalid legacy scene topology".into());}
        Ok(Self{first,end,next:first,windows:frames.iter().map(LegacyWindow::new).collect::<Result<Vec<_>>>()?})
    }
    fn push(&mut self,index:u64,frames:Vec<SceneWindowFrame>,w:u32,h:u32)->Result<()>{
        if index!=self.next||index>=self.end||frames.len()!=self.windows.len(){return Err("legacy scene topology or order changed".into());}
        for (i,(window,frame)) in self.windows.iter_mut().zip(frames).enumerate(){window.push(frame,w,h,i==0)?;}
        self.next+=1;Ok(())
    }
    fn finish(self)->Result<LegacySceneResult>{
        if self.next!=self.end{return Err("incomplete legacy scene".into());}
        let mut result=LegacySceneResult{first:self.first,end:self.end,count:self.windows.len() as u32,..Default::default()};
        for (i,w) in self.windows.into_iter().enumerate(){result.windows[i]=w.finish()?;}
        Ok(result)
    }
}
// Two-pass cache: explicitly supplied authoritative scene boundaries and
// content fingerprint, renderer/configuration fingerprint, fixed geometry.
// All frames must be captured in presentation order. The cache is create-only
// and is published atomically only on successful explicit completion.
const PASS_P:[u32;9]=[1,5,10,25,50,75,90,95,99];
fn valid_v1_schema(p:&[u32;9])->bool{p.iter().all(|&v|v<=100)&&p.windows(2).all(|x|x[0]<x[1])}
fn valid_v1_values(r:&SceneResult,p:&[u32;9])->bool{
    if !valid_v1_schema(p)||!(1..=1000).contains(&r.fraction)||r.maxscl.iter().any(|&v|v>100000){return false;}
    let peak=*r.maxscl.iter().max().unwrap_or(&0);
    if r.average>peak{return false;}
    let reserved=p[1]==5&&p[2]==10;
    if reserved&&(r.percentiles[1]!=0||r.percentiles[2]!=255){return false;}
    let mut last=0;
    for (i,&v) in r.percentiles.iter().enumerate(){
        if reserved&&(i==1||i==2){continue;}
        if v<last||v>peak{return false;}
        last=v;
    }
    true
}
const MAX_SCENES:usize=100_000;
pub(super) struct TwoPass{
    mode:u32,version:u32,key:String,profile:String,width:u32,height:u32,ends:Vec<u64>,
    path:std::path::PathBuf,next:u64,results:Vec<SceneResult>,current:Option<HdrScene>,legacy_results:Vec<LegacySceneResult>,legacy_current:Option<LegacyScene>,schemas:Vec<[u32;9]>,current_schema:Option<[u32;9]>,failed:bool,
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
        Self::open_version(mode,1,plan,cache,key,profile,w,h)
    }
    pub(super) fn open_version(mode:u32,version:u32,plan:&std::path::Path,cache:&std::path::Path,key:&str,profile:&str,w:u32,h:u32)->Result<Self>{
        if version>1||!matches!(mode,1|2)||key.len()!=64||!key.bytes().all(|v|v.is_ascii_hexdigit())||
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
        let mut s=Self{mode,version,key:key.into(),profile:profile.into(),width:w,height:h,ends,path:cache.into(),
            next:0,results:Vec::new(),current:None,legacy_results:Vec::new(),legacy_current:None,schemas:Vec::new(),current_schema:None,failed:false};
        if mode==2{s.read_cache()?;}
        else if cache.exists(){return Err("scene cache already exists".into());}
        Ok(s)
    }
    fn header(&self)->String{
        format!("AMBIENTSVP_SCENE_CACHE_{}\n{}\n{}\n{} {}\n",self.version,self.key,self.profile,self.width,self.height)
    }
    fn read_cache(&mut self)->Result<()>{
        let contents=read_bounded(&self.path,32_000_000)?;
        let header=self.header();
        let body=contents.strip_prefix(&header).ok_or("scene cache identity mismatch")?;
        if self.version==0{
            let mut start=0;
            for l in body.lines(){
                if self.legacy_results.len()>=self.ends.len(){return Err("extra legacy scene".into());}
                let mut f=l.split_ascii_whitespace();
                let first=parse_num(f.next())?;let end=parse_num(f.next())?;let count:u32=parse_num(f.next())?;
                if first!=start||end!=self.ends[self.legacy_results.len()]||!(1..=3).contains(&count){return Err("legacy cache interval".into());}
                let mut result=LegacySceneResult{first,end,count,..Default::default()};
                for w in &mut result.windows[..count as usize]{
                    w.count=parse_num(f.next())?;
                    if w.count>15{return Err("legacy cache percentile count".into());}
                    for v in &mut w.maxscl{*v=parse_num(f.next())?;}
                    w.average=parse_num(f.next())?;w.fraction=parse_num(f.next())?;
                    for j in 0..w.count as usize{
                        w.percentages[j]=parse_num(f.next())?;w.percentiles[j]=parse_num(f.next())?;
                    }
                    if !valid_legacy_window(w){return Err("invalid legacy cache values".into());}
                }
                if f.next().is_some(){return Err("extra legacy cache fields".into());}
                self.legacy_results.push(result);start=end;
            }
            if self.legacy_results.len()!=self.ends.len(){return Err("incomplete legacy cache".into());}
            return Ok(());
        }
        for l in body.lines(){
            if self.results.len()>=self.ends.len(){return Err("extra scene result".into());}
            let mut fields=l.split_ascii_whitespace();
            let first=parse_num(fields.next())?;let end=parse_num(fields.next())?;
            let brightest_frame=parse_num(fields.next())?;
            let mut maxscl=[0;3];for v in &mut maxscl{*v=parse_num(fields.next())?;}
            let average=parse_num(fields.next())?;
            let mut percentiles=[0;9];for v in &mut percentiles{*v=parse_num(fields.next())?;}
            let fraction=parse_num(fields.next())?;
            let mut schema=PASS_P;
            if let Some(first)=fields.next(){
                schema[0]=parse_num(Some(first))?;
                for v in &mut schema[1..]{*v=parse_num(fields.next())?;}
            }
            if fields.next().is_some()||end!=self.ends[self.results.len()]||!valid_v1_schema(&schema){return Err("scene cache plan mismatch".into());}
            self.schemas.push(schema);
            self.results.push(SceneResult{first,end,brightest_frame,maxscl,average,percentiles,fraction});
        }
        if self.results.iter().zip(&self.schemas).any(|(r,p)|!valid_v1_values(r,p)){
            return Err("invalid cached version-1 distribution".into());
        }
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
    pub(super) fn version(&self)->u32{self.version}
    pub(super) fn abort(&mut self){self.failed=true;}
    pub(super) fn capture_legacy(&mut self,index:u64,frames:Vec<SceneWindowFrame>)->Result<()>{
        let result=(||->Result<()>{
            if self.version!=0||self.mode!=1||self.failed||index!=self.next||self.legacy_results.len()>=self.ends.len(){
                return Err("legacy scene capture order".into());
            }
            let end=self.ends[self.legacy_results.len()];
            if self.legacy_current.is_none(){self.legacy_current=Some(LegacyScene::new(index,end,&frames)?);}
            self.legacy_current.as_mut().unwrap().push(index,frames,self.width,self.height)?;
            self.next+=1;
            if self.next==end{self.legacy_results.push(self.legacy_current.take().unwrap().finish()?);}
            Ok(())
        })();
        if result.is_err(){self.abort();}result
    }
    pub(super) fn lookup_legacy(&mut self,index:u64)->Result<LegacySceneResult>{
        if self.version!=0||self.mode!=2||self.failed||index!=self.next||index>=*self.ends.last().unwrap(){
            self.abort();return Err("legacy replay order".into());
        }
        let i=self.ends.partition_point(|&v|v<=index);
        let result=*self.legacy_results.get(i).ok_or("missing legacy result")?;
        self.next+=1;Ok(result)
    }
    pub(super) fn capture(&mut self,index:u64,frame:SceneFrame)->Result<()>{
        self.capture_v1(index,frame,PASS_P)
    }
    pub(super) fn capture_v1(&mut self,index:u64,frame:SceneFrame,schema:[u32;9])->Result<()>{
        if self.version!=1||self.mode!=1||self.failed||index!=self.next||self.results.len()>=self.ends.len(){
            self.abort();return Err("out of order scene capture".into());
        }
        let end=self.ends[self.results.len()];
        if !valid_v1_schema(&schema)||self.current_schema.is_some_and(|p|p!=schema){self.abort();return Err("scene distribution schema changed".into());}
        if self.current.is_none(){self.current=Some(HdrScene::new(index,end,self.width,self.height,schema)?);self.current_schema=Some(schema);}
        if let Err(e)=self.current.as_mut().unwrap().push(index,frame){self.abort();return Err(e);}
        self.next+=1;
        if self.next==end{
            let result=self.current.take().unwrap().finish()?;
            if !valid_v1_values(&result,&schema){self.abort();return Err("invalid measured scene values".into());}
            self.results.push(result);self.schemas.push(schema);self.current_schema=None;
        }
        Ok(())
    }
    pub(super) fn lookup(&mut self,index:u64)->Result<SceneResult>{self.lookup_v1(index,PASS_P)}
    pub(super) fn lookup_v1(&mut self,index:u64,schema:[u32;9])->Result<SceneResult>{
        if self.version!=1||self.mode!=2||self.failed||index!=self.next||index>=*self.ends.last().unwrap(){
            self.abort();return Err("out of order second pass".into());
        }
        let i=self.ends.partition_point(|&v|v<=index);
        if self.schemas.get(i)!=Some(&schema){self.abort();return Err("scene replay schema mismatch".into());}
        let result=*self.results.get(i).ok_or("missing scene result")?;
        self.next+=1;
        Ok(result)
    }
    pub(super) fn finish(&mut self)->Result<()>{
        use std::io::Write;
        if self.failed||self.next!=*self.ends.last().unwrap()||
            (if self.version==0{self.legacy_results.len()!=self.ends.len()||self.legacy_current.is_some()}
                else{self.results.len()!=self.ends.len()||self.current.is_some()}){
            return Err("incomplete two-pass run".into());
        }
        if self.mode==2{return Ok(());}
        if self.mode!=1{return Err("already finalized".into());}
        let mut data=self.header();
        for (r,schema) in self.results.iter().zip(&self.schemas){
            use std::fmt::Write as _;
            write!(&mut data,"{} {} {}",r.first,r.end,r.brightest_frame).map_err(|_|"cache formatting")?;
            for v in r.maxscl{write!(&mut data," {}",v).map_err(|_|"cache formatting")?;}
            write!(&mut data," {}",r.average).map_err(|_|"cache formatting")?;
            for v in r.percentiles{write!(&mut data," {}",v).map_err(|_|"cache formatting")?;}
            write!(&mut data," {}",r.fraction).map_err(|_|"cache formatting")?;
            if schema!=&PASS_P{for v in schema{write!(&mut data," {}",v).map_err(|_|"cache formatting")?;}}
            data.push('\n');
        }
        for r in &self.legacy_results{
            use std::fmt::Write as _;
            write!(&mut data,"{} {} {}",r.first,r.end,r.count).map_err(|_|"cache formatting")?;
            for w in &r.windows[..r.count as usize]{
                write!(&mut data," {}",w.count).map_err(|_|"cache formatting")?;
                for v in w.maxscl{write!(&mut data," {}",v).map_err(|_|"cache formatting")?;}
                write!(&mut data," {} {}",w.average,w.fraction).map_err(|_|"cache formatting")?;
                for j in 0..w.count as usize{write!(&mut data," {} {}",w.percentages[j],w.percentiles[j]).map_err(|_|"cache formatting")?;}
            }
            data.push('\n');
        }
        if data.len()>32_000_000{return Err("scene cache exceeds read limit".into());}
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
        p.capture(i,SceneFrame{width:2,height:1,maxscl:[100+i as u32;3],bins,average:0.1,fraction:1.0,linear_sum:(100+i)*20_000}).unwrap();
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

#[cfg(test)]
mod legacy_scene_cache_tests{
    use super::*;
    fn frame(values:&[u32],indices:&[u32])->SceneWindowFrame{
        let mut bins=vec![0;BINS as usize];let mut percentages=[0;15];
        percentages[..indices.len()].copy_from_slice(indices);
        for &v in values{bins[v as usize]+=1;}
        SceneWindowFrame{count:indices.len() as u32,percentages,frame:SceneFrame{
            width:4,height:1,maxscl:[*values.iter().max().unwrap();3],bins,average:0.2,fraction:1.0,
            linear_sum:values.iter().map(|&v|u64::from(v)*10_000).sum()}}
    }
    #[test]
    fn legacy_scene_uses_per_window_population_and_no_reserved_markers(){
        let p=[1,5,10,25,50,75,90,95,99];
        let f=vec![frame(&[100,200,300,400],&p),frame(&[300,400],&[5,10,99]),frame(&[100],&[50])];
        let mut s=LegacyScene::new(0,2,&f).unwrap();
        s.push(0,f,4,1).unwrap();
        s.push(1,vec![frame(&[100,200,300,400],&p),frame(&[100],&[5,10,99]),frame(&[400,400],&[50])],4,1).unwrap();
        let r=s.finish().unwrap();
        assert_eq!(r.count,3);
        assert_eq!(r.windows[0].average,250);
        // Independent sorted reference: legacy 5 and 10 are real indices.
        assert_eq!(&r.windows[0].percentiles[..9],&[100,100,100,100,200,300,400,400,400]);
        assert_eq!(r.windows[1].average,267);
        assert_eq!(&r.windows[1].percentiles[..3],&[100,100,400]);
        assert_eq!(r.windows[2].average,300);
        assert_eq!(r.windows[2].percentiles[0],400);
        assert!(r.windows.iter().all(|w|w.fraction==0));
    }
    #[test]
    fn legacy_cache_round_trip_and_v1_arbitrary_indices(){
        let root=std::env::temp_dir().join(format!("ambient-svp-legacy-scene-{}",std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let plan=root.join("plan");let cache=root.join("cache");
        let _=std::fs::remove_file(&cache);
        let key="b".repeat(64);
        std::fs::write(&plan,format!("AMBIENTSVP_SCENE_PLAN_1\n{key}\n2\n")).unwrap();
        let mut p=TwoPass::open_version(1,0,&plan,&cache,&key,"fixture",4,1).unwrap();
        p.capture_legacy(0,vec![frame(&[100,200,300,400],&[5,10,99]),frame(&[400],&[99])]).unwrap();
        assert!(p.finish().is_err());assert!(!cache.exists());
        p.capture_legacy(1,vec![frame(&[100,200,300,400],&[5,10,99]),frame(&[200,300],&[99])]).unwrap();
        p.finish().unwrap();
        assert!(TwoPass::open_version(2,1,&plan,&cache,&key,"fixture",4,1).is_err());
        let mut p=TwoPass::open_version(2,0,&plan,&cache,&key,"fixture",4,1).unwrap();
        let r=p.lookup_legacy(0).unwrap();
        assert_eq!(r.windows[1].average,300);
        assert_eq!(r.windows[0].percentiles[1],100);
        assert_eq!(p.lookup_legacy(1).unwrap(),r);p.finish().unwrap();
        std::fs::remove_file(&cache).unwrap();
        let schema=[0,4,11,26,51,76,91,99,100];
        let mut p=TwoPass::open_version(1,1,&plan,&cache,&key,"fixture",4,1).unwrap();
        for index in 0..2{p.capture_v1(index,frame(&[100,200,300,400],&schema).frame,schema).unwrap();}
        p.finish().unwrap();
        let mut p=TwoPass::open_version(2,1,&plan,&cache,&key,"fixture",4,1).unwrap();
        assert!(p.lookup(0).is_err());
        let mut p=TwoPass::open_version(2,1,&plan,&cache,&key,"fixture",4,1).unwrap();
        let r=p.lookup_v1(0,schema).unwrap();
        assert_eq!(r.percentiles[1],100);assert_eq!(r.percentiles[2],100);
        assert_eq!(p.lookup_v1(1,schema).unwrap(),r);p.finish().unwrap();
        let _=std::fs::remove_dir_all(root);
    }
}

#[cfg(test)]
#[test]
fn legacy_ellipse_span_reference_counts(){
    for angle in [0,90,180]{
        let spans=ellipse_spans([0,0,8,8],[0,0],[4,2,angle],8);
        assert_eq!(spans.chunks_exact(2).map(|p|p[1]-p[0]).sum::<u32>(),10);
    }
    let spans=ellipse_spans([0,0,12,12],[5,5],[4,2,90],12);
    for y in 0..12{
        for x in 0..12{
            let (dx,dy)=(x as i32-5,y as i32-5);
            let expected=dy*dy*4+dx*dx*16<=64;
            assert_eq!(x as u32>=spans[y*2]&&(x as u32)<spans[y*2+1],expected);
        }
    }
}

#[cfg(test)]
#[test]
fn pq_table_matches_independent_decimal_vectors(){
    let t=pq_table();
    assert_eq!(t[0],0);assert_eq!(t[(PQ_CODES-1)*8],100000);
    assert_eq!((0x37ff..=0x3803).map(|i|t[i*8]).collect::<Vec<_>>(),[920,922,927,932,936]);
    assert!(t.chunks_exact(8).map(|v|v[0]).collect::<Vec<_>>().windows(2).all(|p|p[0]<=p[1]));
}
