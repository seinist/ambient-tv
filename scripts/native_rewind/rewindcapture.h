// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "rewindbuffer.h"

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>

namespace libretrodroid {

// Reserve the capture scratch AND the maximum paused-preview live state.
// The ring constructor independently budgets its anchor, metadata and byte ring.
inline size_t rewindStorageBudget(size_t total, size_t stateSize, size_t overhead) noexcept {
    if (total <= overhead || !stateSize || stateSize > (total - overhead) / 2) return 0;
    return total - overhead - stateSize - stateSize;
}

enum class RewindCaptureResult { Idle, Busy, Saved, Failed };
struct RewindCaptureCompletion {
    RewindCaptureResult result;
    uint64_t frameSpan = 0;
};

/**
 * Single-slot capture worker. Only the emulation owner may submit, collect or
 * destroy. From successful submission until collection the worker exclusively
 * owns the borrowed ring and scratch bytes; no other ring operation is allowed.
 * The worker never calls the core, renderer or JNI and never holds the mutex
 * while compressing. Forward frames use only try-lock operations and skip
 * captures under pressure. Explicit seek/clear/teardown may drain the slot.
 */
template<class Buffer>
class BasicRewindCapture {
public:
    BasicRewindCapture() : worker([this] { run(); }) {}
    ~BasicRewindCapture() {
        if (outstanding) collect(true);
        {
            std::lock_guard<std::mutex> lock(mutex);
            stopping = true;
        }
        changed.notify_all();
        worker.join();
    }
    BasicRewindCapture(const BasicRewindCapture&) = delete;
    BasicRewindCapture& operator=(const BasicRewindCapture&) = delete;

    bool pending() const noexcept { return outstanding; } // owner thread only

    bool trySubmit(Buffer& buffer, const uint8_t* bytes, size_t size, uint64_t frameSpan) {
        if (outstanding || !bytes || !size) return false;
        std::unique_lock<std::mutex> lock(mutex, std::try_to_lock);
        if (!lock.owns_lock() || state != State::Idle) return false;
        target = &buffer;
        data = bytes;
        length = size;
        span = frameSpan;
        state = State::Queued;
        outstanding = true;
        lock.unlock();
        changed.notify_all();
        return true;
    }

    RewindCaptureCompletion collect(bool wait) {
        if (!outstanding) return {RewindCaptureResult::Idle};
        std::unique_lock<std::mutex> lock(mutex, std::defer_lock);
        if (wait) {
            lock.lock();
            changed.wait(lock, [this] { return state == State::Done; });
        } else if (!lock.try_lock() || state != State::Done) {
            return {RewindCaptureResult::Busy};
        }
        const RewindCaptureCompletion completed{
            saved ? RewindCaptureResult::Saved : RewindCaptureResult::Failed, span};
        target = nullptr;
        data = nullptr;
        state = State::Idle;
        outstanding = false;
        return completed;
    }

private:
    enum class State { Idle, Queued, Working, Done };

    void run() {
        std::unique_lock<std::mutex> lock(mutex);
        for (;;) {
            changed.wait(lock, [this] { return stopping || state == State::Queued; });
            if (stopping) return;
            state = State::Working;
            Buffer* buffer = target;
            const uint8_t* bytes = data;
            const size_t size = length;
            const uint64_t frames = span;
            lock.unlock();
            bool ok = false;
            try { ok = buffer->push(bytes, size, frames); }
            catch (...) { ok = false; }
            lock.lock();
            saved = ok;
            state = State::Done;
            changed.notify_all();
        }
    }

    std::mutex mutex;
    std::condition_variable changed;
    State state = State::Idle;
    bool stopping = false, saved = false;
    bool outstanding = false; // accessed exclusively by emulation owner
    Buffer* target = nullptr;
    const uint8_t* data = nullptr;
    size_t length = 0;
    uint64_t span = 0;
    std::thread worker; // must be last: starts only after all shared fields exist
};

using AsyncRewindCapture = BasicRewindCapture<RewindBuffer>;

} // namespace libretrodroid
