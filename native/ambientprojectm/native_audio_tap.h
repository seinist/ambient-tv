#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <mutex>
#include <cstring>
#include <cstdint>

// Optional native-player tap. Never wait on rendering from an audio-output thread.
struct NativeAudioTap {
    std::atomic<int> consumers{0};
    std::mutex mutex;
    std::array<float, 512> samples{};
    size_t count = 0;
};
static NativeAudioTap nativeAudio[3]; // 1 = VLC, 2 = MPV

extern "C" __attribute__((visibility("default"))) void
ambient_projectm_pcm_v1(int source, const void* data, size_t frames, int channels, int encoding) {
    if (source < 1 || source > 2 || !data || channels < 1 || channels > 32 ||
        (encoding != 1 && encoding != 2 && encoding != 4)) return;
    auto& tap = nativeAudio[source];
    if (tap.consumers.load(std::memory_order_relaxed) == 0) return;
    std::unique_lock<std::mutex> lock(tap.mutex, std::try_to_lock);
    if (!lock.owns_lock()) return;
    const size_t count = std::min(frames, tap.samples.size());
    const auto* bytes = static_cast<const unsigned char*>(data);
    const size_t stride = channels * encoding;
    bytes += (frames - count) * stride;
    for (size_t frame = 0; frame < count; ++frame) {
        float mono = 0;
        for (int ch = 0; ch < channels; ++ch) {
            const auto* sample = bytes + frame * stride + ch * encoding;
            if (encoding == 4) { float value; std::memcpy(&value, sample, 4); mono += value; }
            else if (encoding == 2) { int16_t value; std::memcpy(&value, sample, 2); mono += value / 32768.f; }
            else mono += (int(*sample) - 128) / 128.f;
        }
        tap.samples[frame] = mono / channels;
    }
    tap.count = count;
}

