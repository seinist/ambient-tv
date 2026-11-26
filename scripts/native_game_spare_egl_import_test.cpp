// SPDX-License-Identifier: GPL-3.0-or-later
// Actual Video coordination methods, with a host-only EGL/slot dispatch fixture.
#include <array>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdio>

struct AHardwareBuffer { unsigned identity; };
static unsigned display = 1, context = 2;
unsigned eglGetCurrentDisplay() { return display; }
unsigned eglGetCurrentContext() { return context; }

struct Lease {
    AHardwareBuffer* buffer = nullptr;
    unsigned d = 0, c = 0;
    bool retainsBuffer() const { return buffer != nullptr; }
    bool ownsContext(unsigned a, unsigned b) const {
        return buffer && a && b && a == d && b == c;
    }
    bool matches(AHardwareBuffer* p, unsigned a, unsigned b, unsigned texture) const {
        return texture && buffer == p && ownsContext(a, b);
    }
};
struct Bank {
    struct Slot {
        Lease lease;
        unsigned texture = 0, width = 0, height = 0;
        uint64_t allocationBytes = 0;
    };
    std::array<Slot, 2> slots{};
    std::size_t current = 0;
    unsigned released = 0;
    const Slot* get(std::size_t i) const { return i < 2 ? &slots[i] : nullptr; }
    std::size_t selected() const { return current; }
    bool ownsContext(unsigned d, unsigned c) const {
        return slots[current].texture && slots[current].lease.ownsContext(d, c);
    }
    template<class Ops>
    void releaseSlot(std::size_t i, unsigned& borrowed, unsigned d, unsigned c, Ops&) {
        assert(i == 1 && current == 0 && slots[i].lease.ownsContext(d, c));
        assert(borrowed == slots[0].texture);
        slots[i] = {};
        ++released;
    }
};
struct Video {
    bool externalFrames = true;
    unsigned hwRenderTexture = 11;
    Bank externalFrame;
    unsigned prepares = 0;
    bool prepareOK = true;
    struct ExternalFrameOps {};
    bool matchesPrimaryExternalFrame(AHardwareBuffer*, unsigned, unsigned, uint64_t) const;
    bool prepareSpareExternalFrame(AHardwareBuffer*, unsigned, unsigned, uint64_t);
    bool releaseSpareExternalFrame();
    bool prepareExternalFrameSlot(std::size_t i, AHardwareBuffer* b, unsigned w, unsigned h, uint64_t n) {
        assert(i == 1 && externalFrame.current == 0 && hwRenderTexture == 11);
        ++prepares;
        if (!prepareOK) return false;
        externalFrame.slots[i] = {{b, display, context}, 12, w, h, n};
        return true;
    }
};
#include "spare_egl_under_test.h"

int main() {
    AHardwareBuffer a{1}, b{2};
    unsigned count = 0;
    for (unsigned i = 0; i < 1000; ++i) {
        for (unsigned bits = 0; bits < 64; ++bits) {
            display = 1; context = 2;
            Video v;
            v.externalFrames = bits & 1;
            v.externalFrame.current = (bits & 2) ? 1 : 0;
            v.externalFrame.slots[0] = {{&a, 1, 2}, 11, 32, 24, 4096};
            const bool existing = bits & 4;
            if (existing) v.externalFrame.slots[1] = {{&b, 1, (bits & 8) ? 3u : 2u}, 12, 32, 24, 4096};
            v.prepareOK = bits & 16;
            uint64_t bytes = (bits & 32) ? 0 : 4096;
            const bool primary = v.externalFrames && v.externalFrame.current == 0;
            assert(v.matchesPrimaryExternalFrame(&a, 32, 24, 4096) == primary);
            assert(!v.matchesPrimaryExternalFrame(&b, 32, 24, 4096));
            assert(!v.matchesPrimaryExternalFrame(&a, 33, 24, 4096));
            assert(!v.matchesPrimaryExternalFrame(&a, 32, 24, 8192));
            const bool can = primary && bytes && (!existing || !(bits & 8));
            assert(v.prepareSpareExternalFrame(&b, 32, 24, bytes) == (can && v.prepareOK));
            assert(v.prepares == unsigned(can));
            assert(v.hwRenderTexture == 11);
            const bool live = v.externalFrame.slots[1].lease.retainsBuffer();
            const bool own = v.externalFrame.slots[1].lease.ownsContext(1, 2);
            const bool release = primary && (!live || own);
            assert(v.releaseSpareExternalFrame() == release);
            assert(v.externalFrame.released == unsigned(release && live));
            assert(v.hwRenderTexture == 11 && v.externalFrame.slots[0].lease.buffer == &a);
            if (release) assert(v.releaseSpareExternalFrame());
            ++count;
        }
    }
    std::printf("PASS %u video slot coordination cases\n", count);
}
