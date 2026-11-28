// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkan/vulkanbridgeimage.h"
#include <cassert>
#include <cstdio>
#include <type_traits>
#include <vector>

using Bridge = libretrodroid::VulkanBridgeImage<unsigned, unsigned, unsigned>;
static_assert(!std::is_copy_constructible_v<Bridge> && !std::is_copy_assignable_v<Bridge>);

int main() {
    for (unsigned cycle = 0; cycle < 10000; ++cycle) {
        for (unsigned mask = 0; mask < 8; ++mask) {
            Bridge a, b;
            a.buffer = (mask & 1) ? 1 : 0;
            a.image = (mask & 2) ? 2 : 0;
            a.memory = (mask & 4) ? 3 : 0;
            a.width = 320; a.height = 240; a.fp16 = true; a.allocationBytes = 65536;
            b.buffer = 4; b.image = 5; b.memory = 6; b.allocationBytes = 131072;
            std::vector<unsigned> freed;
            auto release = [&](unsigned id) { freed.push_back(id); };
            assert(!a.retire(true, true, true, true, release, release, release));
            assert(!a.empty() && freed.empty() && a.allocationBytes == 65536);
            if (a.image || a.memory) {
                assert(!a.retire(false, false, true, true, release, release, release));
                assert(!a.retire(false, true, false, false, release, release, release));
                assert(freed.empty());
            }
            assert(a.retire(false, true, true, true, release, release, release));
            std::vector<unsigned> expected;
            if (mask & 2) expected.push_back(2);
            if (mask & 4) expected.push_back(3);
            if (mask & 1) expected.push_back(1);
            assert(freed == expected && a.empty());
            assert(b.buffer == 4 && b.image == 5 && b.memory == 6 && b.allocationBytes == 131072);
            assert(a.retire(false, false, false, false, release, release, release));
            assert(freed == expected);
            assert(b.retire(false, true, true, true, release, release, release));
            expected.insert(expected.end(), {5, 6, 4});
            assert(freed == expected && b.empty());
        }
    }
    std::puts("PASS bridge image retirement, partial ownership and slot isolation (80000 cases)");
}
