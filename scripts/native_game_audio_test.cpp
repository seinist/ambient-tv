// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the actual patched stereo PCM resampler, not a mock audio stream.
#include "resamplers/linearresampler.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <random>
#include <vector>

using libretrodroid::LinearResampler;

static void reference(const int16_t* source, int32_t inputFrames, int16_t* sink, int32_t sinkFrames) {
    double outputTime = 0;
    const double outputTimeStep = 1.0 / sinkFrames;
    while (sinkFrames-- > 0) {
        double integerPart;
        const double fraction = std::modf(outputTime * inputFrames, &integerPart);
        const int32_t lo = static_cast<int32_t>(integerPart);
        const int32_t hi = std::min(lo + 1, inputFrames - 1);
        *sink++ = source[hi * 2] * fraction + source[lo * 2] * (1.0 - fraction);
        *sink++ = source[hi * 2 + 1] * fraction + source[lo * 2 + 1] * (1.0 - fraction);
        outputTime += outputTimeStep;
    }
}

int main() {
    LinearResampler resampler;
    std::mt19937 rng(0x43a7);
    std::uniform_int_distribution<int> pcm(-32768, 32767);
    constexpr int16_t guard = 12345;
    unsigned cases = 0;
    auto check = [&](int inputFrames, int outputFrames) {
        std::vector<int16_t> source(inputFrames * 2);
        for (auto& sample : source) sample = static_cast<int16_t>(pcm(rng));
        source[0] = -32768; source[1] = 32767;
        const auto original = source;
        std::vector<int16_t> actual(outputFrames * 2 + 2, guard), expected(outputFrames * 2);
        if (inputFrames == outputFrames) expected = source;
        else if (inputFrames == 1) {
            for (int i = 0; i < outputFrames; ++i) {
                expected[i * 2] = source[0]; expected[i * 2 + 1] = source[1];
            }
        } else reference(source.data(), inputFrames, expected.data(), outputFrames);
        resampler.resample(source.data(), inputFrames, actual.data() + 1, outputFrames);
        assert(actual.front() == guard && actual.back() == guard);
        assert(std::equal(expected.begin(), expected.end(), actual.begin() + 1));
        assert(source == original);
        ++cases;
    };
    for (int in = 1; in <= 128; ++in)
        for (int out = 1; out <= 128; ++out) check(in, out);
    for (int n : {192, 240, 256, 480, 512, 960, 1024, 4096, 8192}) {
        check(n, n); check(n - 1, n); check(n + 1, n);
        check(n, n / 4); check(n / 4, n);
    }
    for (int in : {0, -1, 4}) {
        std::vector<int16_t> silence(66, guard);
        resampler.resample(nullptr, in, silence.data() + 1, 32);
        assert(silence.front() == guard && silence.back() == guard);
        assert(std::all_of(silence.begin() + 1, silence.end() - 1, [](int16_t v) { return v == 0; }));
        ++cases;
    }
    int16_t stale[] = {32767, -32768};
    int16_t emptyOutput[] = {guard, guard};
    resampler.resample(stale, 0, emptyOutput, 1);
    assert(emptyOutput[0] == 0 && emptyOutput[1] == 0);
    resampler.resample(stale, 1, nullptr, 0);
    resampler.resample(stale, 1, nullptr, -1);
    int16_t overlap[] = {10, 20, 30, 40, 50, 60};
    resampler.resample(overlap, 2, overlap + 2, 2);
    assert(overlap[2] == 10 && overlap[3] == 20 && overlap[4] == 30 && overlap[5] == 40);
    std::printf("PASS: %u stereo resampler cases plus empty/overlap guards\n", cases);
}
