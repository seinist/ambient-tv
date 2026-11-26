// SPDX-License-Identifier: GPL-3.0-or-later
#include "audiocallbackcontrols.h"
#include <array>
#include <cassert>
#include <cstdio>
#include <limits>
#include <thread>
#include <vector>

using libretrodroid::AudioCallbackControls;
using libretrodroid::applyAudioCallbackVolume;

static void testSnapshots() {
    AudioCallbackControls controls;
    auto initial = controls.snapshot();
    assert(initial.speed == 1.0 && initial.volume == 1.0f && !initial.preservePitch);
    controls.setSpeed(3.0);
    controls.setVolume(0.25f);
    controls.setPreservePitch(true);
    auto captured = controls.snapshot();
    controls.setSpeed(1.0);
    controls.setVolume(0.0f);
    controls.setPreservePitch(false);
    assert(captured.speed == 3.0 && captured.volume == 0.25f && captured.preservePitch);
    auto next = controls.snapshot();
    assert(next.speed == 1.0 && next.volume == 0.0f && !next.preservePitch);

    // Fields are independent controls, not an atomic multi-setting transaction.
    // Each snapshot must contain complete scalar values even while being updated.
    std::atomic<bool> start{false};
    std::thread producer([&] {
        while (!start.load(std::memory_order_acquire)) std::this_thread::yield();
        for (int i = 0; i < 200000; ++i) {
            controls.setSpeed(i % 2 ? 2.0 : 1.0);
            controls.setVolume(i % 2 ? 0.25f : 0.0f);
            controls.setPreservePitch(i % 2 != 0);
        }
    });
    start.store(true, std::memory_order_release);
    for (int i = 0; i < 200000; ++i) {
        auto value = controls.snapshot();
        assert(value.speed == 1.0 || value.speed == 2.0);
        assert(value.volume == 0.0f || value.volume == 0.25f);
    }
    producer.join();
    next = controls.snapshot();
    assert(next.speed == 2.0 && next.volume == 0.25f && next.preservePitch);
}

static void testVolume() {
    // All signed 16-bit sample codes, distinct channels and sentinel bounds.
    std::vector<int16_t> source(65536);
    for (int i = 0; i < 65536; ++i) source[i] = static_cast<int16_t>(i - 32768);
    constexpr int16_t sentinel = 12345;
    unsigned cases = 0;
    for (float volume : {0.0f, -0.0f, 0.001f, 0.1f, 0.25f, 0.5f, 0.75f, 0.99f,
                         1.0f, 1.01f, 1.5f, 2.0f, -1.0f}) {
        std::vector<int16_t> samples(source.size() + 2, sentinel);
        std::copy(source.begin(), source.end(), samples.begin() + 1);
        applyAudioCallbackVolume(samples.data() + 1, static_cast<int32_t>(source.size() / 2), volume);
        assert(samples.front() == sentinel && samples.back() == sentinel);
        for (std::size_t i = 0; i < source.size(); ++i) {
            float value = source[i] * volume;
            value = std::clamp(value, -32768.0f, 32767.0f);
            assert(samples[i + 1] == static_cast<int16_t>(std::lrintf(value)));
            ++cases;
        }
    }
    applyAudioCallbackVolume(nullptr, 0, 0.0f);
    applyAudioCallbackVolume(nullptr, -1, 0.5f);
    std::printf("PASS: %u PCM gain samples, 200000 concurrent control snapshots\n", cases);
}

int main() {
    testSnapshots();
    testVolume();
}
