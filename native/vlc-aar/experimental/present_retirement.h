#pragma once
#include <vulkan/vulkan.h>
#include <pthread.h>
#include <atomic>
#include <chrono>
#include <new>
#include <thread>

namespace ambient::vlc_ahb {

// vkQueueWaitIdle has no timeout. The worker must own a copy of every
// parameter until the call returns. On timeout the caller MUST retain the
// complete Vulkan context, including its queue lock and surface, until exit.
struct PresentRetirement {
    using Lock = void (*)(void *);
    struct Work {
        std::atomic<unsigned> refs{2};
        std::atomic<bool> done{false};
        VkResult result = VK_ERROR_INITIALIZATION_FAILED;
        VkQueue queue;
        PFN_vkQueueWaitIdle wait_idle;
        void *opaque;
        Lock lock, unlock;
        Work(VkQueue q, PFN_vkQueueWaitIdle idle, void *ctx, Lock l, Lock u)
            : queue(q), wait_idle(idle), opaque(ctx), lock(l), unlock(u) {}
    };
    static void release(Work *w) {
        if (w->refs.fetch_sub(1, std::memory_order_acq_rel) == 1) delete w;
    }
    static void *run(void *arg) {
        auto *w = static_cast<Work *>(arg);
        w->lock(w->opaque);
        w->result = w->wait_idle(w->queue);
        w->unlock(w->opaque);
        w->done.store(true, std::memory_order_release);
        release(w);
        return nullptr;
    }
    static VkResult wait(VkQueue queue, PFN_vkQueueWaitIdle idle, void *opaque,
                         Lock lock, Lock unlock, std::chrono::milliseconds timeout) {
        auto *w = new (std::nothrow) Work(queue, idle, opaque, lock, unlock);
        if (!w) return VK_ERROR_OUT_OF_HOST_MEMORY;
        pthread_attr_t attr;
        if (pthread_attr_init(&attr)) { delete w; return VK_ERROR_INITIALIZATION_FAILED; }
        if (pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED)) {
            pthread_attr_destroy(&attr);
            delete w;
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        pthread_t thread;
        const int error = pthread_create(&thread, &attr, run, w);
        pthread_attr_destroy(&attr);
        if (error) { delete w; return VK_ERROR_INITIALIZATION_FAILED; }
        const auto deadline = std::chrono::steady_clock::now() + timeout;
        while (!w->done.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() < deadline)
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        const auto result = w->done.load(std::memory_order_acquire) ? w->result : VK_TIMEOUT;
        release(w);
        return result;
    }
};

} // namespace ambient::vlc_ahb
