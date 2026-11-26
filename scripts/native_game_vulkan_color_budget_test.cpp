// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkancolorallocationbudget.h"
#include <cassert>
#include <cstdio>
#include <limits>
#include <type_traits>

using Budget = libretrodroid::VulkanColorAllocationBudget;
static_assert(!std::is_copy_constructible_v<Budget> && !std::is_copy_assignable_v<Budget>);

int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        Budget budget;
        assert(budget.available() == Budget::maximum && !budget.used());
        const auto first = 64ull * 1024ull * 1024ull + i;
        assert(budget.reserve(first));
        const auto second = budget.available();
        assert(!budget.reserve(second + 1));
        assert(budget.reserve(second));
        assert(budget.used() == Budget::maximum && !budget.available());
        assert(!budget.reserve(1) && !budget.reserve(0));
        assert(!budget.release(Budget::maximum + 1) && !budget.available());
        assert(budget.release(first));
        assert(budget.available() == first && budget.used() == second);
        // An allocation/bind failure returns exactly its own reservation.
        assert(budget.reserve(first));
        assert(budget.release(first));
        assert(budget.release(second));
        assert(!budget.used() && budget.available() == Budget::maximum);
        assert(!budget.release(1) && budget.release(0));
        assert(!budget.reserve(std::numeric_limits<std::uint64_t>::max()));
        Budget small(3);
        assert(small.reserve(3) && !small.reserve(1) && small.release(3));
        Budget zero(0);
        assert(!zero.reserve(1) && !zero.available());
        Budget capped(std::numeric_limits<std::uint64_t>::max());
        assert(capped.available() == Budget::maximum);
    }
    std::puts("PASS 10000 shared normalization budget cycles");
}
