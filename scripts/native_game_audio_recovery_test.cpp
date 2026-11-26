// SPDX-License-Identifier: GPL-3.0-or-later
#include "audiostreamrecovery.h"
#include <atomic>
#include <cassert>
#include <iostream>
#include <memory>
#include <thread>

using libretrodroid::AudioCallbackGeneration;
using libretrodroid::AudioRouteRecovery;
using libretrodroid::AudioProcessingGate;
using namespace std::chrono_literals;

struct Owner { int blocks = 0; };

static void waitFor(const std::atomic<bool>& flag) {
    while (!flag.load(std::memory_order_acquire)) std::this_thread::yield();
}

static void testGeneration() {
    auto oldOwner = std::make_unique<Owner>();
    auto old = std::make_shared<AudioCallbackGeneration<Owner>>(*oldOwner);
    std::atomic<bool> active{false}, finish{false}, retiring{false}, retired{false};
    std::thread callback([&] {
        assert(old->dispatch([&](Owner& owner) {
            active.store(true, std::memory_order_release);
            waitFor(finish);
            ++owner.blocks;
        }));
    });
    waitFor(active);
    std::thread control([&] {
        retiring.store(true, std::memory_order_release);
        old->retire();
        assert(oldOwner->blocks == 1);
        oldOwner.reset();
        retired.store(true, std::memory_order_release);
    });
    waitFor(retiring);
    assert(!retired.load(std::memory_order_acquire));
    assert(!old->dispatch([](Owner&) { assert(false); }));
    finish.store(true, std::memory_order_release);
    callback.join();
    control.join();
    assert(retired.load(std::memory_order_acquire));
    Owner replacement;
    AudioCallbackGeneration<Owner> current(replacement);
    for (int i = 0; i < 100000; ++i) {
        assert(!old->dispatch([](Owner&) { assert(false); }));
        assert(current.dispatch([](Owner& owner) { ++owner.blocks; }));
    }
    assert(replacement.blocks == 100000);
    {
        AudioProcessingGate::ControlScope controlScope(current.processingGate());
        assert(!current.dispatch([](Owner&) { assert(false); }));
        replacement.blocks = 0;
    }
    assert(current.dispatch([](Owner& owner) { assert(owner.blocks == 0); }));
    old->retire(); // idempotent after owner destruction
    current.retire();
    assert(!current.dispatch([](Owner&) { assert(false); }));
}

static void testRecoveryCadence() {
    AudioRouteRecovery recovery;
    using TimePoint = AudioRouteRecovery::TimePoint;
    const auto start = TimePoint{};
    assert(!recovery.shouldProbe(false, start));
    assert(recovery.shouldProbe(true, start));
    for (int i = 1; i < 250; ++i) assert(!recovery.shouldProbe(true, start + i * 1ms));
    assert(recovery.shouldProbe(true, start + 250ms));
    recovery.retryLater(start + 250ms);
    for (int i = 251; i < 1250; ++i) assert(!recovery.shouldProbe(true, start + i * 1ms));
    assert(!recovery.shouldProbe(false, start + 1250ms));
    assert(recovery.shouldProbe(true, start + 1250ms));
    recovery.reset();
    assert(recovery.shouldProbe(true, start + 1251ms));
    assert(!recovery.shouldProbe(true, start + 1251ms));
    recovery.retryLater(start + 1251ms);
    recovery.reset(); // explicit resume/latency change can try immediately
    assert(recovery.shouldProbe(true, start + 1252ms));
}

int main() {
    testGeneration();
    testRecoveryCadence();
    std::cout << "PASS: generation retirement, 100000 stale callback rejections, replacement isolation, control exclusion, route probe cadence and retry backoff\n";
}
