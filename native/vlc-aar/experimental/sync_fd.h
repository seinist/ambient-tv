#pragma once
#include <unistd.h>
#include <vulkan/vulkan.h>

namespace ambient::vlc_ahb {

// Ownership of Android sync file descriptors is explicit. -1 means already signaled
// only after a successful import/export or an AImage acquire with no fence.
class UniqueFd {
public:
    explicit UniqueFd(int fd = -1) : fd_(fd) {}
    UniqueFd(const UniqueFd &) = delete;
    UniqueFd &operator=(const UniqueFd &) = delete;
    ~UniqueFd() { reset(); }
    int get() const { return fd_; }
    int release() { int value = fd_; fd_ = -1; return value; }
    void reset(int fd = -1) {
        if (fd_ == fd) return;
        if (fd_ >= 0) close(fd_);
        fd_ = fd;
    }
private:
    int fd_;
};

// Caller must supply a fresh binary semaphore, not a timeline semaphore.
// Successful import consumes the descriptor; failure leaves it with the caller.
inline VkResult import_acquire_fd(VkDevice device, PFN_vkImportSemaphoreFdKHR import_fd,
                                  VkSemaphore semaphore, UniqueFd &fd) {
    if (!device || !import_fd || !semaphore || fd.get() < -1)
        return VK_ERROR_INITIALIZATION_FAILED;
    VkImportSemaphoreFdInfoKHR info{VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR};
    info.semaphore = semaphore;
    info.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
    info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    info.fd = fd.get();
    VkResult result = import_fd(device, &info);
    if (result == VK_SUCCESS) fd.release();
    return result;
}

// Signal a separate exportable binary semaphore in the final submission that releases
// foreign ownership. Call this only after the signal operation has been submitted.
// Success transfers the exported fd to the caller; -1 is a valid signaled result.
inline VkResult export_release_fd(VkDevice device, PFN_vkGetSemaphoreFdKHR export_fd,
                                  VkSemaphore semaphore, UniqueFd &out) {
    if (!device || !export_fd || !semaphore || out.get() != -1)
        return VK_ERROR_INITIALIZATION_FAILED;
    VkSemaphoreGetFdInfoKHR info{VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};
    info.semaphore = semaphore;
    info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    VkResult result = export_fd(device, &info, &fd);
    if (result == VK_SUCCESS) out.reset(fd);
    return result;
}
} // namespace ambient::vlc_ahb
