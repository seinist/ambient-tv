// SPDX-License-Identifier: GPL-3.0-or-later
#include "rewindcapture.h"
#include "rewindtimeline.h"
#include <array>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <thread>

using namespace libretrodroid;

struct BlockingBuffer {
    std::mutex mutex;
    std::condition_variable cv;
    bool started = false, release = false, fail = false;
    unsigned calls = 0;
    uint8_t observed = 0;
    bool push(const uint8_t* data, size_t, uint64_t) {
        std::unique_lock<std::mutex> lock(mutex);
        ++calls;
        started = true;
        cv.notify_all();
        cv.wait(lock, [&] { return release; });
        observed = data[0];
        if (fail) throw std::runtime_error("codec failure");
        return true;
    }
    void waitStarted() {
        std::unique_lock<std::mutex> lock(mutex);
        assert(cv.wait_for(lock, std::chrono::seconds(5), [&] { return started; }));
    }
    void unblock() {
        std::lock_guard<std::mutex> lock(mutex);
        release = true;
        cv.notify_all();
    }
};

template<class Worker, class Buffer>
static void submit(Worker& worker, Buffer& buffer, const uint8_t* data, size_t size, uint64_t span) {
    // Startup can transiently hold the worker mutex; production simply skips that capture.
    const auto limit = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!worker.trySubmit(buffer, data, size, span)) {
        assert(std::chrono::steady_clock::now() < limit);
        std::this_thread::yield();
    }
}

int main() {
    constexpr size_t cap = 64u * 1024u * 1024u;
    assert(rewindStorageBudget(cap, 1024, 256) == cap - 2048 - 256);
    assert(rewindStorageBudget(cap, cap, 256) == 0);
    assert(rewindStorageBudget(cap, std::numeric_limits<size_t>::max(), 1) == 0);
    assert(rewindStorageBudget(1, 1, 1) == 0);
    {
        BlockingBuffer buffer;
        std::array<uint8_t, 8> scratch{42};
        BasicRewindCapture<BlockingBuffer> worker;
        submit(worker, buffer, scratch.data(), scratch.size(), 7);
        buffer.waitStarted();
        assert(worker.pending());
        // No second slot, no wait for a deliberately stalled codec.
        assert(!worker.trySubmit(buffer, scratch.data(), scratch.size(), 9));
        assert(worker.collect(false).result == RewindCaptureResult::Busy);
        buffer.unblock();
        const auto completed = worker.collect(true);
        assert(completed.result == RewindCaptureResult::Saved && completed.frameSpan == 7);
        assert(!worker.pending() && buffer.calls == 1 && buffer.observed == 42);
        assert(worker.collect(false).result == RewindCaptureResult::Idle);
    }
    {
        BlockingBuffer buffer;
        buffer.fail = true;
        buffer.release = true;
        const uint8_t data = 1;
        BasicRewindCapture<BlockingBuffer> worker;
        submit(worker, buffer, &data, 1, 1);
        assert(worker.collect(true).result == RewindCaptureResult::Failed);
        buffer.fail = false;
        submit(worker, buffer, &data, 1, 3);
        assert(worker.collect(true).result == RewindCaptureResult::Saved);
    }
    {
        RewindBuffer ring(32, 256, 16384, 120);
        RewindTimeline timeline;
        timeline.configure(120, 15);
        std::array<uint8_t, 256> capture{};
        AsyncRewindCapture worker;
        // First anchor is captured before running frames; completion is delayed.
        submit(worker, ring, capture.data(), capture.size(), 0);
        timeline.advanced(4);
        auto completed = worker.collect(true);
        assert(completed.result == RewindCaptureResult::Saved);
        timeline.captured(ring, timeline.pendingFrames() - completed.frameSpan);
        assert(timeline.pendingFrames() == 4 && timeline.historyFrames(ring) == 3);
        capture[0] = 17;
        submit(worker, ring, capture.data(), capture.size(), timeline.pendingFrames());
        timeline.advanced(3);
        completed = worker.collect(true);
        assert(completed.result == RewindCaptureResult::Saved);
        timeline.captured(ring, timeline.pendingFrames() - completed.frameSpan);
        assert(timeline.pendingFrames() == 3 && ring.recordFrames(0) == 4);
        assert(timeline.historyFrames(ring) == 6);
        std::array<uint8_t, 256> restored{};
        size_t size = 0;
        assert(ring.pop(restored.data(), &size) && size == capture.size() && restored[0] == 17);
        assert(ring.peek(0, restored.data(), &size) && restored[0] == 0);
        // Destruction drains a final borrowed capture before the caller frees bytes/ring.
        submit(worker, ring, capture.data(), capture.size(), 2);
    }
    std::puts("PASS single-slot rewind worker, nonblocking pressure, failure, exact delayed spans and budget");
}
