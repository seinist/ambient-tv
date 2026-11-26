// SPDX-License-Identifier: GPL-3.0-or-later
// Execute the unmodified production attachVulkanFrame and handleVideoRefresh
// bodies against controlled bridge allocation/import and source metadata.
// GPU rendering is tested separately on EGL.
#include "ambientgametrace.h"
using libretrodroid::traceGameCall;
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <initializer_list>
#include "libretro/libretro-common/include/libretro.h"

// Vulkan's stable VkFormat numeric ABI; no Vulkan device is used in this API test.
constexpr uint32_t VK_FORMAT_A2R10G10B10_UNORM_PACK32 = 58;
constexpr uint32_t VK_FORMAT_A2B10G10R10_UNORM_PACK32 = 64;
static unsigned glFinishes = 0;
void glFinish() { ++glFinishes; }
#define LOGW(...) ((void)0)

struct Environment {
    unsigned handshake = 0;
    int pixelFormat = RETRO_PIXEL_FORMAT_XRGB8888;
    static Environment& getInstance() { static Environment value; return value; }
    unsigned getHdrSourceStatus() const { return handshake; }
    int getPixelFormat() const { return pixelFormat; }
};

struct VulkanBackend {
    static constexpr int NoBridgeWriteFence = -2;
    unsigned format = 0, width = 256, height = 240, submissions = 0;
    bool bridgeAccepted = true, frameConfirmed = false, fatal = false;
    bool fp16 = false, allocationAccepted = true, fallbackAllocationAccepted = true;
    bool disableFails = false;
    bool readFenceSupported = false, readFenceAccepted = false, readFenceFatal = false;
    bool writeFenceSupported = false;
    int writeFenceResult = 43;
    unsigned writeExports = 0;
    unsigned drains = 0, allocations = 0, disables = 0, rejections = 0, fenceImports = 0, duplicates = 0;
    static VulkanBackend& getInstance() { static VulkanBackend value; return value; }
    unsigned getCoreSourceFormat() const { return format; }
    unsigned getFrameBufferWidth() const { return width; }
    unsigned getFrameBufferHeight() const { return height; }
    bool hasFatalError() const { return fatal; }
    bool canImportBridgeReadFence() const { return readFenceSupported; }
    bool importBridgeReadFence(int fd) {
        ++fenceImports;
        assert(fd == -1 || fd == 42);
        if (readFenceFatal) fatal = true;
        return fd >= 0 && readFenceAccepted && !fatal;
    }
    void* getFrameBuffer() { return this; }
    bool isFp16Frame() const { return fp16; }
    bool ensureFrameBuffer(unsigned w, unsigned h) {
        assert(drains > 0 && !fatal);
        ++allocations;
        if (!(allocations == 1 ? allocationAccepted : fallbackAllocationAccepted)) return false;
        width = w;
        height = h;
        return true;
    }
    void disableFp16Frame() {
        assert(fp16 && !fatal);
        ++disables;
        if (disableFails) fatal = true;
        else fp16 = false;
    }
    void rejectBridgeImport() { ++rejections; fatal = true; frameConfirmed = false; }
    bool completeDuplicateFrame() {
        ++duplicates;
        return !fatal;
    }
    bool canExportBridgeWriteFence() const { return writeFenceSupported; }
    bool presentFrame(unsigned, unsigned, float aspect, bool compatible, int* writeFd = nullptr) {
        assert(!fatal && aspect == 4.0F / 3.0F && compatible);
        ++submissions;
        frameConfirmed = false;
        if (writeFd) {
            assert(writeFenceSupported);
            *writeFd = bridgeAccepted ? writeFenceResult : NoBridgeWriteFence;
            if (bridgeAccepted && writeFenceResult >= -1) ++writeExports;
        }
        return bridgeAccepted;
    }
};

struct Video {
    unsigned transfer = 2, transferUpdates = 0, frameCalls = 0, width = 32, height = 24;
    bool external = true;
    bool fp16ImportAccepted = true, rgbaImportAccepted = true;
    unsigned imports = 0, fenceExports = 0;
    int fenceFd = 42;
    bool writeFenceSupported = false, writeFenceAccepted = true;
    unsigned writeImports = 0;
    bool canImportExternalWriteFence() const { return writeFenceSupported; }
    bool importExternalWriteFence(int fd) {
        assert(fd == 43 && VulkanBackend::getInstance().writeExports > writeImports);
        ++writeImports;
        return writeFenceAccepted;
    }
    bool drainAccepted = true, pendingUse = true;
    bool presentsExternalFrames() const { return external; }
    int exportExternalReadFence() { ++fenceExports; return fenceFd; }
    bool finishExternalFrameUse(bool allowEmpty = false) {
        if (allowEmpty) {
            ++VulkanBackend::getInstance().drains;
            return drainAccepted;
        }
        if (!drainAccepted) return false;
        if (pendingUse) glFinish();
        pendingUse = false;
        return true;
    }
    bool attachExternalFrame(void* buffer, unsigned w, unsigned h) {
        auto& backend = VulkanBackend::getInstance();
        assert(buffer == backend.getFrameBuffer() && !backend.fatal);
        assert(w == backend.width && h == backend.height && backend.allocations > imports);
        ++imports;
        pendingUse = true;
        return backend.fp16 ? fp16ImportAccepted : rgbaImportAccepted;
    }
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
    void attachVulkanFrame(unsigned width, unsigned height);
    void renderPrimaryFrame() {}
    void handleVideoRefresh(const void*, unsigned, unsigned, size_t);
};

// Written by the runner from the prepared production source, including the
// original function signatures and full bodies. No rewritten implementation here.
#include "game_video_refresh_under_test.h"

void bridgeImportContracts() {
    struct Case {
        const char* name;
        bool fp16, allocate, fpImport, rgbaImport, fallbackAllocate, disableFails;
        bool accepted;
        unsigned allocations, imports, disables;
    };
    const Case cases[] = {
        {"RGBA8 import", false, true, true, true, true, false, true, 1, 1, 0},
        {"FP16 import", true, true, true, true, true, false, true, 1, 1, 0},
        {"allocation failed", false, false, true, true, true, false, false, 1, 0, 0},
        {"RGBA8 import failed", false, true, true, false, true, false, false, 1, 1, 0},
        {"FP16 fallback succeeds", true, true, false, true, true, false, true, 2, 2, 1},
        {"fallback allocation failed", true, true, false, true, false, false, false, 2, 1, 1},
        {"both imports failed", true, true, false, false, true, false, false, 2, 2, 1},
        {"FP16 retirement failed", true, true, false, true, true, true, false, 1, 1, 1},
    };
    for (bool initial : {false, true}) {
        for (const auto& test : cases) {
            Environment::getInstance() = {3, RETRO_PIXEL_FORMAT_XRGB8888};
            auto& backend = VulkanBackend::getInstance();
            backend = {};
            backend.format = 64;
            backend.fp16 = test.fp16;
            backend.allocationAccepted = test.allocate;
            backend.fallbackAllocationAccepted = test.fallbackAllocate;
            backend.disableFails = test.disableFails;
            backend.frameConfirmed = true;
            Video video;
            video.fp16ImportAccepted = test.fpImport;
            video.rgbaImportAccepted = test.rgbaImport;
            LibretroDroid host {&video};
            const unsigned before = glFinishes;
            if (initial) host.attachVulkanFrame(512, 480);
            host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 512, 480, 2048);
            assert(backend.drains == 1 && backend.allocations == test.allocations);
            assert(video.imports == test.imports && backend.disables == test.disables);
            assert(backend.fatal == !test.accepted && backend.rejections == (test.accepted ? 0u : 1u));
            assert(backend.submissions == (test.accepted ? 1u : 0u));
            assert(glFinishes - before == (test.accepted ? 1u : 0u));
            assert(video.transfer == (test.accepted ? 1u : 2u));
            assert(video.frameCalls == (test.accepted ? 1u : 0u));
            assert(video.transferUpdates == video.frameCalls);
            assert(video.width == (test.accepted ? 512u : 32u));
            assert(video.height == (test.accepted ? 480u : 24u));
            if (!test.accepted) {
                assert(!backend.frameConfirmed);
                // Surface recreation may call attach directly after a fatal import.
                // It must not touch the old EGL lease, allocate or try another import.
                host.attachVulkanFrame(512, 480);
                host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 512, 480, 2048);
                assert(backend.submissions == 0 && video.frameCalls == 0 && backend.drains == 1);
                assert(backend.allocations == test.allocations && video.imports == test.imports);
                assert(backend.disables == test.disables && backend.rejections == 1);
            }
            std::printf("PASS %s bridge: %s\n", initial ? "initial" : "resize", test.name);
        }
    }
}

void readFenceHandoffContracts() {
    // Actual production callback: no GLES finish only after accepted fence
    // import, and no submission or metadata publication after a fatal import.
    struct Case { bool supported; int fd; bool imported, fatal; };
    const Case cases[] = {
        {false, 42, false, false},
        {true, -1, false, false},
        {true, 42, false, false},
        {true, 42, true, false},
        {true, 42, false, true},
    };
    for (const auto& test : cases) {
        Environment::getInstance() = {3, RETRO_PIXEL_FORMAT_XRGB8888};
        auto& backend = VulkanBackend::getInstance();
        backend = {};
        backend.format = 64;
        backend.readFenceSupported = test.supported;
        backend.readFenceAccepted = test.imported;
        backend.readFenceFatal = test.fatal;
        Video video;
        video.fenceFd = test.fd;
        LibretroDroid host {&video};
        const unsigned before = glFinishes;
        host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
        assert(video.fenceExports == (test.supported ? 1u : 0u));
        assert(backend.fenceImports == video.fenceExports);
        assert(glFinishes - before == (!test.imported && !test.fatal ? 1u : 0u));
        assert(backend.submissions == (test.fatal ? 0u : 1u));
        assert(video.frameCalls == backend.submissions && video.transferUpdates == backend.submissions);
        assert(video.transfer == (test.fatal ? 2u : 1u));
    }
    std::puts("PASS production GLES read fence handoff and CPU fallback (5 cases)");
}

void bridgeAccessContracts() {
    for (bool resize : {false, true}) {
        auto& backend = VulkanBackend::getInstance();
        backend = {};
        backend.frameConfirmed = true;
        Video video;
        video.drainAccepted = false;
        LibretroDroid host {&video};
        const unsigned before = glFinishes;
        host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, resize ? 512 : 256, 224, 1024);
        assert(backend.fatal && !backend.frameConfirmed && backend.rejections == 1);
        assert(!backend.allocations && !backend.submissions && !video.imports &&
               !video.frameCalls && !video.transferUpdates && glFinishes == before);
        assert(backend.drains == (resize ? 1u : 0u));
    }
    auto& backend = VulkanBackend::getInstance();
    backend = {};
    Video video;
    LibretroDroid host {&video};
    const unsigned before = glFinishes;
    host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
    host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
    assert(glFinishes == before + 1 && backend.submissions == 2);
    video.pendingUse = true; // forced, secondary or capture read
    host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
    assert(glFinishes == before + 2 && backend.submissions == 3 && video.frameCalls == 3);
    std::puts("PASS bridge access: wrong context, native-only, and resumed GLES reads (3 cases)");
}

void writeFenceHandoffContracts() {
    for (bool exportSupported : {false, true}) {
        for (bool importSupported : {false, true}) {
            for (bool bridgeAccepted : {false, true}) {
                for (bool importAccepted : {false, true}) {
                    auto& backend = VulkanBackend::getInstance();
                    backend = {};
                    backend.writeFenceSupported = exportSupported;
                    backend.bridgeAccepted = bridgeAccepted;
                    Video video;
                    video.writeFenceSupported = importSupported;
                    video.writeFenceAccepted = importAccepted;
                    LibretroDroid host {&video};
                    host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
                    assert(backend.submissions == 1);
                    const unsigned expected = exportSupported && importSupported && bridgeAccepted ? 1u : 0u;
                    assert(backend.writeExports == expected && video.writeImports == expected);
                    assert(video.frameCalls == (bridgeAccepted ? 1u : 0u));
                    assert(!backend.fatal);
                }
            }
        }
    }
    for (int result : {VulkanBackend::NoBridgeWriteFence, -1}) {
        auto& backend = VulkanBackend::getInstance();
        backend = {};
        backend.writeFenceSupported = true;
        backend.writeFenceResult = result;
        Video video;
        video.writeFenceSupported = true;
        LibretroDroid host {&video};
        host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
        assert(backend.submissions == 1 && video.frameCalls == 1 && !backend.fatal);
        assert(backend.writeExports == (result == -1 ? 1u : 0u) && !video.writeImports);
    }
    std::puts("PASS reverse fence ingress: both capability gates, rejected bridge and CPU fallback");
}

int main() {
    writeFenceHandoffContracts();
    bridgeImportContracts();
    readFenceHandoffContracts();
    bridgeAccessContracts();
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
        {"fatal duplicate preserves previous frame", Input::Duplicate, 0, 37,
            RETRO_PIXEL_FORMAT_XRGB8888, true, 2, 0, 0, true},
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
        assert(backend.duplicates == (test.input == Input::Duplicate && !test.fatal ? 1u : 0u));
        if (test.input == Input::Duplicate) {
            assert(!backend.drains && !backend.allocations && !backend.fenceImports);
            assert(video.width == 32 && video.height == 24);
        }
        assert(glFinishes - previousFinishes == (vulkan ? 1u : 0u));
        if (!test.bridgeAccepted) {
            assert(!backend.frameConfirmed);
            assert(video.width == 32 && video.height == 24);
        }
        std::printf("PASS source ingress: %s\n", test.name);
        ++count;
    }
    std::printf("Production bridge and ingress acceptance: %u cases passed\n", count + 24);
}
