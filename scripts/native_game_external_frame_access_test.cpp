// SPDX-License-Identifier: GPL-3.0-or-later
#include "externalframelease.h"
#include <cassert>
#include <cstdio>

struct Driver {
    using Buffer = unsigned;
    using Display = unsigned;
    using Context = unsigned;
    using Image = unsigned;
    using Texture = unsigned;
    using Destroy = unsigned;
    unsigned finishes = 0, deletes = 0, destroys = 0, releases = 0;
    void finish() { ++finishes; }
    void deleteTexture(Texture) { ++deletes; }
    void destroyImage(Destroy, Display, Image) { ++destroys; }
    void releaseBuffer(Buffer) { ++releases; }
};

int main() {
    using Lease = libretrodroid::ExternalFrameLease<Driver>;
    for (unsigned iteration = 0; iteration < 10000; ++iteration) {
        Lease lease;
        Driver driver;
        Driver::Texture texture = 0;
        assert(lease.finishUse(texture, 1, 2, driver, true));
        assert(!lease.finishUse(texture, 1, 2, driver));
        assert(!lease.finishUse(6, 1, 2, driver, true));
        assert(!lease.markUse(6, 1, 2));
        lease.adopt(3, 1, 2, 4, 5);
        texture = 6;
        assert(!lease.finishUse(texture, 1, 9, driver));
        assert(!lease.finishUse(0, 1, 2, driver, true));
        assert(driver.finishes == 0);
        assert(lease.finishUse(texture, 1, 2, driver));
        assert(driver.finishes == 1); // import/setup
        for (unsigned frame = 0; frame < 20; ++frame)
            assert(lease.finishUse(texture, 1, 2, driver));
        assert(driver.finishes == 1); // consecutive native-only frames
        assert(lease.markUse(texture, 1, 2)); // primary draw / capture
        assert(lease.markUse(texture, 1, 2)); // same-context secondary / forced draw
        assert(!lease.finishUse(texture, 9, 2, driver));
        assert(driver.finishes == 1);
        assert(lease.finishUse(texture, 1, 2, driver));
        assert(driver.finishes == 2); // exactly one owning-context drain
        assert(!lease.markUse(texture, 1, 9));
        assert(lease.finishUse(texture, 1, 2, driver, true));
        assert(driver.finishes == 2); // resize may skip already completed work
        assert(lease.markUse(texture, 1, 2));
        assert(lease.needsUseCompletion(texture, 1, 2));
        assert(!lease.markWaitSubmitted(texture, 1, 9));
        assert(lease.needsUseCompletion(texture, 1, 2));
        assert(lease.markWaitSubmitted(texture, 1, 2));
        assert(!lease.needsUseCompletion(texture, 1, 2));
        assert(lease.needsUseCompletion(texture, 1, 9));
        assert(lease.finishUse(texture, 1, 2, driver) && driver.finishes == 2);
        assert(lease.markUse(texture, 1, 2));
        assert(lease.finishUse(texture, 1, 2, driver, true));
        assert(driver.finishes == 3); // new read after consumed wait still drains
        lease.release(texture, 1, 2, driver);
        assert(texture == 0 && driver.finishes == 4 && driver.deletes == 1 &&
               driver.destroys == 1 && driver.releases == 1);
        lease.adopt(13, 1, 2, 14, 15);
        texture = 6;
        assert(lease.finishUse(texture, 1, 2, driver));
        assert(driver.finishes == 5); // new import never inherits a clean state
        assert(lease.markUse(texture, 1, 2));
        lease.invalidateContext();
        assert(!lease.markWaitSubmitted(texture, 1, 2));
        assert(lease.needsUseCompletion(texture, 1, 2));
        assert(!lease.markUse(texture, 1, 2));
        assert(!lease.finishUse(texture, 1, 2, driver, true));
        assert(!lease.finishUse(texture, 1, 0, driver));
        lease.release(texture, 1, 2, driver);
        assert(driver.finishes == 5 && driver.deletes == 1 &&
               driver.destroys == 2 && driver.releases == 2);
    }
    std::puts("PASS: 10000 external-frame access cycles (import, native-only, sampling, resize, context loss)");
}
