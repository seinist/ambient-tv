#pragma once
#include "frame_lease.h"
#include "sync_fd.h"
#include <vulkan/vulkan.h>

namespace ambient::vlc_ahb {

struct QueueDispatch {
    PFN_vkCreateSemaphore create_semaphore{};
    PFN_vkDestroySemaphore destroy_semaphore{};
    PFN_vkCreateFence create_fence{};
    PFN_vkDestroyFence destroy_fence{};
    PFN_vkImportSemaphoreFdKHR import_fd{};
    PFN_vkGetSemaphoreFdKHR export_fd{};
    PFN_vkQueueSubmit submit{};
    PFN_vkGetFenceStatus fence_status{};
    PFN_vkResetFences reset_fences{};

    bool valid() const {
        return create_semaphore && destroy_semaphore && create_fence && destroy_fence &&
            import_fd && submit && fence_status;
    }
};

// One in-flight frame. This object, its FrameLease, ImportedImage, descriptors,
// pipelines and command buffer must remain alive until poll() succeeds.
// Caller serializes access to the actual renderer queue. No vkDeviceWaitIdle.
class Submission {
public:
    Submission(VkDevice device, QueueDispatch dispatch, bool reuse_after_handoff = false)
        : device_(device), d_(dispatch), reuse_after_handoff_(reuse_after_handoff) {}
    Submission(const Submission &) = delete;
    Submission &operator=(const Submission &) = delete;
    ~Submission() { if (!submitted_ || (completed_ && !handed_off_)) destroy(); }

    // cmd must have recorded paired foreign acquire/release barriers around all
    // sampling, and be executable. No caller-owned semaphores are consumed.
    VkResult submit(VkQueue queue, VkCommandBuffer cmd, FrameLease &lease) {
        if (!device_ || !queue || !cmd || !d_.valid() || submitted_ ||
            (reuse_after_handoff_ ? !d_.reset_fences : !d_.export_fd)) return VK_ERROR_INITIALIZATION_FAILED;
        if ((acquire_ || release_ || done_) && !(acquire_ && release_ && done_ && reusable_))
            return VK_ERROR_INITIALIZATION_FAILED;
        UniqueFd producer;
        if (!lease.duplicate_acquire(producer)) return VK_ERROR_INITIALIZATION_FAILED;

        VkResult result;
        if (reusable_) {
            // Conversion and the consumer's semaphore wait have both retired.
            // Temporary imported payload was consumed, restoring the original
            // unsignaled binary semaphore. The ready semaphore was also consumed.
            result = d_.reset_fences(device_, 1, &done_);
            reusable_ = false;
            if (result != VK_SUCCESS) return fail_before_submit(result);
        } else {
            VkSemaphoreCreateInfo plain{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            result = d_.create_semaphore(device_, &plain, nullptr, &acquire_);
            if (result != VK_SUCCESS) return fail_before_submit(result);
            VkExportSemaphoreCreateInfo external{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
            external.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            VkSemaphoreCreateInfo exportable{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            // The real VLC path never exports the ready semaphore: it is
            // handed to libplacebo on the same device instead.
            exportable.pNext = reuse_after_handoff_ ? nullptr : &external;
            result = d_.create_semaphore(device_, &exportable, nullptr, &release_);
            if (result != VK_SUCCESS) return fail_before_submit(result);
            VkFenceCreateInfo fence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            result = d_.create_fence(device_, &fence, nullptr, &done_);
            if (result != VK_SUCCESS) return fail_before_submit(result);
        }
        result = import_acquire_fd(device_, d_.import_fd, acquire_, producer);
        if (result != VK_SUCCESS) return fail_before_submit(result);

        // Conservative stage mask covers the ownership acquisition itself.
        const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo info{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        info.waitSemaphoreCount = 1;
        info.pWaitSemaphores = &acquire_;
        info.pWaitDstStageMask = &wait_stage;
        info.commandBufferCount = 1;
        info.pCommandBuffers = &cmd;
        info.signalSemaphoreCount = 1;
        info.pSignalSemaphores = &release_;
        if (!lease.begin_submit()) return fail_before_submit(VK_ERROR_INITIALIZATION_FAILED);
        lease_ = &lease;
        submitted_ = true;
        result = d_.submit(queue, 1, &info, done_);
        accepted_ = result == VK_SUCCESS;
        // A failed/uncertain submission must not cause premature reuse. The
        // owning renderer retains the whole frame until a safe recovery path.
        return result;
    }

    VkResult return_decoder_image() {
        if (reuse_after_handoff_ || !accepted_ || completed_ || handed_off_ || !lease_ || !lease_->has_decoder_image() || !lease_->allows_early_return() || export_attempted_)
            return VK_ERROR_INITIALIZATION_FAILED;
        export_attempted_ = true;
        UniqueFd release_fd;
        VkResult result = export_release_fd(device_, d_.export_fd, release_, release_fd);
        if (result != VK_SUCCESS) return result;
        return lease_->return_with_fence(release_fd, true)
            ? VK_SUCCESS : VK_ERROR_INITIALIZATION_FAILED;
    }

    // Transfer the conversion-complete binary semaphore to the libplacebo
    // consumer. Unlike sync-fd export, this keeps the Vulkan semaphore alive
    // until the consumer's own hold/reclaim submission has completed.
    VkSemaphore handoff_ready_semaphore() {
        if (!accepted_ || completed_ || handed_off_ || export_attempted_)
            return VK_NULL_HANDLE;
        handed_off_ = true;
        return release_;
    }

    bool retire_after_consumer(bool consumer_done) {
        if (!consumer_done || !completed_ || !handed_off_) return false;
        handed_off_ = false;
        if (reuse_after_handoff_) {
            // No outstanding ownership, reusable only after the actual wait.
            submitted_ = accepted_ = completed_ = export_attempted_ = false;
            reusable_ = true;
        } else destroy();
        return true;
    }

    // Shutdown only: caller has proven that all producer AND consumer work
    // on the shared queue completed, including an uncertain submit.
    bool drain_after_idle(bool queue_idle) {
        if (!queue_idle) return false;
        if (lease_ && !lease_->complete(true, true)) return false;
        lease_ = nullptr;
        completed_ = true;
        handed_off_ = false;
        destroy();
        return true;
    }

    // Terminal teardown only, after VK_ERROR_DEVICE_LOST from queue idle.
    // Vulkan allows destruction of in-use objects on reported device loss;
    // do not conflate this with successful execution or ownership transfer.
    bool drain_after_device_lost() {
        if (lease_ && !lease_->abandon_device_lost()) return false;
        lease_ = nullptr;
        completed_ = true;
        handed_off_ = false;
        destroy();
        return true;
    }

    // Nonblocking retirement. Even if the decoder already received its fence,
    // the Vulkan resources stay alive until actual submission completion.
    VkResult poll() {
        if (completed_) return VK_SUCCESS;
        if (!accepted_ || !lease_) return VK_ERROR_INITIALIZATION_FAILED;
        VkResult result = d_.fence_status(device_, done_);
        if (result != VK_SUCCESS) return result;
        if (!lease_->complete(true, true)) return VK_ERROR_INITIALIZATION_FAILED;
        completed_ = true;
        lease_ = nullptr;
        if (!handed_off_) destroy();
        return VK_SUCCESS;
    }

    bool can_retire() const { return !submitted_ || (completed_ && !handed_off_); }
    bool uncertain() const { return submitted_ && !accepted_; }

private:
    VkResult fail_before_submit(VkResult result) { destroy(); return result; }
    void destroy() {
        if (done_) d_.destroy_fence(device_, done_, nullptr);
        if (release_) d_.destroy_semaphore(device_, release_, nullptr);
        if (acquire_) d_.destroy_semaphore(device_, acquire_, nullptr);
        done_ = VK_NULL_HANDLE;
        release_ = acquire_ = VK_NULL_HANDLE;
        reusable_ = false;
    }
    VkDevice device_{};
    QueueDispatch d_{};
    VkSemaphore acquire_{};
    VkSemaphore release_{};
    VkFence done_{};
    FrameLease *lease_{};
    bool submitted_ = false;
    bool accepted_ = false;
    bool completed_ = false;
    bool export_attempted_ = false;
    bool handed_off_ = false;
    bool reuse_after_handoff_ = false;
    bool reusable_ = false;
};
} // namespace ambient::vlc_ahb
