// SPDX-License-Identifier: GPL-3.0-or-later
#include "externalframelease.h"
#include <cassert>
#include <iostream>
#include <vector>

struct Driver {
    using Buffer = unsigned;
    using Display = unsigned;
    using Context = unsigned;
    using Image = unsigned;
    using Texture = unsigned;
    using Destroy = unsigned;
    enum Call { Finish, DeleteTexture, DestroyImage, ReleaseBuffer };
    std::vector<Call> calls;
    unsigned destroyedDisplay = 0, destroyedImage = 0, destroyedDispatch = 0;
    unsigned deletedTexture = 0, releasedBuffer = 0;
    void finish() { calls.push_back(Finish); }
    void deleteTexture(Texture texture) { calls.push_back(DeleteTexture); deletedTexture = texture; }
    void destroyImage(Destroy proc, Display display, Image image) {
        calls.push_back(DestroyImage); destroyedDispatch = proc;
        destroyedDisplay = display; destroyedImage = image;
    }
    void releaseBuffer(Buffer buffer) { calls.push_back(ReleaseBuffer); releasedBuffer = buffer; }
};

using Lease = libretrodroid::ExternalFrameLease<Driver>;
using Call = Driver::Call;

int main() {
    for (unsigned i = 0; i < 10000; ++i) {
        Driver driver;
        Lease lease;
        Driver::Texture texture = 0;
        lease.release(texture, 1, 2, driver);
        assert(driver.calls.empty());
        lease.adopt(3, 1, 2, 4, 5);
        texture = 6;
        assert(lease.matches(3, 1, 2, texture));
        assert(!lease.matches(7, 1, 2, texture));
        assert(!lease.matches(3, 9, 2, texture));
        assert(!lease.matches(3, 1, 9, texture));
        assert(!lease.matches(3, 1, 2, 0));
        lease.finishUse(texture, 1, 2, driver);
        assert((driver.calls == std::vector<Call>{Driver::Finish}));
        driver.calls.clear();
        lease.release(texture, 1, 2, driver);
        assert((driver.calls == std::vector<Call>{Driver::Finish, Driver::DeleteTexture,
            Driver::DestroyImage, Driver::ReleaseBuffer}));
        assert(texture == 0 && driver.deletedTexture == 6 && driver.destroyedDisplay == 1 &&
               driver.destroyedImage == 4 && driver.destroyedDispatch == 5 && driver.releasedBuffer == 3);
        assert(!lease.matches(3, 1, 2, 6));
        driver.calls.clear();
        lease.release(texture, 1, 2, driver);
        assert(driver.calls.empty());

        // Old EGL image must be destroyed against its original display even when
        // a different context/display already owns the same numeric texture name.
        lease.adopt(11, 12, 13, 14, 15);
        texture = 6;
        lease.finishUse(texture, 99, 100, driver);
        assert(driver.calls.empty());
        lease.release(texture, 99, 100, driver);
        assert((driver.calls == std::vector<Call>{Driver::DestroyImage, Driver::ReleaseBuffer}));
        assert(texture == 0 && driver.destroyedDisplay == 12 && driver.destroyedImage == 14 &&
               driver.destroyedDispatch == 15 && driver.releasedBuffer == 11);
        driver.calls.clear();

        // EGL image imported but GL texture setup failed; release the acquired
        // reference once without calling GL on name zero.
        lease.adopt(21, 22, 23, 24, 25);
        lease.release(texture, 22, 23, driver);
        assert((driver.calls == std::vector<Call>{Driver::DestroyImage, Driver::ReleaseBuffer}));
        assert(driver.releasedBuffer == 21);
        driver.calls.clear();

        // Context recreation is an explicit generation boundary: EGL can reuse
        // the same numeric display/context/texture handles. The old lease must
        // retain its reference until release without touching the new context.
        lease.adopt(41, 42, 43, 44, 45);
        texture = 46;
        lease.invalidateContext();
        assert(!lease.matches(41, 42, 43, texture));
        assert(!lease.matches(41, 42, 0, texture));
        lease.finishUse(texture, 42, 43, driver);
        assert(driver.calls.empty());
        lease.release(texture, 42, 43, driver);
        assert((driver.calls == std::vector<Call>{Driver::DestroyImage, Driver::ReleaseBuffer}));
        assert(texture == 0 && driver.destroyedDisplay == 42 && driver.destroyedImage == 44 &&
               driver.destroyedDispatch == 45 && driver.releasedBuffer == 41);
        driver.calls.clear();
        lease.release(texture, 42, 43, driver);
        assert(driver.calls.empty());

        lease.adopt(31, 32, 33, 34, 35);
        texture = 36;
        lease.release(texture, 0, 0, driver);
        assert((driver.calls == std::vector<Call>{Driver::DestroyImage, Driver::ReleaseBuffer}));
        assert(driver.destroyedDisplay == 32 && driver.releasedBuffer == 31);
    }
    std::cout << "PASS: 10000 external-frame lease cycles (same-context drain, recycled EGL handles, context replacement, failed import and idempotent release)\n";
}
