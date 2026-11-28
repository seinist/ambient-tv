#include "queue_retirement.h"
#include <cassert>
#include <type_traits>

using namespace ambient::vlc_ahb;
namespace {
template<class T> T handle(uintptr_t v) {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(v);
    else return static_cast<T>(v);
}
int creates, submits, destroys, polls;
VkResult submit_result = VK_SUCCESS, fence_result = VK_NOT_READY;
VKAPI_ATTR VkResult VKAPI_CALL create(VkDevice, const VkFenceCreateInfo *,
    const VkAllocationCallbacks *, VkFence *out) {
    ++creates; *out = handle<VkFence>(1); return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy(VkDevice, VkFence fence, const VkAllocationCallbacks *) {
    assert(fence); ++destroys;
}
VKAPI_ATTR VkResult VKAPI_CALL submit(VkQueue, uint32_t count, const VkSubmitInfo *info, VkFence fence) {
    assert(count == 0 && !info && fence); ++submits; return submit_result;
}
VKAPI_ATTR VkResult VKAPI_CALL status(VkDevice, VkFence fence) {
    assert(fence); ++polls; return fence_result;
}
}
int main() {
    QueueDispatch d{};
    d.create_fence = create; d.destroy_fence = destroy; d.submit = submit; d.fence_status = status;
    const auto device = handle<VkDevice>(1);
    const auto queue = handle<VkQueue>(1);
    {
        QueueRetirement r;
        assert(r.begin(device, queue, d) == VK_NOT_READY);
        assert(r.begin(device, queue, d) == VK_NOT_READY);
        assert(creates == 1 && submits == 1);
        assert(r.wait(device, d, [] { return false; }, [] {}) == VK_TIMEOUT);
        assert(!r.release(device, d, false) && destroys == 0);
        fence_result = VK_SUCCESS;
        assert(r.wait(device, d, [] { return false; }, [] {}) == VK_SUCCESS);
        assert(r.release(device, d, false) && destroys == 1);
    }
    {
        QueueRetirement r;
        submit_result = VK_ERROR_OUT_OF_HOST_MEMORY;
        const int before = polls;
        assert(r.begin(device, queue, d) == VK_ERROR_OUT_OF_HOST_MEMORY);
        assert(r.wait(device, d, [] { return false; }, [] {}) == VK_ERROR_OUT_OF_HOST_MEMORY);
        assert(polls == before && !r.release(device, d, false));
        // A separate explicit device-loss observation permits terminal cleanup.
        assert(r.release(device, d, true));
    }
    {
        QueueRetirement r;
        submit_result = VK_SUCCESS;
        fence_result = VK_ERROR_DEVICE_LOST;
        assert(r.begin(device, queue, d) == VK_NOT_READY);
        assert(r.wait(device, d, [] { return false; }, [] {}) == VK_ERROR_DEVICE_LOST);
        assert(r.release(device, d, false));
    }
    assert(creates == 3 && submits == 3 && destroys == 3);
}
