#pragma once

// C adapter intended for VLC's modules/video_output/libplacebo directory.
// Use the pinned decoder's dynamically loaded AImage APIs. The AImage belongs
// to picture_t's context: never independently delete it.
#include <vlc_common.h>
#include <vlc_picture.h>
#include <vlc_fourcc.h>
#include "../android/utils.h"
#include <fcntl.h>

struct ambient_vlc_picture {
    picture_t *held;
    AHardwareBuffer *buffer; // borrowed while held is alive
    AImageCropRect crop;
    int64_t timestamp_ns;
    uint32_t width, height;
    int acquire_fd;          // owned by the recipient, -1 means no fence
};

// Result must be handed to the C++ FrameLease with allow_early_return=false.
// A caller that fails before constructing the lease calls
// ambient_vlc_release_picture(result.held, result.acquire_fd).
static inline bool ambient_vlc_hold_picture(picture_t *pic, struct ambient_vlc_picture *out)
{
    if (!pic || !out || pic->format.i_chroma != VLC_CODEC_ANDROID_OPAQUE || !pic->context)
        return false;
    vlc_video_context *context = picture_GetVideoContext(pic);
    if (!context) return false;
    android_video_context_t *avctx =
        vlc_video_context_GetPrivate(context, VLC_VIDEO_CONTEXT_AWINDOW);
    if (!avctx || !avctx->air || !avctx->air_api) return false;
    struct android_picture_ctx *apctx =
        container_of(pic->context, struct android_picture_ctx, s);
    if (!apctx->image || apctx->fence_fd < -1) return false;
    struct aimage_reader_api *api = avctx->air_api;
    if (!api->AImage.getHardwareBuffer || !api->AImage.getCropRect ||
        !api->AImage.getTimestamp || !api->AHardwareBuffer.describe)
        return false;
    struct ambient_vlc_picture result = {0};
    result.acquire_fd = -1;
    if (api->AImage.getHardwareBuffer(apctx->image, &result.buffer) != 0 || !result.buffer ||
        api->AImage.getCropRect(apctx->image, &result.crop) != 0 ||
        api->AImage.getTimestamp(apctx->image, &result.timestamp_ns) != 0)
        return false;
    AHardwareBuffer_Desc desc = {0};
    api->AHardwareBuffer.describe(result.buffer, &desc);
    if (result.crop.left < 0 || result.crop.top < 0 ||
        result.crop.right <= result.crop.left || result.crop.bottom <= result.crop.top ||
        (uint32_t)result.crop.right > desc.width || (uint32_t)result.crop.bottom > desc.height)
        return false;
    result.width = desc.width;
    result.height = desc.height;
    // Do not mistake a failed dup() for an already signaled producer fence.
    if (apctx->fence_fd >= 0) {
        result.acquire_fd = fcntl(apctx->fence_fd, F_DUPFD_CLOEXEC, 0);
        if (result.acquire_fd < 0) return false;
    }
    result.held = picture_Hold(pic);
    *out = result;
    return true;
}

// The decoder's original fence and AImage are still owned by picture_t.
// This callback is only legal before submission or AFTER proven completion;
// it cannot be used with an early GPU release-fence handoff.
static inline void ambient_vlc_release_picture(void *held, int duplicated_fd)
{
    if (duplicated_fd >= 0) close(duplicated_fd);
    if (held) picture_Release((picture_t *)held);
}
