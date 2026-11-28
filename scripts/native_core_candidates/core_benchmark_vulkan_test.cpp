// SPDX-License-Identifier: GPL-2.0-or-later
#include "core_benchmark_vulkan.h"
#include <cassert>
#include <cstdio>

int main() {
    BenchmarkVulkan backend;
    assert(backend.init());
    auto* api=reinterpret_cast<const retro_hw_render_interface_vulkan*>(backend.interface());
    assert(api && api->get_sync_index(api->handle)==0 && api->get_sync_index_mask(api->handle)==1);
    VkDevice dev=api->device;
    VkCommandPoolCreateInfo poolinfo{};poolinfo.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolinfo.queueFamilyIndex=api->queue_index;
    VkCommandPool pool;
    assert(vkCreateCommandPool(dev,&poolinfo,nullptr,&pool)==VK_SUCCESS);
    VkCommandBufferAllocateInfo alloc{};alloc.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    alloc.commandPool=pool;alloc.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;alloc.commandBufferCount=1;
    VkCommandBuffer cmd;
    assert(vkAllocateCommandBuffers(dev,&alloc,&cmd)==VK_SUCCESS);
    VkImageCreateInfo imageinfo{};imageinfo.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageinfo.imageType=VK_IMAGE_TYPE_2D;imageinfo.format=VK_FORMAT_R8G8B8A8_UNORM;
    imageinfo.extent={4,4,1};imageinfo.mipLevels=1;imageinfo.arrayLayers=1;
    imageinfo.samples=VK_SAMPLE_COUNT_1_BIT;imageinfo.tiling=VK_IMAGE_TILING_OPTIMAL;
    imageinfo.usage=VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
    imageinfo.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    VkImage image;assert(vkCreateImage(dev,&imageinfo,nullptr,&image)==VK_SUCCESS);
    VkMemoryRequirements req{};vkGetImageMemoryRequirements(dev,image,&req);
    VkPhysicalDeviceMemoryProperties mem{};vkGetPhysicalDeviceMemoryProperties(api->gpu,&mem);
    uint32_t type=0;while(type<mem.memoryTypeCount && !(req.memoryTypeBits&(1u<<type)))++type;
    assert(type<mem.memoryTypeCount);
    VkMemoryAllocateInfo minfo{};minfo.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    minfo.allocationSize=req.size;minfo.memoryTypeIndex=type;
    VkDeviceMemory memory;assert(vkAllocateMemory(dev,&minfo,nullptr,&memory)==VK_SUCCESS);
    assert(vkBindImageMemory(dev,image,memory,0)==VK_SUCCESS);
    retro_vulkan_image frame{};
    frame.image_layout=VK_IMAGE_LAYOUT_GENERAL;
    frame.create_info.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    frame.create_info.image=image;frame.create_info.viewType=VK_IMAGE_VIEW_TYPE_2D;
    frame.create_info.format=imageinfo.format;
    frame.create_info.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    assert(vkCreateImageView(dev,&frame.create_info,nullptr,&frame.image_view)==VK_SUCCESS);
    // Verify inverse ownership barriers even on single-family software GPUs.
    const auto acquire=benchmark_ownership_barrier(image,frame.image_layout,frame.create_info.subresourceRange,3,7,true);
    const auto release=benchmark_ownership_barrier(image,frame.image_layout,frame.create_info.subresourceRange,7,3,false);
    assert(acquire.srcQueueFamilyIndex==release.dstQueueFamilyIndex &&
           acquire.dstQueueFamilyIndex==release.srcQueueFamilyIndex);
    assert(acquire.oldLayout==acquire.newLayout && acquire.newLayout==release.oldLayout &&
           release.oldLayout==release.newLayout);
    assert(acquire.srcAccessMask==0 && release.dstAccessMask==0 &&
           acquire.dstAccessMask==release.srcAccessMask);
    assert(acquire.image==image && release.image==image &&
           acquire.subresourceRange.levelCount==1 && release.subresourceRange.layerCount==1);
    VkCommandBufferBeginInfo begin{};begin.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    assert(vkBeginCommandBuffer(cmd,&begin)==VK_SUCCESS);
    VkImageMemoryBarrier barrier{};barrier.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED;barrier.newLayout=VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    barrier.image=image;barrier.subresourceRange=frame.create_info.subresourceRange;
    barrier.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
    VkClearColorValue color{};color.float32[0]=1.0f;
    vkCmdClearColorImage(cmd,image,VK_IMAGE_LAYOUT_GENERAL,&color,1,&barrier.subresourceRange);
    assert(vkEndCommandBuffer(cmd)==VK_SUCCESS);
    VkSemaphoreCreateInfo si{};si.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkSemaphore done;assert(vkCreateSemaphore(dev,&si,nullptr,&done)==VK_SUCCESS);
    api->set_image(api->handle,&frame,0,nullptr,VK_QUEUE_FAMILY_IGNORED);
    api->set_command_buffers(api->handle,1,&cmd);
    api->set_signal_semaphore(api->handle,done);
    assert(backend.frame(true));
    // Consume previous signal using the semaphore submission mode.
    api->set_image(api->handle,&frame,1,&done,VK_QUEUE_FAMILY_IGNORED);
    assert(backend.frame(true));
    assert(backend.frame(false)); // duplicate, no stale command buffer or semaphore
    api->wait_sync_index(api->handle);
    assert(backend.good());
    vkDestroySemaphore(dev,done,nullptr);
    vkDestroyImageView(dev,frame.image_view,nullptr);
    vkDestroyImage(dev,image,nullptr);vkFreeMemory(dev,memory,nullptr);
    vkDestroyCommandPool(dev,pool,nullptr);
    backend.shutdown();
    std::puts("Vulkan headless command, semaphore, duplicate and fence tests passed");
}
