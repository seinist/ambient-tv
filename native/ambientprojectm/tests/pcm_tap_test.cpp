#include "../native_audio_tap.h"
#include <cassert>
#include <cmath>
#include <thread>

int main() {
    const int16_t stereo[] = {32767, -32768, 16384, 16384};
    ambient_projectm_pcm_v1(1, stereo, 2, 2, 2);
    assert(nativeAudio[1].count == 0); // Hidden visualizer does no work.
    nativeAudio[1].consumers = 1;
    ambient_projectm_pcm_v1(1, stereo, 2, 2, 2);
    assert(nativeAudio[1].count == 2);
    assert(std::abs(nativeAudio[1].samples[0]) < .0001f);
    assert(nativeAudio[1].samples[1] == .5f);
    assert(nativeAudio[2].count == 0); // Backend isolation.
    float ramp[600];
    for (int i = 0; i < 600; ++i) ramp[i] = i / 600.f;
    ambient_projectm_pcm_v1(1, ramp, 600, 1, 4);
    assert(nativeAudio[1].count == 512);
    assert(nativeAudio[1].samples[0] == ramp[88]);
    assert(nativeAudio[1].samples[511] == ramp[599]);
    const unsigned char pcm8[] = {0, 128, 255};
    ambient_projectm_pcm_v1(1, pcm8, 3, 1, 1);
    assert(nativeAudio[1].samples[0] == -1.f);
    assert(nativeAudio[1].samples[1] == 0.f);
    nativeAudio[1].mutex.lock();
    std::thread producer([&] { ambient_projectm_pcm_v1(1, stereo, 2, 2, 2); });
    producer.join(); // Must not wait for the renderer's lock.
    nativeAudio[1].mutex.unlock();
    assert(nativeAudio[1].count == 3);
    ambient_projectm_pcm_v1(1, stereo, 2, 0, 2);
    ambient_projectm_pcm_v1(1, stereo, 2, 2, 3);
    ambient_projectm_pcm_v1(5, stereo, 2, 2, 2);
    assert(nativeAudio[1].count == 3);
}
