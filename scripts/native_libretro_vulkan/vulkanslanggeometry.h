// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string_view>

namespace libretrodroid {

struct SlangFrameGeometry {
    uint32_t sourceWidth, sourceHeight, targetWidth, targetHeight;
    float viewportX, viewportY;
    uint32_t viewportWidth, viewportHeight;
};

// Classify only on preset selection. Keep the existing built-in intermediate
// estimates; this is not a general memory estimator for arbitrary shader packs.
class SlangGeometryPolicy {
public:
    enum class Budget { Ordinary, Scale3, Scale2 };
    static constexpr uint32_t maxDimension = 8192;
    static constexpr uint64_t maxRenderPixels = 4096ull * 2160ull;
    static constexpr uint64_t maxIntermediateBytes = 128ull * 1024ull * 1024ull;

    static Budget classify(std::string_view path) noexcept {
        const auto endsWith = [path](std::string_view suffix) {
            return path.size() >= suffix.size() &&
                path.substr(path.size() - suffix.size()) == suffix;
        };
        if (endsWith("/scalefx/scalefx.slangp")) return Budget::Scale3;
        if (endsWith("/xsal/2xsal.slangp") || endsWith("/eagle/super-2xsai.slangp") ||
            endsWith("/scalehq/2xScaleHQ.slangp")) return Budget::Scale2;
        return Budget::Ordinary;
    }

    static bool allows(Budget budget, const SlangFrameGeometry& g, uint32_t deviceLimit) noexcept {
        const uint32_t limit = std::min(maxDimension, deviceLimit);
        const auto extent = [limit](uint32_t w, uint32_t h) {
            return w && h && w <= limit && h <= limit;
        };
        if (!extent(g.sourceWidth, g.sourceHeight) || !extent(g.targetWidth, g.targetHeight) ||
            !extent(g.viewportWidth, g.viewportHeight) ||
            uint64_t(g.targetWidth) * g.targetHeight > maxRenderPixels ||
            uint64_t(g.viewportWidth) * g.viewportHeight > maxRenderPixels ||
            !std::isfinite(g.viewportX) || !std::isfinite(g.viewportY) ||
            g.viewportX < 0 || g.viewportY < 0 ||
            double(g.viewportX) + g.viewportWidth > g.targetWidth ||
            double(g.viewportY) + g.viewportHeight > g.targetHeight) return false;

        const uint64_t pixels = uint64_t(g.sourceWidth) * g.sourceHeight;
        if (budget == Budget::Scale3) {
            return uint64_t(g.sourceWidth) * 3 <= limit && uint64_t(g.sourceHeight) * 3 <= limit &&
                pixels * 160 <= maxIntermediateBytes;
        }
        if (budget == Budget::Scale2) {
            return uint64_t(g.sourceWidth) * 2 <= limit && uint64_t(g.sourceHeight) * 2 <= limit &&
                pixels * 32 + uint64_t(g.sourceWidth) * g.viewportHeight * 16 <= maxIntermediateBytes;
        }
        return true;
    }
};
} // namespace libretrodroid
