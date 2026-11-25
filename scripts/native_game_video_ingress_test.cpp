// SPDX-License-Identifier: GPL-3.0-or-later
// Execute the unmodified production handleVideoRefresh body against controlled
// bridge acceptance and source metadata. GPU rendering is tested separately on EGL.
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include "libretro/libretro-common/include/libretro.h"

// Vulkan's stable VkFormat numeric ABI; no Vulkan device is used in this API test.
constexpr uint32_t VK_FORMAT_A2R10G10B10_UNORM_PACK32 = 58;
constexpr uint32_t VK_FORMAT_A2B10G10R10_UNORM_PACK32 = 64;
static unsigned glFinishes = 0;
void glFinish() { ++glFinishes; }

struct Environment {
    unsigned handshake = 0;
    int pixelFormat = RETRO_PIXEL_FORMAT_XRGB8888;
    static Environment& getInstance() { static Environment value; return value; }
    unsigned getHdrSourceStatus() const { return handshake; }
    int getPixelFormat() const { return pixelFormat; }
};

struct VulkanBackend {
    unsigned format = 0, width = 256, height = 240, submissions = 0;
    bool bridgeAccepted = true, frameConfirmed = false, fatal = false;
    static VulkanBackend& getInstance() { static VulkanBackend value; return value; }
    unsigned getCoreSourceFormat() const { return format; }
    unsigned getFrameBufferWidth() const { return width; }
    unsigned getFrameBufferHeight() const { return height; }
    bool hasFatalError() const { return fatal; }
    bool presentFrame(unsigned, unsigned, float aspect, bool compatible) {
        assert(aspect == 4.0F / 3.0F && compatible);
        ++submissions;
        frameConfirmed = false;
        return bridgeAccepted;
    }
};

struct Video {
    unsigned transfer = 2, transferUpdates = 0, frameCalls = 0, width = 32, height = 24;
    bool external = true;
    bool presentsExternalFrames() const { return external; }
    void setSourceTransfer(unsigned value) { transfer = value; ++transferUpdates; }
    void onNewFrame(const void* data, unsigned newWidth, unsigned newHeight, size_t) {
        ++frameCalls;
        if (data) { width = newWidth; height = newHeight; }
    }
    bool rendersInVideoCallback() const { return false; }
};

struct LibretroDroid {
    Video* video;
    float getAspectRatio() const { return 4.0F / 3.0F; }
    bool nativePictureCompatible() const { return true; }
    void attachVulkanFrame(unsigned width, unsigned height) {
        auto& backend = VulkanBackend::getInstance();
        backend.width = width;
        backend.height = height;
    }
    void renderPrimaryFrame() {}
    void handleVideoRefresh(const void*, unsigned, unsigned, size_t);
};

// Written by the runner from the prepared production source, including the
// original function signature and full body. No rewritten implementation here.
#include "game_video_refresh_under_test.h"

int main() {
    enum class Input { Vulkan, Software, Duplicate, GlesHardware };
    struct Case {
        const char* name;
        Input input;
        unsigned handshake, vkFormat;
        int pixelFormat;
        bool bridgeAccepted;
        unsigned expectedTransfer, expectedUpdates, expectedFrames;
        bool fatal = false;
    };
    const Case cases[] {
        {"PQ Vulkan A2B10", Input::Vulkan, 3, 64, RETRO_PIXEL_FORMAT_XRGB8888, true, 1, 1, 1},
        {"PQ Vulkan A2R10", Input::Vulkan, 3, 58, RETRO_PIXEL_FORMAT_XRGB8888, true, 1, 1, 1},
        {"unconfirmed 10bit", Input::Vulkan, 1, 64, RETRO_PIXEL_FORMAT_XRGB8888, true, 0, 1, 1},
        {"ordinary Vulkan RGBA8", Input::Vulkan, 3, 37, RETRO_PIXEL_FORMAT_XRGB8888, true, 0, 1, 1},
        {"rejected bridge preserves previous frame", Input::Vulkan, 3, 64,
            RETRO_PIXEL_FORMAT_XRGB8888, false, 2, 0, 0},
        {"accepted packed software HDR", Input::Software, 3, 37,
            RETRO_PIXEL_FORMAT_HDR10_2101010, true, 1, 1, 1},
        {"software SDR ignores stale Vulkan format", Input::Software, 3, 64,
            RETRO_PIXEL_FORMAT_XRGB8888, true, 0, 1, 1},
        {"duplicate preserves transfer", Input::Duplicate, 0, 37,
            RETRO_PIXEL_FORMAT_XRGB8888, true, 2, 0, 1},
        {"GLES hardware is not proven PQ", Input::GlesHardware, 3, 64,
            RETRO_PIXEL_FORMAT_HDR10_2101010, true, 0, 1, 1},
        {"fatal GPU state preserves previous frame", Input::Vulkan, 3, 64,
            RETRO_PIXEL_FORMAT_XRGB8888, true, 2, 0, 0, true},
    };
    unsigned count = 0;
    const uint32_t softwarePixel = 0;
    for (const auto& test : cases) {
        auto& environment = Environment::getInstance();
        environment = {test.handshake, test.pixelFormat};
        auto& backend = VulkanBackend::getInstance();
        backend = {};
        backend.format = test.vkFormat;
        backend.bridgeAccepted = test.bridgeAccepted;
        backend.fatal = test.fatal;
        backend.frameConfirmed = true; // failed ingress must invalidate an old success
        Video video;
        video.external = test.input != Input::GlesHardware;
        LibretroDroid host {&video};
        const void* data = test.input == Input::Software ? &softwarePixel :
            test.input == Input::Duplicate ? nullptr : RETRO_HW_FRAME_BUFFER_VALID;
        const unsigned previousFinishes = glFinishes;
        host.handleVideoRefresh(data, 256, 224, 1024);
        assert(video.transfer == test.expectedTransfer);
        assert(video.transferUpdates == test.expectedUpdates);
        assert(video.frameCalls == test.expectedFrames);
        const bool vulkan = test.input == Input::Vulkan && !test.fatal;
        assert(backend.submissions == (vulkan ? 1u : 0u));
        assert(glFinishes - previousFinishes == (vulkan ? 1u : 0u));
        if (!test.bridgeAccepted) {
            assert(!backend.frameConfirmed);
            assert(video.width == 32 && video.height == 24);
        }
        std::printf("PASS source ingress: %s\n", test.name);
        ++count;
    }
    std::printf("Production handleVideoRefresh acceptance: %u cases passed\n", count);
}
