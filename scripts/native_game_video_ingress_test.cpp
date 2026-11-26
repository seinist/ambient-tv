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
    uint64_t getFrameBufferAllocationBytes() const { return fp16 ? 8192 : 4096; }
    unsigned spareIdentity = 0, spareEnsures = 0, spareReleases = 0;
    bool dual = false, spareImported = false, writeWaitAccepted = true;
    unsigned coreIndex = 0, cpuWriteWaits = 0;
    bool writePending = false, eglWaitQueued = false;
    bool wantsSpareFrame() const { return dual; }
    void setSpareFrameImported(bool imported) { spareImported = imported && sparePrepared; }
    unsigned submissionSlotIndex() const { return dual && spareImported ? coreIndex : 0; }
    void* getSubmissionFrameBuffer() { return submissionSlotIndex() ? getSpareFrameBuffer() : getFrameBuffer(); }
    uint64_t getSubmissionAllocationBytes() const {
        return submissionSlotIndex() ? getSpareFrameBufferAllocationBytes() : getFrameBufferAllocationBytes();
    }
    bool waitForSubmissionFrame() {
        ++cpuWriteWaits;
        if (!writeWaitAccepted) { fatal = true; return false; }
        writePending = false;
        return true;
    }
    bool sparePrepared = false, spareAdmitted = true, spareRetireAccepted = true;
    uint64_t spareBudgetSeen = 0;
    void* getSpareFrameBuffer() { return sparePrepared ? &spareIdentity : nullptr; }
    uint64_t getSpareFrameBufferAllocationBytes() const { return 16384; }
    bool ensureSpareFrameBuffer(uint64_t retained) {
        ++spareEnsures;
        spareBudgetSeen = retained;
        if (!spareAdmitted) return false;
        sparePrepared = true;
        return true;
    }
    bool releaseSpareFrameBuffer() {
        ++spareReleases;
        if (!spareRetireAccepted) return false;
        sparePrepared = false;
        return true;
    }
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
        eglWaitQueued = false;
        if (writeFd) {
            assert(writeFenceSupported);
            *writeFd = bridgeAccepted ? writeFenceResult : NoBridgeWriteFence;
            if (bridgeAccepted && writeFenceResult >= -1) {
                ++writeExports;
                writePending = dual && spareImported && writeFenceResult >= 0;
            }
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
        VulkanBackend::getInstance().eglWaitQueued = writeFenceAccepted;
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
    unsigned selected = 0, publications = 0, slotDrains = 0;
    bool publishAccepted = true, spareMatches = true;
    bool prepareExternalFrameResize() {
        if (!finishExternalFrameUse(true)) return false;
        selected = 0;
        return releaseSpareExternalFrame();
    }
    bool finishExternalFrameSlotUse(unsigned index) {
        assert(index == VulkanBackend::getInstance().submissionSlotIndex());
        ++slotDrains;
        return finishExternalFrameUse();
    }
    bool matchesExternalFrameSlot(unsigned index, void* buffer, unsigned w, unsigned h, uint64_t bytes) {
        auto& backend = VulkanBackend::getInstance();
        assert(index == backend.submissionSlotIndex());
        if (!index) return matchesPrimaryExternalFrame(buffer, w, h, bytes);
        assert(buffer == backend.getSpareFrameBuffer() && w == backend.width && h == backend.height &&
               bytes == backend.getSpareFrameBufferAllocationBytes());
        return external && spareImported && spareMatches;
    }
    bool publishExternalFrame(unsigned index, void* buffer, unsigned w, unsigned h, uint64_t bytes) {
        auto& backend = VulkanBackend::getInstance();
        assert(index == backend.submissionSlotIndex() && buffer == backend.getSubmissionFrameBuffer() &&
               bytes == backend.getSubmissionAllocationBytes() && w == backend.width && h == backend.height);
        assert(!backend.writePending || backend.eglWaitQueued);
        if (!publishAccepted) return false;
        ++publications;
        selected = index;
        return true;
    }
    bool attachExternalFrame(void* buffer, unsigned w, unsigned h, uint64_t allocationBytes) {
        auto& backend = VulkanBackend::getInstance();
        assert(buffer == backend.getFrameBuffer() && !backend.fatal);
        assert(w == backend.width && h == backend.height && backend.allocations > imports);
        assert(allocationBytes == backend.getFrameBufferAllocationBytes());
        ++imports;
        selected = 0;
        pendingUse = true;
        return backend.fp16 ? fp16ImportAccepted : rgbaImportAccepted;
    }
    bool primaryMatches = true, spareImportAccepted = true, spareReleaseAccepted = true;
    bool spareImported = false;
    unsigned spareQueries = 0, spareImports = 0, spareDrops = 0;
    uint64_t retainedBytes = 1234;
    bool matchesPrimaryExternalFrame(void* buffer, unsigned w, unsigned h, uint64_t bytes) const {
        auto& backend = VulkanBackend::getInstance();
        assert(buffer == backend.getFrameBuffer() && w == backend.width && h == backend.height &&
               bytes == backend.getFrameBufferAllocationBytes());
        return external && primaryMatches;
    }
    uint64_t retainedExternalFrameBytesExcept(void* primary, void* spare) {
        auto& backend = VulkanBackend::getInstance();
        assert(primary == backend.getFrameBuffer() && spare == backend.getSpareFrameBuffer());
        ++spareQueries;
        return retainedBytes;
    }
    bool prepareSpareExternalFrame(void* buffer, unsigned w, unsigned h, uint64_t bytes) {
        auto& backend = VulkanBackend::getInstance();
        assert(backend.sparePrepared && buffer == backend.getSpareFrameBuffer() &&
               w == backend.width && h == backend.height &&
               bytes == backend.getSpareFrameBufferAllocationBytes());
        ++spareImports;
        if (spareImportAccepted) spareImported = true;
        return spareImportAccepted;
    }
    bool releaseSpareExternalFrame() {
        ++spareDrops;
        if (!spareReleaseAccepted) return false;
        spareImported = false;
        return true;
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
    bool prepareSpareVulkanFrame();
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

void bridgeIdentityContracts() {
    for (bool fenceSupported : {false, true}) {
        auto& backend = VulkanBackend::getInstance();
        backend = {};
        backend.frameConfirmed = true;
        backend.readFenceSupported = fenceSupported;
        backend.readFenceAccepted = true;
        Video video;
        video.primaryMatches = false;
        LibretroDroid host {&video};
        const unsigned before = glFinishes;
        host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
        assert(backend.fatal && backend.rejections == 1 && !backend.frameConfirmed);
        assert(!video.fenceExports && !backend.fenceImports && !backend.submissions);
        assert(!video.frameCalls && !video.transferUpdates && glFinishes == before);
    }
    std::puts("PASS bridge identity preflight precedes fence export and CPU fallback");
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

void dualSlotIngressContracts() {
    for (unsigned bits = 0; bits < 32; ++bits) {
        auto& backend = VulkanBackend::getInstance();
        backend = {};
        backend.dual = true;
        backend.coreIndex = 1;
        backend.writeFenceSupported = true;
        backend.writeWaitAccepted = bits & 1;
        backend.readFenceSupported = bits & 2;
        backend.readFenceAccepted = true;
        Video video;
        video.spareImportAccepted = bits & 4;
        video.writeFenceSupported = true;
        video.writeFenceAccepted = bits & 8;
        video.spareMatches = bits & 16;
        LibretroDroid host {&video};
        host.attachVulkanFrame(256, 240);
        assert(backend.spareImported == video.spareImportAccepted && !backend.fatal);
        assert(video.selected == 0 && !video.publications);
        const bool match = !backend.spareImported || video.spareMatches;
        const bool waitFails = match && !video.writeFenceAccepted && !backend.writeWaitAccepted;
        host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
        assert(backend.submissions == unsigned(match));
        assert(video.publications == unsigned(match && !waitFails));
        assert(video.frameCalls == video.publications && video.transferUpdates == video.publications);
        assert(backend.cpuWriteWaits == unsigned(match && !video.writeFenceAccepted));
        assert(backend.fatal == (!match || waitFails));
        if (match && !waitFails) {
            assert(video.selected == unsigned(backend.spareImported));
            const auto oldSelected = video.selected;
            host.handleVideoRefresh(nullptr, 256, 224, 1024);
            assert(video.selected == oldSelected && video.publications == 1 && backend.duplicates == 1);
        }
    }
    // Refusal to publish a completed frame must not update source metadata.
    auto& backend = VulkanBackend::getInstance();
    backend = {};
    Video video;
    video.publishAccepted = false;
    LibretroDroid host {&video};
    host.handleVideoRefresh(RETRO_HW_FRAME_BUFFER_VALID, 256, 224, 1024);
    assert(backend.fatal && backend.submissions == 1 && !video.frameCalls && !video.transferUpdates);
    std::puts("PASS dual-slot ingress, optional fallback, write proof before publication and duplicate selection");
}

void optionalSpareImportContracts() {
    for (unsigned bits = 0; bits < 256; ++bits) {
        auto& backend = VulkanBackend::getInstance();
        backend = {};
        Video video;
        video.primaryMatches = bits & 1;
        backend.fatal = bits & 2;
        backend.spareAdmitted = bits & 4;
        video.spareImportAccepted = bits & 8;
        video.spareReleaseAccepted = bits & 16;
        backend.spareRetireAccepted = bits & 32;
        backend.sparePrepared = bits & 64;
        video.external = bits & 128;
        LibretroDroid host {&video};
        const bool valid = video.external && video.primaryMatches && !backend.fatal;
        const bool allocated = valid && backend.spareAdmitted;
        const bool success = allocated && video.spareImportAccepted;
        const bool drop = allocated && !video.spareImportAccepted;
        assert(host.prepareSpareVulkanFrame() == success);
        assert(video.spareQueries == unsigned(valid) && backend.spareEnsures == unsigned(valid));
        assert(video.spareImports == unsigned(allocated) && video.spareDrops == unsigned(drop));
        assert(backend.spareReleases == unsigned(drop && video.spareReleaseAccepted));
        if (valid) assert(backend.spareBudgetSeen == video.retainedBytes);
        if (success) assert(video.spareImported && backend.sparePrepared);
        if (drop && video.spareReleaseAccepted && backend.spareRetireAccepted)
            assert(!backend.sparePrepared);
        assert(backend.fatal == bool(bits & 2) && !backend.rejections && !backend.submissions);
        assert(video.transfer == 2 && !video.frameCalls && video.width == 32 && video.height == 24);
    }
    assert(!LibretroDroid{nullptr}.prepareSpareVulkanFrame());
    for (bool releaseAccepted : {false, true}) {
        auto& backend = VulkanBackend::getInstance();
        backend = {};
        Video video;
        video.spareImported = true;
        video.spareReleaseAccepted = releaseAccepted;
        LibretroDroid host {&video};
        host.attachVulkanFrame(512, 480);
        assert(video.spareDrops == 1 && backend.drains == 1);
        assert(backend.allocations == unsigned(releaseAccepted));
        assert(video.spareImported != releaseAccepted && backend.fatal != releaseAccepted);
    }
    std::puts("PASS optional EGL spare coordination, retained budget and resize retirement");
}

int main() {
    optionalSpareImportContracts();
    dualSlotIngressContracts();
    writeFenceHandoffContracts();
    bridgeImportContracts();
    readFenceHandoffContracts();
    bridgeIdentityContracts();
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
