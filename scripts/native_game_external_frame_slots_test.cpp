// SPDX-License-Identifier: GPL-3.0-or-later
#include "externalframeslots.h"
#include <cassert>
#include <cstdio>
#include <type_traits>
#include <vector>

struct Driver {
    using Buffer = unsigned;
    using Display = unsigned;
    using Context = unsigned;
    using Image = unsigned;
    using Texture = unsigned;
    using Destroy = unsigned;
    unsigned finishes = 0;
    std::vector<unsigned> deleted, destroyed, released;
    void finish() { ++finishes; }
    void deleteTexture(Texture texture) { deleted.push_back(texture); }
    void destroyImage(Destroy proc, Display display, Image image) {
        assert(proc == 99 && display == 1);
        destroyed.push_back(image);
    }
    void releaseBuffer(Buffer buffer) { released.push_back(buffer); }
};
using Slots = libretrodroid::ExternalFrameSlots<Driver>;
static_assert(!std::is_copy_constructible_v<Slots> && !std::is_copy_assignable_v<Slots>);

int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        Slots slots;
        Driver driver;
        unsigned borrowed = 0;
        assert(!slots.get(2) && !slots.canImport(2, 3) && !slots.canImport(0, 0));
        assert(!slots.select(0, 1, 2, borrowed));
        assert(slots.finishUse(borrowed, 1, 2, driver, true));
        assert(!slots.finishUse(borrowed, 1, 2, driver));
        auto& a = *slots.get(0);
        auto& b = *slots.get(1);
        assert(slots.canImport(0, 3));
        a.lease.adopt(3, 1, 2, 5, 99); a.texture = 7; a.width = 320; a.height = 240;
        assert(slots.select(0, 1, 2, borrowed) && borrowed == 7);
        assert(!slots.canImport(1, 3) && slots.canImport(1, 4));
        b.lease.adopt(4, 1, 2, 6, 99); b.texture = 8; b.width = 640; b.height = 480;
        assert(borrowed == 7 && slots.selected() == 0);
        assert(slots.finishSlotUse(1, 1, 2, driver) && driver.finishes == 1);
        assert(slots.finishSlotUse(1, 1, 2, driver) && driver.finishes == 1);
        assert(slots.finishUse(borrowed, 1, 2, driver) && driver.finishes == 2);
        assert(!slots.select(1, 1, 3, borrowed) && borrowed == 7);
        assert(slots.select(1, 1, 2, borrowed) && borrowed == 8);
        assert(!slots.markUse(7, 1, 2) && slots.markUse(borrowed, 1, 2));
        assert(slots.finishSlotUse(0, 1, 2, driver) && driver.finishes == 2);
        assert(slots.finishUse(borrowed, 1, 2, driver) && driver.finishes == 3);
        slots.releaseSlot(0, borrowed, 1, 2, driver);
        assert(borrowed == 8 && slots.selected() == 1 && slots.canImport(0, 3));
        assert((driver.deleted == std::vector<unsigned>{7}));
        assert((driver.released == std::vector<unsigned>{3}));
        // Recycled numeric EGL handles must not authorize GL deletion after loss.
        a.lease.adopt(9, 1, 2, 10, 99); a.texture = 11; a.width = 1; a.height = 1;
        slots.invalidateContext();
        assert(!slots.ownsContext(1, 2) && !slots.markUse(borrowed, 1, 2));
        assert(!slots.finishSlotUse(0, 1, 2, driver, true));
        assert(!slots.select(0, 1, 2, borrowed) && borrowed == 8);
        slots.release(borrowed, 1, 2, driver);
        assert(!borrowed && slots.selected() == 0 && driver.finishes == 4);
        assert((driver.deleted == std::vector<unsigned>{7}));
        assert((driver.destroyed == std::vector<unsigned>{5, 10, 6}));
        assert((driver.released == std::vector<unsigned>{3, 9, 4}));
        slots.release(borrowed, 1, 2, driver);
        assert(driver.released.size() == 3 && driver.destroyed.size() == 3);
    }
    std::puts("PASS 10000 independent EGL slot lifecycle cycles");
}
