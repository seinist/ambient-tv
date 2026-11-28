// SPDX-License-Identifier: GPL-3.0-or-later
#include "externalframeslots.h"
#include <cassert>
#include <cstdio>
#include <limits>

struct Driver {
    using Buffer = unsigned;
    using Display = unsigned;
    using Context = unsigned;
    using Image = unsigned;
    using Texture = unsigned;
    using Destroy = unsigned;
    unsigned released = 0;
    void finish() {}
    void deleteTexture(Texture) {}
    void destroyImage(Destroy, Display, Image) {}
    void releaseBuffer(Buffer) { ++released; }
};

int main() {
    using Slots = libretrodroid::ExternalFrameSlots<Driver>;
    constexpr auto unknown = std::numeric_limits<std::uint64_t>::max();
    for (unsigned i = 0; i < 10000; ++i) {
        Slots slots;
        Driver driver;
        unsigned borrowed = 0;
        auto& a = *slots.get(0);
        auto& b = *slots.get(1);
        assert(slots.retainedAllocationBytesExcept() == 0);
        a.lease.adopt(1, 1, 2, 10, 99);
        a.texture = 20; a.width = 320; a.height = 240; a.allocationBytes = 100;
        b.lease.adopt(2, 1, 2, 11, 99);
        b.texture = 21; b.width = 640; b.height = 480; b.allocationBytes = 200;
        assert(slots.retainedAllocationBytesExcept() == 300);
        assert(slots.retainedAllocationBytesExcept(1) == 200);
        assert(slots.retainedAllocationBytesExcept(2) == 100);
        assert(slots.retainedAllocationBytesExcept(1, 2) == 0);
        assert(slots.retainedAllocationBytesExcept(1, 1) == 200);
        assert(slots.retainedAllocationBytesExcept(3) == 300);
        assert(slots.select(1, 1, 2, borrowed));
        slots.invalidateContext();
        assert(slots.retainedAllocationBytesExcept() == 300);
        assert(slots.retainedAllocationBytesExcept(2) == 100);
        // An imported buffer with unknown bytes is never counted as free.
        a.allocationBytes = 0;
        assert(slots.retainedAllocationBytesExcept() == unknown);
        assert(slots.retainedAllocationBytesExcept(1) == 200);
        a.allocationBytes = unknown - 199;
        assert(slots.retainedAllocationBytesExcept() == unknown);
        a.allocationBytes = 100;
        slots.releaseSlot(0, borrowed, 1, 2, driver);
        assert(a.allocationBytes == 0 && driver.released == 1 && borrowed == 21);
        assert(slots.retainedAllocationBytesExcept() == 200);
        slots.release(borrowed, 1, 2, driver);
        assert(b.allocationBytes == 0 && driver.released == 2 && !borrowed);
        assert(slots.retainedAllocationBytesExcept() == 0);
        slots.release(borrowed, 1, 2, driver);
        assert(driver.released == 2);
    }
    std::puts("PASS 10000 EGL retained-allocation accounting cycles");
}
