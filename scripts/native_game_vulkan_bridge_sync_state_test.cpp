// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkan/vulkanbridgesync.h"
#include <cassert>
#include <cstdio>
#include <type_traits>
#include <vector>

using Sync = libretrodroid::VulkanBridgeSync<unsigned>;
static_assert(!std::is_copy_constructible_v<Sync> && !std::is_copy_assignable_v<Sync>);

int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        Sync first, second;
        std::vector<unsigned> destroyed;
        auto destroy = [&](unsigned handle) { destroyed.push_back(handle); };
        assert(first.retire(false, false, false, destroy));
        first.readSemaphore = 1;
        first.writeSemaphore = 2;
        first.readPending = true;
        second.readSemaphore = 3;
        second.writeSemaphore = 4;
        for (bool active : {false, true}) {
            for (bool device : {false, true}) {
                for (bool dispatch : {false, true}) {
                    if (!active && device && dispatch) continue;
                    assert(!first.retire(active, device, dispatch, destroy));
                    assert(first.readPending && first.readSemaphore == 1 && first.writeSemaphore == 2);
                    assert(destroyed.empty());
                }
            }
        }
        assert(first.retire(false, true, true, destroy));
        assert(first.empty() && second.readSemaphore == 3 && second.writeSemaphore == 4);
        assert((destroyed == std::vector<unsigned>{1, 2}));
        assert(first.retire(false, true, true, destroy));
        assert(second.retire(false, true, true, destroy));
        assert((destroyed == std::vector<unsigned>{1, 2, 3, 4}));
        assert(second.empty());
        first.readPending = true;
        assert(!first.retire(false, true, true, destroy));
        first.readSemaphore = first.writeSemaphore = 5;
        assert(!first.retire(false, true, true, destroy));
        assert(destroyed.size() == 4);
        first.writeSemaphore = 0;
        assert(first.retire(false, true, true, destroy));
        first.writeSemaphore = 6;
        assert(first.retire(false, true, true, destroy));
        assert((destroyed == std::vector<unsigned>{1, 2, 3, 4, 5, 6}));
    }
    std::puts("PASS slot-local bridge sync ownership, preflight and retirement (10000 cycles)");
}
