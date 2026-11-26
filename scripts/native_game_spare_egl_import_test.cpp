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
    bool pending = true;
    bool needsUseCompletion(unsigned texture, unsigned a, unsigned b) const {
        return !texture || !ownsContext(a, b) || pending;
    }
    bool markWaitSubmitted(unsigned texture, unsigned a, unsigned b) {
        if (!texture || !ownsContext(a, b)) return false;
        pending = false;
        return true;
    }
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
    unsigned released = 0, finished = 0;
    bool select(std::size_t i, unsigned d, unsigned c, unsigned& borrowed) {
        if (i >= 2 || !slots[i].texture || !slots[i].lease.ownsContext(d, c)) return false;
        current = i;
        borrowed = slots[i].texture;
        return true;
    }
    template<class Ops>
    bool finishSlotUse(std::size_t i, unsigned d, unsigned c, Ops&) {
        if (i >= 2 || !slots[i].texture || !slots[i].lease.ownsContext(d, c)) return false;
        ++finished;
        return true;
    }
    Slot* get(std::size_t i) { return i < 2 ? &slots[i] : nullptr; }
    const Slot* get(std::size_t i) const { return i < 2 ? &slots[i] : nullptr; }
    bool selectMatching(std::size_t i, AHardwareBuffer* b, unsigned w, unsigned h, uint64_t bytes,
                        unsigned d, unsigned c, unsigned& borrowed) {
        const auto* slot = get(i);
        if (!slot || !w || !h || !bytes || !slot->lease.matches(b, d, c, slot->texture) ||
                slot->width != w || slot->height != h || slot->allocationBytes != bytes) return false;
        return select(i, d, c, borrowed);
    }
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
    unsigned hwRenderTexture = 11, hwFBOWidth = 32, hwFBOHeight = 24;
    struct Renderer {
        unsigned updates = 0;
        void updateRenderedResolution(unsigned, unsigned) { ++updates; }
    };
    Renderer* renderer = nullptr;
    Bank externalFrame;
    unsigned prepares = 0;
    bool prepareOK = true;
    struct ExternalFrameOps {};
    bool matchesPrimaryExternalFrame(AHardwareBuffer*, unsigned, unsigned, uint64_t) const;
    bool matchesExternalFrameSlot(std::size_t, AHardwareBuffer*, unsigned, unsigned, uint64_t) const;
    bool finishExternalFrameSlotUse(std::size_t);
    bool prepareExternalFrameResize();
    bool externalFrameSlotNeedsReadWait(std::size_t) const;
    void externalFrameSlotReadWaitSubmitted(std::size_t);
    bool selectExternalFrameSlot(std::size_t, AHardwareBuffer*, unsigned, unsigned, uint64_t);
    bool finishExternalFrameUse(bool allowEmpty = false) {
        if (!externalFrames) return false;
        const auto* slot = externalFrame.get(externalFrame.selected());
        if (allowEmpty && slot && !slot->lease.retainsBuffer() && !hwRenderTexture) return true;
        if (!slot || slot->texture != hwRenderTexture) return false;
        return finishExternalFrameSlotUse(externalFrame.selected());
    }
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
    for (unsigned bits = 0; bits < 16; ++bits) {
        display = 1; context = 2;
        Video v;
        v.externalFrame.slots[0] = {{&a, 1, (bits & 1) ? 3u : 2u}, 11, 32, 24, 4096};
        v.externalFrame.slots[1] = {{&b, 1, (bits & 2) ? 3u : 2u}, 12, 32, 24, 4096};
        v.externalFrame.current = (bits & 4) ? 1 : 0;
        v.hwRenderTexture = (bits & 4) ? 12 : 11;
        v.externalFrames = !(bits & 8);
        assert(v.matchesExternalFrameSlot(0, &a, 32, 24, 4096) == (!(bits & 1) && v.externalFrames));
        assert(v.matchesExternalFrameSlot(1, &b, 32, 24, 4096) == (!(bits & 2) && v.externalFrames));
        assert(!v.matchesExternalFrameSlot(1, &a, 32, 24, 4096));
        assert(!v.matchesExternalFrameSlot(2, &a, 32, 24, 4096));
        const bool valid = (bits & 11) == 0;
        assert(v.prepareExternalFrameResize() == valid);
        if (valid) assert(v.externalFrame.current == 0 && v.hwRenderTexture == 11 &&
                          v.externalFrame.released == 1 && !v.externalFrame.slots[1].lease.buffer);
    }
    // Publication rotates only the borrowed texture. Same geometry must
    // not force renderer reconfiguration; new geometry/first import must.
    {
        display = 1; context = 2;
        Video v;
        Video::Renderer renderer;
        v.renderer = &renderer;
        v.externalFrame.slots[0] = {{&a, 1, 2}, 11, 32, 24, 4096};
        v.externalFrame.slots[1] = {{&b, 1, 2}, 12, 32, 24, 4096};
        assert(v.externalFrameSlotNeedsReadWait(0) && v.externalFrameSlotNeedsReadWait(1));
        v.externalFrameSlotReadWaitSubmitted(1);
        assert(v.externalFrameSlotNeedsReadWait(0) && !v.externalFrameSlotNeedsReadWait(1));
        context = 3;
        v.externalFrameSlotReadWaitSubmitted(0);
        assert(v.externalFrameSlotNeedsReadWait(1));
        context = 2;
        assert(v.externalFrameSlotNeedsReadWait(0));
        for (unsigned j = 0; j < 1000; ++j) {
            assert(v.selectExternalFrameSlot(1, &b, 32, 24, 4096) && v.hwRenderTexture == 12);
            assert(v.selectExternalFrameSlot(0, &a, 32, 24, 4096) && v.hwRenderTexture == 11);
        }
        assert(renderer.updates == 0);
        v.hwRenderTexture = 0;
        assert(v.selectExternalFrameSlot(0, &a, 32, 24, 4096) && renderer.updates == 1);
        v.externalFrame.slots[1].width = 64;
        assert(v.selectExternalFrameSlot(1, &b, 64, 24, 4096) && renderer.updates == 2);
        assert(!v.selectExternalFrameSlot(1, &a, 64, 24, 4096) && renderer.updates == 2);
    }
    std::printf("PASS %u video slot coordination cases plus resize, read proof and stable geometry\n", count);
}
