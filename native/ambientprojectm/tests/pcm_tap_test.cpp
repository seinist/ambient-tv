#include "../native_audio_tap.h"
#include <cassert>
#include <cmath>
#include <thread>

int main() {
    const int16_t stereo[] = {32767, -32768, 16384, 16384};
    std::array<float, PcmRing::capacity> output{};
    auto& pcm = nativeAudio[1].pcm;
    ambient_projectm_pcm_v1(1, stereo, 2, 2, 2);
    assert(pcm.count == 0);
    nativeAudio[1].consumers = 1;
    ambient_projectm_pcm_v1(1, stereo, 2, 2, 2);
    assert(pcm.drainLatest(output.data(), output.size()) == 2);
    assert(std::abs(output[0]) < .0001f);
    assert(output[1] == .5f);
    assert(nativeAudio[2].pcm.count == 0);
    float ramp[3000];
    for (int i = 0; i < 3000; ++i) ramp[i] = i / 3000.f;
    ambient_projectm_pcm_v1(1, ramp, 3000, 1, 4);
    assert(pcm.count == PcmRing::capacity);
    assert(pcm.drainLatest(output.data(), 576) == 576);
    assert(output[0] == ramp[3000 - 576]);
    assert(output[575] == ramp[2999]);
    assert(pcm.count == 0);
    const unsigned char pcm8[] = {0, 128, 255};
    ambient_projectm_pcm_v1(1, pcm8, 3, 1, 1);
    ambient_projectm_pcm_v1(1, stereo, 2, 2, 2);
    assert(pcm.count == 5); // Accumulate across callbacks.
    nativeAudio[1].mutex.lock();
    std::thread producer([&] { ambient_projectm_pcm_v1(1, stereo, 2, 2, 2); });
    producer.join();
    nativeAudio[1].mutex.unlock();
    assert(pcm.count == 5);
    ambient_projectm_pcm_v1(1, stereo, 2, 0, 2);
    ambient_projectm_pcm_v1(1, stereo, 2, 2, 3);
    ambient_projectm_pcm_v1(5, stereo, 2, 2, 2);
    assert(pcm.drainLatest(output.data(), output.size()) == 5);
    assert(output[0] == -1.f && output[1] == 0.f && output[2] == 127.f / 128.f);
    assert(output[4] == .5f);
    PcmRing ring;
    ring.append(ramp, 2000);
    ring.append(ramp + 2000, 1000);
    assert(ring.drainLatest(output.data(), output.size()) == PcmRing::capacity);
    for (size_t i = 0; i < output.size(); ++i)
        assert(output[i] == ramp[3000 - output.size() + i]);
    assert(ring.drainLatest(output.data(), output.size()) == 0);

    // Independent renderers can read the same latest samples without consuming each other's history.
    ring.clear();
    uint64_t firstCursor = 0, secondCursor = 0;
    ring.append(ramp, 1000);
    assert(ring.readLatestSince(output.data(), 576, firstCursor) == 576);
    assert(output[0] == ramp[424]);
    assert(ring.readLatestSince(output.data(), 576, firstCursor) == 0);
    assert(ring.readLatestSince(output.data(), 576, secondCursor) == 576);
    ring.append(ramp + 1000, 100);
    assert(ring.readLatestSince(output.data(), 576, firstCursor) == 100);
    assert(output[0] == ramp[1000] && output[99] == ramp[1099]);
    assert(ring.readLatestSince(output.data(), 576, secondCursor) == 100);
    pcm.clear();
    nativeAudio[1].maxFrames = 576;
    ambient_projectm_pcm_v1(1, ramp, 3000, 1, 4);
    assert(pcm.count == 576);
    assert(pcm.drainLatest(output.data(), 576) == 576);
    assert(output[0] == ramp[2424] && output[575] == ramp[2999]);
}
