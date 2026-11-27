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
    uint64_t sequence = 0;

    void clear() { end = count = 0; sequence = 0; }
    void push(float sample) {
        samples[end] = sample;
        end = (end + 1) % capacity;
        count = std::min(count + 1, capacity);
        ++sequence;
    }
    void append(const float* data, size_t size) {
        if (size > capacity) { data += size - capacity; size = capacity; }
        const size_t first = std::min(size, capacity - end);
        std::copy_n(data, first, samples.data() + end);
        std::copy_n(data + first, size - first, samples.data());
        end = (end + size) % capacity;
        count = std::min(count + size, capacity);
        sequence += size;
    }
    size_t copyLatest(float* output, size_t limit) const {
        const size_t size = std::min(count, limit);
        const size_t start = (end + capacity - size) % capacity;
        const size_t first = std::min(size, capacity - start);
        std::copy_n(samples.data() + start, first, output);
        std::copy_n(samples.data(), size - first, output + first);
        return size;
    }
    size_t drainLatest(float* output, size_t limit) {
        const size_t size = copyLatest(output, limit);
        count = 0;
        return size;
    }
    size_t readLatestSince(float* output, size_t limit, uint64_t& cursor) const {
        const size_t fresh = static_cast<size_t>(std::min<uint64_t>(sequence - cursor, capacity));
        cursor = sequence;
        return copyLatest(output, std::min(limit, fresh));
    }
};

// Optional native-player tap. Never wait on rendering from an audio-output thread.
struct NativeAudioTap {
    std::atomic<int> consumers{0};
    std::mutex mutex;
    PcmRing pcm;
    size_t maxFrames = PcmRing::capacity; // Configured from the pinned projectM API.
};
static NativeAudioTap nativeAudio[3]; // 1 = VLC, 2 = MPV

// Format and common channel-count dispatch occur once per callback. The compiler can
// unroll/vectorize the fixed mono/stereo loops without changing floating-point semantics.
template<int Encoding>
inline float readPcmSample(const unsigned char* sample) {
    if constexpr (Encoding == 4) {
        float value;
        std::memcpy(&value, sample, sizeof(value));
        return value;
    } else if constexpr (Encoding == 2) {
        int16_t value;
        std::memcpy(&value, sample, sizeof(value));
        return value / 32768.f;
    } else {
        return (int(*sample) - 128) / 128.f;
    }
}

template<int Encoding, int FixedChannels>
inline void downmix(const unsigned char* data, float* output, size_t count, int channelCount) {
    const int channels = FixedChannels ? FixedChannels : channelCount;
    const size_t stride = channels * Encoding;
    for (size_t frame = 0; frame < count; ++frame) {
        float mono = 0.f;
        for (int ch = 0; ch < channels; ++ch)
            mono += readPcmSample<Encoding>(data + frame * stride + ch * Encoding);
        output[frame] = mono / channels;
    }
}

template<int Encoding>
inline void downmixChannels(const unsigned char* data, float* output, size_t count, int channels) {
    if (channels == 1) downmix<Encoding, 1>(data, output, count, channels);
    else if (channels == 2) downmix<Encoding, 2>(data, output, count, channels);
    else downmix<Encoding, 0>(data, output, count, channels);
}

extern "C" __attribute__((visibility("default"))) void
ambient_projectm_pcm_v1(int source, const void* data, size_t frames, int channels, int encoding) {
    if (source < 1 || source > 2 || !data || frames == 0 || channels < 1 || channels > 32 ||
        (encoding != 1 && encoding != 2 && encoding != 4)) return;
    auto& tap = nativeAudio[source];
    if (tap.consumers.load(std::memory_order_relaxed) == 0) return;
    std::unique_lock<std::mutex> lock(tap.mutex, std::try_to_lock);
    if (!lock.owns_lock()) return;
    if (tap.consumers.load(std::memory_order_relaxed) == 0) return;
    const size_t count = std::min(frames, tap.maxFrames);
    const auto* bytes = static_cast<const unsigned char*>(data);
    const size_t stride = channels * encoding;
    if (frames > SIZE_MAX / stride || count == 0) return;
    bytes += (frames - count) * stride;
    std::array<float, PcmRing::capacity> converted;
    if (encoding == 4) downmixChannels<4>(bytes, converted.data(), count, channels);
    else if (encoding == 2) downmixChannels<2>(bytes, converted.data(), count, channels);
    else downmixChannels<1>(bytes, converted.data(), count, channels);
    tap.pcm.append(converted.data(), count);
}

