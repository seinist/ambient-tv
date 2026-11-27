#include "submission.h"
#include <cassert>
#include <unistd.h>
#include <type_traits>

using namespace ambient::vlc_ahb;
namespace {
template<class T> T handle(uintptr_t value) {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(value);
    else return static_cast<T>(value);
}
int sem_created, sem_destroyed, fence_destroyed, released, fence_resets;
bool completed, fail_submit;
int last_released_fd = -2;
VKAPI_ATTR VkResult VKAPI_CALL create_sem(VkDevice, const VkSemaphoreCreateInfo *,
    const VkAllocationCallbacks *, VkSemaphore *out) {
    *out = handle<VkSemaphore>(++sem_created + 10);
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy_sem(VkDevice, VkSemaphore, const VkAllocationCallbacks *) {
    ++sem_destroyed;
}
VKAPI_ATTR VkResult VKAPI_CALL create_fence(VkDevice, const VkFenceCreateInfo *,
    const VkAllocationCallbacks *, VkFence *out) {
    *out = handle<VkFence>(1);
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy_fence(VkDevice, VkFence, const VkAllocationCallbacks *) {
    ++fence_destroyed;
}
VKAPI_ATTR VkResult VKAPI_CALL import_fd(VkDevice, const VkImportSemaphoreFdInfoKHR *info) {
    if (info->fd >= 0) close(info->fd);
    return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL export_fd(VkDevice, const VkSemaphoreGetFdInfoKHR *, int *fd) {
    *fd = -1;
    return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL queue_submit(VkQueue, uint32_t count, const VkSubmitInfo *info, VkFence) {
    assert(count == 1 && info->waitSemaphoreCount == 1 && info->signalSemaphoreCount == 1);
    return fail_submit ? VK_ERROR_DEVICE_LOST : VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL fence_status(VkDevice, VkFence) {
    return completed ? VK_SUCCESS : VK_NOT_READY;
}
VKAPI_ATTR VkResult VKAPI_CALL reset_fences(VkDevice, uint32_t count, const VkFence *) {
    assert(count == 1); ++fence_resets; completed = false; return VK_SUCCESS;
}
void release(void *, int fd) {
    last_released_fd = fd;
    if (fd >= 0) close(fd);
    ++released;
}
const QueueDispatch dispatch{create_sem, destroy_sem, create_fence, destroy_fence,
                             import_fd, export_fd, queue_submit, fence_status, reset_fences};
}
int main() {
    int picture = 1;
    const auto device = handle<VkDevice>(1);
    const auto queue = handle<VkQueue>(1);
    const auto cmd = handle<VkCommandBuffer>(1);
    {
        FrameLease lease(&picture, -1, release, false);
        Submission submit(device, dispatch);
        assert(submit.submit(queue, cmd, lease) == VK_SUCCESS);
        assert(submit.handoff_ready_semaphore());
        assert(!submit.handoff_ready_semaphore());
        assert(submit.return_decoder_image() == VK_ERROR_INITIALIZATION_FAILED);
        assert(submit.poll() == VK_NOT_READY);
        assert(released == 0 && sem_destroyed == 0);
        completed = true;
        assert(submit.poll() == VK_SUCCESS);
        assert(released == 1 && sem_destroyed == 0 && fence_destroyed == 0);
        assert(!submit.can_retire() && !submit.retire_after_consumer(false));
        assert(submit.retire_after_consumer(true));
        assert(submit.can_retire() && sem_destroyed == 2 && fence_destroyed == 1);
    }
    {
        FrameLease lease(&picture, -1, release, false);
        Submission submit(device, dispatch);
        fail_submit = true;
        assert(submit.submit(queue, cmd, lease) == VK_ERROR_DEVICE_LOST);
        assert(submit.uncertain() && !submit.handoff_ready_semaphore());
        assert(!submit.drain_after_idle(false) && released == 1);
        // Model the distinct terminal device-lost teardown, not success.
        assert(submit.drain_after_device_lost());
        assert(submit.can_retire() && released == 2 && sem_destroyed == 4 && fence_destroyed == 2);
    }
    {
        FrameLease lease(&picture, -1, release, false);
        Submission submit(device, dispatch);
        fail_submit = false;
        assert(submit.submit(queue, cmd, lease) == VK_SUCCESS);
        assert(submit.handoff_ready_semaphore());
        assert(submit.drain_after_device_lost());
        assert(submit.can_retire() && released == 3 && sem_destroyed == 6 && fence_destroyed == 3);
    }
    {
        fail_submit = false;
        completed = false;
        const int before = sem_created, destroyed = sem_destroyed, fences = fence_destroyed;
        Submission submit(device, dispatch, true);
        VkSemaphore previous = VK_NULL_HANDLE;
        for (int i = 0; i < 10; ++i) {
            FrameLease lease(&picture, -1, release, false);
            assert(submit.submit(queue, cmd, lease) == VK_SUCCESS);
            auto ready = submit.handoff_ready_semaphore();
            assert(ready && (!previous || previous == ready));
            previous = ready;
            assert(submit.return_decoder_image() == VK_ERROR_INITIALIZATION_FAILED);
            assert(submit.poll() == VK_NOT_READY);
            completed = true;
            assert(submit.poll() == VK_SUCCESS);
            assert(!submit.retire_after_consumer(false));
            assert(submit.retire_after_consumer(true));
            assert(sem_created == before + 2 && sem_destroyed == destroyed);
            assert(fence_destroyed == fences);
        }
        assert(fence_resets == 9);
        assert(submit.drain_after_idle(true));
        assert(sem_destroyed == destroyed + 2 && fence_destroyed == fences + 1);
    }
    {
        int fds[2];
        assert(pipe(fds) == 0);
        const int before = released;
        FrameLease lease(&picture, fds[0], release, false);
        Submission submit(device, dispatch);
        fail_submit = true;
        assert(submit.submit(queue, cmd, lease) == VK_ERROR_DEVICE_LOST);
        assert(submit.uncertain());
        assert(!submit.drain_after_idle(false));
        assert(released == before);
        assert(submit.drain_after_idle(true));
        assert(submit.can_retire() && released == before + 1);
        // No producer wait was proven by a failed submit. The original
        // unsignaled fence must be returned, never replaced by -1.
        assert(last_released_fd == fds[0]);
        close(fds[1]);
    }
}
