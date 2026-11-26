// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkanpresentfences.h"
#include <cassert>
#include <cstdio>
#include <type_traits>
#include <vector>

using Fences = libretrodroid::VulkanPresentFences<unsigned>;
static_assert(!std::is_copy_constructible_v<Fences> && !std::is_copy_assignable_v<Fences>);

int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        Fences fences;
        std::vector<unsigned> destroyed;
        unsigned next = 1, waits = 0, resets = 0;
        const auto destroy = [&](unsigned f) { destroyed.push_back(f); };
        const auto wait = [&](unsigned f) { assert(f); ++waits; return true; };
        const auto reset = [&](unsigned f) { assert(f); ++resets; return true; };
        const auto fail = [](unsigned) { return false; };
        assert(fences.create(3, [&](unsigned& f) { f = next++; return true; }));
        assert(!fences.create(3, [&](unsigned& f) { f = next++; return true; }));
        assert(!fences.get(3) && !fences.markPending(3));
        assert(fences.prepare(0, wait, reset) && !waits && !resets);
        assert(fences.markPending(0) && fences.markPending(2));
        assert(!fences.markPending(0));
        assert(!fences.retire(true, true, destroy) && destroyed.empty());
        assert(!fences.prepare(0, fail, reset) && !resets);
        assert(!fences.prepare(0, wait, fail) && waits == 1);
        assert(fences.get(0)->phase == Fences::Phase::Complete);
        assert(fences.prepare(0, wait, reset) && waits == 1 && resets == 1);
        assert(fences.ready(0) && !fences.ready(2));
        fences.markUncertain(1);
        assert(!fences.completeAll(wait) && !fences.prepare(1, wait, reset));
        assert(!fences.retire(true, true, destroy) && destroyed.empty());
        fences.abandonAfterDeviceLoss();
        assert(!fences.retire(false, true, destroy) && !fences.retire(true, false, destroy));
        assert(fences.retire(true, true, destroy) && fences.empty());
        assert((destroyed == std::vector<unsigned>{1, 2, 3}));
        assert(fences.retire(true, true, destroy) && destroyed.size() == 3);
        // Allocation failure may poison an output handle; do not adopt it.
        next = 4;
        assert(!fences.create(3, [&](unsigned& f) { f = next++; return f != 5; }));
        assert(fences.get(0)->fence == 4 && !fences.get(1)->fence);
        assert(fences.retire(true, true, destroy));
        assert((destroyed == std::vector<unsigned>{1, 2, 3, 4}));
        assert(!fences.create(2, [](unsigned& f) { f = 7; return true; }));
        assert(fences.retire(true, true, destroy) && destroyed.back() == 7);
        assert(fences.create(2, [&](unsigned& f) { f = next++; return true; }));
        assert(fences.markPending(0) && fences.markPending(1));
        assert(fences.completeAll(wait));
        assert(fences.retire(true, true, destroy));
    }
    std::puts("PASS 10000 present fence ownership, completion, retry and retirement cycles");
}
