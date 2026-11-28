#pragma once
#include <vulkan/vulkan.h>

namespace ambient::vlc_ahb {

// The decoder/Android buffer queue owns the image in GENERAL layout. Importing
// memory is not acquisition. Record both halves on the renderer's graphics queue,
// bracketing the actual fragment sampling pass. Wait on the producer semaphore
// before executing the acquire; signal the consumer semaphore after the release.
inline bool record_foreign_acquire(PFN_vkCmdPipelineBarrier barrier,
                                   VkCommandBuffer cmd, VkImage image, uint32_t graphics_family) {
    if (!barrier || !cmd || !image || graphics_family >= VK_QUEUE_FAMILY_FOREIGN_EXT)
        return false;
    VkImageMemoryBarrier transfer{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    transfer.srcAccessMask = 0;
    transfer.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    transfer.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    transfer.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    transfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    transfer.dstQueueFamilyIndex = graphics_family;
    transfer.image = image;
    transfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &transfer);
    return true;
}

inline bool record_foreign_release(PFN_vkCmdPipelineBarrier barrier,
                                   VkCommandBuffer cmd, VkImage image, uint32_t graphics_family) {
    if (!barrier || !cmd || !image || graphics_family >= VK_QUEUE_FAMILY_FOREIGN_EXT)
        return false;
    VkImageMemoryBarrier transfer{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    transfer.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    transfer.dstAccessMask = 0;
    transfer.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    transfer.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    transfer.srcQueueFamilyIndex = graphics_family;
    transfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    transfer.image = image;
    transfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 1, &transfer);
    return true;
}

} // namespace ambient::vlc_ahb
