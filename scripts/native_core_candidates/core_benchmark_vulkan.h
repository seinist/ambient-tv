// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <libretro_vulkan.h>
#include <mutex>
#include <vector>
#include <atomic>
#include <cstdint>
#include <cstring>

// Build the matching acquire/release barriers for a core's exclusive image.
// Both halves preserve the supplied image layout; no pixels are sampled.
static inline VkImageMemoryBarrier benchmark_ownership_barrier(
    VkImage image, VkImageLayout layout, VkImageSubresourceRange range,
    uint32_t source, uint32_t destination, bool acquire) {
    VkImageMemoryBarrier barrier{};
    barrier.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask=acquire?0:VK_ACCESS_MEMORY_READ_BIT;
    barrier.dstAccessMask=acquire?VK_ACCESS_MEMORY_READ_BIT:0;
    barrier.oldLayout=barrier.newLayout=layout;
    barrier.srcQueueFamilyIndex=source;barrier.dstQueueFamilyIndex=destination;
    barrier.image=image;barrier.subresourceRange=range;
    return barrier;
}

// Headless, single-slot, synchronous GPU benchmark. No presentation.
class BenchmarkVulkan {
    retro_hw_render_context_negotiation_interface_vulkan negotiation{};
    retro_vulkan_context context{};
    retro_hw_render_interface_vulkan iface{};
    std::mutex queue_mutex, frame_mutex;
    VkFence fence = VK_NULL_HANDLE;
    VkCommandPool ownership_pool = VK_NULL_HANDLE;
    VkCommandBuffer ownership_cmd = VK_NULL_HANDLE;
    uint32_t family_count=0;
    VkBuffer readback_buffer=VK_NULL_HANDLE;
    VkDeviceMemory readback_memory=VK_NULL_HANDLE;
    void* readback_map=nullptr;
    VkDeviceSize readback_capacity=0;
    bool readback_coherent=false;
    VkFormat frame_format=VK_FORMAT_UNDEFINED;
    VkImage frame_image=VK_NULL_HANDLE;
    VkImageLayout frame_layout=VK_IMAGE_LAYOUT_UNDEFINED;
    VkImageSubresourceRange frame_range{};
    uint32_t frame_family=VK_QUEUE_FAMILY_IGNORED;
    std::vector<VkSemaphore> waits;
    std::vector<VkCommandBuffer> commands;
    VkSemaphore signal = VK_NULL_HANDLE;
    bool image_set = false, created = false;
    std::atomic<bool> failed{false};
    static BenchmarkVulkan& self(void* p) { return *static_cast<BenchmarkVulkan*>(p); }
    static VkInstance instance_wrapper(void*, const VkInstanceCreateInfo* info) {
        VkInstance result=VK_NULL_HANDLE;
        return vkCreateInstance(info,nullptr,&result)==VK_SUCCESS?result:VK_NULL_HANDLE;
    }
    static VkDevice device_wrapper(VkPhysicalDevice gpu,void*,const VkDeviceCreateInfo* info) {
        VkDevice result=VK_NULL_HANDLE;
        return vkCreateDevice(gpu,info,nullptr,&result)==VK_SUCCESS?result:VK_NULL_HANDLE;
    }
    static void set_image(void* p,const retro_vulkan_image* image,uint32_t count,
                          const VkSemaphore* semaphores,uint32_t family) {
        auto& s=self(p);
        std::lock_guard<std::mutex> lock(s.frame_mutex);
        if (!image || !image->image_view || (count && !semaphores)) {
            s.failed=true;return;
        }
        const bool transfer=family!=VK_QUEUE_FAMILY_IGNORED && family!=s.iface.queue_index;
        // A release from a different queue family must be ordered by a
        // semaphore; never submit an unmatched ownership acquire.
        if(transfer && (!count || family>=s.family_count ||
            !image->create_info.image ||
            (image->image_layout!=VK_IMAGE_LAYOUT_GENERAL &&
             image->image_layout!=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) ||
            !image->create_info.subresourceRange.aspectMask ||
            !image->create_info.subresourceRange.levelCount ||
            !image->create_info.subresourceRange.layerCount)) {
            s.failed=true;return;
        }
        s.frame_image=image->create_info.image;
        s.frame_format=image->create_info.format;
        s.frame_layout=image->image_layout;
        s.frame_range=image->create_info.subresourceRange;
        s.frame_family=family;
        s.image_set=true;
        s.waits.clear();
        if(count)s.waits.assign(semaphores,semaphores+count);
    }
    static void set_commands(void* p,uint32_t count,const VkCommandBuffer* cmd) {
        auto& s=self(p);
        std::lock_guard<std::mutex> lock(s.frame_mutex);
        s.commands.clear();
        if(count && !cmd){s.failed=true;return;}
        if(count)s.commands.assign(cmd,cmd+count);
    }
    static void set_signal(void* p,VkSemaphore semaphore) {
        auto& s=self(p);
        std::lock_guard<std::mutex> lock(s.frame_mutex);
        s.signal=semaphore;
    }
    static void lock(void* p){self(p).queue_mutex.lock();}
    static void unlock(void* p){self(p).queue_mutex.unlock();}
    static uint32_t index(void*){return 0;}
    static uint32_t mask(void*){return 1;}
    static void wait_index(void* p) {
        auto& s=self(p);
        std::lock_guard<std::mutex> lock(s.queue_mutex);
        if(vkQueueWaitIdle(s.iface.queue)!=VK_SUCCESS)s.failed=true;
    }
    bool default_device(VkPhysicalDevice gpu) {
        uint32_t count=0;
        vkGetPhysicalDeviceQueueFamilyProperties(gpu,&count,nullptr);
        std::vector<VkQueueFamilyProperties> families(count);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu,&count,families.data());
        for(uint32_t i=0;i<count;++i) {
            constexpr VkQueueFlags required=VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT;
            if(!families[i].queueCount || (families[i].queueFlags&required)!=required)continue;
            float priority=1.0f;
            VkDeviceQueueCreateInfo queue{};
            queue.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
            queue.queueFamilyIndex=i;queue.queueCount=1;queue.pQueuePriorities=&priority;
            VkDeviceCreateInfo info{};
            info.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
            info.queueCreateInfoCount=1;info.pQueueCreateInfos=&queue;
            if(vkCreateDevice(gpu,&info,nullptr,&context.device)!=VK_SUCCESS)continue;
            context.gpu=gpu;context.queue_family_index=i;
            vkGetDeviceQueue(context.device,i,0,&context.queue);
            context.presentation_queue=context.queue;context.presentation_queue_family_index=i;
            return true;
        }
        return false;
    }
    void destroy_readback() {
        if(readback_map)vkUnmapMemory(context.device,readback_memory);
        if(readback_buffer)vkDestroyBuffer(context.device,readback_buffer,nullptr);
        if(readback_memory)vkFreeMemory(context.device,readback_memory,nullptr);
        readback_map=nullptr;readback_buffer=VK_NULL_HANDLE;
        readback_memory=VK_NULL_HANDLE;readback_capacity=0;
    }
    bool ensure_readback(VkDeviceSize bytes) {
        if(readback_capacity>=bytes)return true;
        destroy_readback();
        VkBufferCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        info.size=bytes;info.usage=VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        info.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        if(vkCreateBuffer(context.device,&info,nullptr,&readback_buffer)!=VK_SUCCESS)return false;
        VkMemoryRequirements req{};vkGetBufferMemoryRequirements(context.device,readback_buffer,&req);
        VkPhysicalDeviceMemoryProperties props{};vkGetPhysicalDeviceMemoryProperties(context.gpu,&props);
        uint32_t type=UINT32_MAX;
        for(uint32_t i=0;i<props.memoryTypeCount;++i) {
            const auto flags=props.memoryTypes[i].propertyFlags;
            if(!(req.memoryTypeBits&(1u<<i)) || !(flags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))continue;
            if(type==UINT32_MAX)type=i;
            if(flags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT){type=i;break;}
        }
        if(type==UINT32_MAX){destroy_readback();return false;}
        readback_coherent=(props.memoryTypes[type].propertyFlags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)!=0;
        VkMemoryAllocateInfo alloc{};alloc.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        alloc.allocationSize=req.size;alloc.memoryTypeIndex=type;
        if(vkAllocateMemory(context.device,&alloc,nullptr,&readback_memory)!=VK_SUCCESS ||
           vkBindBufferMemory(context.device,readback_buffer,readback_memory,0)!=VK_SUCCESS ||
           vkMapMemory(context.device,readback_memory,0,VK_WHOLE_SIZE,0,&readback_map)!=VK_SUCCESS) {
            destroy_readback();return false;
        }
        readback_capacity=bytes;return true;
    }
public:
    bool configure(const retro_hw_render_context_negotiation_interface* base) {
        if(created || !base || base->interface_type!=RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN ||
           base->interface_version<1)return false;
        const auto* v=reinterpret_cast<const retro_hw_render_context_negotiation_interface_vulkan*>(base);
        negotiation.interface_type=v->interface_type;negotiation.interface_version=v->interface_version>=2?2:1;
        negotiation.get_application_info=v->get_application_info;
        negotiation.create_device=v->create_device;negotiation.destroy_device=v->destroy_device;
        if(v->interface_version>=2){negotiation.create_instance=v->create_instance;negotiation.create_device2=v->create_device2;}
        return true;
    }
    bool init() {
        if(created)return !failed;
        VkApplicationInfo app{};
        app.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;app.pApplicationName="Ambient core benchmark";
        app.apiVersion=VK_API_VERSION_1_0;
        const VkApplicationInfo* requested=negotiation.get_application_info?negotiation.get_application_info():&app;
        if(!requested)return false;
        if(negotiation.create_instance)
            iface.instance=negotiation.create_instance(vkGetInstanceProcAddr,requested,instance_wrapper,this);
        else {
            VkInstanceCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;info.pApplicationInfo=requested;
            iface.instance=instance_wrapper(this,&info);
        }
        if(!iface.instance)return false;
        uint32_t count=0;
        if(vkEnumeratePhysicalDevices(iface.instance,&count,nullptr)!=VK_SUCCESS || !count)return false;
        std::vector<VkPhysicalDevice> devices(count);
        if(vkEnumeratePhysicalDevices(iface.instance,&count,devices.data())!=VK_SUCCESS)return false;
        bool ok=false;
        for(auto gpu:devices) {
            context={};
            if(negotiation.create_device2)
                ok=negotiation.create_device2(&context,iface.instance,gpu,VK_NULL_HANDLE,
                                             vkGetInstanceProcAddr,device_wrapper,this);
            else if(negotiation.create_device) {
                VkPhysicalDeviceFeatures features{};
                ok=negotiation.create_device(&context,iface.instance,gpu,VK_NULL_HANDLE,
                    vkGetInstanceProcAddr,nullptr,0,nullptr,0,&features);
            } else ok=default_device(gpu);
            if(ok)break;
            if(context.device)return false;
        }
        if(!ok || !context.gpu || !context.device || !context.queue)return false;
        iface.interface_type=RETRO_HW_RENDER_INTERFACE_VULKAN;
        iface.interface_version=RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
        iface.handle=this;iface.gpu=context.gpu;iface.device=context.device;
        iface.get_instance_proc_addr=vkGetInstanceProcAddr;iface.get_device_proc_addr=vkGetDeviceProcAddr;
        iface.queue=context.queue;iface.queue_index=context.queue_family_index;
        iface.set_image=set_image;iface.set_command_buffers=set_commands;
        iface.get_sync_index=index;iface.get_sync_index_mask=mask;iface.wait_sync_index=wait_index;
        iface.lock_queue=lock;iface.unlock_queue=unlock;iface.set_signal_semaphore=set_signal;
        VkFenceCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        if(vkCreateFence(context.device,&info,nullptr,&fence)!=VK_SUCCESS)return false;
        vkGetPhysicalDeviceQueueFamilyProperties(context.gpu,&family_count,nullptr);
        VkCommandPoolCreateInfo pool{};pool.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pool.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pool.queueFamilyIndex=iface.queue_index;
        if(vkCreateCommandPool(context.device,&pool,nullptr,&ownership_pool)!=VK_SUCCESS)return false;
        VkCommandBufferAllocateInfo alloc{};alloc.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        alloc.commandPool=ownership_pool;alloc.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;alloc.commandBufferCount=1;
        if(vkAllocateCommandBuffers(context.device,&alloc,&ownership_cmd)!=VK_SUCCESS)return false;
        created=true;return true;
    }
    const retro_hw_render_interface* interface() {
        return init()?reinterpret_cast<const retro_hw_render_interface*>(&iface):nullptr;
    }
    // Optional correctness readback; no staging allocation or copy in timing mode.
    bool frame(bool valid,uint32_t width=0,uint32_t height=0,
               std::vector<uint8_t>* pixels=nullptr,VkFormat* pixel_format=nullptr) {
        std::vector<VkSemaphore> frame_waits;
        std::vector<VkCommandBuffer> frame_commands;
        VkSemaphore frame_signal=VK_NULL_HANDLE;
        VkImage image=VK_NULL_HANDLE;
        VkImageLayout layout=VK_IMAGE_LAYOUT_UNDEFINED;
        VkFormat format=VK_FORMAT_UNDEFINED;
        VkImageSubresourceRange range{};
        uint32_t family=VK_QUEUE_FAMILY_IGNORED;
        {
            std::lock_guard<std::mutex> frame_lock(frame_mutex);
            if(!created || failed || (valid && !image_set))return false;
            waits.swap(frame_waits);commands.swap(frame_commands);
            frame_signal=signal;signal=VK_NULL_HANDLE;
            if(valid){image=frame_image;layout=frame_layout;format=frame_format;range=frame_range;family=frame_family;}
            image_set=false;frame_image=VK_NULL_HANDLE;frame_family=VK_QUEUE_FAMILY_IGNORED;
        }
        // Never hold the callback state mutex while waiting on the queue lock.
        // A core worker may own the queue while calling set_image.
        std::lock_guard<std::mutex> queue_lock(queue_mutex);
        const bool wait_image=valid && frame_commands.empty();
        const bool transfer=valid && family!=VK_QUEUE_FAMILY_IGNORED && family!=iface.queue_index;
        const bool capture=valid && pixels;
        if(transfer && (!wait_image || frame_waits.empty())) {failed=true;return false;}
        if(capture) {
            // The Libretro Vulkan interface requires TRANSFER_SRC usage.
            // Restrict strict readback to explicitly supported packed formats.
            if(!image || !width || !height || width>8192 || height>8192 ||
               (format!=VK_FORMAT_R8G8B8A8_UNORM && format!=VK_FORMAT_R8G8B8A8_SRGB &&
                format!=VK_FORMAT_B8G8R8A8_UNORM && format!=VK_FORMAT_B8G8R8A8_SRGB) ||
               (layout!=VK_IMAGE_LAYOUT_GENERAL && layout!=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) ||
               range.aspectMask!=VK_IMAGE_ASPECT_COLOR_BIT || range.levelCount!=1 || range.layerCount!=1 ||
               !ensure_readback(VkDeviceSize(width)*height*4)) {failed=true;return false;}
        }
        if(transfer || capture) {
            VkCommandBufferBeginInfo begin{};begin.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            if(vkResetCommandBuffer(ownership_cmd,0)!=VK_SUCCESS ||
               vkBeginCommandBuffer(ownership_cmd,&begin)!=VK_SUCCESS){failed=true;return false;}
            if(transfer) {
                auto acquire=benchmark_ownership_barrier(image,layout,range,family,iface.queue_index,true);
                vkCmdPipelineBarrier(ownership_cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                                     0,0,nullptr,0,nullptr,1,&acquire);
            }
            if(capture) {
                VkImageMemoryBarrier transition{};transition.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                transition.srcAccessMask=VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_MEMORY_READ_BIT;
                transition.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
                transition.oldLayout=layout;transition.newLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                transition.srcQueueFamilyIndex=transition.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
                transition.image=image;transition.subresourceRange=range;
                vkCmdPipelineBarrier(ownership_cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     0,0,nullptr,0,nullptr,1,&transition);
                VkBufferImageCopy copy{};
                copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,range.baseMipLevel,range.baseArrayLayer,1};
                copy.imageExtent={width,height,1};
                vkCmdCopyImageToBuffer(ownership_cmd,image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback_buffer,1,&copy);
                transition.srcAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
                transition.dstAccessMask=VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT;
                transition.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;transition.newLayout=layout;
                VkBufferMemoryBarrier host{};host.sType=VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                host.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;host.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
                host.srcQueueFamilyIndex=host.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
                host.buffer=readback_buffer;host.size=VK_WHOLE_SIZE;
                vkCmdPipelineBarrier(ownership_cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     VK_PIPELINE_STAGE_ALL_COMMANDS_BIT|VK_PIPELINE_STAGE_HOST_BIT,
                                     0,0,nullptr,1,&host,1,&transition);
            }
            if(transfer) {
                auto release=benchmark_ownership_barrier(image,layout,range,iface.queue_index,family,false);
                vkCmdPipelineBarrier(ownership_cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                                     0,0,nullptr,0,nullptr,1,&release);
            }
            if(vkEndCommandBuffer(ownership_cmd)!=VK_SUCCESS){failed=true;return false;}
            frame_commands.push_back(ownership_cmd);
        }
        std::vector<VkPipelineStageFlags> stages(wait_image?frame_waits.size():0,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        VkSubmitInfo submit{};submit.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount=static_cast<uint32_t>(stages.size());
        submit.pWaitSemaphores=stages.empty()?nullptr:frame_waits.data();
        submit.pWaitDstStageMask=stages.data();
        submit.commandBufferCount=static_cast<uint32_t>(frame_commands.size());
        submit.pCommandBuffers=frame_commands.data();
        submit.signalSemaphoreCount=frame_signal?1:0;submit.pSignalSemaphores=frame_signal?&frame_signal:nullptr;
        bool ok=vkResetFences(context.device,1,&fence)==VK_SUCCESS &&
                vkQueueSubmit(context.queue,1,&submit,fence)==VK_SUCCESS &&
                vkWaitForFences(context.device,1,&fence,VK_TRUE,30000000000ull)==VK_SUCCESS;
        if(ok && capture) {
            if(!readback_coherent) {
                VkMappedMemoryRange mapped{};mapped.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
                mapped.memory=readback_memory;mapped.size=VK_WHOLE_SIZE;
                ok=vkInvalidateMappedMemoryRanges(context.device,1,&mapped)==VK_SUCCESS;
            }
            if(ok) {
                pixels->resize(size_t(width)*height*4);
                std::memcpy(pixels->data(),readback_map,pixels->size());
                if(pixel_format)*pixel_format=format;
            }
        }
        if(!ok)failed=true;
        return ok;
    }
    bool good(){return !failed;}
    void idle(){if(created)wait_index(this);}
    void shutdown() {
        idle();
        destroy_readback();
        if(ownership_pool)vkDestroyCommandPool(context.device,ownership_pool,nullptr);
        if(fence)vkDestroyFence(context.device,fence,nullptr);
        if(negotiation.destroy_device)negotiation.destroy_device();
        if(context.device)vkDestroyDevice(context.device,nullptr);
        if(iface.instance)vkDestroyInstance(iface.instance,nullptr);
        created=false;
    }
};
