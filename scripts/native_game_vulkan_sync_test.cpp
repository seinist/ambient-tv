// SPDX-License-Identifier: GPL-3.0-or-later
// Real backend policy and declarations, with only Vulkan/Android dispatch and
// unrelated shader/color implementations controlled by this fault fixture.
#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#define private public
#include "vulkan/vulkanbackend.h"
#undef private
#include "utils/libretrodroidexception.h"

using libretrodroid::VulkanBackend;
template<class T> T object(uintptr_t value) { return reinterpret_cast<T>(value); }

struct Driver {
    std::deque<VkResult> waits, idles;
    VkResult reset = VK_SUCCESS, commandReset = VK_SUCCESS, begin = VK_SUCCESS,
             end = VK_SUCCESS, submit = VK_SUCCESS, present = VK_SUCCESS;
    bool pending = false;
    unsigned waitCalls = 0, resets = 0, idleCalls = 0, commandResets = 0,
             begins = 0, ends = 0, submits = 0, presents = 0, warnings = 0,
             imageFrees = 0, memoryFrees = 0, bufferFrees = 0, windowReleases = 0,
             cachePrepares = 0, cacheReleases = 0, slangReleases = 0,
             allocations = 0, deviceFrees = 0, coreDestroys = 0, swapchainCreates = 0,
             swapchainFrees = 0, commandPoolFrees = 0, fenceFrees = 0, colorClears = 0;
    VkCompositeAlphaFlagsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    VkCompositeAlphaFlagBitsKHR chosenAlpha = VK_COMPOSITE_ALPHA_FLAG_BITS_MAX_ENUM_KHR;
    VkSurfaceTransformFlagsKHR transforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    VkSurfaceTransformFlagBitsKHR chosenTransform = VK_SURFACE_TRANSFORM_FLAG_BITS_MAX_ENUM_KHR;
    VkResult next(std::deque<VkResult>& values) {
        if (values.empty()) return VK_SUCCESS;
        const auto value = values.front();
        values.pop_front();
        return value;
    }
} driver;

#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#define LOGW(...) (++driver.warnings)
void AHardwareBuffer_release(AHardwareBuffer*) { assert(!driver.pending); ++driver.bufferFrees; }
void ANativeWindow_release(ANativeWindow*) { ++driver.windowReleases; }
int ANativeWindow_getWidth(ANativeWindow*) { return 640; }
int ANativeWindow_getHeight(ANativeWindow*) { return 480; }

namespace libretrodroid {
constexpr uint64_t FENCE_TIMEOUT_NS = 5'000'000'000ULL;
struct Environment {
    static Environment& getInstance() { static Environment value; return value; }
    float getScreenRotation() const { return 0.0F; }
    double getGameTimingFps() const { return 60.0; }
};
// These subsystems have separate real Mesa tests. Here they expose whether the
// production backend touched or freed them before the frame lifetime permitted it.
VulkanSlangPipeline::~VulkanSlangPipeline() = default;
void VulkanSlangPipeline::releaseGpu() { assert(!driver.pending); ++driver.slangReleases; }
void VulkanSlangPipeline::setPresetPath(const std::string& value) { presetPath = value; }
bool VulkanSlangPipeline::ensure(VkInstance, VkPhysicalDevice, VkDevice, VkQueue, PFN_vkGetInstanceProcAddr) {
    return true;
}
bool VulkanSlangPipeline::record(VkCommandBuffer, uint64_t, VkImage, VkFormat, uint32_t, uint32_t,
    VkImage, VkFormat, uint32_t, uint32_t, float, float, uint32_t, uint32_t, float, double) {
    return true;
}
bool VulkanColorCopy::validSubresource(const Source&) { return true; }
bool VulkanColorCopy::initialize(VkInstance, VkPhysicalDevice, VkDevice, PFN_vkGetInstanceProcAddr) {
    return true;
}
bool VulkanColorCopy::prepare(const Source& source, VkFormat, uint32_t, uint32_t) {
    assert(!driver.pending);
    ++driver.cachePrepares;
    width = source.width; height = source.height;
    return true;
}
VulkanColorCopy::Image VulkanColorCopy::preparedImage(VkImage bridge) const {
    return {bridge, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, width, height, true};
}
VulkanColorCopy::Image VulkanColorCopy::record(VkCommandBuffer, VkImage, VkImageLayout,
    const Source&, VkImage bridge) { return preparedImage(bridge); }
void VulkanColorCopy::release() { assert(!driver.pending); ++driver.cacheReleases; }
bool VulkanBackend::createFrameBuffer(unsigned width, unsigned height, bool fp16) {
    assert(!driver.pending);
    ++driver.allocations;
    frameWidth = width; frameHeight = height; fp16Frame = fp16;
    return true;
}
void VulkanBackend::destroyPresentationSurface() { assert(!driver.pending); }

// The runner extracts these complete bodies without editing their implementation.
#include "vulkan_sync_under_test.h"

struct Video {
    unsigned mainBinds = 0;
    bool isHWAccelerated() const { return true; }
    void bindMainContext() { ++mainBinds; }
};
struct LibretroDroid {
    Video* video;
    void checkVulkanFrameError();
};
int ERROR_GENERIC = -1;
#include "vulkan_error_boundary_under_test.h"
} // namespace libretrodroid

void setup(VulkanBackend& backend) {
    driver = {};
    backend.device = object<VkDevice>(1);
    backend.instance = object<VkInstance>(2);
    backend.gpu = object<VkPhysicalDevice>(3);
    backend.queue = object<VkQueue>(4);
    backend.commandBuffer = object<VkCommandBuffer>(5);
    backend.frameFence = object<VkFence>(6);
    backend.commandPool = object<VkCommandPool>(7);
    backend.frameImage = object<VkImage>(8);
    backend.frameMemory = object<VkDeviceMemory>(9);
    backend.frameBuffer = object<AHardwareBuffer*>(10);
    backend.frameWidth = 256; backend.frameHeight = 240;
    backend.pendingImage.image = object<VkImage>(11);
    backend.pendingImage.format = VK_FORMAT_R8G8B8A8_UNORM;
    backend.pendingImage.layout = VK_IMAGE_LAYOUT_GENERAL;
    backend.pendingImage.srcQueueFamily = VK_QUEUE_FAMILY_IGNORED;
    backend.surface = object<VkSurfaceKHR>(12);
    backend.surfaceExtensionsEnabled = true;
    auto& vk = backend.vk;
    vk.waitForFences = [](VkDevice, uint32_t, const VkFence*, VkBool32, uint64_t) {
        ++driver.waitCalls;
        const auto value = driver.next(driver.waits);
        if (value == VK_SUCCESS || value == VK_ERROR_DEVICE_LOST) driver.pending = false;
        return value;
    };
    vk.resetFences = [](VkDevice, uint32_t, const VkFence*) {
        assert(!driver.pending); ++driver.resets; return driver.reset;
    };
    vk.deviceWaitIdle = [](VkDevice) {
        ++driver.idleCalls;
        const auto value = driver.next(driver.idles);
        if (value == VK_SUCCESS || value == VK_ERROR_DEVICE_LOST) driver.pending = false;
        return value;
    };
    vk.resetCommandBuffer = [](VkCommandBuffer, VkCommandBufferResetFlags) {
        assert(!driver.pending); ++driver.commandResets; return driver.commandReset;
    };
    vk.beginCommandBuffer = [](VkCommandBuffer, const VkCommandBufferBeginInfo*) {
        assert(!driver.pending); ++driver.begins; return driver.begin;
    };
    vk.endCommandBuffer = [](VkCommandBuffer) { ++driver.ends; return driver.end; };
    vk.queueSubmit = [](VkQueue, uint32_t, const VkSubmitInfo*, VkFence) {
        assert(!driver.pending); ++driver.submits;
        if (driver.submit == VK_SUCCESS) driver.pending = true;
        return driver.submit;
    };
    vk.cmdPipelineBarrier = [](VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags,
        VkDependencyFlags, uint32_t, const VkMemoryBarrier*, uint32_t, const VkBufferMemoryBarrier*,
        uint32_t, const VkImageMemoryBarrier*) {};
    vk.cmdClearColorImage = [](VkCommandBuffer, VkImage, VkImageLayout, const VkClearColorValue*,
        uint32_t, const VkImageSubresourceRange*) { ++driver.colorClears; };
    vk.cmdBlitImage = [](VkCommandBuffer, VkImage, VkImageLayout, VkImage, VkImageLayout,
        uint32_t, const VkImageBlit*, VkFilter) {};
    vk.destroyImage = [](VkDevice, VkImage, const VkAllocationCallbacks*) {
        assert(!driver.pending); ++driver.imageFrees;
    };
    vk.freeMemory = [](VkDevice, VkDeviceMemory, const VkAllocationCallbacks*) {
        assert(!driver.pending); ++driver.memoryFrees;
    };
    vk.destroyFence = [](VkDevice, VkFence, const VkAllocationCallbacks*) {
        assert(!driver.pending); ++driver.fenceFrees;
    };
    vk.destroyCommandPool = [](VkDevice, VkCommandPool, const VkAllocationCallbacks*) {
        assert(!driver.pending); ++driver.commandPoolFrees;
    };
    vk.destroyDevice = [](VkDevice, const VkAllocationCallbacks*) {
        assert(!driver.pending); ++driver.deviceFrees;
    };
    vk.destroyInstance = [](VkInstance, const VkAllocationCallbacks*) {};
    vk.createAndroidSurface = [](VkInstance, const VkAndroidSurfaceCreateInfoKHR*,
        const VkAllocationCallbacks*, VkSurfaceKHR* surface) {
        *surface = object<VkSurfaceKHR>(13); return VK_SUCCESS;
    };
    vk.destroySurface = [](VkInstance, VkSurfaceKHR, const VkAllocationCallbacks*) { assert(!driver.pending); };
    vk.getPhysicalDeviceSurfaceSupport = [](VkPhysicalDevice, uint32_t, VkSurfaceKHR, VkBool32* supported) {
        *supported = VK_TRUE; return VK_SUCCESS;
    };
    vk.getPhysicalDeviceSurfaceCapabilities = [](VkPhysicalDevice, VkSurfaceKHR, VkSurfaceCapabilitiesKHR* caps) {
        *caps = {};
        caps->supportedUsageFlags = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        caps->supportedTransforms = driver.transforms;
        caps->currentTransform = VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR;
        caps->currentExtent = {640,480};
        caps->supportedCompositeAlpha = driver.alpha;
        caps->minImageCount = 2; caps->maxImageCount = 3;
        return VK_SUCCESS;
    };
    vk.getPhysicalDeviceSurfaceFormats = [](VkPhysicalDevice, VkSurfaceKHR, uint32_t* count,
        VkSurfaceFormatKHR* formats) {
        *count = 1;
        if (formats) *formats = {VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
        return VK_SUCCESS;
    };
    vk.getPhysicalDeviceFormatProperties = [](VkPhysicalDevice, VkFormat, VkFormatProperties* properties) {
        *properties = {};
        properties->optimalTilingFeatures = VK_FORMAT_FEATURE_BLIT_DST_BIT |
            VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    };
    vk.createSwapchain = [](VkDevice, const VkSwapchainCreateInfoKHR* info, const VkAllocationCallbacks*,
        VkSwapchainKHR* swapchain) {
        assert(info->minImageCount == 2); // Request no extra FIFO image beyond driver minimum.
        ++driver.swapchainCreates; driver.chosenAlpha = info->compositeAlpha;
        driver.chosenTransform = info->preTransform;
        *swapchain = object<VkSwapchainKHR>(14); return VK_SUCCESS;
    };
    vk.destroySwapchain = [](VkDevice, VkSwapchainKHR, const VkAllocationCallbacks*) {
        assert(!driver.pending); ++driver.swapchainFrees;
    };
    vk.getSwapchainImages = [](VkDevice, VkSwapchainKHR, uint32_t* count, VkImage* images) {
        *count = 2;
        if (images) { images[0] = object<VkImage>(15); images[1] = object<VkImage>(16); }
        return VK_SUCCESS;
    };
    vk.createSemaphore = [](VkDevice, const VkSemaphoreCreateInfo*, const VkAllocationCallbacks*, VkSemaphore* value) {
        *value = object<VkSemaphore>(17); return VK_SUCCESS;
    };
    vk.destroySemaphore = [](VkDevice, VkSemaphore, const VkAllocationCallbacks*) { assert(!driver.pending); };
    vk.acquireNextImage = [](VkDevice, VkSwapchainKHR, uint64_t, VkSemaphore, VkFence, uint32_t* index) {
        *index = 0; return VK_SUCCESS;
    };
    vk.queuePresent = [](VkQueue, const VkPresentInfoKHR*) {
        assert(!driver.pending); ++driver.presents; return driver.present;
    };
}

void pendingFrame(VulkanBackend& backend) {
    backend.frameInFlight = true;
    driver.pending = true;
}
void window(VulkanBackend& backend) { backend.nativeSdrWindow = object<ANativeWindow*>(18); }

void timeoutsPreserveWork() {
    VulkanBackend backend;
    setup(backend); pendingFrame(backend);
    driver.waits = {VK_TIMEOUT, VK_TIMEOUT, VK_SUCCESS};
    assert(backend.waitForFrame());
    assert(driver.waitCalls == 3 && driver.warnings == 1 && driver.resets == 1);
    assert(!backend.frameInFlight && !backend.hasFatalError());
    assert(driver.commandResets == 0 && driver.imageFrees == 0);
    assert(backend.waitForFrame() && driver.waitCalls == 3);
    std::puts("PASS Vulkan repeated timeout preserves ownership until actual completion");
}

void errorsStopWorkAndResize() {
    for (VkResult failure : {VK_ERROR_DEVICE_LOST, VK_ERROR_OUT_OF_HOST_MEMORY,
                              VK_ERROR_OUT_OF_DEVICE_MEMORY, VK_ERROR_UNKNOWN}) {
        VulkanBackend backend;
        setup(backend); pendingFrame(backend); window(backend);
        backend.nativePresentation.setFullscreenActive(true);
        backend.nativePresentation.didPresentFrame();
        driver.waits = {failure};
        assert(!backend.waitForFrame());
        assert(backend.frameInFlight && backend.getFatalError() == failure && driver.resets == 0);
        assert(backend.getNativeSdrStatus() == 9);
        assert(backend.primaryDraw(false, true) != libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
        assert(!backend.waitForFrame() && driver.waitCalls == 1);
        assert(!backend.presentFrame(256,224,4.0F/3.0F,true));
        assert(!backend.clearFrameImage());
        assert(!backend.ensureFrameBuffer(512,480));
        assert(!backend.destroyFrameBuffer());
        backend.setNativeShaderPresetPath("changed");
        backend.setNativeOutputTransfer(1);
        backend.setNativeCoreSourceTransfer(1);
        assert(driver.cachePrepares == 0 && driver.allocations == 0 &&
               driver.commandResets == 0 && driver.submits == 0 &&
               driver.imageFrees == 0 && driver.cacheReleases == 0);
    }
    std::puts("PASS Vulkan wait failures latch once and stop present/clear/resize/filter changes");
}

void resetAndSubmitFailures() {
    for (unsigned operation = 0; operation != 3; ++operation) {
        VulkanBackend backend;
        setup(backend); pendingFrame(backend);
        driver.waits = {VK_ERROR_OUT_OF_HOST_MEMORY};
        const bool result = operation == 0 ? backend.presentFrame(256,224,4.0F/3.0F,true) :
            operation == 1 ? backend.clearFrameImage() : backend.ensureFrameBuffer(512,480);
        assert(!result && backend.hasFatalError() && backend.frameInFlight);
        assert(driver.cachePrepares == 0 && driver.commandResets == 0 &&
               driver.allocations == 0 && driver.imageFrees == 0 && driver.submits == 0);
    }
    for (bool clear : {false,true}) {
        for (unsigned phase = 0; phase != 5; ++phase) {
            VulkanBackend backend;
            setup(backend); window(backend);
            const VkResult failure = VK_ERROR_OUT_OF_HOST_MEMORY;
            if (phase == 0) driver.commandReset = failure;
            if (phase == 1) driver.begin = failure;
            if (phase == 2) driver.end = failure;
            if (phase == 3) driver.submit = failure;
            if (phase == 4) driver.reset = failure;
            const bool result = clear ? backend.clearFrameImage() :
                backend.presentFrame(256,224,4.0F/3.0F,true);
            assert(!result && backend.getFatalError() == failure);
            assert(driver.presents == 0);
            assert(driver.submits == (phase >= 3 ? 1u : 0u));
            assert(driver.resets == (phase == 4 ? 1u : 0u));
            assert(backend.frameInFlight == (phase == 4));
            const unsigned previous = driver.commandResets;
            assert(!backend.presentFrame(256,224,4.0F/3.0F,true));
            assert(!backend.clearFrameImage() && driver.commandResets == previous);
        }
    }
    std::puts("PASS Vulkan real clear/present command, submit and fence-reset failures abort safely");
}

void safeCallbackAndTeardown() {
    for (VkResult completed : {VK_SUCCESS,VK_ERROR_DEVICE_LOST}) {
        VulkanBackend backend;
        setup(backend); pendingFrame(backend);
        driver.waits = {VK_ERROR_OUT_OF_HOST_MEMORY};
        driver.idles = {VK_ERROR_OUT_OF_DEVICE_MEMORY, VK_ERROR_UNKNOWN, completed};
        VulkanBackend::callbackWaitSyncIndex(&backend);
        assert(driver.idleCalls == 3 && driver.resets == 0 && !driver.pending);
        assert(!backend.frameInFlight && backend.getFatalError() == VK_ERROR_OUT_OF_HOST_MEMORY);
        assert(!backend.waitForFrame()); // safe retirement never clears the fatal latch
    }
    {
        VulkanBackend backend;
        setup(backend); pendingFrame(backend); window(backend);
        backend.nativeSdrSwapchain = object<VkSwapchainKHR>(19);
        driver.waits = {VK_ERROR_UNKNOWN};
        assert(!backend.waitForFrame());
        driver.idles = {VK_ERROR_OUT_OF_HOST_MEMORY, VK_ERROR_OUT_OF_HOST_MEMORY,
                        VK_ERROR_UNKNOWN, VK_ERROR_UNKNOWN};
        assert(!backend.prepareForShutdown());
        assert(!backend.destroyNativeSdrSwapchain());
        backend.destroyCoreDevice();
        backend.shutdown();
        assert(driver.idleCalls == 4 && backend.device != VK_NULL_HANDLE &&
               backend.frameImage != VK_NULL_HANDLE && backend.frameBuffer != nullptr);
        assert(driver.imageFrees == 0 && driver.bufferFrees == 0 &&
               driver.cacheReleases == 0 && driver.slangReleases == 0 &&
               driver.deviceFrees == 0 && driver.swapchainFrees == 0);
        driver.idles = {VK_SUCCESS};
        assert(backend.prepareForShutdown());
        backend.shutdown();
        assert(backend.device == VK_NULL_HANDLE && !backend.hasFatalError() && !backend.shutdownReady);
        assert(driver.deviceFrees == 1 && driver.imageFrees == 1 && driver.bufferFrees == 1 &&
               driver.cacheReleases == 1 && driver.swapchainFrees == 1 &&
               driver.fenceFrees == 1 && driver.commandPoolFrees == 1);
    }
    {
        VulkanBackend backend;
        setup(backend); pendingFrame(backend);
        driver.waits = {VK_ERROR_DEVICE_LOST};
        assert(!backend.waitForFrame());
        driver.idles = {VK_ERROR_DEVICE_LOST};
        assert(backend.prepareForShutdown());
        assert(backend.hasFatalError());
        backend.shutdown();
        assert(driver.deviceFrees == 1 && !backend.hasFatalError());
    }
    std::puts("PASS Vulkan void callback retains ownership until idle; teardown retains unsafe resources");
}

void nativeSurfacePolicyAndSuccess() {
    for (bool filtered : {false,true}) {
        for (VkCompositeAlphaFlagBitsKHR alpha : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR}) {
            VulkanBackend backend;
            setup(backend); window(backend); driver.alpha = alpha;
            if (filtered) backend.nativeSlang.setPresetPath("preset");
            const bool allowed = alpha == VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            assert(backend.ensureNativeSdrSwapchain() == allowed);
            assert(driver.swapchainCreates == (allowed ? 1u : 0u));
            if (allowed) {
                assert(driver.chosenAlpha == VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
                assert(driver.chosenTransform == VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR);
            }
        }
    }
    {
        VulkanBackend backend;
        setup(backend); window(backend);
        driver.transforms = VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR;
        assert(!backend.ensureNativeSdrSwapchain() && driver.swapchainCreates == 0);
    }
    {
        VulkanBackend backend;
        setup(backend); window(backend);
        backend.nativePresentation.setFullscreenActive(true);
        driver.waits = {VK_TIMEOUT, VK_SUCCESS};
        assert(backend.presentFrame(256,224,4.0F/3.0F,true));
        assert(driver.submits == 1 && driver.waitCalls == 2 && driver.resets == 1 && driver.presents == 1);
        assert(backend.primaryDraw(false,true) == libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
    }
    {
        VulkanBackend backend;
        setup(backend); window(backend);
        driver.present = VK_ERROR_DEVICE_LOST;
        assert(!backend.presentFrame(256,224,4.0F/3.0F,true));
        assert(backend.getFatalError() == VK_ERROR_DEVICE_LOST);
    }
    std::puts("PASS raw/filtered WSI requires opaque identity surfaces; only completed frames present");
}

void filteredPresetSwitchRetainsSwapchain() {
    VulkanBackend backend;
    setup(backend); window(backend);
    backend.setNativeShaderPresetPath("shader-a");
    assert(backend.ensureNativeSdrSwapchain());
    assert(driver.swapchainCreates == 1 && driver.swapchainFrees == 0);
    backend.nativePresentation.setFullscreenActive(true);
    assert(backend.presentFrame(256, 224, 4.0F/3.0F, true));
    assert(driver.colorClears == 0 && driver.presents == 1);
    assert(backend.primaryDraw(false, true) ==
           libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
    const unsigned oldReleases = driver.slangReleases;
    backend.setNativeShaderPresetPath("shader-b");
    assert(driver.idleCalls >= 1 && driver.slangReleases > oldReleases);
    assert(driver.swapchainCreates == 1 && driver.swapchainFrees == 0);
    assert(backend.getNativeSdrStatus() == 3u);
    assert(backend.primaryDraw(false, true) !=
           libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
    assert(backend.presentFrame(256, 224, 4.0F/3.0F, true));
    assert(driver.colorClears == 0 && driver.presents == 2);
    backend.setNativeShaderPresetPath("");
    assert(driver.swapchainFrees == 1);
    assert(backend.ensureNativeSdrSwapchain());
    assert(driver.swapchainCreates == 2);
    assert(backend.presentFrame(256, 224, 4.0F/3.0F, true));
    assert(driver.colorClears == 1);
    std::puts("PASS filtered preset switches retain WSI and skip duplicate clear; raw restores clear");
}

void managedBoundary() {
    auto& backend = VulkanBackend::getInstance();
    libretrodroid::Video video;
    libretrodroid::LibretroDroid host {&video};
    host.checkVulkanFrameError();
    backend.fatalFrameError = VK_ERROR_DEVICE_LOST;
    bool caught = false;
    try { host.checkVulkanFrameError(); }
    catch (libretrodroid::LibretroDroidError& error) {
        caught = true;
        assert(error.getErrorCode() == libretrodroid::ERROR_GENERIC);
    }
    assert(caught && video.mainBinds == 1 && backend.hasFatalError());
    backend.fatalFrameError = VK_SUCCESS;
    std::puts("PASS production frontend boundary raises typed managed error after returning from core");
}

int main() {
    timeoutsPreserveWork();
    errorsStopWorkAndResize();
    resetAndSubmitFailures();
    safeCallbackAndTeardown();
    nativeSurfacePolicyAndSuccess();
    filteredPresetSwitchRetainsSwapchain();
    managedBoundary();
    std::puts("PASS production Vulkan synchronization and presentation fault regression");
}
