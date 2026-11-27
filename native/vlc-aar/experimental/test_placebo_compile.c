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
