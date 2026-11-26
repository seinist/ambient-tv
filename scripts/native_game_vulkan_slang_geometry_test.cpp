// SPDX-License-Identifier: GPL-3.0-or-later
#include "vulkanslanggeometry.h"
#include <cassert>
#include <cstdio>
#include <limits>

using libretrodroid::SlangFrameGeometry;
using Policy = libretrodroid::SlangGeometryPolicy;
using Budget = Policy::Budget;

int main() {
    assert(Policy::classify("/packs/scalefx/scalefx.slangp") == Budget::Scale3);
    assert(Policy::classify("/packs/xsal/2xsal.slangp") == Budget::Scale2);
    assert(Policy::classify("/packs/eagle/super-2xsai.slangp") == Budget::Scale2);
    assert(Policy::classify("/packs/scalehq/2xScaleHQ.slangp") == Budget::Scale2);
    assert(Policy::classify("/packs/scalefx/scalefx.slangp.backup") == Budget::Ordinary);
    assert(Policy::classify("") == Budget::Ordinary);
    for (unsigned i = 0; i < 10000; ++i) {
        const SlangFrameGeometry base {320, 240, 1920, 1080, 240, 0, 1440, 1080};
        for (auto budget : {Budget::Ordinary, Budget::Scale2, Budget::Scale3}) {
            assert(Policy::allows(budget, base, 8192));
            assert(!Policy::allows(budget, base, 1024));
            auto g = base;
            g.viewportX = -1;
            assert(!Policy::allows(budget, g, 8192));
            g = base; g.viewportY = 1;
            assert(!Policy::allows(budget, g, 8192));
            g = base; g.viewportX = std::numeric_limits<float>::quiet_NaN();
            assert(!Policy::allows(budget, g, 8192));
            g = base; g.viewportY = std::numeric_limits<float>::infinity();
            assert(!Policy::allows(budget, g, 8192));
            g = base; g.targetWidth = std::numeric_limits<uint32_t>::max();
            assert(!Policy::allows(budget, g, 8192));
            g = base; g.sourceWidth = 0;
            assert(!Policy::allows(budget, g, 8192));
            g = base; g.viewportWidth = 0;
            assert(!Policy::allows(budget, g, 8192));
            g = base; g.targetWidth = 4096; g.targetHeight = 2161;
            assert(!Policy::allows(budget, g, 8192));
        }
        SlangFrameGeometry large {1024, 1024, 1920, 1080, 0, 0, 1080, 1080};
        assert(Policy::allows(Budget::Ordinary, large, 8192));
        assert(Policy::allows(Budget::Scale2, large, 8192));
        assert(!Policy::allows(Budget::Scale3, large, 8192));
        large.sourceWidth = large.sourceHeight = 2048;
        assert(!Policy::allows(Budget::Scale2, large, 8192));
        const SlangFrameGeometry smallDevice {700, 100, 1024, 768, 0, 0, 1024, 768};
        assert(Policy::allows(Budget::Ordinary, smallDevice, 1024));
        assert(!Policy::allows(Budget::Scale2, smallDevice, 1024));
        assert(!Policy::allows(Budget::Scale3, smallDevice, 1024));
    }
    std::puts("PASS: 10000 Slang geometry/budget cycles and preset classification");
}
