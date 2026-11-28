#include "present_retirement.h"
#include <cassert>
#include <condition_variable>
#include <mutex>
using namespace ambient::vlc_ahb;
using namespace std::chrono_literals;
namespace {
std::mutex queue_mutex;
std::mutex test_mutex;
std::condition_variable test_cv;
bool unblock, finished;
VkResult response = VK_SUCCESS;
void lock(void *) { queue_mutex.lock(); }
void unlock(void *) { queue_mutex.unlock(); }
VKAPI_ATTR VkResult VKAPI_CALL idle(VkQueue) {
    std::unique_lock<std::mutex> l(test_mutex);
    test_cv.wait(l, [] { return unblock; });
    finished = true;
    test_cv.notify_all();
    return response;
}
}
int main() {
    auto queue = reinterpret_cast<VkQueue>(1);
    unblock = true;
    assert(PresentRetirement::wait(queue, idle, nullptr, lock, unlock, 500ms) == VK_SUCCESS);
    response = VK_ERROR_DEVICE_LOST;
    assert(PresentRetirement::wait(queue, idle, nullptr, lock, unlock, 500ms) == VK_ERROR_DEVICE_LOST);
    {
        std::lock_guard<std::mutex> l(test_mutex);
        unblock = false; finished = false; response = VK_SUCCESS;
    }
    const auto start = std::chrono::steady_clock::now();
    assert(PresentRetirement::wait(queue, idle, nullptr, lock, unlock, 25ms) == VK_TIMEOUT);
    assert(std::chrono::steady_clock::now() - start < 500ms);
    // The test retains the queue and lock even after the caller times out.
    {
        std::unique_lock<std::mutex> l(test_mutex);
        unblock = true; test_cv.notify_all();
        assert(test_cv.wait_for(l, 500ms, [] { return finished; }));
    }
    { std::lock_guard<std::mutex> l(queue_mutex); }
}
