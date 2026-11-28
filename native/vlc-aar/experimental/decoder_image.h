#pragma once
#include "frame_lease.h"
#include <media/NdkImage.h>
#include <android/hardware_buffer.h>

namespace ambient::vlc_ahb {

// Accept an AImage acquired with AImageReader_acquire*ImageAsync. The decoder
// context and its reader must outlive the outstanding lease.
inline void return_decoder_image(void *image, int release_fd) {
    AImage_deleteAsync(static_cast<AImage *>(image), release_fd);
}

struct DecoderImageInfo {
    AHardwareBuffer *buffer = nullptr; // borrowed until the AImage is returned
    AImageCropRect crop{};
    int64_t timestamp_ns = 0;
};

// Import and retain the hardware buffer independently before returning the AImage.
// Failure leaves the original lease available for a fenced discard.
inline media_status_t read_decoder_image(AImage *image, DecoderImageInfo &out) {
    if (!image) return AMEDIA_ERROR_INVALID_PARAMETER;
    DecoderImageInfo info;
    media_status_t result = AImage_getHardwareBuffer(image, &info.buffer);
    if (result != AMEDIA_OK) return result;
    if (!info.buffer) return AMEDIA_ERROR_INVALID_PARAMETER;
    result = AImage_getCropRect(image, &info.crop);
    if (result != AMEDIA_OK) return result;
    result = AImage_getTimestamp(image, &info.timestamp_ns);
    if (result != AMEDIA_OK) return result;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(info.buffer, &desc);
    if (info.crop.left < 0 || info.crop.top < 0 ||
        info.crop.right <= info.crop.left || info.crop.bottom <= info.crop.top ||
        static_cast<uint32_t>(info.crop.right) > desc.width ||
        static_cast<uint32_t>(info.crop.bottom) > desc.height)
        return AMEDIA_ERROR_INVALID_PARAMETER;
    out = info;
    return AMEDIA_OK;
}
} // namespace ambient::vlc_ahb
