//! Native Vulkan entry points for the GPU-resident interpolation kernels.
//! The player supplies real VkImageViews on its existing VkDevice. No
//! wgpu re-wrapping, external layout guessing, pixel mapping or readback.
//! All command submission, image transitions and external-memory acquire/
//! release remain the caller's responsibility. See ambientsvp_vulkan.h.
use ash::vk;
use std::{sync::{Arc,Mutex,atomic::{AtomicBool,AtomicU32,Ordering}},panic::{catch_unwind,AssertUnwindSafe},ptr};
use crate::resident::{MOTION,SYNTHESIS};

const MAX_PHASES:usize=8;
const MAX_IN_FLIGHT:u32=4;
const MAX_PIXELS:u64=1920*1080;
type Result<T>=std::result::Result<T,String>;
fn err(e:vk::Result)->String{format!("Vulkan result {}",e.as_raw())}
fn spirv(source:&str,uniform:&str,entry:&str)->Result<Vec<u32>>{
    if source.matches(uniform).count()!=1{return Err("invalid resident uniform contract".into());}
    let name=if entry=="analyse"{"geometry"}else{"params"};
    let source=source.replace(uniform,&format!("var<push_constant> {name}:vec4u;"));
    let module=naga::front::wgsl::parse_str(&source).map_err(|e|e.emit_to_string(&source))?;
    let info=naga::valid::Validator::new(naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::PUSH_CONSTANT).validate(&module).map_err(|e|e.to_string())?;
    naga::back::spv::write_vec(&module,&info,&Default::default(),Some(&naga::back::spv::PipelineOptions{
        shader_stage:naga::ShaderStage::Compute,entry_point:entry.into(),
    })).map_err(|e|e.to_string())
}
fn motion_spirv()->Result<Vec<u32>>{
    spirv(MOTION,"@group(0) @binding(3) var<uniform> geometry:vec4u;","analyse")
}
fn synthesis_spirv()->Result<Vec<u32>>{
    spirv(SYNTHESIS,"@group(0) @binding(8) var<uniform> params:vec4u;","synthesize")
}

struct Pipeline{
    device:ash::Device,
    set:vk::DescriptorSetLayout,
    layout:vk::PipelineLayout,
    pipeline:vk::Pipeline,
}
impl Drop for Pipeline{
    fn drop(&mut self){unsafe{
        self.device.destroy_pipeline(self.pipeline,None);
        self.device.destroy_pipeline_layout(self.layout,None);
        self.device.destroy_descriptor_set_layout(self.set,None);
    }}
}
impl Pipeline{
    fn new(device:&ash::Device,types:&[vk::DescriptorType],words:&[u32],entry:&std::ffi::CStr)->Result<Self>{
        let mut p=Self{device:device.clone(),set:vk::DescriptorSetLayout::null(),
            layout:vk::PipelineLayout::null(),pipeline:vk::Pipeline::null()};
        let bindings=types.iter().enumerate().map(|(i,&t)|vk::DescriptorSetLayoutBinding::default()
            .binding(i as u32).descriptor_count(1).descriptor_type(t).stage_flags(vk::ShaderStageFlags::COMPUTE)
        ).collect::<Vec<_>>();
        unsafe{
            p.set=device.create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),None).map_err(err)?;
            let layouts=[p.set];
            let ranges=[vk::PushConstantRange::default().stage_flags(vk::ShaderStageFlags::COMPUTE).offset(0).size(16)];
            p.layout=device.create_pipeline_layout(&vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts)
                .push_constant_ranges(&ranges),None).map_err(err)?;
            let shader=device.create_shader_module(&vk::ShaderModuleCreateInfo::default().code(words),None).map_err(err)?;
            let stage=vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::COMPUTE).module(shader).name(entry);
            let result=device.create_compute_pipelines(vk::PipelineCache::null(),
                &[vk::ComputePipelineCreateInfo::default().stage(stage).layout(p.layout)],None);
            device.destroy_shader_module(shader,None);
            p.pipeline=match result{
                Ok(pipelines)=>pipelines[0],
                Err((partial,e))=>{for pipeline in partial{device.destroy_pipeline(pipeline,None);}return Err(err(e));}
            };
        }
        Ok(p)
    }
    unsafe fn dispatch(&self,cmd:vk::CommandBuffer,set:vk::DescriptorSet,words:[u32;4],x:u32,y:u32){
        let bytes:Vec<u8>=words.into_iter().flat_map(u32::to_ne_bytes).collect();
        unsafe{
            self.device.cmd_bind_pipeline(cmd,vk::PipelineBindPoint::COMPUTE,self.pipeline);
            self.device.cmd_bind_descriptor_sets(cmd,vk::PipelineBindPoint::COMPUTE,self.layout,0,&[set],&[]);
            self.device.cmd_push_constants(cmd,self.layout,vk::ShaderStageFlags::COMPUTE,0,&bytes);
            self.device.cmd_dispatch(cmd,x,y,1);
        }
    }
}

// Descriptor sets and device-local fields are private to one pair until
// its exact submission fence retires. Cache only fully initialized bundles.
struct PairResources{
    device:ash::Device,pool:vk::DescriptorPool,field:vk::Buffer,
    memory:vk::DeviceMemory,sets:Vec<vk::DescriptorSet>,
}
impl Drop for PairResources{
    fn drop(&mut self){unsafe{
        self.device.destroy_descriptor_pool(self.pool,None);
        self.device.destroy_buffer(self.field,None);
        self.device.free_memory(self.memory,None);
    }}
}
struct Core{
    device:ash::Device,
    memory:vk::PhysicalDeviceMemoryProperties,
    motion:Pipeline,synthesis:Pipeline,
    width:u32,height:u32,gw:u32,gh:u32,field_size:u64,
    in_flight:AtomicU32,
    idle:Mutex<Vec<PairResources>>,
    lost:AtomicBool,
}
pub struct VulkanResident(Arc<Core>);
#[repr(C)]
#[derive(Clone,Copy)]
pub struct VulkanPicture{
    y:vk::ImageView,u:vk::ImageView,v:vk::ImageView,
    // 0: separate R8 U/V; 1: RG8 interleaved UV; 2: SDR RGB in y.
    nv12:u32,
    chroma_location:u32,
}
impl VulkanPicture{
    fn valid(self)->bool{
        self.nv12<=2 && self.chroma_location<=2 && self.y!=vk::ImageView::null()
            && (self.nv12==2 || self.u!=vk::ImageView::null()
            && (self.nv12==1 || self.v!=vk::ImageView::null()))
    }
    fn views(self)->[vk::ImageView;3]{
        if self.nv12==2{[self.y;3]}else{[self.y,self.u,if self.nv12==1{self.u}else{self.v}]}
    }
    fn rgb(self)->bool{self.nv12==2}
}

pub struct VulkanPair{
    core:Arc<Core>,
    pool:vk::DescriptorPool,
    field:vk::Buffer,
    memory:vk::DeviceMemory,
    sets:Vec<vk::DescriptorSet>,
    command:vk::CommandBuffer,
    a:VulkanPicture,b:VulkanPicture,
    phases:usize,
    fence:vk::Fence,
    #[cfg(target_os="android")]
    ahb_inputs:Option<[*const crate::ahb_vulkan::Job;2]>,
}
impl Drop for VulkanPair{
    fn drop(&mut self){unsafe{
        let d=&self.core.device;
        d.destroy_descriptor_pool(self.pool,None);
        d.destroy_buffer(self.field,None);
        d.free_memory(self.memory,None);
        #[cfg(target_os="android")]
        if let Some(inputs)=self.ahb_inputs.take(){for p in inputs{(&*p).release_consumer();}}
        self.core.in_flight.fetch_sub(1,Ordering::AcqRel);
    }}
}
impl VulkanPair{
    fn new(core:Arc<Core>,command:vk::CommandBuffer,a:VulkanPicture,b:VulkanPicture)->Result<Self>{
        if core.lost.load(Ordering::Acquire) || command==vk::CommandBuffer::null() || !a.valid() || !b.valid() || a.rgb()!=b.rgb(){return Err("invalid resident pictures".into());}
        core.in_flight.fetch_update(Ordering::AcqRel,Ordering::Acquire,|n|
            (n<MAX_IN_FLIGHT).then_some(n+1)).map_err(|_|"resident in-flight budget")?;
        let mut p=Self{core,pool:vk::DescriptorPool::null(),field:vk::Buffer::null(),
            memory:vk::DeviceMemory::null(),sets:Vec::new(),command,a,b,phases:0,fence:vk::Fence::null(),
            #[cfg(target_os="android")]
            ahb_inputs:None};
        let cached=p.core.idle.lock().unwrap_or_else(|e|e.into_inner()).pop();
        if let Some(mut cached)=cached{
            p.pool=std::mem::replace(&mut cached.pool,vk::DescriptorPool::null());
            p.field=std::mem::replace(&mut cached.field,vk::Buffer::null());
            p.memory=std::mem::replace(&mut cached.memory,vk::DeviceMemory::null());
            p.sets=std::mem::take(&mut cached.sets);
            return Ok(p);
        }
        let c=&p.core;
        let d=&c.device;
        unsafe{
            p.field=d.create_buffer(&vk::BufferCreateInfo::default().size(c.field_size)
                .usage(vk::BufferUsageFlags::STORAGE_BUFFER).sharing_mode(vk::SharingMode::EXCLUSIVE),None).map_err(err)?;
            let req=d.get_buffer_memory_requirements(p.field);
            let memory_type=(0..c.memory.memory_type_count).find(|&i|
                req.memory_type_bits&(1u32<<i)!=0 &&
                c.memory.memory_types[i as usize].property_flags.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL))
                .ok_or("no device-local motion field memory")?;
            p.memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size)
                .memory_type_index(memory_type),None).map_err(err)?;
            d.bind_buffer_memory(p.field,p.memory,0).map_err(err)?;
            let pool_sizes=[
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::SAMPLED_IMAGE).descriptor_count(2+6*MAX_PHASES as u32),
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::STORAGE_BUFFER).descriptor_count(1+MAX_PHASES as u32),
                vk::DescriptorPoolSize::default().ty(vk::DescriptorType::STORAGE_IMAGE).descriptor_count(MAX_PHASES as u32),
            ];
            p.pool=d.create_descriptor_pool(&vk::DescriptorPoolCreateInfo::default().max_sets(1+MAX_PHASES as u32)
                .pool_sizes(&pool_sizes),None).map_err(err)?;
            let mut layouts=vec![c.synthesis.set;MAX_PHASES+1];layouts[0]=c.motion.set;
            p.sets=d.allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(p.pool)
                .set_layouts(&layouts)).map_err(err)?;
        }
        Ok(p)
    }
    // Called only after successful fence retirement or explicit cancellation
    // of a discarded command buffer. The next pair rewrites every used set.
    fn recycle(&mut self){
        if self.core.lost.load(Ordering::Acquire){return;}
        let mut idle=self.core.idle.lock().unwrap_or_else(|e|e.into_inner());
        if idle.len()>=MAX_IN_FLIGHT as usize{return;}
        idle.push(PairResources{
            device:self.core.device.clone(),
            pool:std::mem::replace(&mut self.pool,vk::DescriptorPool::null()),
            field:std::mem::replace(&mut self.field,vk::Buffer::null()),
            memory:std::mem::replace(&mut self.memory,vk::DeviceMemory::null()),
            sets:std::mem::take(&mut self.sets),
        });
    }
    fn image(&self,set:vk::DescriptorSet,binding:u32,view:vk::ImageView,ty:vk::DescriptorType){
        let info=[vk::DescriptorImageInfo::default().image_view(view).image_layout(vk::ImageLayout::GENERAL)];
        let write=[vk::WriteDescriptorSet::default().dst_set(set).dst_binding(binding).descriptor_type(ty).image_info(&info)];
        unsafe{self.core.device.update_descriptor_sets(&write,&[]);}
    }
    fn field(&self,set:vk::DescriptorSet,binding:u32){
        let info=[vk::DescriptorBufferInfo::default().buffer(self.field).offset(0).range(self.core.field_size)];
        let write=[vk::WriteDescriptorSet::default().dst_set(set).dst_binding(binding)
            .descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&info)];
        unsafe{self.core.device.update_descriptor_sets(&write,&[]);}
    }
    unsafe fn analyse(&self){
        let c=&self.core;
        let set=self.sets[0];
        self.image(set,0,self.a.y,vk::DescriptorType::SAMPLED_IMAGE);
        self.image(set,1,self.b.y,vk::DescriptorType::SAMPLED_IMAGE);
        self.field(set,2);
        unsafe{
            c.motion.dispatch(self.command,set,[c.width,c.height,c.gw,c.gh|if self.a.rgb(){1<<31}else{0}],c.gw,c.gh*2);
            let barrier=[vk::BufferMemoryBarrier::default().src_access_mask(vk::AccessFlags::SHADER_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(self.field).offset(0).size(c.field_size)];
            c.device.cmd_pipeline_barrier(self.command,vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::DependencyFlags::empty(),&[],&barrier,&[]);
        }
    }
    unsafe fn render(&mut self,phase:f32,matrix:u32,output:vk::ImageView)->Result<()>{
        if self.fence!=vk::Fence::null() || self.phases>=MAX_PHASES || !phase.is_finite()
            || !(0.0..=1.0).contains(&phase) || matrix>3 || output==vk::ImageView::null()
            || self.a.views().contains(&output) || self.b.views().contains(&output)
        {return Err("invalid resident output phase/view".into());}
        let set=self.sets[1+self.phases];
        for (binding,view) in self.a.views().into_iter().chain(self.b.views()).enumerate(){
            self.image(set,binding as u32,view,vk::DescriptorType::SAMPLED_IMAGE);
        }
        self.field(set,6);
        self.image(set,7,output,vk::DescriptorType::STORAGE_IMAGE);
        let c=&self.core;
        let flags=if self.a.rgb(){64}else{self.a.nv12|(self.b.nv12<<1)|(self.a.chroma_location<<2)|(self.b.chroma_location<<4)};
        unsafe{c.synthesis.dispatch(self.command,set,[phase.to_bits(),flags,matrix,c.width|(c.height<<16)],
            c.width.div_ceil(8),c.height.div_ceil(8));}
        self.phases+=1;
        Ok(())
    }
}

fn new_core(instance:&ash::Instance,physical:vk::PhysicalDevice,device:ash::Device,width:u32,height:u32)->Result<VulkanResident>{
    if width<32 || height<32 || width>3840 || height>2160 || (width|height)&1!=0 || u64::from(width)*u64::from(height)>MAX_PIXELS{
        return Err("unsupported resident dimensions".into());
    }
    let properties=unsafe{instance.get_physical_device_properties(physical)};
    let memory=unsafe{instance.get_physical_device_memory_properties(physical)};
    let limits=properties.limits;
    let (gw,gh)=(width.div_ceil(16),height.div_ceil(16));
    let field_size=u64::from(gw)*u64::from(gh)*32;
    if width>limits.max_image_dimension2_d || height>limits.max_image_dimension2_d
        || limits.max_per_stage_descriptor_sampled_images<6 || limits.max_per_stage_descriptor_storage_images<1
        || limits.max_per_stage_descriptor_storage_buffers<1 || limits.max_push_constants_size<16
        || limits.max_compute_work_group_invocations<64 || limits.max_compute_work_group_size[0]<64
        || limits.max_compute_work_group_size[1]<8 || gw>limits.max_compute_work_group_count[0]
        || gh*2>limits.max_compute_work_group_count[1] || width.div_ceil(8)>limits.max_compute_work_group_count[0]
        || height.div_ceil(8)>limits.max_compute_work_group_count[1] || field_size>u64::from(limits.max_storage_buffer_range)
    {return Err("native Vulkan resident limits".into());}
    for format in [vk::Format::R8_UNORM,vk::Format::R8G8_UNORM,vk::Format::R8G8B8A8_UNORM]{
        let f=unsafe{instance.get_physical_device_format_properties(physical,format)};
        let needed=if format==vk::Format::R8G8B8A8_UNORM{
            vk::FormatFeatureFlags::STORAGE_IMAGE|vk::FormatFeatureFlags::SAMPLED_IMAGE
        }else{vk::FormatFeatureFlags::SAMPLED_IMAGE};
        if !f.optimal_tiling_features.contains(needed){return Err("resident image format unsupported".into());}
    }
    let motion=Pipeline::new(&device,&[vk::DescriptorType::SAMPLED_IMAGE,vk::DescriptorType::SAMPLED_IMAGE,
        vk::DescriptorType::STORAGE_BUFFER],&motion_spirv()?,c"analyse")?;
    let mut types=vec![vk::DescriptorType::SAMPLED_IMAGE;6];
    types.extend([vk::DescriptorType::STORAGE_BUFFER,vk::DescriptorType::STORAGE_IMAGE]);
    let synthesis=Pipeline::new(&device,&types,&synthesis_spirv()?,c"synthesize")?;
    Ok(VulkanResident(Arc::new(Core{device,memory,motion,synthesis,width,height,gw,gh,field_size,in_flight:AtomicU32::new(0),idle:Mutex::new(Vec::new()),lost:AtomicBool::new(false)})))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_create(instance:vk::Instance,physical:vk::PhysicalDevice,device:vk::Device,
    get_proc:Option<vk::PFN_vkGetInstanceProcAddr>,width:u32,height:u32)->*mut VulkanResident{
    if instance==vk::Instance::null() || physical==vk::PhysicalDevice::null() || device==vk::Device::null() || get_proc.is_none(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let f=ash::StaticFn{get_instance_proc_addr:get_proc.unwrap()};
        let instance=unsafe{ash::Instance::load(&f,instance)};
        let device=unsafe{ash::Device::load(instance.fp_v1_0(),device)};
        new_core(&instance,physical,device,width,height).map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s)))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_destroy(s:*mut VulkanResident){
    if !s.is_null(){let _=catch_unwind(AssertUnwindSafe(||unsafe{drop(Box::from_raw(s))}));}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_begin(s:*const VulkanResident,command:vk::CommandBuffer,
    a:*const VulkanPicture,b:*const VulkanPicture)->*mut VulkanPair{
    if s.is_null() || a.is_null() || b.is_null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let core=unsafe{(*s).0.clone()};
        let Ok(pair)=VulkanPair::new(core,command,unsafe{*a},unsafe{*b})else{return ptr::null_mut();};
        unsafe{pair.analyse();}
        Box::into_raw(Box::new(pair))
    })).unwrap_or(ptr::null_mut())
}
/// Begin a GPU-only resident pair directly from converted MediaCodec jobs.
/// Same queue/device and geometry are checked. The caller must record and
/// submit on that queue after the converter submissions. Each job remains
/// pinned until the pair fence retires (or an unsubmitted pair is canceled).
#[cfg(target_os="android")]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_begin_ahb(s:*const VulkanResident,command:vk::CommandBuffer,
    queue:vk::Queue,a:*const crate::ahb_vulkan::Job,b:*const crate::ahb_vulkan::Job)->*mut VulkanPair{
    if s.is_null() || a.is_null() || b.is_null() || queue==vk::Queue::null(){return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let core=unsafe{(*s).0.clone()};
        let convert=|p:*const crate::ahb_vulkan::Job|{
            let (device,q,w,h,view)=unsafe{(&*p).picture()};
            if device!=core.device.handle() || q!=queue || (w,h)!=(core.width,core.height){return None;}
            Some(VulkanPicture{y:view,u:vk::ImageView::null(),v:vk::ImageView::null(),nv12:2,chroma_location:0})
        };
        let (Some(pa),Some(pb))=(convert(a),convert(b))else{return ptr::null_mut();};
        let Ok(mut pair)=VulkanPair::new(core,command,pa,pb)else{return ptr::null_mut();};
        unsafe{(&*a).retain_consumer();(&*b).retain_consumer();}
        pair.ahb_inputs=Some([a,b]);
        unsafe{pair.analyse();}
        Box::into_raw(Box::new(pair))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_render(pair:*mut VulkanPair,phase:f32,matrix:u32,output:vk::ImageView)->i32{
    if pair.is_null(){return -1;}
    catch_unwind(AssertUnwindSafe(||unsafe{(&mut *pair).render(phase,matrix,output)}.map_or(-1,|_|0))).unwrap_or(-1)
}
// The caller must pass the fence of the successful submission containing
// this exact command buffer. The fence is borrowed and must not be reset
// or destroyed before release. Never free in-flight descriptors/images.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_seal(pair:*mut VulkanPair,fence:vk::Fence)->i32{
    if pair.is_null() || fence==vk::Fence::null(){return -1;}
    let p=unsafe{&mut *pair};
    if p.fence!=vk::Fence::null() || p.phases==0{return -1;}
    p.fence=fence;0
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_release(pair:*mut VulkanPair)->i32{
    if pair.is_null(){return -1;}
    let p=unsafe{&*pair};
    if p.fence==vk::Fence::null(){return -1;}
    match unsafe{p.core.device.get_fence_status(p.fence)}{
        Ok(true)=>{let mut p=unsafe{Box::from_raw(pair)};p.recycle();drop(p);1}
        Ok(false)=>0,
        // A confirmed lost device no longer executes the submission.
        Err(vk::Result::ERROR_DEVICE_LOST)=>{p.core.lost.store(true,Ordering::Release);unsafe{drop(Box::from_raw(pair))};1}
        Err(_)=>-1,
    }
}
// Only after discarding/resetting the UNSUBMITTED command buffer. Never
// cancel a submitted pair. Device-lost teardown is explicit at the caller.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_vk_cancel(pair:*mut VulkanPair)->i32{
    if pair.is_null() || unsafe{(*pair).fence}!=vk::Fence::null(){return -1;}
    let mut p=unsafe{Box::from_raw(pair)};p.recycle();drop(p);0
}

#[cfg(test)]
mod tests{
    #[test]
    fn resident_native_shaders_compile_to_spirv(){
        let m=super::motion_spirv().unwrap();
        let s=super::synthesis_spirv().unwrap();
        assert_eq!(m[0],0x07230203);assert_eq!(s[0],0x07230203);
        assert!(m.len()>100 && s.len()>100);
    }
}

#[cfg(test)]
mod gpu_tests{
    use super::*;
    struct Image{image:vk::Image,view:vk::ImageView,memory:vk::DeviceMemory}
    fn memory_index(props:&vk::PhysicalDeviceMemoryProperties,bits:u32,flags:vk::MemoryPropertyFlags)->u32{
        (0..props.memory_type_count).find(|&i|bits&(1<<i)!=0&&props.memory_types[i as usize].property_flags.contains(flags)).unwrap()
    }
    unsafe fn image(d:&ash::Device,props:&vk::PhysicalDeviceMemoryProperties,w:u32,h:u32,format:vk::Format,usage:vk::ImageUsageFlags)->Image{
        let image=unsafe{d.create_image(&vk::ImageCreateInfo::default().image_type(vk::ImageType::TYPE_2D)
            .format(format).extent(vk::Extent3D{width:w,height:h,depth:1}).mip_levels(1).array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1).tiling(vk::ImageTiling::OPTIMAL)
            .usage(usage).sharing_mode(vk::SharingMode::EXCLUSIVE).initial_layout(vk::ImageLayout::UNDEFINED),None)}.unwrap();
        let req=unsafe{d.get_image_memory_requirements(image)};
        let memory=unsafe{d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size)
            .memory_type_index(memory_index(props,req.memory_type_bits,vk::MemoryPropertyFlags::DEVICE_LOCAL)),None)}.unwrap();
        unsafe{d.bind_image_memory(image,memory,0)}.unwrap();
        let view=unsafe{d.create_image_view(&vk::ImageViewCreateInfo::default().image(image).view_type(vk::ImageViewType::TYPE_2D)
            .format(format).subresource_range(range()),None)}.unwrap();
        Image{image,view,memory}
    }
    fn range()->vk::ImageSubresourceRange{
        vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR).level_count(1).layer_count(1)
    }
    unsafe fn transition(d:&ash::Device,cmd:vk::CommandBuffer,img:vk::Image,
        old:vk::ImageLayout,new:vk::ImageLayout,src:vk::AccessFlags,dst:vk::AccessFlags,
        stage_src:vk::PipelineStageFlags,stage_dst:vk::PipelineStageFlags){
        let barriers=[vk::ImageMemoryBarrier::default().image(img).old_layout(old).new_layout(new)
            .src_access_mask(src).dst_access_mask(dst)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .subresource_range(range())];
        unsafe{d.cmd_pipeline_barrier(cmd,stage_src,stage_dst,vk::DependencyFlags::empty(),&[],&[],&barriers)}
    }
    #[test]
    #[ignore="requires Vulkan, including Mesa software Vulkan"]
    fn native_resident_commands_keep_images_on_shared_device(){
        unsafe{
            let entry=ash::Entry::load().unwrap();
            let app=vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_1);
            let instance=entry.create_instance(&vk::InstanceCreateInfo::default().application_info(&app),None).unwrap();
            let (physical,family)=instance.enumerate_physical_devices().unwrap().into_iter()
                .find_map(|p|instance.get_physical_device_queue_family_properties(p).iter().position(|q|
                    q.queue_count>0&&q.queue_flags.contains(vk::QueueFlags::COMPUTE)).map(|i|(p,i as u32))).unwrap();
            let priorities=[1.0];
            let queues=[vk::DeviceQueueCreateInfo::default().queue_family_index(family).queue_priorities(&priorities)];
            let d=instance.create_device(physical,&vk::DeviceCreateInfo::default().queue_create_infos(&queues),None).unwrap();
            let queue=d.get_device_queue(family,0);
            let props=instance.get_physical_device_memory_properties(physical);
            let (w,h)=(66u32,34u32);
            let engine=ambient_svp_vk_create(instance.handle(),physical,d.handle(),
                Some(entry.static_fn().get_instance_proc_addr),w,h);
            assert!(!engine.is_null(),"native resident initialization failed");
            let pool=d.create_command_pool(&vk::CommandPoolCreateInfo::default().queue_family_index(family)
                .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),None).unwrap();
            let cmd=d.allocate_command_buffers(&vk::CommandBufferAllocateInfo::default().command_pool(pool)
                .level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1)).unwrap()[0];
            let src=vk::ImageUsageFlags::SAMPLED|vk::ImageUsageFlags::TRANSFER_DST;
            let out=vk::ImageUsageFlags::STORAGE|vk::ImageUsageFlags::SAMPLED|vk::ImageUsageFlags::TRANSFER_SRC;
            let images=[
                image(&d,&props,w,h,vk::Format::R8_UNORM,src),
                image(&d,&props,w,h,vk::Format::R8_UNORM,src),
                image(&d,&props,w/2,h/2,vk::Format::R8G8_UNORM,src),
                image(&d,&props,w/2,h/2,vk::Format::R8_UNORM,src),
                image(&d,&props,w/2,h/2,vk::Format::R8_UNORM,src),
                image(&d,&props,w,h,vk::Format::R8G8B8A8_UNORM,out),
                image(&d,&props,w,h,vk::Format::R8G8B8A8_UNORM,out),
                image(&d,&props,w,h,vk::Format::R8G8B8A8_UNORM,out),
            ];
            let bytes=u64::from(w*h*4);
            let readback=d.create_buffer(&vk::BufferCreateInfo::default().size(bytes*3)
                .usage(vk::BufferUsageFlags::TRANSFER_DST).sharing_mode(vk::SharingMode::EXCLUSIVE),None).unwrap();
            let req=d.get_buffer_memory_requirements(readback);
            let readback_memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size)
                .memory_type_index(memory_index(&props,req.memory_type_bits,
                    vk::MemoryPropertyFlags::HOST_VISIBLE|vk::MemoryPropertyFlags::HOST_COHERENT)),None).unwrap();
            d.bind_buffer_memory(readback,readback_memory,0).unwrap();
            let fence=d.create_fence(&Default::default(),None).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            for (i,img) in images.iter().enumerate(){
                if i<5{
                    transition(&d,cmd,img.image,vk::ImageLayout::UNDEFINED,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::empty(),vk::AccessFlags::TRANSFER_WRITE,
                        vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::TRANSFER);
                    let value=[64.0,96.0,128.0,128.0,128.0][i]/255.0;
                    d.cmd_clear_color_image(cmd,img.image,vk::ImageLayout::GENERAL,
                        &vk::ClearColorValue{float32:[value,value,0.0,1.0]},&[range()]);
                    transition(&d,cmd,img.image,vk::ImageLayout::GENERAL,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::TRANSFER_WRITE,vk::AccessFlags::SHADER_READ,
                        vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
                }else{
                    transition(&d,cmd,img.image,vk::ImageLayout::UNDEFINED,vk::ImageLayout::GENERAL,
                        vk::AccessFlags::empty(),vk::AccessFlags::SHADER_WRITE,
                        vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::COMPUTE_SHADER);
                }
            }
            let a=VulkanPicture{y:images[0].view,u:images[2].view,v:vk::ImageView::null(),nv12:1,chroma_location:1};
            let b=VulkanPicture{y:images[1].view,u:images[3].view,v:images[4].view,nv12:0,chroma_location:2};
            let pair=ambient_svp_vk_begin(engine,cmd,&a,&b);
            assert!(!pair.is_null());
            assert_eq!(ambient_svp_vk_release(pair),-1);
            assert_eq!(ambient_svp_vk_render(pair,0.5,3,a.y),-1);
            assert_eq!(ambient_svp_vk_render(pair,f32::NAN,3,images[5].view),-1);
            for (i,phase) in [0.0,0.5,1.0].into_iter().enumerate(){
                assert_eq!(ambient_svp_vk_render(pair,phase,3,images[5+i].view),0);
            }
            // Readback belongs exclusively to this test. Production API
            // has no pixel upload, buffer map, copy or queue wait.
            for (i,img) in images[5..].iter().enumerate(){
                transition(&d,cmd,img.image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                    vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                    vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
                let region=[vk::BufferImageCopy::default().buffer_offset(bytes*i as u64)
                    .image_subresource(vk::ImageSubresourceLayers::default().aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1))
                    .image_extent(vk::Extent3D{width:w,height:h,depth:1})];
                d.cmd_copy_image_to_buffer(cmd,img.image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
            }
            let host=[vk::MemoryBarrier::default().src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            assert_eq!(ambient_svp_vk_seal(pair,fence),0);
            assert_eq!(ambient_svp_vk_cancel(pair),-1);
            assert_eq!(ambient_svp_vk_render(pair,0.25,3,images[5].view),-1);
            // Destroy the session handle before completion: pair Arc
            // retains pipelines until the actual fence is signaled.
            ambient_svp_vk_destroy(engine);
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            assert_eq!(ambient_svp_vk_release(pair),1);
            let mapped=d.map_memory(readback_memory,0,bytes*3,vk::MemoryMapFlags::empty()).unwrap();
            let data=std::slice::from_raw_parts(mapped.cast::<u8>(),(bytes*3) as usize);
            for (i,expected) in [64u8,80,96].into_iter().enumerate(){
                for pixel in data[i*bytes as usize..(i+1)*bytes as usize].chunks_exact(4){
                    assert!(pixel[..3].iter().all(|&v|v.abs_diff(expected)<=1)&&pixel[3]==255,
                        "unexpected resident output phase {i}: {pixel:?}");
                }
            }
            d.unmap_memory(readback_memory);
            // Simulate the RGB textures produced by Android external-format
            // conversion, without uploading or mapping any source pixels.
            // Reuse the two GPU endpoint outputs as the next input pair.
            let engine=ambient_svp_vk_create(instance.handle(),physical,d.handle(),
                Some(entry.static_fn().get_instance_proc_addr),w,h);
            assert!(!engine.is_null());
            d.reset_fences(&[fence]).unwrap();
            d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            for (i,img) in images[5..].iter().enumerate(){
                transition(&d,cmd,img.image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,vk::ImageLayout::GENERAL,
                    vk::AccessFlags::TRANSFER_READ,if i==1{vk::AccessFlags::SHADER_WRITE}else{vk::AccessFlags::SHADER_READ},
                    vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
            }
            let rgb=|view|VulkanPicture{y:view,u:vk::ImageView::null(),v:vk::ImageView::null(),nv12:2,chroma_location:0};
            let a=rgb(images[5].view);let b=rgb(images[7].view);
            let pair=ambient_svp_vk_begin(engine,cmd,&a,&b);
            assert!(!pair.is_null());
            let handles=((&*pair).field,(&*pair).pool,(&*pair).sets.clone());
            // Matrix=limited BT601 intentionally: RGB must not be converted again.
            assert_eq!(ambient_svp_vk_render(pair,0.5,0,images[6].view),0);
            transition(&d,cmd,images[6].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
            let region=[vk::BufferImageCopy::default().image_subresource(vk::ImageSubresourceLayers::default()
                .aspect_mask(vk::ImageAspectFlags::COLOR).layer_count(1)).image_extent(vk::Extent3D{width:w,height:h,depth:1})];
            d.cmd_copy_image_to_buffer(cmd,images[6].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            assert_eq!(ambient_svp_vk_seal(pair,fence),0);
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            assert_eq!(ambient_svp_vk_release(pair),1);
            let mapped=d.map_memory(readback_memory,0,bytes,vk::MemoryMapFlags::empty()).unwrap();
            for pixel in std::slice::from_raw_parts(mapped.cast::<u8>(),bytes as usize).chunks_exact(4){
                assert!(pixel[..3].iter().all(|&v|v.abs_diff(80)<=1)&&pixel[3]==255,"RGB resident result {pixel:?}");
            }
            d.unmap_memory(readback_memory);
            // The completed pair's entire bundle is reusable, but no
            // in-flight bundle can be borrowed by another pair.
            assert_eq!((&*engine).0.idle.lock().unwrap().len(),1);
            d.reset_fences(&[fence]).unwrap();
            d.reset_command_buffer(cmd,vk::CommandBufferResetFlags::empty()).unwrap();
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
            transition(&d,cmd,images[6].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,vk::ImageLayout::GENERAL,
                vk::AccessFlags::TRANSFER_READ,vk::AccessFlags::SHADER_WRITE,
                vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::COMPUTE_SHADER);
            let stationary=ambient_svp_vk_begin(engine,cmd,&a,&a);
            assert!(!stationary.is_null());
            assert_eq!(((&*stationary).field,(&*stationary).pool,&(&*stationary).sets),(handles.0,handles.1,&handles.2));
            assert_eq!((&*engine).0.idle.lock().unwrap().len(),0);
            assert_eq!(ambient_svp_vk_render(stationary,0.5,0,images[6].view),0);
            transition(&d,cmd,images[6].image,vk::ImageLayout::GENERAL,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                vk::AccessFlags::SHADER_WRITE,vk::AccessFlags::TRANSFER_READ,
                vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::TRANSFER);
            d.cmd_copy_image_to_buffer(cmd,images[6].image,vk::ImageLayout::TRANSFER_SRC_OPTIMAL,readback,&region);
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TRANSFER,vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),&host,&[],&[]);
            d.end_command_buffer(cmd).unwrap();
            d.queue_submit(queue,&[vk::SubmitInfo::default().command_buffers(&[cmd])],fence).unwrap();
            assert_eq!(ambient_svp_vk_seal(stationary,fence),0);
            d.wait_for_fences(&[fence],true,20_000_000_000).unwrap();
            assert_eq!(ambient_svp_vk_release(stationary),1);
            let mapped=d.map_memory(readback_memory,0,bytes,vk::MemoryMapFlags::empty()).unwrap();
            for pixel in std::slice::from_raw_parts(mapped.cast::<u8>(),bytes as usize).chunks_exact(4){
                assert!(pixel[..3].iter().all(|&v|v.abs_diff(64)<=1)&&pixel[3]==255,"reused resident result {pixel:?}");
            }
            d.unmap_memory(readback_memory);
            ambient_svp_vk_destroy(engine);
            d.destroy_fence(fence,None);d.destroy_command_pool(pool,None);
            d.destroy_buffer(readback,None);d.free_memory(readback_memory,None);
            for img in images{
                d.destroy_image_view(img.view,None);d.destroy_image(img.image,None);d.free_memory(img.memory,None);
            }
            d.destroy_device(None);instance.destroy_instance(None);
        }
    }
}
