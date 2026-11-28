#pragma once
#include "ahb_import.h"
#include "rgb_target.h"
#include "sample_pass.h"
#include <optional>

namespace ambient::vlc_ahb {

// One bounded cache entry per conversion slot, on the libplacebo device.
// Only call prepare_retired after BOTH the conversion and libplacebo hold
// completion. A held pl_tex wrapper may survive when the target geometry and
// format are unchanged; destroy it before replacing the target. A cache hit
// still requires a fresh decoder picture lease and fresh acquire/release fences.
// The imported image keeps an AHB reference, so pointer identity cannot be
// recycled while this entry exists. It does NOT retain a decoder picture.
class ConversionCacheEntry {
public:
    ConversionCacheEntry(VkPhysicalDevice physical, VkDevice device,
                         Dispatch image_dispatch, TargetDispatch target_dispatch,
                         SampleDispatch sample_dispatch, ShaderCode vert, ShaderCode frag,
                         VkPipelineCache pipeline_cache = VK_NULL_HANDLE)
        : physical_(physical), device_(device), image_dispatch_(image_dispatch), sample_dispatch_(sample_dispatch),
          vert_(vert), frag_(frag), pipeline_cache_(pipeline_cache), target_(physical, device, target_dispatch) {}
    ConversionCacheEntry(const ConversionCacheEntry &) = delete;
    ConversionCacheEntry &operator=(const ConversionCacheEntry &) = delete;

    bool same_buffer(AHardwareBuffer *buffer, uint64_t id = 0, bool id_valid = false) const {
        return buffer && buffer_ && (id_valid && buffer_id_valid_ ?
            id == buffer_id_ : buffer_ == buffer);
    }

    bool matches(AHardwareBuffer *buffer, uint32_t width, uint32_t height,
                 VkFormat format, uint32_t descriptors, uint64_t id = 0,
                 bool id_valid = false, bool raw_yuv = false) const {
        return raw_yuv_ == raw_yuv && pass_.has_value() && same_buffer(buffer, id, id_valid) &&
            width_ == width && height_ == height && format_ == format &&
            descriptors_ == descriptors;
    }

    VkResult prepare_retired(AHardwareBuffer *buffer, uint32_t width, uint32_t height,
                             VkFormat format, uint32_t descriptors, uint64_t id = 0,
                             bool id_valid = false, bool raw_yuv = false) {
        if (!buffer || !width || !height || !descriptors)
            return VK_ERROR_INITIALIZATION_FAILED;
        if (matches(buffer, width, height, format, descriptors, id, id_valid, raw_yuv)) return VK_SUCCESS;

        // Different AHB, same output geometry and exact conversion format:
        // import just the replacement image and update its descriptor, not
        // the immutable sampler, render pass, framebuffer or graphics pipeline.
        if (raw_yuv_ == raw_yuv && pass_ && imported_ && !same_buffer(buffer, id, id_valid) && width_ == width &&
            height_ == height && format_ == format && descriptors_ == descriptors) {
            bool reused = false;
            VkResult result = imported_->try_rebind(buffer, reused);
            if (result != VK_SUCCESS) return result;
            if (reused) {
                buffer_ = buffer;
                buffer_id_ = id; buffer_id_valid_ = id_valid;
                if (pass_->rebind_input(imported_->view())) return VK_SUCCESS;
                pass_.reset();
                return VK_ERROR_INITIALIZATION_FAILED;
            }
        }

        // Framebuffer/descriptor/pipeline reference the target and immutable
        // sampler. Destroy the pass FIRST, before changing either dependency.
        pass_.reset();
        if (raw_yuv_ != raw_yuv || !same_buffer(buffer, id, id_valid) || !imported_) {
            imported_.reset();
            buffer_ = nullptr;
            buffer_id_valid_ = false;
            imported_.emplace(device_, image_dispatch_, physical_);
            VkResult result = imported_->import(buffer, raw_yuv);
            if (result != VK_SUCCESS) {
                imported_.reset();
                return result;
            }
            buffer_ = buffer;
            buffer_id_ = id; buffer_id_valid_ = id_valid;
            raw_yuv_ = raw_yuv;
        }

        VkResult result = target_.ensure(width, height, format);
        if (result != VK_SUCCESS) return result;
        pass_.emplace(device_, sample_dispatch_);
        // Native P010 has an exact descriptor count from the format query.
        // Only opaque external imports need the device-wide conservative budget.
        const uint32_t pool_descriptors = raw_yuv ? imported_->native_descriptors() : descriptors;
        result = pass_->create(imported_->sampler(), imported_->view(), target_.view(),
                               format, width, height, pool_descriptors, vert_, frag_, pipeline_cache_);
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
        buffer_id_valid_ = false;
    }
    const ImportedImage *imported() const { return pass_ ? &*imported_ : nullptr; }
    const SamplePass *pass() const { return pass_ ? &*pass_ : nullptr; }
    const RgbTarget &target() const { return target_; }

private:
    VkPhysicalDevice physical_{};
    VkDevice device_{};
    Dispatch image_dispatch_{};
    SampleDispatch sample_dispatch_{};
    ShaderCode vert_{}, frag_{};
    VkPipelineCache pipeline_cache_{};
    // Reverse member destruction: pass, imported image, then target.
    RgbTarget target_;
    std::optional<ImportedImage> imported_;
    std::optional<SamplePass> pass_;
    AHardwareBuffer *buffer_{};
    uint64_t buffer_id_{};
    bool buffer_id_valid_{};
    bool raw_yuv_{};
    uint32_t width_{}, height_{}, descriptors_{};
    VkFormat format_ = VK_FORMAT_UNDEFINED;
};

} // namespace ambient::vlc_ahb
