// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkanslanghistoryuse.h"
#include <cassert>
#include <cstdio>
#include <initializer_list>

using Use = libretrodroid::VulkanSlangHistoryUse;
int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        Use use;
        assert(use.idle() && use.owner() == Use::noOwner && !use.submitted());
        assert(!use.begin(2) && !use.markSubmitted(0));
        for (std::size_t slot : {std::size_t{0}, std::size_t{1}}) {
            const auto other = 1 - slot;
            assert(use.begin(slot) && use.owner() == slot && !use.submitted());
            assert(!use.begin(slot) && !use.begin(other));
            assert(!use.markSubmitted(other));
            use.completeSubmitted(slot); // no submit, no fence proof
            assert(!use.idle());
            assert(use.markSubmitted(slot) && use.submitted());
            assert(!use.markSubmitted(slot));
            use.completeSubmitted(other);
            assert(!use.idle() && !use.begin(other));
            use.completeSubmitted(slot);
            assert(use.idle() && use.owner() == Use::noOwner);
            assert(use.begin(other)); // failed recording / failed queue submit
            use.completeSubmitted(other);
            assert(!use.idle());
            use.retireAfterDeviceIdle();
            assert(use.idle());
            assert(use.begin(slot) && use.markSubmitted(slot));
            use.retireAfterDeviceIdle();
            use.retireAfterDeviceIdle();
            assert(use.idle());
        }
    }
    std::puts("PASS 10000 Slang history owner and completion cycles");
}
