// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkanbridgeallocationbudget.h"
#include <cassert>
#include <cstdio>
#include <limits>

using Budget = libretrodroid::VulkanBridgeAllocationBudget;
using Bytes = Budget::Bytes;
constexpr Bytes cap = Budget::maximum;
constexpr Bytes unknown = std::numeric_limits<Bytes>::max();
static_assert(Budget::primary().permitsAllocation(cap + 1));
static_assert(!Budget::additional(0, 0).permitsAllocation(1));
static_assert(Budget::additional(cap - 1, 0).permitsAllocation(1));
static_assert(!Budget::additional(cap - 1, 1).permitsAllocation(1));

int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        const Bytes primary = 1 + i * 8192ull;
        const Bytes retained = i * 1024ull;
        const auto budget = Budget::additional(primary, retained);
        assert(budget.available() == cap - primary - retained);
        assert(budget.permitsAllocation(budget.available()));
        assert(!budget.permitsAllocation(budget.available() + 1));
        assert(!budget.permitsAllocation(0));
        assert(Budget::additional(primary, unknown).available() == 0);
        assert(Budget::additional(unknown, retained).available() == 0);
        assert(Budget::additional(primary, cap - primary).available() == 0);
        assert(Budget::additional(primary, cap - primary - 1).available() == 1);
        assert(!budget.permitsRequest(0, 720, true));
        assert(!budget.permitsRequest(1280, 0, false));
        assert(!budget.permitsRequest(std::numeric_limits<unsigned>::max(),
                                      std::numeric_limits<unsigned>::max(), true));
        const auto onePixel = Budget::additional(cap - 4, 0);
        assert(onePixel.permitsRequest(1, 1, false));
        assert(!onePixel.permitsRequest(1, 1, true));
        assert(!onePixel.permitsAllocation(5)); // driver alignment beyond estimate
    }
    constexpr Bytes rgba4k = 3840ull * 2160 * 4;
    constexpr Bytes fp164k = rgba4k * 2;
    assert(Budget::additional(fp164k, 0).permitsRequest(3840, 2160, true));
    assert(!Budget::additional(fp164k, rgba4k).permitsRequest(3840, 2160, true));
    assert(Budget::primary().permitsRequest(7680, 4320, true));
    assert(!Budget::primary().permitsRequest(std::numeric_limits<unsigned>::max(),
                                            std::numeric_limits<unsigned>::max(), false));
    std::puts("PASS 10000 Vulkan bridge budget admission cycles");
}
