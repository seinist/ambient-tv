//! GPU-only Android external-memory consumer for MediaCodec AImageReader.
//! Borrow the renderer's Vulkan device and compute queue. Import the original
//! AHB allocation, use an immutable YCbCr conversion sampler, then produce
//! a GPU-local RGB texture for resident motion/synthesis. There is no
//! AHardwareBuffer_lock, host image upload or pixel readback.
use ash::vk;
use crate::mediacodec_reader::{Frame,FrameInfo,AHardwareBuffer_acquire,AHardwareBuffer_release};
use std::{ffi::{c_void,CStr,c_char},ptr,sync::{Arc,atomic::{AtomicU32,Ordering}},
    panic::{catch_unwind,AssertUnwindSafe}};
use std::os::fd::{FromRawFd,IntoRawFd,OwnedFd,AsRawFd};

const MAX_JOBS:u32=4;
type Result<T>=std::result::Result<T,String>;
fn err(e:vk::Result)->String{format!("Vulkan result {}",e.as_raw())}
const HANDLE:vk::ExternalSemaphoreHandleTypeFlags=vk::ExternalSemaphoreHandleTypeFlags::SYNC_FD;
const MEMORY:vk::ExternalMemoryHandleTypeFlags=vk::ExternalMemoryHandleTypeFlags::ANDROID_HARDWARE_BUFFER_ANDROID;
fn range()->vk::ImageSubresourceRange{
    vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR).level_count(1).layer_count(1)
}
struct Core{
    device:ash::Device,
    ahb:ash::android::external_memory_android_hardware_buffer::Device,
    fd:ash::khr::external_semaphore_fd::Device,
    memory:vk::PhysicalDeviceMemoryProperties,
    limits:vk::PhysicalDeviceLimits,
    queue:vk::Queue,family:u32,
    in_flight:AtomicU32,
}
pub struct Importer(Arc<Core>);
fn memory_type(core:&Core,bits:u32,local:bool)->Option<u32>{
    (0..core.memory.memory_type_count).find(|&i|{
        let flags=core.memory.memory_types[i as usize].property_flags;
        bits&(1u32<<i)!=0 && !flags.contains(vk::MemoryPropertyFlags::PROTECTED)
            && (!local || flags.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL))
    })
}

/// The extension list and feature flag are the ACTUALLY ENABLED device
/// configuration supplied by the device owner, not merely advertised caps.
unsafe fn make_importer(instance:vk::Instance,physical:vk::PhysicalDevice,device:vk::Device,
    get_proc:vk::PFN_vkGetInstanceProcAddr,queue:vk::Queue,family:u32,
    enabled:*const *const c_char,count:u32,ycbcr_enabled:u32)->Result<Importer>{
    if instance==vk::Instance::null() || physical==vk::PhysicalDevice::null()
        || device==vk::Device::null() || queue==vk::Queue::null()
        || enabled.is_null() || count==0 || count>512 || ycbcr_enabled!=1
    {return Err("invalid device contract".into());}
    let names=unsafe{std::slice::from_raw_parts(enabled,count as usize)};
    for required in [c"VK_ANDROID_external_memory_android_hardware_buffer",
        c"VK_EXT_queue_family_foreign",c"VK_KHR_external_semaphore_fd"]{
        if !names.iter().any(|&p|!p.is_null()&&unsafe{CStr::from_ptr(p)}==required){
            return Err("required extension not enabled".into());
        }
    }
    let static_fn=ash::StaticFn{get_instance_proc_addr:get_proc};
    let instance=unsafe{ash::Instance::load(&static_fn,instance)};
    let properties=unsafe{instance.get_physical_device_properties(physical)};
    if properties.api_version<vk::API_VERSION_1_1{return Err("Vulkan 1.1 required".into());}
    let queues=unsafe{instance.get_physical_device_queue_family_properties(physical)};
    if !queues.get(family as usize).is_some_and(|q|q.queue_count>0&&q.queue_flags.contains(vk::QueueFlags::COMPUTE)){
        return Err("compute queue required".into());
    }
    let mut conversion=vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
    let mut features=vk::PhysicalDeviceFeatures2::default().push_next(&mut conversion);
    unsafe{instance.get_physical_device_features2(physical,&mut features)};
    if conversion.sampler_ycbcr_conversion==0{return Err("YCbCr conversion unavailable".into());}
    let mut semaphore=vk::ExternalSemaphoreProperties::default();
    unsafe{instance.get_physical_device_external_semaphore_properties(physical,
        &vk::PhysicalDeviceExternalSemaphoreInfo::default().handle_type(HANDLE),&mut semaphore)};
    if !semaphore.external_semaphore_features.contains(
        vk::ExternalSemaphoreFeatureFlags::IMPORTABLE|vk::ExternalSemaphoreFeatureFlags::EXPORTABLE){
        return Err("sync_file semaphore interop unavailable".into());
    }
    let format=unsafe{instance.get_physical_device_format_properties(physical,vk::Format::R8G8B8A8_UNORM)};
    if !format.optimal_tiling_features.contains(vk::FormatFeatureFlags::STORAGE_IMAGE|vk::FormatFeatureFlags::SAMPLED_IMAGE){
        return Err("RGBA storage/sampling unavailable".into());
    }
    // Never call a generated ash fallback stub for a missing extension.
    for name in [c"vkGetAndroidHardwareBufferPropertiesANDROID",c"vkImportSemaphoreFdKHR",c"vkGetSemaphoreFdKHR",
        c"vkCreateSamplerYcbcrConversion",c"vkDestroySamplerYcbcrConversion"]{
        if unsafe{instance.get_device_proc_addr(device,name.as_ptr())}.is_none(){
            return Err("required device entry point missing".into());
        }
    }
    let d=unsafe{ash::Device::load(instance.fp_v1_0(),device)};
    let ahb=ash::android::external_memory_android_hardware_buffer::Device::new(&instance,&d);
    let fd=ash::khr::external_semaphore_fd::Device::new(&instance,&d);
    let memory=unsafe{instance.get_physical_device_memory_properties(physical)};
    Ok(Importer(Arc::new(Core{device:d,ahb,fd,memory,limits:properties.limits,queue,family,in_flight:AtomicU32::new(0)})))
}

pub struct Job{
    core:Arc<Core>,info:FrameInfo,
    buffer:*mut c_void,source:vk::Image,source_memory:vk::DeviceMemory,source_view:vk::ImageView,
    conversion:vk::SamplerYcbcrConversion,sampler:vk::Sampler,
    output:vk::Image,output_memory:vk::DeviceMemory,output_view:vk::ImageView,
    set_layout:vk::DescriptorSetLayout,pipeline_layout:vk::PipelineLayout,pipeline:vk::Pipeline,
    pool:vk::DescriptorPool,commands:vk::CommandPool,fence:vk::Fence,
    wait:vk::Semaphore,signal:vk::Semaphore,
    frame:Option<Box<Frame>>,submitted:bool,completed:bool,
    consumers:AtomicU32,
}
impl Drop for Job{
    fn drop(&mut self){
        // Construction failure (never submitted) or confirmed completion.
        // On failed release-fence export keep the AImage until this point.
        if self.completed{
            if let Some(frame)=self.frame.as_mut(){frame.return_after_gpu(-1);}
        }
        // If a device is lost before its acquire wait completes, Frame's
        // Drop returns the original producer fence rather than -1.
        let d=&self.core.device;
        unsafe{
            d.destroy_command_pool(self.commands,None);
            d.destroy_descriptor_pool(self.pool,None);
            d.destroy_pipeline(self.pipeline,None);
            d.destroy_pipeline_layout(self.pipeline_layout,None);
            d.destroy_descriptor_set_layout(self.set_layout,None);
            d.destroy_sampler(self.sampler,None);
            d.destroy_image_view(self.source_view,None);
            d.destroy_sampler_ycbcr_conversion(self.conversion,None);
            d.destroy_image(self.source,None);
            d.free_memory(self.source_memory,None);
            d.destroy_image_view(self.output_view,None);
            d.destroy_image(self.output,None);
            d.free_memory(self.output_memory,None);
            d.destroy_semaphore(self.wait,None);d.destroy_semaphore(self.signal,None);
            d.destroy_fence(self.fence,None);
            if !self.buffer.is_null(){AHardwareBuffer_release(self.buffer);}
        }
        self.core.in_flight.fetch_sub(1,Ordering::AcqRel);
    }
}
impl Job{
    fn new(core:Arc<Core>,frame:&Frame)->Result<Self>{
        let info=frame.info;
        if frame.buffer.is_null() || info.buffer_width>core.limits.max_image_dimension2_d
            || info.buffer_height>core.limits.max_image_dimension2_d
            || core.limits.max_push_constants_size<24
            || core.limits.max_compute_work_group_invocations<64
            || core.limits.max_compute_work_group_size[0]<8 || core.limits.max_compute_work_group_size[1]<8
            || info.width.div_ceil(8)>core.limits.max_compute_work_group_count[0]
            || info.height.div_ceil(8)>core.limits.max_compute_work_group_count[1]
        {return Err("AHB image/dispatch limits".into());}
        core.in_flight.fetch_update(Ordering::AcqRel,Ordering::Acquire,|n|(n<MAX_JOBS).then_some(n+1))
            .map_err(|_|"AHB outstanding job budget")?;
        let mut j=Self{core,info,buffer:frame.buffer,source:vk::Image::null(),source_memory:vk::DeviceMemory::null(),
            source_view:vk::ImageView::null(),conversion:vk::SamplerYcbcrConversion::null(),sampler:vk::Sampler::null(),
            output:vk::Image::null(),output_memory:vk::DeviceMemory::null(),output_view:vk::ImageView::null(),
            set_layout:vk::DescriptorSetLayout::null(),pipeline_layout:vk::PipelineLayout::null(),pipeline:vk::Pipeline::null(),
            pool:vk::DescriptorPool::null(),commands:vk::CommandPool::null(),fence:vk::Fence::null(),
            wait:vk::Semaphore::null(),signal:vk::Semaphore::null(),frame:None,submitted:false,
            completed:false,consumers:AtomicU32::new(0)};
        unsafe{AHardwareBuffer_acquire(j.buffer)};
        j.import()?;
        j.create_output()?;
        j.pipeline()?;
        j.fences(frame.acquire_fd)?;
        Ok(j)
    }
    pub(crate) fn picture(&self)->(vk::Device,vk::Queue,u32,u32,vk::ImageView){
        (self.core.device.handle(),self.core.queue,self.info.width,self.info.height,self.output_view)
    }
    pub(crate) fn retain_consumer(&self){self.consumers.fetch_add(1,Ordering::AcqRel);}
    pub(crate) fn release_consumer(&self){self.consumers.fetch_sub(1,Ordering::AcqRel);}
    fn import(&mut self)->Result<()>{
        let d=&self.core.device;
        let mut fmt=vk::AndroidHardwareBufferFormatPropertiesANDROID::default();
        let mut props=vk::AndroidHardwareBufferPropertiesANDROID::default().push_next(&mut fmt);
        unsafe{self.core.ahb.get_android_hardware_buffer_properties(self.buffer.cast_const(),&mut props)}.map_err(err)?;
        let (size,bits)=(props.allocation_size,props.memory_type_bits);
        if size==0 || fmt.external_format==0 || !fmt.format_features.contains(vk::FormatFeatureFlags::SAMPLED_IMAGE){
            return Err("AHB external format unavailable".into());
        }
        // Use the external-format path even if a conventional VkFormat
        // also exists. Preserve the allocator/decoder's actual format.
        let mut ext_format=vk::ExternalFormatANDROID::default().external_format(fmt.external_format);
        let mut ext_memory=vk::ExternalMemoryImageCreateInfo::default().handle_types(MEMORY);
        let image=vk::ImageCreateInfo::default().push_next(&mut ext_memory).push_next(&mut ext_format)
            .image_type(vk::ImageType::TYPE_2D).format(vk::Format::UNDEFINED)
            .extent(vk::Extent3D{width:self.info.buffer_width,height:self.info.buffer_height,depth:1})
            .mip_levels(1).array_layers(1).samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::OPTIMAL).usage(vk::ImageUsageFlags::SAMPLED)
            .sharing_mode(vk::SharingMode::EXCLUSIVE).initial_layout(vk::ImageLayout::UNDEFINED);
        unsafe{
            self.source=d.create_image(&image,None).map_err(err)?;
            // AHB allocation size and memory type bits come directly from
            // vkGetAndroidHardwareBufferPropertiesANDROID; no pre-bind
            // vkGetImageMemoryRequirements call on an external image.
            let index=memory_type(&self.core,bits,true).or_else(||memory_type(&self.core,bits,false))
                .ok_or("no compatible AHB memory type")?;
            let mut dedicated=vk::MemoryDedicatedAllocateInfo::default().image(self.source);
            let mut import=vk::ImportAndroidHardwareBufferInfoANDROID::default().buffer(self.buffer);
            self.source_memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(size)
                .memory_type_index(index).push_next(&mut dedicated).push_next(&mut import),None).map_err(err)?;
            d.bind_image_memory(self.source,self.source_memory,0).map_err(err)?;
            let mut conversion_format=vk::ExternalFormatANDROID::default().external_format(fmt.external_format);
            let conversion=vk::SamplerYcbcrConversionCreateInfo::default().push_next(&mut conversion_format)
                .format(vk::Format::UNDEFINED).ycbcr_model(fmt.suggested_ycbcr_model)
                .ycbcr_range(fmt.suggested_ycbcr_range).components(fmt.sampler_ycbcr_conversion_components)
                .x_chroma_offset(fmt.suggested_x_chroma_offset).y_chroma_offset(fmt.suggested_y_chroma_offset)
                .chroma_filter(vk::Filter::NEAREST).force_explicit_reconstruction(false);
            self.conversion=d.create_sampler_ycbcr_conversion(&conversion,None).map_err(err)?;
            let mut attach=vk::SamplerYcbcrConversionInfo::default().conversion(self.conversion);
            self.source_view=d.create_image_view(&vk::ImageViewCreateInfo::default().push_next(&mut attach)
                .image(self.source).view_type(vk::ImageViewType::TYPE_2D).format(vk::Format::UNDEFINED)
                .subresource_range(range()),None).map_err(err)?;
            let mut attach=vk::SamplerYcbcrConversionInfo::default().conversion(self.conversion);
            self.sampler=d.create_sampler(&vk::SamplerCreateInfo::default().push_next(&mut attach)
                .mag_filter(vk::Filter::NEAREST).min_filter(vk::Filter::NEAREST)
                .mipmap_mode(vk::SamplerMipmapMode::NEAREST)
                .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .address_mode_w(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                .min_lod(0.0).max_lod(0.0),None).map_err(err)?;
        }
        Ok(())
    }
    fn create_output(&mut self)->Result<()>{
        let d=&self.core.device;
        unsafe{
            self.output=d.create_image(&vk::ImageCreateInfo::default().image_type(vk::ImageType::TYPE_2D)
                .format(vk::Format::R8G8B8A8_UNORM)
                .extent(vk::Extent3D{width:self.info.width,height:self.info.height,depth:1})
                .mip_levels(1).array_layers(1).samples(vk::SampleCountFlags::TYPE_1)
                .tiling(vk::ImageTiling::OPTIMAL).usage(vk::ImageUsageFlags::STORAGE|vk::ImageUsageFlags::SAMPLED)
                .sharing_mode(vk::SharingMode::EXCLUSIVE).initial_layout(vk::ImageLayout::UNDEFINED),None).map_err(err)?;
            let req=d.get_image_memory_requirements(self.output);
            let index=memory_type(&self.core,req.memory_type_bits,true).ok_or("no device-local RGB memory")?;
            self.output_memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(req.size)
                .memory_type_index(index),None).map_err(err)?;
            d.bind_image_memory(self.output,self.output_memory,0).map_err(err)?;
            self.output_view=d.create_image_view(&vk::ImageViewCreateInfo::default().image(self.output)
                .view_type(vk::ImageViewType::TYPE_2D).format(vk::Format::R8G8B8A8_UNORM)
                .subresource_range(range()),None).map_err(err)?;
        }
        Ok(())
    }
    fn pipeline(&mut self)->Result<()>{
        let d=&self.core.device;
        let samplers=[self.sampler];
        let bindings=[
            vk::DescriptorSetLayoutBinding::default().binding(0).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1).stage_flags(vk::ShaderStageFlags::COMPUTE).immutable_samplers(&samplers),
            vk::DescriptorSetLayoutBinding::default().binding(1).descriptor_type(vk::DescriptorType::STORAGE_IMAGE)
                .descriptor_count(1).stage_flags(vk::ShaderStageFlags::COMPUTE),
        ];
        unsafe{
            self.set_layout=d.create_descriptor_set_layout(&vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),None).map_err(err)?;
            let layouts=[self.set_layout];
            let ranges=[vk::PushConstantRange::default().stage_flags(vk::ShaderStageFlags::COMPUTE).size(24)];
            self.pipeline_layout=d.create_pipeline_layout(&vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts)
                .push_constant_ranges(&ranges),None).map_err(err)?;
            let words=include_bytes!(concat!(env!("OUT_DIR"),"/ahb_convert.spv")).chunks_exact(4)
                .map(|w|u32::from_le_bytes(w.try_into().unwrap())).collect::<Vec<_>>();
            let shader=d.create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&words),None).map_err(err)?;
            let stage=vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::COMPUTE).module(shader).name(c"main");
            let result=d.create_compute_pipelines(vk::PipelineCache::null(),
                &[vk::ComputePipelineCreateInfo::default().stage(stage).layout(self.pipeline_layout)],None);
            d.destroy_shader_module(shader,None);
            self.pipeline=match result{
                Ok(p)=>p[0],Err((partial,e))=>{for p in partial{d.destroy_pipeline(p,None);}return Err(err(e));}
            };
        }
        Ok(())
    }
    fn fences(&mut self,fd:i32)->Result<()>{
        let d=&self.core.device;
        unsafe{
            if fd>=0{
                self.wait=d.create_semaphore(&Default::default(),None).map_err(err)?;
                let duplicate=libc::fcntl(fd,libc::F_DUPFD_CLOEXEC,0);
                if duplicate<0{return Err("could not duplicate acquire sync_file".into());}
                let duplicate=OwnedFd::from_raw_fd(duplicate);
                let import=vk::ImportSemaphoreFdInfoKHR::default().semaphore(self.wait)
                    .flags(vk::SemaphoreImportFlags::TEMPORARY).handle_type(HANDLE).fd(duplicate.as_raw_fd());
                self.core.fd.import_semaphore_fd(&import).map_err(err)?;
                // Vulkan consumes the duplicate only on successful import.
                let _=duplicate.into_raw_fd();
            }else if fd!= -1{return Err("invalid acquire fence".into());}
            let mut export=vk::ExportSemaphoreCreateInfo::default().handle_types(HANDLE);
            self.signal=d.create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut export),None).map_err(err)?;
            self.fence=d.create_fence(&Default::default(),None).map_err(err)?;
        }
        Ok(())
    }
    fn record_submit(&mut self)->Result<()>{
        let d=&self.core.device;
        // A converted external-format descriptor can consume more than
        // one combined sampler slot. Reserve up to the device stage budget
        // (bounded), and fail cleanly if the implementation requires more.
        let slots=self.core.limits.max_per_stage_descriptor_samplers
            .min(self.core.limits.max_per_stage_descriptor_sampled_images).min(64);
        if slots<1{return Err("combined sampler budget unavailable".into());}
        let sizes=[
            vk::DescriptorPoolSize::default().ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(slots),
            vk::DescriptorPoolSize::default().ty(vk::DescriptorType::STORAGE_IMAGE).descriptor_count(1),
        ];
        unsafe{
            self.pool=d.create_descriptor_pool(&vk::DescriptorPoolCreateInfo::default().max_sets(1).pool_sizes(&sizes),None).map_err(err)?;
            let set=d.allocate_descriptor_sets(&vk::DescriptorSetAllocateInfo::default().descriptor_pool(self.pool)
                .set_layouts(&[self.set_layout])).map_err(err)?[0];
            let input=[vk::DescriptorImageInfo::default().image_view(self.source_view).image_layout(vk::ImageLayout::GENERAL)];
            let output=[vk::DescriptorImageInfo::default().image_view(self.output_view).image_layout(vk::ImageLayout::GENERAL)];
            let writes=[
                vk::WriteDescriptorSet::default().dst_set(set).dst_binding(0)
                    .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).image_info(&input),
                vk::WriteDescriptorSet::default().dst_set(set).dst_binding(1)
                    .descriptor_type(vk::DescriptorType::STORAGE_IMAGE).image_info(&output),
            ];
            d.update_descriptor_sets(&writes,&[]);
            self.commands=d.create_command_pool(&vk::CommandPoolCreateInfo::default()
                .queue_family_index(self.core.family).flags(vk::CommandPoolCreateFlags::TRANSIENT),None).map_err(err)?;
            let cmd=d.allocate_command_buffers(&vk::CommandBufferAllocateInfo::default().command_pool(self.commands)
                .level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1)).map_err(err)?[0];
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).map_err(err)?;
            let acquire=[
                // AHB content originates outside Vulkan. GENERAL is the
                // preserved external layout; UNDEFINED here discards it.
                vk::ImageMemoryBarrier::default().image(self.source).subresource_range(range())
                    .old_layout(vk::ImageLayout::GENERAL).new_layout(vk::ImageLayout::GENERAL)
                    .src_queue_family_index(vk::QUEUE_FAMILY_FOREIGN_EXT).dst_queue_family_index(self.core.family)
                    .dst_access_mask(vk::AccessFlags::SHADER_READ),
                vk::ImageMemoryBarrier::default().image(self.output).subresource_range(range())
                    .old_layout(vk::ImageLayout::UNDEFINED).new_layout(vk::ImageLayout::GENERAL)
                    .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                    .dst_access_mask(vk::AccessFlags::SHADER_WRITE),
            ];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),&[],&[],&acquire);
            d.cmd_bind_pipeline(cmd,vk::PipelineBindPoint::COMPUTE,self.pipeline);
            d.cmd_bind_descriptor_sets(cmd,vk::PipelineBindPoint::COMPUTE,self.pipeline_layout,0,&[set],&[]);
            let f=self.info;
            let bytes=[f.crop_left,f.crop_top,f.width,f.height,f.buffer_width,f.buffer_height]
                .into_iter().flat_map(u32::to_ne_bytes).collect::<Vec<_>>();
            d.cmd_push_constants(cmd,self.pipeline_layout,vk::ShaderStageFlags::COMPUTE,0,&bytes);
            d.cmd_dispatch(cmd,f.width.div_ceil(8),f.height.div_ceil(8),1);
            let release=[vk::ImageMemoryBarrier::default().image(self.source).subresource_range(range())
                .old_layout(vk::ImageLayout::GENERAL).new_layout(vk::ImageLayout::GENERAL)
                .src_queue_family_index(self.core.family).dst_queue_family_index(vk::QUEUE_FAMILY_FOREIGN_EXT)
                .src_access_mask(vk::AccessFlags::SHADER_READ)];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                vk::DependencyFlags::empty(),&[],&[],&release);
            let ready=[vk::ImageMemoryBarrier::default().image(self.output).subresource_range(range())
                .old_layout(vk::ImageLayout::GENERAL).new_layout(vk::ImageLayout::GENERAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED).dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .src_access_mask(vk::AccessFlags::SHADER_WRITE).dst_access_mask(vk::AccessFlags::SHADER_READ)];
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::ALL_COMMANDS,
                vk::DependencyFlags::empty(),&[],&[],&ready);
            d.end_command_buffer(cmd).map_err(err)?;
            let waits=[self.wait];
            let stages=[vk::PipelineStageFlags::ALL_COMMANDS];
            let cmds=[cmd];let signals=[self.signal];
            let mut submit=vk::SubmitInfo::default().command_buffers(&cmds).signal_semaphores(&signals);
            if self.wait!=vk::Semaphore::null(){submit=submit.wait_semaphores(&waits).wait_dst_stage_mask(&stages);}
            // Caller owns external synchronization of this exact queue.
            d.queue_submit(self.core.queue,&[submit],self.fence).map_err(err)?;
        }
        self.submitted=true;
        Ok(())
    }
    fn finish_producer(&mut self){
        // Exporting SYNC_FD transfers the semaphore payload to the FD.
        // If export fails, retain the original AImage until the fence.
        let info=vk::SemaphoreGetFdInfoKHR::default().semaphore(self.signal).handle_type(HANDLE);
        if let Ok(fd)=unsafe{self.core.fd.get_semaphore_fd(&info)}{
            if fd>= -1{
                if let Some(frame)=self.frame.as_mut(){frame.return_after_gpu(fd);}
                else if fd>=0{unsafe{libc::close(fd)};}
            }
        }
    }
}

#[repr(C)]
#[derive(Clone,Copy)]
pub struct Output{
    pub image:vk::Image,pub view:vk::ImageView,pub device:vk::Device,pub queue:vk::Queue,pub family:u32,
    pub info:FrameInfo,
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_ahb_importer_create(instance:vk::Instance,physical:vk::PhysicalDevice,
    device:vk::Device,get_proc:Option<vk::PFN_vkGetInstanceProcAddr>,queue:vk::Queue,family:u32,
    enabled:*const *const c_char,count:u32,ycbcr_enabled:u32)->*mut Importer{
    let Some(get_proc)=get_proc else{return ptr::null_mut();};
    catch_unwind(AssertUnwindSafe(||unsafe{make_importer(instance,physical,device,get_proc,queue,family,enabled,count,ycbcr_enabled)}
        .map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s))))).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_ahb_importer_destroy(importer:*mut Importer){
    if !importer.is_null(){unsafe{drop(Box::from_raw(importer))}}
}
/// 0 success (consumes *frame), -2 bounded backpressure, -4 unavailable.
/// On failure before submission, the caller still owns the original lease.
/// The queue must be externally synchronized by the caller throughout.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_ahb_submit(importer:*const Importer,frame:*mut *mut Frame,
    sdr8:u32,out:*mut *mut Job)->i32{
    if importer.is_null() || frame.is_null() || out.is_null() || sdr8!=1{return -4;}
    unsafe{*out=ptr::null_mut();}
    if unsafe{(*frame).is_null()}{return -4;}
    catch_unwind(AssertUnwindSafe(||{
        let core=unsafe{(&*importer).0.clone()};
        if core.in_flight.load(Ordering::Acquire)>=MAX_JOBS{return -2;}
        let Ok(mut j)=Job::new(core,unsafe{&**frame})else{return -4;};
        if j.record_submit().is_err(){return -4;}
        // No fallible work between successful submit and publishing the
        // owned job; frame cannot be discarded by the caller after this.
        j.frame=Some(unsafe{Box::from_raw(*frame)});
        unsafe{*frame=ptr::null_mut();}
        let raw=Box::into_raw(Box::new(j));
        unsafe{*out=raw;}
        unsafe{(&mut *raw).finish_producer();}
        0
    })).unwrap_or_else(|_|if unsafe{(*out).is_null()}{-4}else{0})
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_ahb_output(job:*const Job,out:*mut Output)->i32{
    if job.is_null() || out.is_null(){return -1;}
    let j=unsafe{&*job};
    unsafe{*out=Output{image:j.output,view:j.output_view,device:j.core.device.handle(),
        queue:j.core.queue,family:j.core.family,info:j.info};}
    0
}
/// Nonblocking release. The conversion fence must signal; if the RGB
/// image was used in later commands, the caller must also supply the final
/// consumer fence. The same borrowed VkDevice must remain alive.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_ahb_release(job:*mut Job,consumer:vk::Fence)->i32{
    if job.is_null(){return -1;}
    let j=unsafe{&mut *job};
    if j.consumers.load(Ordering::Acquire)!=0{return 0;}
    for fence in [j.fence,consumer]{
        if fence==vk::Fence::null(){continue;}
        match unsafe{j.core.device.get_fence_status(fence)}{
            Ok(true)=>{if fence==j.fence{j.completed=true;}},Ok(false)=>return 0,
            Err(vk::Result::ERROR_DEVICE_LOST)=>break,Err(_)=>return -1,
        }
    }
    unsafe{drop(Box::from_raw(job))};
    1
}
