// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkan/vulkancoloralternatepolicy.h"
#include <cassert>
#include <cstdio>
#include <limits>

using Policy = libretrodroid::VulkanColorAlternatePolicy;
int main() {
    Policy policy;
    for (unsigned i = 0; i < 10000; ++i) {
        assert(!Policy::canRetain(0));
        assert(Policy::canRetain(1));
        assert(Policy::canRetain(Policy::idleBudget));
        assert(!Policy::canRetain(Policy::idleBudget + 1));
        assert(Policy::fits(Policy::totalBudget, 0));
        assert(!Policy::fits(Policy::totalBudget, 1));
        assert(Policy::fits(Policy::totalBudget - Policy::idleBudget, Policy::idleBudget));
        assert(!Policy::fits(Policy::totalBudget - Policy::idleBudget + 1, Policy::idleBudget));
        assert(!Policy::fits(1, Policy::idleBudget + 1));
        assert(!Policy::fits(std::numeric_limits<std::uint64_t>::max(), 1));
        assert(!Policy::fits(1, std::numeric_limits<std::uint64_t>::max()));
        policy.reset();
        for (unsigned frame = 0; frame < Policy::maxIdleFrames; ++frame)
            assert(policy.keepUnusedFrame());
        assert(!policy.keepUnusedFrame());
        assert(!policy.keepUnusedFrame());
        policy.reset();
        assert(policy.keepUnusedFrame());
    }
    std::puts("PASS: 10000 alternate-cache budget and expiry cycles");
}
