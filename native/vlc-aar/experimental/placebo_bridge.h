#pragma once

// C adapter for the pinned libplacebo 5.264.1 display module. This is intentionally
// restricted to a single graphics queue; concurrent queue families need additional
// target-image sharing and explicit synchronization before they can be enabled.
#include <libplacebo/vulkan.h>
#include <libplacebo/renderer.h>

static inline bool ambient_pl_single_queue(pl_vulkan vk, VkDevice device, uint32_t family)
{
    return vk && device && vk->device == device && vk->num_queues == 1 &&
           vk->queue_graphics.index == family && vk->queue_graphics.count == 1 &&
           vk->queues && vk->queues[0].index == family && vk->queues[0].count == 1 &&
           vk->lock_queue && vk->unlock_queue;
}

// Call only after the external submission fence has actually signaled. Its
// render pass has transitioned the RGB target to SHADER_READ_ONLY_OPTIMAL.
// No cross-device handle import and no CPU download. Use exactly the same queue
// (index zero) for conversion submissions and libplacebo. Do not wrap twice.
static inline pl_tex ambient_pl_wrap_completed_rgb(pl_gpu gpu, VkDevice device,
                                                   uint32_t family, VkImage image,
                                                   VkFormat format, int width, int height,
                                                   VkImageUsageFlags usage)
{
    if (!gpu || !image || width <= 0 || height <= 0 ||
        !ambient_pl_single_queue(pl_vulkan_get(gpu), device, family) ||
        (usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)) !=
        (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT))
        return NULL;
    if (format != VK_FORMAT_R8G8B8A8_UNORM && format != VK_FORMAT_B8G8R8A8_UNORM &&
        format != VK_FORMAT_R16G16B16A16_UNORM && format != VK_FORMAT_R16G16B16A16_SFLOAT)
        return NULL;
    struct pl_vulkan_wrap_params wrap = {0};
    wrap.image = image;
    wrap.width = width;
    wrap.height = height;
    wrap.format = format;
    wrap.usage = usage;
    pl_tex texture = pl_vulkan_wrap(gpu, &wrap);
    if (!texture) return NULL;
    struct pl_vulkan_release_params release = {0};
    release.tex = texture;
    release.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    release.qf = family;
    pl_vulkan_release_ex(gpu, &release);
    return texture;
}

// Driver YCbCr conversion has already produced RGB. Never run YCbCr matrix or
// limited-range expansion twice. The caller supplies verified SDR primaries
// and transfer, and applies the existing VLC orientation and destination crop.
// The input crop was already applied in the conversion pass.
static inline struct pl_frame ambient_pl_rgb_frame(pl_tex texture, struct pl_color_space color)
{
    struct pl_frame frame = {0};
    if (!texture) return frame;
    frame.num_planes = 1;
    frame.planes[0].texture = texture;
    frame.planes[0].components = 3;
    frame.planes[0].component_mapping[0] = 0;
    frame.planes[0].component_mapping[1] = 1;
    frame.planes[0].component_mapping[2] = 2;
    frame.planes[0].component_mapping[3] = -1;
    frame.repr.sys = PL_COLOR_SYSTEM_RGB;
    frame.repr.levels = PL_COLOR_LEVELS_FULL;
    frame.color = color;
    frame.crop.x1 = texture->params.w;
    frame.crop.y1 = texture->params.h;
    return frame;
}

// Once no more rendering calls reference the texture, request ownership back.
// The supplied binary semaphore must remain alive; the owner waits for a
// submission consuming it and proves completion BEFORE reusing or freeing the
// raw VkImage or destroying the wrapper. No implicit global device wait.
static inline bool ambient_pl_hold_rgb(pl_gpu gpu, pl_tex texture, uint32_t family,
                                      VkSemaphore semaphore)
{
    if (!gpu || !texture || !semaphore) return false;
    pl_vulkan vk = pl_vulkan_get(gpu);
    if (!vk || !ambient_pl_single_queue(vk, vk->device, family)) return false;
    struct pl_vulkan_hold_params hold = {0};
    hold.tex = texture;
    hold.layout = VK_IMAGE_LAYOUT_GENERAL;
    hold.qf = family;
    hold.semaphore.sem = semaphore;
    return pl_vulkan_hold_ex(gpu, &hold);
}
