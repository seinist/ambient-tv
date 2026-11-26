// SPDX-License-Identifier: GPL-3.0-or-later
// Compare the native WSI geometry with production GLES layout, including initial
// nonsquare-pixel core AV information, and exercise native/GLES handoffs.
#include "vulkan/nativepresentation.h"
#include "videolayout.h"
#include <array>
#include <cassert>
#include <cstdio>
#include <limits>

using namespace libretrodroid;

static void verifyGeometry() {
    struct Size { unsigned width, height; };
    const Size sources[] {{256, 224}, {256, 240}, {320, 224}, {640, 480},
                          {512, 448}, {720, 576}, {480, 640}};
    const Size targets[] {{1920, 1080}, {3840, 2160}, {1280, 720}, {1080, 1920},
                          {960, 540}, {16, 16}};
    const float coreAspects[] {4.0F / 3.0F, 16.0F / 9.0F, 1.0F, 0.5F, 0.0F,
        std::numeric_limits<float>::quiet_NaN()};
    unsigned cases = 0;
    for (const auto source : sources) for (const auto target : targets) {
        for (const float coreAspect : coreAspects) for (const int mode : {0, 1, 2}) {
            // Same resolution rule used by LibretroDroid's initial AV information and
            // its runtime geometry updates. A user override supplies an already valid DAR.
            const float aspect = aspectRatioForGeometry(coreAspect, source.width, source.height);
            VideoLayout gles(false, 0.0F, Rect(0.0F, 0.0F, 1.0F, 1.0F));
            gles.updateScreenSize(target.width, target.height);
            gles.updateContentSize(source.width, source.height);
            gles.updateAspectRatio(aspect);
            gles.setIntegerScalingAuto(mode == 2);
            gles.setIntegerScaling(mode != 0);
            float left = 1.0F, right = -1.0F, top = 1.0F, bottom = -1.0F;
            const auto& vertices = gles.getForegroundVertices();
            for (size_t i = 0; i < vertices.size(); i += 2) {
                left = std::min(left, vertices[i]);
                right = std::max(right, vertices[i]);
                top = std::min(top, vertices[i + 1]);
                bottom = std::max(bottom, vertices[i + 1]);
            }
            const double glWidth = (right - left) * target.width / 2.0;
            const double glHeight = (bottom - top) * target.height / 2.0;
            const auto vk = nativeViewportFor(target.width, target.height, source.width,
                source.height, aspect, mode != 0, mode == 2);
            assert(std::abs(vk.width - glWidth) <= 0.51);
            assert(std::abs(vk.height - glHeight) <= 0.51);
            assert(vk.x == static_cast<int32_t>((target.width - vk.width) / 2));
            assert(vk.y == static_cast<int32_t>((target.height - vk.height) / 2));
            ++cases;
        }
    }
    // Reproduction: the old Environment-only lookup returned -1 until SET_GEOMETRY,
    // so a 256x224 core's declared 4:3 picture was only 1234 instead of 1440 pixels wide.
    const float initialAspect = aspectRatioForGeometry(4.0F / 3.0F, 256, 224);
    const auto initial = nativeViewportFor(1920, 1080, 256, 224, initialAspect, false, false);
    assert(initial.width == 1440 && initial.height == 1080 && initial.x == 240);
    const auto palWidescreen = nativeViewportFor(1920, 1080, 720, 576, 16.0F / 9.0F, false, false);
    assert(palWidescreen.width == 1920 && palWidescreen.height == 1080);
    assert(nativeViewportFor(0, 1080, 256, 224, initialAspect, false, false).width == 0);
    assert(nativeViewportFor(1920, 1080, 0, 224, initialAspect, false, false).width == 0);
    std::printf("Native viewport matches production GLES layout: %u cases\n", cases);
}

static void verifyPresentationHandoffs() {
    using Draw = NativePresentationGate::PrimaryDraw;
    NativePresentationGate gate;
    gate.didPresentFrame();
    assert(gate.primaryDraw(false, true) == Draw::Draw); // diagnostic is still hidden
    gate.setFullscreenActive(true);
    assert(gate.primaryDraw(false, true) == Draw::Draw); // old 16px success cannot promote
    gate.didPresentFrame();
    assert(gate.primaryDraw(false, true) == Draw::Skip);
    gate.setFullscreenActive(true); // idempotent UI updates keep current evidence
    assert(gate.primaryDraw(false, true) == Draw::Skip);

    gate.beginStep(); // ordinary step is not a surface-generation transition
    assert(!gate.hasPresentedFrame() && gate.hasConfirmedGeneration());
    assert(gate.primaryDraw(false, true) == Draw::Force);
    gate.didPresentFrame();
    assert(gate.primaryDraw(false, true) == Draw::Skip);
    gate.invalidateFrame(); // failed present / surface resize / preset change
    assert(!gate.hasConfirmedGeneration());
    assert(gate.primaryDraw(false, true) == Draw::Force);
    assert(gate.primaryDraw(false, true) == Draw::Draw);
    gate.didPresentFrame();
    assert(gate.primaryDraw(false, true) == Draw::Skip);
    gate.setFullscreenActive(false); // menu/demotion, no new core frame is required
    assert(gate.primaryDraw(false, true) == Draw::Force);
    gate.didPresentFrame();
    assert(gate.primaryDraw(false, true) == Draw::Draw);

    gate.setFullscreenActive(true);
    gate.didPresentFrame();
    assert(gate.primaryDraw(false, true) == Draw::Skip);
    assert(gate.primaryDraw(true, true) == Draw::Force); // capture/paused redraw
    assert(gate.primaryDraw(false, true) == Draw::Skip);
    assert(gate.primaryDraw(false, false) == Draw::Force); // secondary output/rewind
    assert(gate.primaryDraw(false, false) == Draw::Draw);
    gate.reset(); // new core cannot inherit old fullscreen ownership
    gate.didPresentFrame();
    assert(gate.primaryDraw(false, true) == Draw::Draw);
    std::puts("Native presentation handoffs: diagnostic/fullscreen/fallback/capture/secondary passed");
}

static void verifyMaintenancePairs() {
    using M = NativePresentMaintenance;
    for (unsigned mask = 0; mask < 4; ++mask) for (unsigned dev = 0; dev < 4; ++dev) {
        assert(M::surfaceMask((mask & 1) != 0, (mask & 2) != 0) == mask);
        const auto* selected = M::deviceExtension(mask, (dev & 1) != 0, (dev & 2) != 0);
        const unsigned matching = mask & dev;
        if (matching & M::khr) assert(selected == M::khrSwapchain);
        else if (matching & M::ext) assert(selected == M::extSwapchain);
        else assert(selected == nullptr);
    }
    std::puts("PASS all KHR/EXT surface and device extension pair combinations");
}

int main() {
    verifyGeometry();
    verifyPresentationHandoffs();
    verifyMaintenancePairs();
}
