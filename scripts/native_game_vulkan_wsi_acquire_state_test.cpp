// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkanwsiacquirestate.h"
#include <cassert>
#include <cstdio>

using State = libretrodroid::VulkanWsiAcquireState<>;
int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        State state;
        assert(state.allIdle() && !state.markAcquired(2));
        assert(state.markAcquired(0) && state.markAcquired(1));
        assert(!state.ready(0) && !state.markAcquired(0));
        // A plain device idle cannot complete an acquisition not yet waited on.
        state.completeAllSubmitted();
        assert(state.acquired(0) && state.acquired(1) && !state.allIdle());
        state.markSubmitted(0);
        assert(!state.acquired(0) && !state.ready(0));
        state.completeSubmitted(1);
        assert(state.acquired(1));
        state.completeSubmitted(0);
        assert(state.ready(0) && !state.allIdle());
        assert(state.markAcquired(0));
        state.markUncertain(0);
        state.markSubmitted(0);
        state.completeAllSubmitted();
        assert(!state.ready(0) && !state.acquired(0));
        state.markSubmitted(1);
        state.completeAllSubmitted();
        assert(state.ready(1) && !state.allIdle());
        state.abandonAfterDeviceLoss();
        assert(state.allIdle());
        assert(state.markAcquired(1));
        state.markSubmitted(1);
        state.completeAllSubmitted();
        assert(state.allIdle());
    }
    std::puts("PASS 10000 WSI acquire state lifecycle cycles");
}
