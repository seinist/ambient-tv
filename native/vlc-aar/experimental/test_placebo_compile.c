/* Compile against pinned libplacebo; does not execute or link GPU work. */
#include "placebo_bridge.h"

bool compile_placebo_handoff(pl_gpu gpu, VkDevice device, uint32_t family,
                            VkImage image, VkSemaphore converted, VkSemaphore reclaimed)
{
    if (!ambient_pl_ahb_device_enabled(pl_vulkan_get(gpu)))
        return false;
    pl_tex texture = ambient_pl_wrap_rgb_held(gpu, device, family, image,
                        VK_FORMAT_R8G8B8A8_UNORM, 1920, 1080,
                        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    if (!texture)
        return false;
    ambient_pl_release_rgb(gpu, texture, family, converted);
    struct pl_frame frame = ambient_pl_rgb_frame(texture, pl_color_space_bt709);
    (void)frame;
    struct pl_frame raw = ambient_pl_raw_p010_frame(texture, pl_color_space_bt709, PL_COLOR_LEVELS_LIMITED);
    (void)raw;
    /* The real owner retains the wrapper until the hold semaphore is consumed. */
    return ambient_pl_hold_rgb(gpu, texture, family, reclaimed);
}

/* Topology contract: one family with one or multiple queues is supported;
 * independent queue families must never accidentally enter the AHB path. */
static void fake_queue_lock(pl_vulkan vk, int family, int index)
{
    (void)vk; (void)family; (void)index;
}
bool compile_placebo_queue_topology(void)
{
    struct pl_vulkan_queue families[] = {{3, 2}, {4, 1}};
    struct pl_vulkan_t vk = {
        .device = (VkDevice)(uintptr_t)1, .queue_graphics = {3, 2},
        .queues = families, .num_queues = 1,
        .lock_queue = fake_queue_lock, .unlock_queue = fake_queue_lock,
    };
    if (!ambient_pl_single_queue_family(&vk, vk.device, 3)) return false;
    vk.num_queues = 2;
    if (ambient_pl_single_queue_family(&vk, vk.device, 3)) return false;
    vk.num_queues = 1;
    vk.queue_graphics.count = 1;
    families[0].count = 1;
    return ambient_pl_single_queue_family(&vk, vk.device, 3);
}
