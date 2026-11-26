// SPDX-License-Identifier: GPL-3.0-or-later
#include "audioprocessinggate.h"
#include <atomic>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <thread>

using libretrodroid::AudioProcessingGate;

static void waitFor(const std::atomic<bool>& flag) {
    while (!flag.load(std::memory_order_acquire)) std::this_thread::yield();
}

static void testControlAndCallback() {
    AudioProcessingGate gate;
    // Model the consumer-owned read cursor/processing state. These are plain
    // fields on purpose: all cross-thread visibility must come from the gate.
    uint64_t read = 2, write = 12;
    int rate = 48000;
    bool cleared = false;
    std::atomic<bool> active{false}, finishBlock{false}, requested{false},
        controlEntered{false}, finishControl{false};
    std::thread callback([&] {
        AudioProcessingGate::CallbackScope scope(gate);
        assert(scope);
        active.store(true, std::memory_order_release);
        waitFor(finishBlock);
        assert(!cleared && rate == 48000);
        read += 4;
    });
    waitFor(active);
    std::thread control([&] {
        requested.store(true, std::memory_order_release);
        AudioProcessingGate::ControlScope scope(gate);
        assert(read == 6);
        read = write; // exact producer boundary, only after the read finished
        rate = 44100;
        cleared = true;
        controlEntered.store(true, std::memory_order_release);
        waitFor(finishControl);
    });
    waitFor(requested);
    {
        AudioProcessingGate::CallbackScope rejected(gate);
        assert(!rejected);
    }
    assert(!controlEntered.load(std::memory_order_acquire));
    finishBlock.store(true, std::memory_order_release);
    callback.join();
    waitFor(controlEntered);
    for (unsigned i = 0; i < 1000; ++i) {
        AudioProcessingGate::CallbackScope rejected(gate);
        assert(!rejected); // must return immediately, not wait on control
    }
    finishControl.store(true, std::memory_order_release);
    control.join();
    {
        AudioProcessingGate::CallbackScope scope(gate);
        assert(scope && cleared && read == write && rate == 44100);
    }
    try {
        AudioProcessingGate::ControlScope scope(gate);
        throw std::runtime_error("unwind");
    } catch (const std::runtime_error&) {}
    {
        AudioProcessingGate::CallbackScope scope(gate);
        assert(scope);
    }
}

static void testConcurrentOwnership() {
    AudioProcessingGate gate;
    std::atomic<bool> done{false};
    std::atomic<unsigned> inside{0}, entered{0};
    uint64_t state = 0;
    std::thread consumer([&] {
        while (!done.load(std::memory_order_acquire)) {
            AudioProcessingGate::CallbackScope scope(gate);
            if (!scope) continue;
            assert(inside.fetch_add(1, std::memory_order_relaxed) == 0);
            ++state;
            entered.fetch_add(1, std::memory_order_relaxed);
            assert(inside.fetch_sub(1, std::memory_order_relaxed) == 1);
        }
    });
    while (entered.load(std::memory_order_relaxed) == 0) std::this_thread::yield();
    for (unsigned i = 0; i < 20000; ++i) {
        AudioProcessingGate::ControlScope scope(gate);
        assert(inside.fetch_add(1, std::memory_order_relaxed) == 0);
        ++state;
        assert(inside.fetch_sub(1, std::memory_order_relaxed) == 1);
    }
    done.store(true, std::memory_order_release);
    consumer.join();
    assert(state == 20000ull + entered.load(std::memory_order_relaxed));
}

int main() {
    testControlAndCallback();
    testConcurrentOwnership();
    std::cout << "PASS: in-flight block retirement, exact FIFO boundary, nonblocking denial, publication, exception release and 20000 concurrent control transitions\n";
}
