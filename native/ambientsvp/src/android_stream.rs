//! Android SurfaceControl output transport. Owns a small compute device
//! for players whose Android output is a compositor surface, not a
//! renderer-owned Vulkan device (VLC). No CPU pixel copies.
use ash::vk;
use crate::{ahb_vulkan::*,mediacodec_reader::{Frame,Crop,Desc,AHardwareBuffer_acquire,AHardwareBuffer_release},
    resident_vulkan::*};
use std::{ffi::c_void,ptr,panic::{catch_unwind,AssertUnwindSafe},sync::{Arc,Mutex},time::{Duration,Instant}};
type Result<T>=std::result::Result<T,String>;
type ReleaseSource=unsafe extern "C" fn(*mut c_void,i32);
const HANDLE:vk::ExternalSemaphoreHandleTypeFlags=vk::ExternalSemaphoreHandleTypeFlags::SYNC_FD;
const MEMORY:vk::ExternalMemoryHandleTypeFlags=vk::ExternalMemoryHandleTypeFlags::ANDROID_HARDWARE_BUFFER_ANDROID;
const MAX_PHASES:usize=8;
const MAX_BATCHES:usize=3;
const MAX_RECYCLED_BUFFERS:usize=8;

// A compositor callback can outlive the stream. Its lease owns only
// an AHB reference and sync_file descriptors, never a Vulkan object or
// a pointer to the stream. The pool is closed under the same mutex.
struct RecycledBuffer{buffer:usize,producer:i32,consumer:i32}
impl Drop for RecycledBuffer{
    fn drop(&mut self){unsafe{
        if self.producer>=0{libc::close(self.producer);}
        if self.consumer>=0{libc::close(self.consumer);}
        if self.buffer!=0{AHardwareBuffer_release(self.buffer as *mut c_void);}
    }}
}
fn poll_signaled(fd:i32)->bool{
    if fd<0{return true;}
    let mut p=libc::pollfd{fd,events:libc::POLLIN,revents:0};
    unsafe{libc::poll(&mut p,1,0)==1 && p.revents==libc::POLLIN}
}
// Linux sync_file UAPI (linux/sync_file.h): a completed error fence
// can also be readable. Require an actual successful sync_file status.
#[repr(C)]
struct SyncFileInfo{name:[u8;32],status:i32,flags:u32,num_fences:u32,pad:u32,sync_fence_info:u64}
fn fence_ready(fd:i32)->bool{
    if fd<0{return fd== -1;}
    if !poll_signaled(fd){return false;}
    let mut info=SyncFileInfo{name:[0;32],status:0,flags:0,num_fences:0,pad:0,sync_fence_info:0};
    // _IOWR('>', 4, struct sync_file_info), fixed 56-byte UAPI.
    const REQUEST:u32=(3<<30)|(56<<16)|((b'>' as u32)<<8)|4;
    unsafe{libc::ioctl(fd,REQUEST as _,&mut info)==0 && info.status==1}
}
impl RecycledBuffer{
    fn ready_with(&self,ready:impl Fn(i32)->bool)->bool{self.consumer>=0&&ready(self.producer)&&ready(self.consumer)}
    fn ready(&self)->bool{self.ready_with(fence_ready)}
    fn take(mut self)->*mut c_void{std::mem::take(&mut self.buffer) as *mut c_void}
}
#[derive(Default)]
struct OutputPool{closed:bool,buffers:Vec<RecycledBuffer>}
pub struct OutputLease{pool:Arc<Mutex<OutputPool>>,buffer:RecycledBuffer}

#[link(name="android")]
unsafe extern "C"{
    fn AHardwareBuffer_allocate(desc:*const Desc,out:*mut *mut c_void)->i32;
}
fn err(e:vk::Result)->String{format!("Vulkan result {}",e.as_raw())}
fn range()->vk::ImageSubresourceRange{
    vk::ImageSubresourceRange::default().aspect_mask(vk::ImageAspectFlags::COLOR).level_count(1).layer_count(1)
}
struct DeviceOwner{
    _entry:ash::Entry,instance:ash::Instance,device:Option<ash::Device>,
    physical:vk::PhysicalDevice,queue:vk::Queue,family:u32,
}
impl Drop for DeviceOwner{
    fn drop(&mut self){unsafe{
        if let Some(d)=self.device.take(){d.destroy_device(None);}
        self.instance.destroy_instance(None);
    }}
}
impl DeviceOwner{
    fn d(&self)->&ash::Device{self.device.as_ref().unwrap()}
    fn new()->Result<Arc<Self>>{
        let entry=unsafe{ash::Entry::load()}.map_err(|e|e.to_string())?;
        let app=vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_1);
        let instance=unsafe{entry.create_instance(&vk::InstanceCreateInfo::default().application_info(&app),None)}.map_err(err)?;
        let mut owner=Self{_entry:entry,instance,device:None,physical:vk::PhysicalDevice::null(),queue:vk::Queue::null(),family:0};
        let names=[c"VK_ANDROID_external_memory_android_hardware_buffer",c"VK_EXT_queue_family_foreign",c"VK_KHR_external_semaphore_fd"];
        let (p,family)=unsafe{owner.instance.enumerate_physical_devices()}.map_err(err)?.into_iter().find_map(|p|unsafe{
            let props=owner.instance.get_physical_device_properties(p);
            if props.api_version<vk::API_VERSION_1_1 || props.device_type==vk::PhysicalDeviceType::CPU{return None;}
            let ext=owner.instance.enumerate_device_extension_properties(p).ok()?;
            if !names.iter().all(|n|ext.iter().any(|e|std::ffi::CStr::from_ptr(e.extension_name.as_ptr())==*n)){return None;}
            let mut y=vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default();
            let mut f=vk::PhysicalDeviceFeatures2::default().push_next(&mut y);
            owner.instance.get_physical_device_features2(p,&mut f);
            if y.sampler_ycbcr_conversion==0{return None;}
            owner.instance.get_physical_device_queue_family_properties(p).iter().position(|q|
                q.queue_count>0&&q.queue_flags.contains(vk::QueueFlags::COMPUTE)).map(|i|(p,i as u32))
        }).ok_or("no compatible Android compute device")?;
        let pointers=names.map(|n|n.as_ptr());
        let priority=[1.0];
        let queues=[vk::DeviceQueueCreateInfo::default().queue_family_index(family).queue_priorities(&priority)];
        let mut y=vk::PhysicalDeviceSamplerYcbcrConversionFeatures::default().sampler_ycbcr_conversion(true);
        let device=unsafe{owner.instance.create_device(p,&vk::DeviceCreateInfo::default().queue_create_infos(&queues)
            .enabled_extension_names(&pointers).push_next(&mut y),None)}.map_err(err)?;
        owner.queue=unsafe{device.get_device_queue(family,0)};
        owner.physical=p;owner.family=family;owner.device=Some(device);
        Ok(Arc::new(owner))
    }
}
struct DisplayImage{
    owner:Arc<DeviceOwner>,buffer:*mut c_void,image:vk::Image,view:vk::ImageView,
    memory:vk::DeviceMemory,signal:vk::Semaphore,leased:bool,
}
impl Drop for DisplayImage{
    fn drop(&mut self){unsafe{
        let d=self.owner.d();
        d.destroy_semaphore(self.signal,None);
        d.destroy_image_view(self.view,None);d.destroy_image(self.image,None);d.free_memory(self.memory,None);
        if !self.buffer.is_null(){AHardwareBuffer_release(self.buffer);}
    }}
}
impl DisplayImage{
    fn new(owner:Arc<DeviceOwner>,width:u32,height:u32,recycled:Option<RecycledBuffer>,high:bool)->Result<Self>{
        let format=if high{vk::Format::R16G16B16A16_SFLOAT}else{vk::Format::R8G8B8A8_UNORM};
        let mut s=Self{owner,buffer:recycled.map_or(ptr::null_mut(),RecycledBuffer::take),image:vk::Image::null(),view:vk::ImageView::null(),
            memory:vk::DeviceMemory::null(),signal:vk::Semaphore::null(),leased:false};
        // GPU_SAMPLED_IMAGE | GPU_COLOR_OUTPUT | COMPOSER_OVERLAY.
        // Never request CPU-readable/writable allocations.
        let desc=Desc{width,height,layers:1,format:if high{0x16}else{1},usage:(1<<8)|(1<<9)|(1<<11),..Default::default()};
        if s.buffer.is_null() && (unsafe{AHardwareBuffer_allocate(&desc,&mut s.buffer)}!=0||s.buffer.is_null()){return Err("compositor AHB allocation unsupported".into());}
        let d=s.owner.d();
        let ahb=ash::android::external_memory_android_hardware_buffer::Device::new(&s.owner.instance,d);
        let mut fmt=vk::AndroidHardwareBufferFormatPropertiesANDROID::default();
        let mut props=vk::AndroidHardwareBufferPropertiesANDROID::default().push_next(&mut fmt);
        unsafe{ahb.get_android_hardware_buffer_properties(s.buffer.cast_const(),&mut props)}.map_err(err)?;
        let (size,bits)=(props.allocation_size,props.memory_type_bits);
        if size==0 || fmt.format!=format ||
            !fmt.format_features.contains(vk::FormatFeatureFlags::STORAGE_IMAGE){
            return Err("compositor AHB is not a storage image".into());
        }
        let mut ext=vk::ExternalMemoryImageCreateInfo::default().handle_types(MEMORY);
        let mut ext_in=vk::PhysicalDeviceExternalImageFormatInfo::default().handle_type(MEMORY);
        let usage=vk::ImageUsageFlags::STORAGE|vk::ImageUsageFlags::SAMPLED|vk::ImageUsageFlags::COLOR_ATTACHMENT;
        let info=vk::PhysicalDeviceImageFormatInfo2::default().format(format)
            .ty(vk::ImageType::TYPE_2D).tiling(vk::ImageTiling::OPTIMAL).usage(usage).push_next(&mut ext_in);
        let mut external=vk::ExternalImageFormatProperties::default();
        let mut format_props=vk::ImageFormatProperties2::default().push_next(&mut external);
        unsafe{s.owner.instance.get_physical_device_image_format_properties2(s.owner.physical,&info,&mut format_props)}.map_err(err)?;
        if !external.external_memory_properties.external_memory_features.contains(vk::ExternalMemoryFeatureFlags::IMPORTABLE){
            return Err("compositor AHB external format cannot be imported".into());
        }
        unsafe{
            s.image=d.create_image(&vk::ImageCreateInfo::default().push_next(&mut ext)
                .image_type(vk::ImageType::TYPE_2D).format(format)
                .extent(vk::Extent3D{width,height,depth:1}).mip_levels(1).array_layers(1)
                .samples(vk::SampleCountFlags::TYPE_1).tiling(vk::ImageTiling::OPTIMAL)
                .usage(usage).sharing_mode(vk::SharingMode::EXCLUSIVE).initial_layout(vk::ImageLayout::UNDEFINED),None).map_err(err)?;
            let mem=s.owner.instance.get_physical_device_memory_properties(s.owner.physical);
            let index=(0..mem.memory_type_count).find(|&i|bits&(1u32<<i)!=0 &&
                !mem.memory_types[i as usize].property_flags.contains(vk::MemoryPropertyFlags::PROTECTED))
                .ok_or("no compositor AHB memory type")?;
            let mut dedicated=vk::MemoryDedicatedAllocateInfo::default().image(s.image);
            let mut import=vk::ImportAndroidHardwareBufferInfoANDROID::default().buffer(s.buffer);
            s.memory=d.allocate_memory(&vk::MemoryAllocateInfo::default().allocation_size(size).memory_type_index(index)
                .push_next(&mut dedicated).push_next(&mut import),None).map_err(err)?;
            d.bind_image_memory(s.image,s.memory,0).map_err(err)?;
            s.view=d.create_image_view(&vk::ImageViewCreateInfo::default().image(s.image)
                .view_type(vk::ImageViewType::TYPE_2D).format(format).subresource_range(range()),None).map_err(err)?;
            let mut export=vk::ExportSemaphoreCreateInfo::default().handle_types(HANDLE);
            s.signal=d.create_semaphore(&vk::SemaphoreCreateInfo::default().push_next(&mut export),None).map_err(err)?;
        }
        Ok(s)
    }
}
#[repr(C)]
#[derive(Clone,Copy)]
pub struct DisplayOutput{pub buffer:*mut c_void,pub fence_fd:i32}
impl Default for DisplayOutput{fn default()->Self{Self{buffer:ptr::null_mut(),fence_fd:-1}}}
// These objects are never exposed to SurfaceControl. Only the exact
// GPU fence is needed to recycle them, unlike compositor-owned images.
struct BatchCommands{
    owner:Arc<DeviceOwner>,pool:vk::CommandPool,command:vk::CommandBuffer,fence:vk::Fence,
}
impl Drop for BatchCommands{
    fn drop(&mut self){unsafe{
        self.owner.d().destroy_command_pool(self.pool,None);
        self.owner.d().destroy_fence(self.fence,None);
    }}
}
impl BatchCommands{
    fn new(owner:Arc<DeviceOwner>)->Result<Self>{
        let mut s=Self{owner,pool:vk::CommandPool::null(),command:vk::CommandBuffer::null(),fence:vk::Fence::null()};
        let d=s.owner.d();
        unsafe{
            s.pool=d.create_command_pool(&vk::CommandPoolCreateInfo::default().queue_family_index(s.owner.family)
                .flags(vk::CommandPoolCreateFlags::TRANSIENT),None).map_err(err)?;
            s.command=d.allocate_command_buffers(&vk::CommandBufferAllocateInfo::default().command_pool(s.pool)
                .level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1)).map_err(err)?[0];
            s.fence=d.create_fence(&Default::default(),None).map_err(err)?;
        }
        Ok(s)
    }
    fn reset(&self)->Result<()>{
        let d=self.owner.d();
        unsafe{
            if !d.get_fence_status(self.fence).map_err(err)?{return Err("compositor command bundle still active".into());}
            d.reset_command_pool(self.pool,vk::CommandPoolResetFlags::empty()).map_err(err)?;
            d.reset_fences(&[self.fence]).map_err(err)?;
        }
        Ok(())
    }
}
struct Batch{
    owner:Arc<DeviceOwner>,commands:Option<BatchCommands>,
    pair:*mut VulkanPair,images:Vec<DisplayImage>,submitted:bool,
}
impl Drop for Batch{
    fn drop(&mut self){unsafe{
        // Discard the unsubmitted command buffer before canceling its
        // pair. Submitted batches only reach Drop after retirement.
        drop(self.commands.take());
        if !self.pair.is_null()&&!self.submitted{ambient_svp_vk_cancel(self.pair);}
    }}
}
impl Batch{
    fn record(owner:Arc<DeviceOwner>,resident:*mut VulkanResident,a:*mut Job,b:*mut Job,
        width:u32,height:u32,phases:&[f32],cached:Option<BatchCommands>,
        preflight:Option<DisplayImage>,mut recycled:Vec<RecycledBuffer>,high:bool)->Result<Self>{
        let commands=if let Some(cached)=cached{cached.reset()?;cached}
            else{BatchCommands::new(owner.clone())?};
        let cmd=commands.command;let fence=commands.fence;
        let mut s=Self{owner,commands:Some(commands),
            pair:ptr::null_mut(),images:Vec::with_capacity(phases.len()),submitted:false};
        // The preflight image has never been submitted or exposed to
        // any consumer. Subsequent output images remain fresh.
        if let Some(img)=preflight{s.images.push(img);}
        while s.images.len()<phases.len(){s.images.push(DisplayImage::new(s.owner.clone(),width,height,recycled.pop(),high)?);}
        let d=s.owner.d();
        unsafe{
            d.begin_command_buffer(cmd,&vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).map_err(err)?;
            let barriers=s.images.iter().map(|img|vk::ImageMemoryBarrier::default().image(img.image)
                .subresource_range(range()).old_layout(vk::ImageLayout::UNDEFINED).new_layout(vk::ImageLayout::GENERAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_FOREIGN_EXT).dst_queue_family_index(s.owner.family)
                .dst_access_mask(vk::AccessFlags::SHADER_WRITE)).collect::<Vec<_>>();
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::TOP_OF_PIPE,vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),&[],&[],&barriers);
            s.pair=ambient_svp_vk_begin_ahb(resident,cmd,s.owner.queue,a,b);
            if s.pair.is_null(){return Err("resident pair rejected".into());}
            for (&phase,img) in phases.iter().zip(&s.images){
                if ambient_svp_vk_render(s.pair,phase,3,img.view)!=0{return Err("resident synthesis rejected".into());}
            }
            let barriers=s.images.iter().map(|img|vk::ImageMemoryBarrier::default().image(img.image)
                .subresource_range(range()).old_layout(vk::ImageLayout::GENERAL).new_layout(vk::ImageLayout::GENERAL)
                .src_queue_family_index(s.owner.family).dst_queue_family_index(vk::QUEUE_FAMILY_FOREIGN_EXT)
                .src_access_mask(vk::AccessFlags::SHADER_WRITE)).collect::<Vec<_>>();
            d.cmd_pipeline_barrier(cmd,vk::PipelineStageFlags::COMPUTE_SHADER,vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                vk::DependencyFlags::empty(),&[],&[],&barriers);
            d.end_command_buffer(cmd).map_err(err)?;
            let signals=s.images.iter().map(|i|i.signal).collect::<Vec<_>>();
            d.queue_submit(s.owner.queue,&[vk::SubmitInfo::default().command_buffers(&[cmd]).signal_semaphores(&signals)],fence).map_err(err)?;
            s.submitted=true;
            // A non-null pair with at least one render and a valid fence
            // necessarily seals. Keep the batch in flight even on error.
            let _=ambient_svp_vk_seal(s.pair,fence);
        }
        Ok(s)
    }
    fn outputs(&self,out:&mut[DisplayOutput])->Result<()>{
        let fd=ash::khr::external_semaphore_fd::Device::new(&self.owner.instance,self.owner.d());
        for (i,img) in self.images.iter().enumerate(){
            let result=unsafe{fd.get_semaphore_fd(&vk::SemaphoreGetFdInfoKHR::default().semaphore(img.signal).handle_type(HANDLE))};
            match result{
                Ok(fence) if fence>=-1=>{
                    unsafe{AHardwareBuffer_acquire(img.buffer);}
                    out[i]=DisplayOutput{buffer:img.buffer,fence_fd:fence};
                }
                _=>{
                    // No output without a valid producer fence. Earlier
                    // exported handles can be discarded without reuse.
                    for x in &mut out[..i]{discard_output(x);}
                    return Err("could not export display fence".into());
                }
            }
        }
        Ok(())
    }
    fn reap(&mut self)->bool{
        if self.pair.is_null(){return true;}
        if unsafe{ambient_svp_vk_release(self.pair)}==1{self.pair=ptr::null_mut();true}else{false}
    }
    fn take_retired_commands(&mut self)->Option<BatchCommands>{
        if !self.submitted || !self.pair.is_null(){return None;}
        let commands=self.commands.as_ref()?;
        // Release also accepts confirmed device loss. Only a genuinely
        // signaled fence may enter the cache; never recycle on errors.
        if !matches!(unsafe{self.owner.d().get_fence_status(commands.fence)},Ok(true)){return None;}
        self.commands.take()
    }
}
fn discard_output(out:&mut DisplayOutput){
    unsafe{
        if out.fence_fd>=0{libc::close(out.fence_fd);}
        if !out.buffer.is_null(){AHardwareBuffer_release(out.buffer);}
    }
    *out=DisplayOutput::default();
}
pub struct Stream{
    owner:Arc<DeviceOwner>,importer:*mut Importer,resident:*mut VulkanResident,
    width:u32,height:u32,mode:u32,previous:*mut Job,retired:Vec<*mut Job>,batches:Vec<Batch>,
    idle_commands:Vec<BatchCommands>,preflight:Option<DisplayImage>,output_pool:Arc<Mutex<OutputPool>>,
}
impl Stream{
    fn new(width:u32,height:u32,mode:u32,quality:Option<ResidentOptions>)->Result<Self>{
        if !matches!(mode,0|4|6){return Err("unsupported compositor mode".into());}
        let owner=DeviceOwner::new()?;
        let names=[c"VK_ANDROID_external_memory_android_hardware_buffer",c"VK_EXT_queue_family_foreign",c"VK_KHR_external_semaphore_fd"];
        let pointers=names.map(|n|n.as_ptr());
        let get=Some(owner._entry.static_fn().get_instance_proc_addr);
        let importer=unsafe{ambient_svp_ahb_importer_create(owner.instance.handle(),owner.physical,owner.d().handle(),
            get,owner.queue,owner.family,pointers.as_ptr(),pointers.len() as u32,1)};
        if importer.is_null(){return Err("Android importer rejected device".into());}
        let format=if mode==0{0}else{3};
        let resident=unsafe{if let Some(q)=quality{
            ambient_svp_vk_create_quality(owner.instance.handle(),owner.physical,owner.d().handle(),get,width,height,format,&q)
        }else{ambient_svp_vk_create_format(owner.instance.handle(),owner.physical,owner.d().handle(),get,width,height,format)}};
        if resident.is_null(){unsafe{ambient_svp_ahb_importer_destroy(importer)};return Err("resident device rejected geometry".into());}
        // Preflight real compositor-format output before consuming frames.
        let mut s=Self{owner,importer,resident,width,height,mode,previous:ptr::null_mut(),retired:Vec::new(),
            batches:Vec::with_capacity(MAX_BATCHES),idle_commands:Vec::with_capacity(MAX_BATCHES),preflight:None,
            output_pool:Arc::new(Mutex::new(OutputPool::default()))};
        match DisplayImage::new(s.owner.clone(),width,height,None,mode!=0){
            Ok(img)=>s.preflight=Some(img),
            Err(e)=>{s.dispose();return Err(e);}
        }
        Ok(s)
    }
    fn reap(&mut self){
        let mut i=0;
        while i<self.batches.len(){
            if !self.batches[i].reap(){i+=1;continue;}
            let mut batch=self.batches.swap_remove(i);
            if self.idle_commands.len()<MAX_BATCHES{
                if let Some(commands)=batch.take_retired_commands(){self.idle_commands.push(commands);}
            }
        }
        self.retired.retain(|&p|unsafe{ambient_svp_ahb_release(p,vk::Fence::null())}!=1);
    }
    fn reset(&mut self){
        if !self.previous.is_null(){self.retired.push(std::mem::replace(&mut self.previous,ptr::null_mut()));}
        self.reap();
    }
    fn dispose(&mut self){
        {let mut pool=self.output_pool.lock().unwrap_or_else(|e|e.into_inner());pool.closed=true;pool.buffers.clear();}
        unsafe{ambient_svp_vk_destroy(self.resident);ambient_svp_ahb_importer_destroy(self.importer);}
        self.resident=ptr::null_mut();self.importer=ptr::null_mut();
    }
    fn render(&mut self,a:*mut Job,b:*mut Job,phases:&[f32],out:&mut[DisplayOutput])->Result<()>{
        if phases.is_empty() || phases.len()>MAX_PHASES || phases.iter().any(|p|!p.is_finite()||!(0.0..=1.0).contains(p)){
            return Err("invalid compositor phases".into());
        }
        if self.batches.len()>=MAX_BATCHES{return Err("compositor GPU backpressure".into());}
        let mut recycled=Vec::new();
        {
            let mut pool=self.output_pool.lock().unwrap_or_else(|e|e.into_inner());
            let mut i=0;
            let needed=phases.len()-usize::from(self.preflight.is_some());
            while i<pool.buffers.len()&&recycled.len()<needed{
                let b=&pool.buffers[i];
                // The old VkImage must also have retired and been
                // destroyed before importing the same allocation again.
                let busy=self.batches.iter().any(|batch|batch.images.iter().any(|img|img.buffer as usize==b.buffer));
                if !busy&&b.ready(){recycled.push(pool.buffers.swap_remove(i));}else{i+=1;}
            }
        }
        let batch=Batch::record(self.owner.clone(),self.resident,a,b,self.width,self.height,phases,
            self.idle_commands.pop(),self.preflight.take(),recycled,self.mode!=0)?;
        let result=batch.outputs(out);
        self.batches.push(batch);
        if result.is_ok(){
            crate::ANDROID_RESIDENT_OUTPUTS.fetch_add(phases.len() as u64,std::sync::atomic::Ordering::Relaxed);
        }
        result
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_stream_create(width:u32,height:u32)->*mut Stream{
    unsafe{ambient_svp_android_stream_create_format(width,height,0)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_stream_create_format(width:u32,height:u32,mode:u32)->*mut Stream{
    if width<32||height<32||(width|height)&1!=0||u64::from(width)*u64::from(height)>1920*1080{return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||Stream::new(width,height,mode,None).map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s))))).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_stream_create_quality(width:u32,height:u32,mode:u32,
    quality:*const ResidentOptions)->*mut Stream{
    if quality.is_null()||width<32||height<32||(width|height)&1!=0||u64::from(width)*u64::from(height)>1920*1080{return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||Stream::new(width,height,mode,Some(unsafe{*quality}))
        .map_or(ptr::null_mut(),|s|Box::into_raw(Box::new(s))))).unwrap_or(ptr::null_mut())
}
/// Extra source-picture reference transferred unconditionally via callback.
/// On failure, caller still owns its ORIGINAL picture reference for fallback.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_stream_push(stream:*mut Stream,buffer:*mut c_void,fd:i32,
    timestamp:i64,left:u32,top:u32,width:u32,height:u32,context:*mut c_void,release:Option<ReleaseSource>,
    phases:*const f32,count:u32,out:*mut DisplayOutput)->i32{
    let Some(release)=release else{return -4;};
    if stream.is_null() || out.is_null() || count as usize>MAX_PHASES || (count!=0&&phases.is_null())
        || left.checked_add(width).is_none_or(|x|x>i32::MAX as u32)
        || top.checked_add(height).is_none_or(|x|x>i32::MAX as u32){
        unsafe{release(context,-1)};return -4;
    }
    catch_unwind(AssertUnwindSafe(||{
        let s=unsafe{&mut *stream};
        s.reap();
        if (width,height)!=(s.width,s.height)||s.previous.is_null()!=(count==0){
            unsafe{release(context,-1)};return -4;
        }
        let out=unsafe{std::slice::from_raw_parts_mut(out,count as usize)};
        out.fill(DisplayOutput::default());
        let crop=Crop{left:left as i32,top:top as i32,right:(left+width) as i32,bottom:(top+height) as i32};
        let Some(frame)= (unsafe{Frame::borrowed(buffer,fd,crop,timestamp,context,release,s.mode!=0)}) else{return -4;};
        let mut frame=Box::into_raw(Box::new(frame));
        let mut next=ptr::null_mut();
        let submitted=unsafe{if s.mode==0{ambient_svp_ahb_submit(s.importer,&mut frame,1,&mut next)}
            else{submit_high_local(s.importer,&mut frame,s.mode,&mut next)}};
        if submitted!=0{
            if !frame.is_null(){unsafe{drop(Box::from_raw(frame))};}
            return -4;
        }
        if !unsafe{(&mut *next).ensure_external_handoff()}{
            // Original picture must not be presented; the retained token
            // will be returned safely when this conversion retires.
            s.retired.push(next);return -5;
        }
        if count!=0{
            let phases=unsafe{std::slice::from_raw_parts(phases,count as usize)};
            if s.render(s.previous,next,phases,out).is_err(){s.retired.push(next);return -4;}
            s.retired.push(s.previous);
        }
        s.previous=next;0
    })).unwrap_or(-4)
}
// Optional ABI: the caller holds its normal output AHB ref separately.
// Only one lease may be obtained for an exported output. The original
// producer fence is duplicated here and remains caller-owned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_stream_lease_output(stream:*mut Stream,buffer:*mut c_void,producer:i32)->*mut OutputLease{
    if stream.is_null()||buffer.is_null()||producer< -1{return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let s=unsafe{&mut *stream};
        let Some(img)=s.batches.iter_mut().flat_map(|b|b.images.iter_mut()).find(|img|img.buffer==buffer&&!img.leased)
            else{return ptr::null_mut();};
        let fd=if producer>=0{unsafe{libc::fcntl(producer,libc::F_DUPFD_CLOEXEC,0)}}else{-1};
        if producer>=0&&fd<0{return ptr::null_mut();}
        unsafe{AHardwareBuffer_acquire(buffer);}
        img.leased=true;
        Box::into_raw(Box::new(OutputLease{pool:s.output_pool.clone(),
            buffer:RecycledBuffer{buffer:buffer as usize,producer:fd,consumer:-1}}))
    })).unwrap_or(ptr::null_mut())
}
// Takes the lease and the explicit previous-buffer release sync_file.
// A negative fd means unproven consumer retirement: discard, never pool.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_output_release(lease:*mut OutputLease,consumer:i32){
    if lease.is_null(){if consumer>=0{unsafe{libc::close(consumer);}}return;}
    let OutputLease{pool,mut buffer}=*unsafe{Box::from_raw(lease)};
    buffer.consumer=consumer;
    if consumer<0{return;}
    let mut pool=pool.lock().unwrap_or_else(|e|e.into_inner());
    if !pool.closed&&pool.buffers.len()<MAX_RECYCLED_BUFFERS{
        pool.buffers.push(buffer);
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_stream_drain(stream:*mut Stream,phases:*const f32,count:u32,out:*mut DisplayOutput)->i32{
    if stream.is_null()||phases.is_null()||out.is_null()||count==0||count as usize>MAX_PHASES{return -4;}
    catch_unwind(AssertUnwindSafe(||{
        let s=unsafe{&mut *stream};s.reap();
        if s.previous.is_null(){return -4;}
        let out=unsafe{std::slice::from_raw_parts_mut(out,count as usize)};out.fill(DisplayOutput::default());
        let phases=unsafe{std::slice::from_raw_parts(phases,count as usize)};
        if s.render(s.previous,s.previous,phases,out).is_err(){return -4;}
        s.reset();0
    })).unwrap_or(-4)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_stream_reset(stream:*mut Stream){
    if !stream.is_null(){unsafe{(&mut *stream).reset()};}
}
/// Returns 1 when destroyed, 0 if work is still in flight. No device or
/// caller callback is released prematurely; caller must retain the library
/// and source picture callbacks on the rare stuck-driver path.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_android_stream_destroy(stream:*mut Stream)->i32{
    if stream.is_null(){return 1;}
    let s=unsafe{&mut *stream};s.reset();
    let deadline=Instant::now()+Duration::from_secs(3);
    while !s.batches.is_empty()||!s.retired.is_empty(){
        if Instant::now()>=deadline{return 0;}
        std::thread::sleep(Duration::from_millis(1));s.reap();
    }
    s.dispose();
    unsafe{drop(Box::from_raw(stream))};1
}


#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn recycled_buffer_requires_both_explicit_ready_fences(){
        // eventfd acts as a pollable readiness fixture; actual sync_file
        // export/consumption is exercised only on Android hardware.
        unsafe{
            let producer=libc::eventfd(0,libc::EFD_CLOEXEC|libc::EFD_NONBLOCK);
            let consumer=libc::eventfd(0,libc::EFD_CLOEXEC|libc::EFD_NONBLOCK);
            assert!(producer>=0&&consumer>=0);
            let signal=|fd|{let v=1u64;assert_eq!(libc::write(fd,(&v as *const u64).cast(),8),8);};
            let b=RecycledBuffer{buffer:0,producer,consumer};
            assert_eq!(std::mem::size_of::<SyncFileInfo>(),56);
            assert!(!b.ready_with(poll_signaled));signal(producer);assert!(!b.ready_with(poll_signaled));
            signal(consumer);assert!(b.ready_with(poll_signaled));
            assert!(!b.ready(),"eventfd is not a successful sync_file");
            let missing=RecycledBuffer{buffer:0,producer:-1,consumer:-1};
            assert!(!missing.ready_with(poll_signaled));
            let pool=Arc::new(Mutex::new(OutputPool{closed:true,buffers:Vec::new()}));
            let fd=libc::eventfd(1,libc::EFD_CLOEXEC);
            assert!(fd>=0);
            let lease=Box::into_raw(Box::new(OutputLease{pool:pool.clone(),
                buffer:RecycledBuffer{buffer:0,producer:-1,consumer:-1}}));
            ambient_svp_android_output_release(lease,fd);
            assert!(pool.lock().unwrap().buffers.is_empty());
            assert_eq!(libc::fcntl(fd,libc::F_GETFD),-1);
        }
    }
    #[test]
    #[ignore="requires a physical Android Vulkan device with FP16 compositor AHB storage support"]
    fn hdr_compositor_preflight_uses_explicit_fp16_allocations(){
        let owner=DeviceOwner::new().unwrap();
        let image=DisplayImage::new(owner.clone(),64,64,None,true).unwrap();
        let mut desc=Desc::default();
        unsafe{crate::mediacodec_reader::AHardwareBuffer_describe(image.buffer,&mut desc);}
        assert_eq!((desc.width,desc.height,desc.layers,desc.format),(64,64,1,0x16));
        assert_eq!(desc.usage&((1<<8)|(1<<9)|(1<<11)),(1<<8)|(1<<9)|(1<<11));
        let stream=unsafe{ambient_svp_android_stream_create_format(64,64,4)};
        assert!(!stream.is_null());
        let s=unsafe{&*stream};
        assert_eq!(s.mode,4);
        assert!(s.preflight.is_some());
        assert_eq!(unsafe{ambient_svp_android_stream_destroy(stream)},1);
        assert!(unsafe{ambient_svp_android_stream_create_format(64,64,99)}.is_null());
    }
    #[test]
    #[ignore="requires a physical Android Vulkan device with compositor AHB extensions"]
    fn compositor_allocation_can_be_reimported_after_successful_gpu_sync(){
        let owner=DeviceOwner::new().unwrap();
        let image=DisplayImage::new(owner.clone(),64,64,None,false).unwrap();
        let buffer=image.buffer;
        // Submit a real GPU clear-independent handoff semaphore and
        // export its sync_file; no compositor transaction is simulated.
        let commands=BatchCommands::new(owner.clone()).unwrap();
        let d=owner.d();
        unsafe{
            d.begin_command_buffer(commands.command,&vk::CommandBufferBeginInfo::default()).unwrap();
            d.end_command_buffer(commands.command).unwrap();
            d.queue_submit(owner.queue,&[vk::SubmitInfo::default().command_buffers(&[commands.command])
                .signal_semaphores(&[image.signal])],commands.fence).unwrap();
        }
        let fd=unsafe{ash::khr::external_semaphore_fd::Device::new(&owner.instance,d)
            .get_semaphore_fd(&vk::SemaphoreGetFdInfoKHR::default().semaphore(image.signal).handle_type(HANDLE))}.unwrap();
        unsafe{d.wait_for_fences(&[commands.fence],true,20_000_000_000).unwrap();}
        assert!(fence_ready(fd));
        if fd>=0{unsafe{libc::close(fd);}}
        unsafe{AHardwareBuffer_acquire(buffer);}
        drop(image);
        let new=DisplayImage::new(owner.clone(),64,64,Some(RecycledBuffer{
            buffer:buffer as usize,producer:-1,consumer:-1}),false).unwrap();
        assert_eq!(new.buffer,buffer);
    }
    #[test]
    #[ignore="requires a physical Android Vulkan device with compositor AHB extensions"]
    fn compositor_command_bundle_reuses_only_signaled_fences(){
        let owner=DeviceOwner::new().unwrap();
        let commands=BatchCommands::new(owner.clone()).unwrap();
        let d=owner.d();
        assert!(commands.reset().is_err(),"an unsignaled fence must not be recycled");
        for _ in 0..2{
            unsafe{
                d.begin_command_buffer(commands.command,&vk::CommandBufferBeginInfo::default()
                    .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT)).unwrap();
                d.end_command_buffer(commands.command).unwrap();
                d.queue_submit(owner.queue,&[vk::SubmitInfo::default()
                    .command_buffers(&[commands.command])],commands.fence).unwrap();
                d.wait_for_fences(&[commands.fence],true,20_000_000_000).unwrap();
            }
            commands.reset().unwrap();
            assert!(!unsafe{d.get_fence_status(commands.fence)}.unwrap());
        }
    }
}
