#pragma once
/* Included by the pinned VLC display.c only in experimental Android builds. */
#include "display_owner.h"
#include "placebo_bridge.h"
#include "vlc_picture_bridge.h"
#include "ambient_sample_spv.h"

static inline void ambient_display_lock(void *opaque)
{
    pl_vulkan vk = opaque;
    vk->lock_queue(vk, vk->queue_graphics.index, 0);
}
static inline void ambient_display_unlock(void *opaque)
{
    pl_vulkan vk = opaque;
    vk->unlock_queue(vk, vk->queue_graphics.index, 0);
}
static inline void *ambient_display_wrap(void *opaque, VkImage image, VkFormat format,
                                        uint32_t width, uint32_t height, VkImageUsageFlags usage)
{
    pl_vulkan vk = opaque;
    if (width > INT32_MAX || height > INT32_MAX) return NULL;
    return (void *)ambient_pl_wrap_rgb_held(vk->gpu, vk->device, vk->queue_graphics.index,
                                            image, format, width, height, usage);
}
static inline void ambient_display_release(void *opaque, void *texture, VkSemaphore semaphore)
{
    pl_vulkan vk = opaque;
    ambient_pl_release_rgb(vk->gpu, texture, vk->queue_graphics.index, semaphore);
}
static inline bool ambient_display_hold(void *opaque, void *texture, VkSemaphore semaphore)
{
    pl_vulkan vk = opaque;
    if (!ambient_pl_hold_rgb(vk->gpu, texture, vk->queue_graphics.index, semaphore))
        return false;
    pl_gpu_flush(vk->gpu);
    return true;
}
static inline void ambient_display_destroy_texture(void *opaque, void *texture)
{
    pl_vulkan vk = opaque;
    pl_tex tex = texture;
    pl_tex_destroy(vk->gpu, &tex);
    // Rare resize/teardown only: retire libplacebo's deferred view destruction
    // before the external owner destroys or reallocates the raw VkImage.
    pl_gpu_finish(vk->gpu);
}

/* Admit only explicit BT.709 SDR and BT.2020 PQ/HLG. PQ/HLG is routed
 * through a 16-bit UNORM target with the original nonlinear color transfer.
 * Do not infer the driver matrix, range or unknown transfer. */
static inline bool ambient_display_sdr(const video_format_t *fmt)
{
    return fmt && fmt->primaries == COLOR_PRIMARIES_BT709 &&
        fmt->transfer == TRANSFER_FUNC_BT709 && fmt->space == COLOR_SPACE_BT709 &&
        (fmt->color_range == COLOR_RANGE_FULL || fmt->color_range == COLOR_RANGE_LIMITED) &&
        !fmt->mastering.max_luminance && !fmt->lighting.MaxCLL && !fmt->lighting.MaxFALL;
}
static inline bool ambient_display_hdr(const video_format_t *fmt)
{
    return fmt && fmt->primaries == COLOR_PRIMARIES_BT2020 &&
        fmt->space == COLOR_SPACE_BT2020 &&
        (fmt->transfer == TRANSFER_FUNC_SMPTE_ST2084 || fmt->transfer == TRANSFER_FUNC_HLG) &&
        (fmt->color_range == COLOR_RANGE_FULL || fmt->color_range == COLOR_RANGE_LIMITED);
}
static inline struct ambient_ahb_owner *
ambient_display_open(pl_gpu gpu, const video_format_t *fmt, vlc_video_context *context)
{
    if (!fmt || fmt->i_chroma != VLC_CODEC_ANDROID_OPAQUE ||
        (!ambient_display_sdr(fmt) && !ambient_display_hdr(fmt)) || !context)
        return NULL;
    android_video_context_t *avctx =
        vlc_video_context_GetPrivate(context, VLC_VIDEO_CONTEXT_AWINDOW);
    if (!avctx || !avctx->air || !avctx->air_api) return NULL;
    pl_vulkan vk = pl_vulkan_get(gpu);
    if (!ambient_pl_ahb_device_enabled(vk)) return NULL;
    // A conservative descriptor-pool budget, not an asserted per-format
    // descriptor count. Allocation failure rejects this experimental path.
    struct ambient_ahb_config config = {
        .instance = vk->instance, .physical = vk->phys_device, .device = vk->device,
        .family = vk->queue_graphics.index, .get_proc = vk->get_proc_addr,
        .opaque = (void *)vk, .lock_queue = ambient_display_lock,
        .unlock_queue = ambient_display_unlock, .wrap = ambient_display_wrap,
        .release = ambient_display_release, .hold_and_flush = ambient_display_hold,
        .destroy_texture = ambient_display_destroy_texture,
        .vert_words = ambient_sample_vert, .vert_bytes = sizeof(ambient_sample_vert),
        .frag_words = ambient_sample_frag, .frag_bytes = sizeof(ambient_sample_frag),
        .descriptor_count = 16,
    };
    return ambient_ahb_open(&config);
}

static inline VkResult ambient_display_prepare(struct ambient_ahb_owner *owner,
                                              picture_t *pic, struct ambient_ahb_output *out)
{
    if (owner) ambient_ahb_poll(owner);
    if (!owner || !pic ||
        (!ambient_display_sdr(&pic->format) && !ambient_display_hdr(&pic->format)) ||
        picture_GetAncillary(pic, VLC_ANCILLARY_ID_DOVI) ||
        picture_GetAncillary(pic, VLC_ANCILLARY_ID_HDR10PLUS))
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    struct ambient_vlc_picture held;
    if (!ambient_vlc_hold_picture(pic, &held)) return VK_ERROR_FORMAT_NOT_SUPPORTED;
    uint32_t width = held.crop.right - held.crop.left;
    uint32_t height = held.crop.bottom - held.crop.top;
    if (width != pic->format.i_visible_width || height != pic->format.i_visible_height ||
        (pic->format.i_x_offset && pic->format.i_x_offset != (unsigned)held.crop.left) ||
        (pic->format.i_y_offset && pic->format.i_y_offset != (unsigned)held.crop.top)) {
        ambient_vlc_release_picture(held.held, held.acquire_fd);
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    }
    const struct ambient_ahb_input input = {
        .picture = held.held, .acquire_fd = held.acquire_fd,
        .release_picture = ambient_vlc_release_picture, .buffer = held.buffer,
        .buffer_id = held.buffer_id, .buffer_id_valid = held.buffer_id_valid,
        .width = width, .height = height,
        .crop = { (float)held.crop.left / held.width, (float)held.crop.top / held.height,
                  (float)width / held.width, (float)height / held.height },
        .model = ambient_display_hdr(&pic->format) ? VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020 :
            VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709,
        .high_precision = ambient_display_hdr(&pic->format),
        .range = pic->format.color_range == COLOR_RANGE_FULL ?
            VK_SAMPLER_YCBCR_RANGE_ITU_FULL : VK_SAMPLER_YCBCR_RANGE_ITU_NARROW,
    };
    return ambient_ahb_prepare(owner, &input, out);
}
