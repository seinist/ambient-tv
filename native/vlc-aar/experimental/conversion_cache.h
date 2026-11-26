#pragma once
#include "ahb_import.h"
#include "rgb_target.h"
#include "sample_pass.h"
#include <optional>

namespace ambient::vlc_ahb {

// One bounded cache entry per conversion slot, on the libplacebo device.
// Only call prepare_retired after BOTH the conversion and libplacebo hold
// completion, with no live pl_tex wrapper referencing the target. A cache hit
// still requires a fresh decoder picture lease and fresh acquire/release fences.
// The imported image keeps an AHB reference, so pointer identity cannot be
// recycled while this entry exists. It does NOT retain a decoder picture.
class ConversionCacheEntry {
public:
    ConversionCacheEntry(VkPhysicalDevice physical, VkDevice device,
                         Dispatch image_dispatch, TargetDispatch target_dispatch,
                         SampleDispatch sample_dispatch, ShaderCode vert, ShaderCode frag)
        : device_(device), image_dispatch_(image_dispatch), sample_dispatch_(sample_dispatch),
          vert_(vert), frag_(frag), target_(physical, device, target_dispatch) {}
    ConversionCacheEntry(const ConversionCacheEntry &) = delete;
    ConversionCacheEntry &operator=(const ConversionCacheEntry &) = delete;

    bool matches(AHardwareBuffer *buffer, uint32_t width, uint32_t height,
                 VkFormat format, uint32_t descriptors) const {
        return pass_.has_value() && buffer && buffer_ == buffer &&
            width_ == width && height_ == height && format_ == format &&
            descriptors_ == descriptors;
    }

    VkResult prepare_retired(AHardwareBuffer *buffer, uint32_t width, uint32_t height,
                             VkFormat format, uint32_t descriptors) {
        if (!buffer || !width || !height || !descriptors)
            return VK_ERROR_INITIALIZATION_FAILED;
        if (matches(buffer, width, height, format, descriptors)) return VK_SUCCESS;

        // Framebuffer/descriptor/pipeline reference the target and immutable
        // sampler. Destroy the pass FIRST, before changing either dependency.
        pass_.reset();
        if (buffer_ != buffer || !imported_) {
            imported_.reset();
            buffer_ = nullptr;
            imported_.emplace(device_, image_dispatch_);
            VkResult result = imported_->import(buffer);
            if (result != VK_SUCCESS) {
                imported_.reset();
                return result;
            }
            buffer_ = buffer;
        }

        VkResult result = target_.ensure(width, height, format);
        if (result != VK_SUCCESS) return result;
        pass_.emplace(device_, sample_dispatch_);
        result = pass_->create(imported_->sampler(), imported_->view(), target_.view(),
                               format, width, height, descriptors, vert_, frag_);
        if (result != VK_SUCCESS) {
            pass_.reset();
            return result;
        }
        width_ = width;
        height_ = height;
        format_ = format;
        descriptors_ = descriptors;
        return VK_SUCCESS;
    }

    // Evict after full GPU retirement; keep the reusable output image.
    void evict_import_retired() {
        pass_.reset();
        imported_.reset();
        buffer_ = nullptr;
    }
    const ImportedImage *imported() const { return pass_ ? &*imported_ : nullptr; }
    const SamplePass *pass() const { return pass_ ? &*pass_ : nullptr; }
    const RgbTarget &target() const { return target_; }

private:
    VkDevice device_{};
    Dispatch image_dispatch_{};
    SampleDispatch sample_dispatch_{};
    ShaderCode vert_{}, frag_{};
    // Reverse member destruction: pass, imported image, then target.
    RgbTarget target_;
    std::optional<ImportedImage> imported_;
    std::optional<SamplePass> pass_;
    AHardwareBuffer *buffer_{};
    uint32_t width_{}, height_{}, descriptors_{};
    VkFormat format_ = VK_FORMAT_UNDEFINED;
};

} // namespace ambient::vlc_ahb
