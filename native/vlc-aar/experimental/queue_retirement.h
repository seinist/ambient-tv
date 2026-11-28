#pragma once
#include "submission.h"

namespace ambient::vlc_ahb {

// Explicit lifetime: a timed-out fence remains allocated with its device.
// The owner may release it only after completion or reported device loss.
class QueueRetirement {
public:
    VkResult begin(VkDevice device, VkQueue queue, const QueueDispatch &d) {
        if (started_) return state_;
        started_ = true;
        VkFenceCreateInfo info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        VkFence fence = VK_NULL_HANDLE;
        state_ = d.create_fence(device, &info, nullptr, &fence);
        if (state_ != VK_SUCCESS) return state_;
        fence_ = fence;
        // A zero-submit fence covers prior submissions on the same queue.
        state_ = d.submit(queue, 0, nullptr, fence_);
        if (state_ == VK_SUCCESS) state_ = VK_NOT_READY;
        return state_;
    }

    template<class Continue, class Pause>
    VkResult wait(VkDevice device, const QueueDispatch &d, Continue keep_waiting, Pause pause) {
        if (!started_) return VK_ERROR_INITIALIZATION_FAILED;
        while (state_ == VK_NOT_READY) {
            state_ = d.fence_status(device, fence_);
            if (state_ != VK_NOT_READY) break;
            if (!keep_waiting()) return VK_TIMEOUT;
            pause();
        }
        return state_;
    }

    bool release(VkDevice device, const QueueDispatch &d, bool device_lost) {
        if (fence_ && state_ != VK_SUCCESS && state_ != VK_ERROR_DEVICE_LOST && !device_lost)
            return false;
        if (fence_) d.destroy_fence(device, fence_, nullptr);
        fence_ = VK_NULL_HANDLE;
        return true;
    }

private:
    VkFence fence_{};
    bool started_{};
    VkResult state_ = VK_ERROR_INITIALIZATION_FAILED;
};

} // namespace ambient::vlc_ahb
