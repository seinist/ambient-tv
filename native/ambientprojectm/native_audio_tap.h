#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <mutex>
#include <cstring>
#include <cstdint>

// Bounded newest-sample queue. External synchronization is required; no allocation.
struct PcmRing {
    static constexpr size_t capacity = 2048;
    std::array<float, capacity> samples{};
    size_t end = 0;
    size_t count = 0;

    void clear() { end = count = 0; }
    void push(float sample) {
        samples[end] = sample;
        end = (end + 1) % capacity;
        count = std::min(count + 1, capacity);
    }
    void append(const float* data, size_t size) {
        if (size > capacity) { data += size - capacity; size = capacity; }
        const size_t first = std::min(size, capacity - end);
        std::copy_n(data, first, samples.data() + end);
        std::copy_n(data + first, size - first, samples.data());
        end = (end + size) % capacity;
        count = std::min(count + size, capacity);
    }
    size_t drainLatest(float* output, size_t limit) {
        const size_t size = std::min(count, limit);
        const size_t start = (end + capacity - size) % capacity;
        const size_t first = std::min(size, capacity - start);
        std::copy_n(samples.data() + start, first, output);
        std::copy_n(samples.data(), size - first, output + first);
        count = 0;
        return size;
    }
};

// Optional native-player tap. Never wait on rendering from an audio-output thread.
struct NativeAudioTap {
    std::atomic<int> consumers{0};
    std::mutex mutex;
    PcmRing pcm;
};
static NativeAudioTap nativeAudio[3]; // 1 = VLC, 2 = MPV

extern "C" __attribute__((visibility("default"))) void
ambient_projectm_pcm_v1(int source, const void* data, size_t frames, int channels, int encoding) {
    if (source < 1 || source > 2 || !data || frames == 0 || channels < 1 || channels > 32 ||
        (encoding != 1 && encoding != 2 && encoding != 4)) return;
    auto& tap = nativeAudio[source];
    if (tap.consumers.load(std::memory_order_relaxed) == 0) return;
    std::unique_lock<std::mutex> lock(tap.mutex, std::try_to_lock);
    if (!lock.owns_lock()) return;
    if (tap.consumers.load(std::memory_order_relaxed) == 0) return;
    const size_t count = std::min(frames, PcmRing::capacity);
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
        tap.pcm.push(mono / channels);
    }
}

