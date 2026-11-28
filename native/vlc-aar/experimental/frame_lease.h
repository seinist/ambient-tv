#pragma once
#include "unique_fd.h"
#include <fcntl.h>

namespace ambient::vlc_ahb {

// Owner-thread-only contract for one asynchronously acquired decoder image.
// The release callback consumes the image and fence fd, as AImage_deleteAsync does.
// Vulkan resource destruction is a separate milestone from returning the AImage.
class FrameLease {
public:
    using Release = void (*)(void *, int);
    FrameLease(void *image, int acquire_fd, Release release, bool allow_early_return = true)
        : image_(image), acquire_(acquire_fd), release_(release), allow_early_return_(allow_early_return) {}
    FrameLease(const FrameLease &) = delete;
    FrameLease &operator=(const FrameLease &) = delete;
    // Unsubmitted frames can always be returned with their original producer fence.
    // Submitted frames MUST be explicitly drained. If completion is uncertain,
    // fail closed: do not return a buffer that the GPU may still be reading.
    ~FrameLease() { discard(); }

    bool duplicate_acquire(UniqueFd &out) const {
        if (!image_ || !release_ || submitted_ || acquire_.get() < -1 || out.get() != -1) return false;
        if (acquire_.get() == -1) return true;
        int fd = fcntl(acquire_.get(), F_DUPFD_CLOEXEC, 0);
        if (fd < 0) return false;
        out.reset(fd);
        return true;
    }

    // Call before handing commands to the queue. An ambiguous submit failure
    // remains submitted until completion is proven; never discard on that path.
    bool begin_submit() {
        if (!image_ || !release_ || submitted_ || acquire_.get() < -1) return false;
        submitted_ = true;
        return true;
    }

    bool discard() {
        if (submitted_ || !image_ || !release_ || acquire_.get() < -1) return false;
        return_image(acquire_.release());
        return true;
    }

    // Only after successful export from a submission containing the foreign
    // ownership release. -1 is valid when export actually succeeded.
    bool return_with_fence(UniqueFd &fence, bool exported) {
        if (!allow_early_return_ || !submitted_ || !image_ || !release_ || !exported || fence.get() < -1) return false;
        acquire_.reset();
        return_image(fence.release());
        return true;
    }

    // The renderer supplies actual GPU completion AND foreign ownership release
    // evidence. A failed fence export alone does not permit buffer reuse.
    bool complete(bool gpu_done, bool foreign_released) {
        if (!submitted_ || !gpu_done || !foreign_released) return false;
        completed_ = true;
        acquire_.reset();
        if (image_ && release_) return_image(-1);
        return true;
    }

    // Only after queue idle or terminal device loss proves GPU retirement.
    // This does not claim successful execution, foreign ownership release
    // or a producer-fence wait. Return the original producer fence when
    // discarding the decoder picture; never export a GPU fence.
    bool abandon_after_gpu_retired() {
        if (!submitted_) return false;
        completed_ = true;
        if (image_ && release_) return_image(acquire_.release());
        else acquire_.reset();
        return true;
    }

    bool abandon_device_lost() { return abandon_after_gpu_retired(); }

    bool can_destroy_gpu_objects() const { return !submitted_ || completed_; }
    bool allows_early_return() const { return allow_early_return_; }
    bool has_decoder_image() const { return image_ != nullptr; }

private:
    void return_image(int fd) {
        void *image = image_;
        image_ = nullptr;
        release_(image, fd);
    }
    void *image_{};
    UniqueFd acquire_;
    Release release_{};
    bool allow_early_return_ = true;
    bool submitted_ = false;
    bool completed_ = false;
};
} // namespace ambient::vlc_ahb
