// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkanwsiacquireslots.h"
#include <cassert>
#include <cstdio>
#include <type_traits>
#include <vector>

using Slots = libretrodroid::VulkanWsiAcquireSlots<unsigned>;
static_assert(!std::is_copy_constructible_v<Slots> && !std::is_copy_assignable_v<Slots>);

int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        Slots slots;
        std::vector<unsigned> complete{5, 6};
        std::vector<unsigned> freed;
        unsigned creates = 0;
        const auto create = [&](unsigned value, bool ok = true) {
            return [&, value, ok](unsigned& out) { ++creates; out = value; return ok; };
        };
        const auto destroy = [&](unsigned semaphore) { freed.push_back(semaphore); };
        assert(!slots.get(2) && !slots.ensure(2, complete, create(9)) && creates == 0);
        assert(!slots.ensure(0, complete, create(99, false)) && !slots.get(0));
        assert(!slots.ensure(0, complete, create(0)));
        assert(!slots.ensure(0, complete, create(5)));
        assert(slots.ensure(0, complete, create(1)) && slots.get(0) == 1 && !slots.get(1));
        assert(slots.ensure(0, complete, create(8)) && creates == 4 && slots.get(0) == 1);
        assert(!slots.ensure(1, complete, create(1)) && !slots.get(1));
        assert(!slots.ensure(1, complete, create(6)) && !slots.get(1));
        assert(slots.ensure(1, complete, create(2)) && slots.get(1) == 2 && creates == 7);
        assert(slots.owns(1) && slots.owns(2) && !slots.owns(0) && !slots.owns(5));
        assert(!slots.canRetire(false, true, complete) && !slots.canRetire(true, false, complete));
        assert(!slots.retire(false, true, true, complete, destroy));
        assert(!slots.retire(true, false, true, complete, destroy));
        complete.push_back(2);
        assert(!slots.retire(true, true, true, complete, destroy));
        complete.back() = 5;
        assert(!slots.retire(true, true, true, complete, destroy));
        complete.pop_back();
        assert(freed.empty() && slots.get(0) == 1 && slots.get(1) == 2);
        assert(slots.retire(true, true, true, complete, destroy));
        assert((freed == std::vector<unsigned>{1, 2}) && !slots.get(0) && !slots.get(1));
        assert(slots.retire(true, true, true, complete, destroy) && freed.size() == 2);
        assert((complete == std::vector<unsigned>{5, 6}));
        complete.clear();
        assert(slots.canRetire(false, false, complete));
        assert(slots.ensure(1, complete, create(3)) && !slots.get(0) && slots.get(1) == 3);
        assert(slots.retire(true, true, true, complete, destroy));
        assert((freed == std::vector<unsigned>{1, 2, 3}));
    }
    std::puts("PASS 10000 WSI acquire-slot ownership and rollback cycles");
}
