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
#include <type_traits>
#include <vector>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#define private public
#include "vulkan/vulkanbackend.h"
#undef private
#include "utils/libretrodroidexception.h"
#include "ambientgametrace.h"

using libretrodroid::VulkanBackend;
template<class T> T object(uintptr_t value) { return reinterpret_cast<T>(value); }

struct Driver {
    std::deque<VkResult> waits, idles;
    VkResult reset = VK_SUCCESS, commandReset = VK_SUCCESS, begin = VK_SUCCESS,
             end = VK_SUCCESS, submit = VK_SUCCESS, present = VK_SUCCESS;
    bool pending = false;
    bool slangRecordAccepted = true;
    std::vector<VkCommandBuffer> submittedCommands;
    std::vector<VkSemaphore> submittedWaits, submittedSignals;
    std::vector<VkPipelineStageFlags> submittedStages;
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

struct BridgeAllocationMock {
    unsigned failure = 0, ahbAllocations = 0, propertyQueries = 0, imageCreates = 0,
             memoryAllocations = 0, binds = 0;
    VkDeviceSize allocationBytes = 4096;
} bridgeAllocation;

#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#define LOGW(...) (++driver.warnings)
int AHardwareBuffer_allocate(const AHardwareBuffer_Desc* desc, AHardwareBuffer** buffer) {
    assert(desc && desc->width && desc->height && desc->layers == 1);
    ++bridgeAllocation.ahbAllocations;
    *buffer = object<AHardwareBuffer*>(101);
    return bridgeAllocation.failure == 1 ? -1 : 0;
}
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
bool VulkanSlangPipeline::ensure(VkInstance, VkPhysicalDevice, VkDevice, VkQueue, PFN_vkGetInstanceProcAddr,
                                 const SlangFrameGeometry*) {
    return true;
}
bool VulkanSlangPipeline::record(VkCommandBuffer, uint64_t, VkImage, VkFormat, uint32_t, uint32_t,
    VkImage, VkFormat, uint32_t, uint32_t, float, float, uint32_t, uint32_t, float, double) {
    return driver.slangRecordAccepted;
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
VkFormatFeatureFlags VulkanColorCopy::optimalFeatures(VkFormat) {
    return VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT |
           VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
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
    frameSlot.bridgeImage.width = width; frameSlot.bridgeImage.height = height; frameSlot.bridgeImage.fp16 = fp16;
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
    backend.frameSlot.command = object<VkCommandBuffer>(5);
    backend.frameSlot.fence = object<VkFence>(6);
    backend.frameSlot.pool = object<VkCommandPool>(7);
    backend.frameSlot.bridgeImage.image = object<VkImage>(8);
    backend.frameSlot.bridgeImage.memory = object<VkDeviceMemory>(9);
    backend.frameSlot.bridgeImage.buffer = object<AHardwareBuffer*>(10);
    backend.frameSlot.bridgeImage.width = 256; backend.frameSlot.bridgeImage.height = 240;
    backend.frameSlot.bridgeImage.allocationBytes = 256 * 240 * 4;
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
    vk.queueSubmit = [](VkQueue, uint32_t count, const VkSubmitInfo* info, VkFence) {
        assert(!driver.pending); ++driver.submits;
        assert(count == 1 && info != nullptr);
        driver.submittedCommands.clear();
        driver.submittedWaits.clear();
        driver.submittedStages.clear();
        driver.submittedSignals.clear();
        if (info->commandBufferCount != 0)
            driver.submittedCommands.assign(info->pCommandBuffers, info->pCommandBuffers + info->commandBufferCount);
        if (info->waitSemaphoreCount != 0) {
            driver.submittedWaits.assign(info->pWaitSemaphores, info->pWaitSemaphores + info->waitSemaphoreCount);
            driver.submittedStages.assign(info->pWaitDstStageMask, info->pWaitDstStageMask + info->waitSemaphoreCount);
        }
        if (info->signalSemaphoreCount != 0)
            driver.submittedSignals.assign(info->pSignalSemaphores, info->pSignalSemaphores + info->signalSemaphoreCount);
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
    vk.queuePresent = [](VkQueue, const VkPresentInfoKHR* info) {
        // WSI consumes the submitted per-image semaphore before displaying,
        // so host fence completion need not precede queuing presentation.
        assert(driver.pending && info && info->waitSemaphoreCount == 1 &&
               info->swapchainCount == 1 && info->pImageIndices);
        assert(std::find(driver.submittedSignals.begin(), driver.submittedSignals.end(),
                         *info->pWaitSemaphores) != driver.submittedSignals.end());
        ++driver.presents;
        return driver.present;
    };
}

void pendingFrame(VulkanBackend& backend) {
    backend.frameSlot.inFlight = true;
    driver.pending = true;
}
void window(VulkanBackend& backend) { backend.nativeSdrWindow = object<ANativeWindow*>(18); }

void dualSlotRetirementContracts() {
    static unsigned semaphoreFrees;
    for (unsigned mode = 0; mode < 5; ++mode) {
        VulkanBackend backend;
        setup(backend);
        auto& spare = backend.spareFrameSlot;
        spare.pool = object<VkCommandPool>(301);
        spare.command = object<VkCommandBuffer>(302);
        spare.fence = object<VkFence>(303);
        spare.bridgeImage.image = object<VkImage>(304);
        spare.bridgeImage.memory = object<VkDeviceMemory>(305);
        spare.bridgeImage.buffer = object<AHardwareBuffer*>(306);
        spare.bridgeImage.width = spare.bridgeImage.height = 32;
        spare.bridgeImage.allocationBytes = 4096;
        backend.frameSlot.bridgeSync.readSemaphore = object<VkSemaphore>(307);
        spare.bridgeSync.writeSemaphore = object<VkSemaphore>(308);
        semaphoreFrees = 0;
        backend.vk.destroySemaphore = [](VkDevice, VkSemaphore semaphore, const VkAllocationCallbacks*) {
            assert(!driver.pending && (semaphore == object<VkSemaphore>(307) ||
                                       semaphore == object<VkSemaphore>(308)));
            ++semaphoreFrees;
        };
        backend.frameSlot.inFlight = spare.inFlight = driver.pending = true;
        window(backend);
        backend.nativeSdrSwapchain = object<VkSwapchainKHR>(19);
        if (mode == 0) {
            driver.idles = {VK_ERROR_OUT_OF_HOST_MEMORY};
            backend.shutdown();
            assert(backend.frameSlot.inFlight && spare.inFlight && driver.pending);
        } else if (mode == 1) {
            spare.bridgeSync.writeSemaphore = backend.frameSlot.bridgeSync.readSemaphore;
            backend.shutdown();
            assert(!backend.frameSlot.inFlight && !spare.inFlight && !driver.pending);
            spare.bridgeSync.writeSemaphore = object<VkSemaphore>(308);
        } else if (mode == 2) {
            spare.pool = VK_NULL_HANDLE; // only the spare has an invalid ownership record
            backend.shutdown();
            assert(!backend.frameSlot.inFlight && !spare.inFlight);
            spare.pool = object<VkCommandPool>(301);
        } else if (mode == 3) {
            const auto destroyDevice = backend.vk.destroyDevice;
            backend.vk.destroyDevice = nullptr;
            backend.shutdown();
            assert(!backend.frameSlot.inFlight && !spare.inFlight);
            backend.vk.destroyDevice = destroyDevice;
        } else {
            const auto device = backend.device;
            backend.device = VK_NULL_HANDLE;
            assert(!backend.prepareForShutdown());
            assert(backend.frameSlot.inFlight && spare.inFlight && driver.pending);
            backend.device = device;
        }
        assert(backend.device && !backend.shutdownReady && backend.nativeSdrSwapchain);
        assert(backend.frameSlot.bridgeImage.buffer && spare.bridgeImage.buffer);
        assert(driver.imageFrees == 0 && driver.memoryFrees == 0 && driver.bufferFrees == 0 &&
               driver.fenceFrees == 0 && driver.commandPoolFrees == 0 && driver.cacheReleases == 0 &&
               driver.swapchainFrees == 0 && driver.slangReleases == 0 && driver.deviceFrees == 0 &&
               semaphoreFrees == 0);
        driver.idles = {mode == 4 ? VK_ERROR_DEVICE_LOST : VK_SUCCESS};
        backend.shutdown();
        assert(!backend.device && !backend.shutdownReady && !backend.hasFatalError());
        assert(!backend.frameSlot.inFlight && !spare.inFlight);
        assert(backend.frameSlot.bridgeImage.empty() && spare.bridgeImage.empty());
        assert(backend.frameSlot.bridgeSync.empty() && spare.bridgeSync.empty());
        assert(!backend.frameSlot.pool && !spare.pool && !backend.frameSlot.fence && !spare.fence);
        assert(driver.imageFrees == 2 && driver.memoryFrees == 2 && driver.bufferFrees == 2 &&
               driver.fenceFrees == 2 && driver.commandPoolFrees == 2 && driver.cacheReleases == 2 &&
               driver.swapchainFrees == 1 && driver.deviceFrees == 1 && semaphoreFrees == 2);
    }
    for (bool failure : {false, true}) {
        VulkanBackend backend;
        setup(backend);
        backend.spareFrameSlot.fence = object<VkFence>(401);
        backend.spareFrameSlot.inFlight = driver.pending = true;
        backend.vk.waitForFences = [](VkDevice, uint32_t count, const VkFence* fences, VkBool32, uint64_t) {
            assert(count == 1 && *fences == object<VkFence>(401));
            ++driver.waitCalls;
            auto result = driver.next(driver.waits);
            if (result == VK_SUCCESS || result == VK_ERROR_DEVICE_LOST) driver.pending = false;
            return result;
        };
        if (failure) {
            driver.waits = {VK_ERROR_OUT_OF_HOST_MEMORY};
            driver.idles = {VK_ERROR_DEVICE_LOST};
        }
        VulkanBackend::callbackWaitSyncIndex(&backend);
        assert(driver.waitCalls == 1 && !backend.spareFrameSlot.inFlight && !driver.pending);
        assert(backend.hasFatalError() == failure && driver.idleCalls == (failure ? 1u : 0u));
        assert(driver.resets == (failure ? 0u : 1u));
    }
    std::puts("PASS dual-slot shutdown preflight, exact retirement and sync-index-zero drain");
}

void slotFenceAndClearContracts() {
    using Slot = VulkanBackend::FrameSlot;
    static VkFence expectedFence;
    static VkCommandBuffer expectedCommand;
    static VkImage expectedImage;
    static unsigned clears;
    for (unsigned mode = 0; mode < 7; ++mode) {
        VulkanBackend backend;
        setup(backend);
        Slot second;
        second.pool = object<VkCommandPool>(201);
        second.command = expectedCommand = object<VkCommandBuffer>(202);
        second.fence = expectedFence = object<VkFence>(203);
        second.bridgeImage.image = expectedImage = object<VkImage>(204);
        second.bridgeImage.memory = object<VkDeviceMemory>(205);
        second.bridgeImage.buffer = object<AHardwareBuffer*>(206);
        second.bridgeImage.width = second.bridgeImage.height = 32;
        second.bridgeImage.allocationBytes = 4096;
        const auto primaryFence = backend.frameSlot.fence;
        const auto primaryImage = backend.frameSlot.bridgeImage.image;
        auto& vk = backend.vk;
        clears = 0;
        vk.waitForFences = [](VkDevice, uint32_t count, const VkFence* fences, VkBool32 all, uint64_t) {
            assert(count == 1 && *fences == expectedFence && all == VK_TRUE);
            ++driver.waitCalls;
            auto result = driver.next(driver.waits);
            if (result == VK_SUCCESS || result == VK_ERROR_DEVICE_LOST) driver.pending = false;
            return result;
        };
        vk.resetFences = [](VkDevice, uint32_t count, const VkFence* fences) {
            assert(!driver.pending && count == 1 && *fences == expectedFence);
            ++driver.resets;
            return driver.reset;
        };
        vk.resetCommandBuffer = [](VkCommandBuffer command, VkCommandBufferResetFlags) {
            assert(command == expectedCommand && !driver.pending);
            ++driver.commandResets;
            return VK_SUCCESS;
        };
        vk.cmdClearColorImage = [](VkCommandBuffer command, VkImage image, VkImageLayout layout,
                const VkClearColorValue*, uint32_t count, const VkImageSubresourceRange*) {
            assert(command == expectedCommand && image == expectedImage &&
                   layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && count == 1);
            ++clears;
        };
        vk.queueSubmit = [](VkQueue, uint32_t count, const VkSubmitInfo* info, VkFence fence) {
            assert(!driver.pending && count == 1 && fence == expectedFence &&
                   info->commandBufferCount == 1 && *info->pCommandBuffers == expectedCommand);
            ++driver.submits;
            driver.pending = true;
            return VK_SUCCESS;
        };
        if (mode == 0 || mode == 1) {
            driver.waits = mode == 0 ? std::deque<VkResult>{VK_TIMEOUT, VK_TIMEOUT, VK_SUCCESS}
                                    : std::deque<VkResult>{VK_ERROR_DEVICE_LOST};
            assert(backend.clearSlotImage(second) == (mode == 0));
            assert(clears == 1 && driver.submits == 1 && driver.commandResets == 1);
            assert(second.inFlight == (mode == 1));
            assert(driver.waitCalls == (mode == 0 ? 3u : 1u) && driver.resets == (mode == 0 ? 1u : 0u));
        } else if (mode == 2 || mode == 3 || mode == 4) {
            // The synthetic primary's flag must not be modified by the other slot's wait.
            backend.frameSlot.inFlight = true;
            second.inFlight = true;
            driver.pending = true;
            if (mode == 3) driver.reset = VK_ERROR_OUT_OF_HOST_MEMORY;
            if (mode == 4) second.fence = VK_NULL_HANDLE;
            assert(backend.waitForSlotFrame(second) == (mode == 2));
            assert(second.inFlight == (mode != 2) && backend.frameSlot.inFlight);
            assert(driver.waitCalls == (mode == 4 ? 0u : 1u));
            assert(driver.resets == (mode == 4 ? 0u : 1u));
        } else if (mode == 5) {
            second.bridgeImage.image = VK_NULL_HANDLE;
            assert(!backend.clearSlotImage(second) && backend.hasFatalError());
            assert(!second.inFlight && driver.commandResets == 0 && driver.submits == 0);
        } else {
            assert(backend.waitForSlotFrame(second));
            assert(driver.waitCalls == 0 && driver.resets == 0);
        }
        assert(backend.frameSlot.fence == primaryFence && backend.frameSlot.bridgeImage.image == primaryImage);
        assert(backend.frameSlot.inFlight == (mode >= 2 && mode <= 4));
        assert(driver.imageFrees == 0 && driver.memoryFrees == 0 && driver.bufferFrees == 0);
        // These are mock handles. Simulate device-idle before retiring the
        // unowned test slot; production still has only its single frameSlot.
        driver.pending = false;
        second.inFlight = false;
        assert(second.bridgeImage.retire(false, true, true, true,
            [&](VkImage image) { vk.destroyImage(backend.device, image, nullptr); },
            [&](VkDeviceMemory memory) { vk.freeMemory(backend.device, memory, nullptr); },
            [](AHardwareBuffer* buffer) { AHardwareBuffer_release(buffer); }));
        assert(second.retire(vk, backend.device));
    }
    std::puts("PASS explicit slot clear/wait uses only the target command, image and fence");
}

void bridgeBudgetAdmissionContracts() {
    using Budget = libretrodroid::VulkanBridgeAllocationBudget;
    for (unsigned failure = 0; failure <= 8; ++failure) {
        VulkanBackend backend;
        setup(backend);
        bridgeAllocation = {};
        bridgeAllocation.failure = failure;
        if (failure == 7) bridgeAllocation.allocationBytes = 4097;
        if (failure == 8) bridgeAllocation.allocationBytes = 0;
        auto& vk = backend.vk;
        vk.getAndroidHardwareBufferProperties = [](VkDevice, const AHardwareBuffer* buffer,
                VkAndroidHardwareBufferPropertiesANDROID* props) {
            assert(buffer == object<AHardwareBuffer*>(101));
            ++bridgeAllocation.propertyQueries;
            props->allocationSize = bridgeAllocation.allocationBytes;
            props->memoryTypeBits = bridgeAllocation.failure == 3 ? 0 : 1;
            return bridgeAllocation.failure == 2 ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS;
        };
        vk.createImage = [](VkDevice, const VkImageCreateInfo*, const VkAllocationCallbacks*, VkImage* image) {
            ++bridgeAllocation.imageCreates;
            *image = object<VkImage>(102);
            return bridgeAllocation.failure == 4 ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS;
        };
        vk.allocateMemory = [](VkDevice, const VkMemoryAllocateInfo* info,
                const VkAllocationCallbacks*, VkDeviceMemory* memory) {
            assert(info->allocationSize == bridgeAllocation.allocationBytes);
            ++bridgeAllocation.memoryAllocations;
            *memory = object<VkDeviceMemory>(103);
            return bridgeAllocation.failure == 5 ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS;
        };
        vk.bindImageMemory = [](VkDevice, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
            assert(image == object<VkImage>(102) && memory == object<VkDeviceMemory>(103) && offset == 0);
            ++bridgeAllocation.binds;
            return bridgeAllocation.failure == 6 ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_SUCCESS;
        };
        const auto primaryBytes = backend.getFrameBufferAllocationBytes();
        const auto primary = backend.getFrameBuffer();
        const auto status = backend.getFrameStatus();
        const auto budget = backend.additionalBridgeAllocationBudget(Budget::maximum - primaryBytes - 4096);
        assert(budget.available() == 4096);
        assert(!backend.additionalBridgeAllocationBudget(std::numeric_limits<uint64_t>::max()).available());
        decltype(backend.frameSlot.bridgeImage) secondary;
        assert(!backend.allocateBridgeImage(secondary, 64, 64, false, budget));
        assert(secondary.empty() && bridgeAllocation.ahbAllocations == 0);
        const bool accepted = backend.allocateBridgeImage(secondary, 32, 32, false, budget);
        assert(accepted == (failure == 0));
        assert(bridgeAllocation.ahbAllocations == 1);
        assert(backend.getFrameBuffer() == primary && backend.getFrameBufferAllocationBytes() == primaryBytes);
        assert(backend.getFrameStatus() == status && !backend.hasFatalError() && driver.submits == 0);
        if (accepted) {
            assert(secondary.allocationBytes == 4096 && secondary.buffer != primary && secondary.width == 32);
            assert(!backend.allocateBridgeImage(secondary, 32, 32, false, budget));
            assert(bridgeAllocation.ahbAllocations == 1);
            assert(secondary.retire(false, true, true, true,
                [&](VkImage image) { vk.destroyImage(backend.device, image, nullptr); },
                [&](VkDeviceMemory memory) { vk.freeMemory(backend.device, memory, nullptr); },
                [](AHardwareBuffer* buffer) { AHardwareBuffer_release(buffer); }));
        }
        assert(secondary.empty());
        assert(driver.bufferFrees == (failure == 1 ? 0u : 1u));
        assert(driver.imageFrees == (failure == 0 || failure == 3 || failure == 5 || failure == 6 ? 1u : 0u));
        assert(driver.memoryFrees == (failure == 0 || failure == 6 ? 1u : 0u));
        if (failure == 7 || failure == 8) {
            assert(bridgeAllocation.imageCreates == 0 && bridgeAllocation.memoryAllocations == 0);
        }
    }
    std::puts("PASS production optional bridge budget admission and isolated rollback");
}

void frameSlotAllocationContracts() {
    using Slot = VulkanBackend::FrameSlot;
    static_assert(!std::is_copy_constructible_v<Slot> && !std::is_copy_assignable_v<Slot>);
    static unsigned failure, allocations;
    static std::vector<VkCommandPool> pools;
    static std::vector<VkFence> fences;
    VulkanBackend backend;
    backend.device = object<VkDevice>(1);
    backend.queueFamily = 7;
    auto& vk = backend.vk;
    driver = {};
    vk.createCommandPool = [](VkDevice device, const VkCommandPoolCreateInfo* info,
                              const VkAllocationCallbacks*, VkCommandPool* out) {
        assert(device == object<VkDevice>(1) &&
               info->sType == VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO &&
               info->flags == VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT &&
               info->queueFamilyIndex == 7);
        ++allocations;
        *out = object<VkCommandPool>(failure == 1 ? 999 : 100 + allocations);
        if (failure == 1) return VK_ERROR_OUT_OF_HOST_MEMORY;
        pools.push_back(*out);
        return VK_SUCCESS;
    };
    vk.allocateCommandBuffers = [](VkDevice device, const VkCommandBufferAllocateInfo* info,
                                   VkCommandBuffer* out) {
        assert(device == object<VkDevice>(1) && !pools.empty() &&
               info->sType == VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO &&
               info->commandPool == pools.back() &&
               info->level == VK_COMMAND_BUFFER_LEVEL_PRIMARY && info->commandBufferCount == 1);
        ++allocations;
        *out = object<VkCommandBuffer>(failure == 2 ? 999 : 200 + allocations);
        return failure == 2 ? VK_ERROR_OUT_OF_HOST_MEMORY : VK_SUCCESS;
    };
    vk.createFence = [](VkDevice device, const VkFenceCreateInfo* info,
                         const VkAllocationCallbacks*, VkFence* out) {
        assert(device == object<VkDevice>(1) && info->sType == VK_STRUCTURE_TYPE_FENCE_CREATE_INFO &&
               info->flags == 0);
        ++allocations;
        *out = object<VkFence>(failure == 3 ? 999 : 300 + allocations);
        if (failure == 3) return VK_ERROR_OUT_OF_HOST_MEMORY;
        fences.push_back(*out);
        return VK_SUCCESS;
    };
    vk.destroyCommandPool = [](VkDevice device, VkCommandPool pool, const VkAllocationCallbacks*) {
        assert(device == object<VkDevice>(1) && !pools.empty() && pool == pools.back());
        pools.pop_back();
        ++driver.commandPoolFrees;
    };
    vk.destroyFence = [](VkDevice device, VkFence fence, const VkAllocationCallbacks*) {
        assert(device == object<VkDevice>(1) && !fences.empty() && fence == fences.back());
        fences.pop_back();
        ++driver.fenceFrees;
    };
    for (failure = 1; failure <= 3; ++failure) {
        allocations = 0;
        driver.commandPoolFrees = driver.fenceFrees = 0;
        assert(!backend.createFrameResources());
        assert(allocations == failure && driver.commandPoolFrees == (failure == 1 ? 0u : 1u) &&
               !driver.fenceFrees);
        assert(!backend.frameSlot.pool && !backend.frameSlot.command &&
               !backend.frameSlot.fence && !backend.frameSlot.inFlight);
        assert(pools.empty() && fences.empty() && backend.frameSlot.retire(vk, backend.device));
    }
    failure = allocations = 0;
    driver.commandPoolFrees = driver.fenceFrees = 0;
    const auto destroyFence = vk.destroyFence;
    vk.destroyFence = nullptr;
    assert(!backend.createFrameResources() && !allocations);
    vk.destroyFence = destroyFence;
    assert(backend.createFrameResources());
    Slot second;
    assert(second.create(vk, backend.device, backend.queueFamily));
    assert(backend.frameSlot.pool != second.pool && backend.frameSlot.command != second.command &&
           backend.frameSlot.fence != second.fence && allocations == 6);
    libretrodroid::VulkanColorCopy::Source firstSource {}, secondSource {};
    firstSource.width = 320; firstSource.height = 240;
    secondSource.width = 640; secondSource.height = 480;
    assert(backend.frameSlot.colorCopy.prepare(firstSource, VK_FORMAT_R8G8B8A8_UNORM, 320, 240));
    assert(second.colorCopy.prepare(secondSource, VK_FORMAT_R8G8B8A8_UNORM, 640, 480));
    second.colorCopyRejected = true;
    assert(backend.frameSlot.colorCopy.preparedImage(VK_NULL_HANDLE).width == 320);
    assert(second.colorCopy.preparedImage(VK_NULL_HANDLE).width == 640);
    assert(!backend.createFrameResources() && allocations == 6);
    second.inFlight = true;
    assert(!second.retire(vk, backend.device));
    second.inFlight = false;
    vk.destroyFence = nullptr;
    assert(!second.retire(vk, backend.device));
    vk.destroyFence = destroyFence;
    assert(!second.retire(vk, VK_NULL_HANDLE));
    assert(!driver.commandPoolFrees && !driver.fenceFrees && !driver.cacheReleases);
    assert(second.colorCopyRejected && second.colorCopy.preparedImage(VK_NULL_HANDLE).width == 640);
    assert(second.retire(vk, backend.device) && second.retire(vk, backend.device));
    assert(driver.commandPoolFrees == 1 && driver.fenceFrees == 1 && driver.cacheReleases == 1);
    assert(!second.colorCopyRejected && backend.frameSlot.colorCopy.preparedImage(VK_NULL_HANDLE).width == 320);
    assert(backend.frameSlot.pool && backend.frameSlot.command && backend.frameSlot.fence);
    assert(backend.frameSlot.retire(vk, backend.device));
    assert(driver.commandPoolFrees == 2 && driver.fenceFrees == 2 && driver.cacheReleases == 2 &&
           pools.empty() && fences.empty());
    std::puts("PASS frame-slot transactional creation, independent handles and safe retirement");
}

void slotRetirementPreflightContracts() {
    VulkanBackend backend;
    setup(backend);
    assert(backend.presentFrame(256, 224, 4.0F / 3.0F, true));
    assert(backend.frameSlot.colorCopy.preparedImage(VK_NULL_HANDLE).width == 256);
    backend.frameSlot.colorCopyRejected = true;
    window(backend);
    backend.nativeSdrSwapchain = object<VkSwapchainKHR>(19);
    backend.frameSlot.bridgeSync.readSemaphore = object<VkSemaphore>(20);
    backend.frameSlot.bridgeSync.writeSemaphore = object<VkSemaphore>(21);
    backend.frameSlot.bridgeSync.readPending = true;
    static unsigned semaphoreFrees;
    semaphoreFrees = 0;
    backend.vk.destroySemaphore = [](VkDevice, VkSemaphore semaphore, const VkAllocationCallbacks*) {
        assert(semaphore == object<VkSemaphore>(20) || semaphore == object<VkSemaphore>(21));
        ++semaphoreFrees;
    };
    const auto destroyFence = backend.vk.destroyFence;
    backend.vk.destroyFence = nullptr;
    backend.shutdown();
    assert(backend.getFatalError() == VK_ERROR_INITIALIZATION_FAILED && !backend.shutdownReady);
    assert(backend.device && backend.nativeSdrSwapchain && backend.frameSlot.bridgeImage.buffer && backend.frameSlot.pool);
    assert(backend.frameSlot.bridgeSync.readSemaphore && backend.frameSlot.bridgeSync.writeSemaphore && backend.frameSlot.colorCopyRejected);
    assert(driver.cacheReleases == 0 && driver.slangReleases == 0 && driver.swapchainFrees == 0 &&
           driver.imageFrees == 0 && driver.bufferFrees == 0 && driver.commandPoolFrees == 0 &&
           driver.fenceFrees == 0 && driver.deviceFrees == 0 && semaphoreFrees == 0);
    backend.vk.destroyFence = destroyFence;
    const auto destroySemaphore = backend.vk.destroySemaphore;
    backend.vk.destroySemaphore = nullptr;
    backend.shutdown();
    assert(backend.device && !backend.shutdownReady && backend.frameSlot.bridgeSync.readPending);
    assert(driver.imageFrees == 0 && driver.swapchainFrees == 0 && semaphoreFrees == 0);
    backend.vk.destroySemaphore = destroySemaphore;
    const auto destroyImage = backend.vk.destroyImage;
    backend.vk.destroyImage = nullptr;
    backend.shutdown();
    assert(backend.device && !backend.shutdownReady && backend.frameSlot.bridgeImage.allocationBytes == 256 * 240 * 4);
    assert(driver.imageFrees == 0 && driver.swapchainFrees == 0 && semaphoreFrees == 0);
    backend.vk.destroyImage = destroyImage;
    backend.shutdown();
    assert(!backend.device && !backend.hasFatalError() && !backend.frameSlot.colorCopyRejected);
    assert(backend.frameSlot.bridgeSync.empty() && backend.frameSlot.bridgeImage.empty());
    assert(driver.cacheReleases == 1 && driver.swapchainFrees == 1 && driver.imageFrees == 1 &&
           driver.bufferFrees == 1 && driver.commandPoolFrees == 1 && driver.fenceFrees == 1 &&
           driver.deviceFrees == 1 && semaphoreFrees == 2);
    std::puts("PASS slot retirement preflight retains all resources on failure, retries exactly once");
}

void timeoutsPreserveWork() {
    VulkanBackend backend;
    setup(backend); pendingFrame(backend);
    driver.waits = {VK_TIMEOUT, VK_TIMEOUT, VK_SUCCESS};
    assert(backend.waitForFrame());
    assert(driver.waitCalls == 3 && driver.warnings == 1 && driver.resets == 1);
    assert(!backend.frameSlot.inFlight && !backend.hasFatalError());
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
        assert(backend.frameSlot.inFlight && backend.getFatalError() == failure && driver.resets == 0);
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

void bridgeContextReplacementRetires() {
    {
        VulkanBackend backend;
        setup(backend); pendingFrame(backend); window(backend);
        backend.frameStatus.store(7u);
        backend.nativePresentation.setFullscreenActive(true);
        backend.nativePresentation.didPresentFrame();
        driver.waits = {VK_TIMEOUT, VK_SUCCESS};
        assert(backend.retireBridgeForContextReplacement());
        assert(driver.waitCalls == 2 && driver.resets == 1 && !driver.pending);
        assert(driver.imageFrees == 1 && driver.memoryFrees == 1 && driver.bufferFrees == 1);
        assert(!backend.frameSlot.bridgeImage.buffer && backend.frameSlot.bridgeImage.image == VK_NULL_HANDLE &&
               backend.frameSlot.bridgeImage.memory == VK_NULL_HANDLE && backend.getFrameStatus() == 0);
        assert(backend.getFrameBufferWidth() == 0 && backend.getFrameBufferHeight() == 0);
        assert(backend.primaryDraw(false, true) != libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
        // Even identical dimensions require a new allocation for the new EGL generation.
        assert(backend.ensureFrameBuffer(256, 240));
        assert(driver.allocations == 1 && driver.imageFrees == 1 && driver.bufferFrees == 1);
    }
    for (VkResult failure : {VK_ERROR_DEVICE_LOST, VK_ERROR_OUT_OF_DEVICE_MEMORY}) {
        VulkanBackend backend;
        setup(backend); pendingFrame(backend); window(backend);
        backend.nativePresentation.setFullscreenActive(true);
        backend.nativePresentation.didPresentFrame();
        driver.waits = {failure};
        assert(!backend.retireBridgeForContextReplacement());
        assert(backend.getFatalError() == failure && backend.frameSlot.inFlight);
        assert(driver.pending == (failure != VK_ERROR_DEVICE_LOST));
        assert(backend.frameSlot.bridgeImage.buffer && backend.frameSlot.bridgeImage.image != VK_NULL_HANDLE &&
               backend.frameSlot.bridgeImage.memory != VK_NULL_HANDLE);
        assert(driver.imageFrees == 0 && driver.memoryFrees == 0 && driver.bufferFrees == 0);
        assert(backend.primaryDraw(false, true) != libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
        assert(!backend.retireBridgeForContextReplacement() && driver.waitCalls == 1);
        assert(!backend.ensureFrameBuffer(256, 240) && driver.allocations == 0);
        assert(backend.prepareForShutdown());
        backend.shutdown();
        assert(driver.imageFrees == 1 && driver.bufferFrees == 1 && !driver.pending);
        assert(backend.frameSlot.pool == VK_NULL_HANDLE && backend.frameSlot.command == VK_NULL_HANDLE &&
               backend.frameSlot.fence == VK_NULL_HANDLE && !backend.frameSlot.inFlight);
    }
    std::puts("PASS Vulkan context generation retirement requires completion and forces a fresh same-size allocation");
}

void bridgeImportFailureLatches() {
    VulkanBackend backend;
    setup(backend); pendingFrame(backend); window(backend);
    backend.frameStatus.store(7u);
    backend.nativePresentation.setFullscreenActive(true);
    backend.nativePresentation.didPresentFrame();
    backend.rejectBridgeImport();
    assert(backend.getFatalError() == VK_ERROR_INITIALIZATION_FAILED);
    assert(backend.getFrameStatus() == 0 && backend.getNativeSdrStatus() == 9u);
    assert(backend.primaryDraw(false, true) != libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
    assert(backend.frameSlot.inFlight && driver.pending);
    assert(!backend.presentFrame(256, 224, 4.0F / 3.0F, true));
    assert(!backend.ensureFrameBuffer(512, 480));
    assert(driver.submits == 0 && driver.imageFrees == 0 && driver.bufferFrees == 0);
    backend.fatalFrameError = VK_ERROR_DEVICE_LOST;
    backend.rejectBridgeImport();
    assert(backend.getFatalError() == VK_ERROR_DEVICE_LOST);
    assert(backend.prepareForShutdown());
    backend.shutdown();
    assert(driver.imageFrees == 1 && driver.bufferFrees == 1 && !driver.pending);
    std::puts("PASS production bridge rejection latches first error and retires only after safe idle");
}

void resetAndSubmitFailures() {
    for (unsigned operation = 0; operation != 3; ++operation) {
        VulkanBackend backend;
        setup(backend); pendingFrame(backend);
        driver.waits = {VK_ERROR_OUT_OF_HOST_MEMORY};
        const bool result = operation == 0 ? backend.presentFrame(256,224,4.0F/3.0F,true) :
            operation == 1 ? backend.clearFrameImage() : backend.ensureFrameBuffer(512,480);
        assert(!result && backend.hasFatalError() && backend.frameSlot.inFlight);
        assert(driver.cachePrepares == 0 && driver.commandResets == 0 &&
               driver.allocations == 0 && driver.imageFrees == 0 && driver.submits == 0);
    }
    for (bool clear : {false,true}) {
        for (unsigned phase = 0; phase != 5; ++phase) {
            VulkanBackend backend;
            setup(backend); window(backend);
            backend.nativePresentation.setFullscreenActive(true);
            const VkResult failure = VK_ERROR_OUT_OF_HOST_MEMORY;
            if (phase == 0) driver.commandReset = failure;
            if (phase == 1) driver.begin = failure;
            if (phase == 2) driver.end = failure;
            if (phase == 3) driver.submit = failure;
            if (phase == 4) driver.reset = failure;
            const bool result = clear ? backend.clearFrameImage() :
                backend.presentFrame(256,224,4.0F/3.0F,true);
            assert(!result && backend.getFatalError() == failure);
            // Fence reset failure can occur after WSI was queued; it must
            // still invalidate the frontend gate and retain unretired work.
            assert(driver.presents == (!clear && phase == 4 ? 1u : 0u));
            assert(backend.primaryDraw(false, true) != libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
            assert(driver.submits == (phase >= 3 ? 1u : 0u));
            assert(driver.resets == (phase == 4 ? 1u : 0u));
            assert(backend.frameSlot.inFlight == (phase == 4));
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
        assert(!backend.frameSlot.inFlight && backend.getFatalError() == VK_ERROR_OUT_OF_HOST_MEMORY);
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
               backend.frameSlot.bridgeImage.image != VK_NULL_HANDLE && backend.frameSlot.bridgeImage.buffer != nullptr);
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
    std::puts("PASS raw/filtered WSI requires opaque identity surfaces; confirmation requires completed work");
}

void earlyNativePresentContracts() {
    for (bool filtered : {false, true}) {
        for (VkResult outcome : {VK_SUCCESS, VK_SUBOPTIMAL_KHR, VK_ERROR_OUT_OF_DATE_KHR, VK_ERROR_DEVICE_LOST}) {
            for (bool waitFails : {false, true}) {
                VulkanBackend backend;
                setup(backend); window(backend);
                if (filtered) backend.nativeSlang.setPresetPath("preset");
                backend.nativePresentation.setFullscreenActive(true);
                driver.present = outcome;
                driver.waits = {waitFails ? VK_ERROR_OUT_OF_HOST_MEMORY : VK_SUCCESS};
                const bool result = backend.presentFrame(256,224,4.0F/3.0F,true);
                const bool fatal = outcome == VK_ERROR_DEVICE_LOST || waitFails;
                assert(driver.submits == 1 && driver.presents == 1);
                assert(driver.waitCalls == (outcome == VK_ERROR_DEVICE_LOST ? 0u : 1u));
                assert(result == !fatal && backend.hasFatalError() == fatal);
                const bool confirmed = !fatal && (outcome == VK_SUCCESS || outcome == VK_SUBOPTIMAL_KHR);
                assert((backend.primaryDraw(false,true) == libretrodroid::NativePresentationGate::PrimaryDraw::Skip) == confirmed);
                if (fatal) {
                    assert(backend.frameSlot.inFlight && driver.swapchainFrees == 0 && driver.cacheReleases == 0);
                    assert(!backend.presentFrame(256,224,4.0F/3.0F,true) && driver.submits == 1);
                } else {
                    assert(!backend.frameSlot.inFlight);
                    if (outcome == VK_ERROR_OUT_OF_DATE_KHR) assert(driver.swapchainFrees == 1);
                }
                backend.shutdown();
            }
        }
    }
    {
        VulkanBackend backend;
        setup(backend); window(backend);
        backend.nativeSlang.setPresetPath("preset");
        backend.nativePresentation.setFullscreenActive(true);
        driver.slangRecordAccepted = false;
        assert(backend.presentFrame(256,224,4.0F/3.0F,true));
        assert(driver.submits == 1 && driver.presents == 0 && driver.waitCalls == 1);
        assert(driver.swapchainFrees == 1 && !backend.frameSlot.inFlight);
        assert(backend.primaryDraw(false,true) != libretrodroid::NativePresentationGate::PrimaryDraw::Skip);
        backend.shutdown();
    }
    std::puts("PASS WSI queues before host fence, confirms after completion, and safely handles failures");
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

void reusableSubmitStorage() {
    for (bool direct : {false, true}) {
        VulkanBackend backend;
        setup(backend);
        if (direct) {
            window(backend);
            backend.nativePresentation.setFullscreenActive(true);
            assert(backend.ensureNativeSdrSwapchain());
        }
        const auto commandA = object<VkCommandBuffer>(101);
        const auto commandB = object<VkCommandBuffer>(102);
        const auto waitA = object<VkSemaphore>(103);
        const auto waitB = object<VkSemaphore>(104);
        const auto signal = object<VkSemaphore>(105);
        const auto verify = [&](bool commands, bool waits, bool coreSignal) {
            backend.pendingCommandBuffers = commands ? std::vector<VkCommandBuffer>{commandA, commandB}
                                                     : std::vector<VkCommandBuffer>{};
            backend.pendingImage.waitSemaphores = waits ? std::vector<VkSemaphore>{waitA, waitB}
                                                        : std::vector<VkSemaphore>{};
            backend.pendingSignalSemaphore = coreSignal ? signal : VK_NULL_HANDLE;
            auto expectedCommands = backend.pendingCommandBuffers;
            expectedCommands.push_back(backend.frameSlot.command);
            std::vector<VkSemaphore> expectedWaits, expectedSignals;
            if (!commands && waits) expectedWaits = {waitA, waitB};
            if (direct) expectedWaits.push_back(backend.nativeSdrAcquire);
            if (coreSignal) expectedSignals.push_back(signal);
            if (direct) expectedSignals.push_back(backend.nativeSdrComplete[0]);
            assert(backend.presentFrame(256, 224, 4.0F/3.0F, true));
            assert(driver.submittedCommands == expectedCommands);
            assert(driver.submittedWaits == expectedWaits);
            assert(driver.submittedSignals == expectedSignals);
            assert(driver.submittedStages == std::vector<VkPipelineStageFlags>(
                expectedWaits.size(), VK_PIPELINE_STAGE_TRANSFER_BIT));
            assert(backend.pendingCommandBuffers.empty() && backend.pendingImage.waitSemaphores.empty());
            assert(backend.pendingSignalSemaphore == VK_NULL_HANDLE);
            assert(!driver.pending && !backend.frameSlot.inFlight);
        };
        verify(true, true, true);  // Core command buffers suppress core wait semaphores.
        verify(false, true, true); // Exercise all wait slots, including optional WSI acquire.
        const auto cmdCapacity = backend.submitCommands.capacity();
        const auto waitCapacity = backend.submitWaits.capacity();
        const auto stageCapacity = backend.submitWaitStages.capacity();
        for (int i = 0; i < 8; ++i) {
            verify(false, false, false);
            verify(true, true, true);
            verify(false, true, true);
            assert(backend.submitCommands.capacity() == cmdCapacity);
            assert(backend.submitWaits.capacity() == waitCapacity);
            assert(backend.submitWaitStages.capacity() == stageCapacity);
        }
        backend.shutdown();
        assert(backend.submitCommands.capacity() == 0 && backend.submitWaits.capacity() == 0 &&
               backend.submitWaitStages.capacity() == 0);
    }
    std::puts("PASS Vulkan submit storage reuse preserves command/wait/signal ordering and clears stale descriptors");
}

void readFenceQueueContracts() {
    constexpr uintptr_t readSemaphoreId = 801;
    const auto readSemaphore = object<VkSemaphore>(readSemaphoreId);
    const auto newFd = [] {
        int fds[2] {};
        assert(pipe(fds) == 0);
        assert(close(fds[1]) == 0);
        return fds[0];
    };
    const auto closed = [](int fd) {
        errno = 0;
        assert(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
    };
    for (bool direct : {false, true}) {
        VulkanBackend backend;
        setup(backend);
        if (direct) {
            window(backend);
            backend.nativePresentation.setFullscreenActive(true);
            assert(backend.ensureNativeSdrSwapchain());
        }
        // Fake driver closes on successful import to model transferred ownership.
        unsigned creates = 0, destroys = 0;
        // Vulkan dispatch is a C function pointer, so use fixture-owned counters
        // rather than capturing these local variables.
        static unsigned* createCount;
        static unsigned* destroyCount;
        createCount = &creates;
        destroyCount = &destroys;
        static VkResult createResult = VK_SUCCESS, importResult = VK_SUCCESS;
        createResult = importResult = VK_SUCCESS;
        backend.vk.createSemaphore = [](VkDevice, const VkSemaphoreCreateInfo* info,
                                       const VkAllocationCallbacks*, VkSemaphore* semaphore) {
            assert(!driver.pending && info->sType == VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            ++*createCount;
            if (createResult == VK_SUCCESS) *semaphore = object<VkSemaphore>(801);
            return createResult;
        };
        backend.vk.destroySemaphore = [](VkDevice, VkSemaphore semaphore, const VkAllocationCallbacks*) {
            assert(!driver.pending);
            if (semaphore == object<VkSemaphore>(801)) ++*destroyCount;
        };
        backend.vk.importSemaphoreFd = [](VkDevice, const VkImportSemaphoreFdInfoKHR* info) {
            assert(!driver.pending && info->semaphore == object<VkSemaphore>(801));
            assert(info->sType == VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR);
            assert(info->flags == VK_SEMAPHORE_IMPORT_TEMPORARY_BIT);
            assert(info->handleType == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
            assert(fcntl(info->fd, F_GETFD) >= 0);
            if (importResult == VK_SUCCESS) assert(close(info->fd) == 0);
            return importResult;
        };
        assert(!backend.canImportBridgeReadFence());
        int fd = newFd();
        assert(!backend.importBridgeReadFence(fd));
        closed(fd);
        backend.bridgeFenceEnabled = true;
        assert(backend.canImportBridgeReadFence());
        for (bool coreCommands : {false, true}) {
            const auto coreWait = object<VkSemaphore>(802);
            backend.pendingImage.waitSemaphores = {coreWait};
            if (coreCommands) backend.pendingCommandBuffers = {object<VkCommandBuffer>(803)};
            fd = newFd();
            assert(backend.importBridgeReadFence(fd));
            closed(fd);
            assert(backend.frameSlot.bridgeSync.readPending && creates == 1 && destroys == 0);
            assert(backend.presentFrame(256, 224, 4.0F/3.0F, true));
            assert(!backend.frameSlot.bridgeSync.readPending && !backend.frameSlot.inFlight && !driver.pending);
            std::vector<VkSemaphore> expected;
            if (!coreCommands) expected.push_back(coreWait);
            if (direct) expected.push_back(backend.nativeSdrAcquire);
            expected.push_back(readSemaphore);
            assert(driver.submittedWaits == expected);
            assert(driver.submittedStages.size() == expected.size());
            assert(driver.submittedStages.back() == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            for (size_t i = 0; i + 1 < expected.size(); ++i)
                assert(driver.submittedStages[i] == VK_PIPELINE_STAGE_TRANSFER_BIT);
        }
        // A rejected frame has not consumed the temporary payload.
        fd = newFd();
        assert(backend.importBridgeReadFence(fd));
        closed(fd);
        const auto image = backend.pendingImage.image;
        backend.pendingImage.image = VK_NULL_HANDLE;
        assert(!backend.presentFrame(256, 224, 4.0F/3.0F, true));
        assert(backend.frameSlot.bridgeSync.readPending && destroys == 0);
        backend.pendingImage.image = image;
        fd = newFd();
        assert(backend.importBridgeReadFence(fd));
        closed(fd);
        assert(creates == 2 && destroys == 1);
        // Export failure discards the unsubmitted payload before CPU fallback.
        assert(!backend.importBridgeReadFence(-1));
        assert(!backend.frameSlot.bridgeSync.readPending && !backend.canImportBridgeReadFence() && destroys == 2);
        assert(backend.presentFrame(256, 224, 4.0F/3.0F, true));
        assert(std::find(driver.submittedWaits.begin(), driver.submittedWaits.end(), readSemaphore) ==
               driver.submittedWaits.end());
        backend.bridgeFenceEnabled = true;
        createResult = VK_ERROR_OUT_OF_HOST_MEMORY;
        fd = newFd();
        assert(!backend.importBridgeReadFence(fd));
        closed(fd);
        assert(!backend.hasFatalError() && !backend.canImportBridgeReadFence());
        assert(backend.frameSlot.bridgeSync.readSemaphore == VK_NULL_HANDLE && creates == 3);
        backend.bridgeFenceEnabled = true;
        createResult = VK_SUCCESS;
        importResult = VK_ERROR_INVALID_EXTERNAL_HANDLE;
        fd = newFd();
        assert(!backend.importBridgeReadFence(fd));
        closed(fd);
        assert(!backend.hasFatalError() && !backend.canImportBridgeReadFence() && !backend.frameSlot.bridgeSync.readPending);
        // An import failure retains the unused semaphore; a fatal import latches
        // the first error and does not permit another submit.
        backend.bridgeFenceEnabled = true;
        importResult = VK_ERROR_DEVICE_LOST;
        fd = newFd();
        assert(!backend.importBridgeReadFence(fd));
        closed(fd);
        assert(backend.hasFatalError() && backend.getFatalError() == VK_ERROR_DEVICE_LOST);
        const unsigned oldSubmits = driver.submits;
        assert(!backend.presentFrame(256, 224, 4.0F/3.0F, true));
        assert(driver.submits == oldSubmits && destroys == 2);
        driver.idles = {VK_ERROR_UNKNOWN};
        backend.shutdown();
        assert(destroys == 2 && backend.frameSlot.bridgeSync.readSemaphore == readSemaphore);
        driver.idles = {VK_SUCCESS};
        backend.shutdown();
        assert(destroys == 3 && backend.frameSlot.bridgeSync.readSemaphore == VK_NULL_HANDLE);
        assert(!backend.bridgeFenceEnabled && !backend.frameSlot.bridgeSync.readPending);
    }
    std::puts("PASS Vulkan SYNC_FD ownership, queue wait order, reuse, fallback and safe teardown");
}

void writeFenceQueueContracts() {
    const auto semaphore = object<VkSemaphore>(950);
    static unsigned creates, exports, destroys;
    static int lastFd;
    static VkResult createResult, exportResult;
    static bool alreadySignaled;
    for (bool direct : {false, true}) {
        VulkanBackend backend;
        setup(backend);
        if (direct) {
            window(backend);
            backend.nativePresentation.setFullscreenActive(true);
            assert(backend.ensureNativeSdrSwapchain());
        }
        creates = exports = destroys = 0;
        lastFd = -1;
        alreadySignaled = false;
        createResult = exportResult = VK_SUCCESS;
        backend.vk.createSemaphore = [](VkDevice, const VkSemaphoreCreateInfo* info,
                                       const VkAllocationCallbacks*, VkSemaphore* out) {
            assert(!driver.pending && info->sType == VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            const auto* external = static_cast<const VkExportSemaphoreCreateInfo*>(info->pNext);
            assert(external && external->sType == VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO &&
                   external->handleTypes == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
            ++creates;
            if (createResult == VK_SUCCESS) *out = object<VkSemaphore>(950);
            return createResult;
        };
        backend.vk.destroySemaphore = [](VkDevice, VkSemaphore handle, const VkAllocationCallbacks*) {
            assert(!driver.pending);
            if (handle == object<VkSemaphore>(950)) ++destroys;
        };
        backend.vk.getSemaphoreFd = [](VkDevice, const VkSemaphoreGetFdInfoKHR* info, int* fd) {
            assert(driver.pending && info->sType == VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR &&
                   info->semaphore == object<VkSemaphore>(950) &&
                   info->handleType == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
            assert(!driver.submittedSignals.empty() && driver.submittedSignals.back() == info->semaphore);
            ++exports;
            if (exportResult == VK_SUCCESS) {
                if (alreadySignaled) {
                    *fd = -1;
                } else {
                    int fds[2] {};
                    assert(pipe(fds) == 0 && close(fds[1]) == 0);
                    *fd = lastFd = fds[0];
                }
            }
            return exportResult;
        };
        int fd = 99;
        assert(backend.presentFrame(256, 224, 4.0F/3.0F, true, &fd) &&
               fd == VulkanBackend::NoBridgeWriteFence);
        assert(!creates && !exports);
        backend.bridgeWriteFenceEnabled = true;
        assert(backend.canExportBridgeWriteFence());
        assert(backend.presentFrame(256, 224, 4.0F/3.0F, true));
        assert(!creates && !exports);
        const auto signal = object<VkSemaphore>(951);
        for (unsigned i = 0; i < 3; ++i) {
            backend.pendingSignalSemaphore = signal;
            alreadySignaled = i == 1;
            assert(backend.presentFrame(256, 224, 4.0F/3.0F, true, &fd));
            assert(creates == 1 && exports == i + 1 && !driver.pending && !backend.frameSlot.inFlight);
            assert(driver.submittedSignals.front() == signal && driver.submittedSignals.back() == semaphore);
            assert(driver.submittedSignals.size() == (direct ? 3u : 2u));
            if (alreadySignaled) assert(fd == -1);
            else assert(fd == lastFd && fd >= 0 && close(fd) == 0);
        }
        // Failed export may leave a signalled binary semaphore. Do not signal it again.
        exportResult = VK_ERROR_OUT_OF_HOST_MEMORY;
        assert(backend.presentFrame(256, 224, 4.0F/3.0F, true, &fd) &&
               fd == VulkanBackend::NoBridgeWriteFence);
        assert(!backend.canExportBridgeWriteFence() && !backend.hasFatalError());
        fd = -1; // A previous proof must not leak into a subsequent frame.
        assert(backend.presentFrame(256, 224, 4.0F/3.0F, true, &fd) && exports == 4 &&
               fd == VulkanBackend::NoBridgeWriteFence);
        assert(std::find(driver.submittedSignals.begin(), driver.submittedSignals.end(), semaphore) ==
               driver.submittedSignals.end());
        backend.shutdown();
        assert(destroys == 1 && backend.frameSlot.bridgeSync.writeSemaphore == VK_NULL_HANDLE &&
               !backend.bridgeWriteFenceEnabled);

        VulkanBackend failed;
        setup(failed);
        creates = exports = destroys = 0;
        failed.bridgeWriteFenceEnabled = true;
        failed.vk.createSemaphore = [](VkDevice, const VkSemaphoreCreateInfo* info,
                                       const VkAllocationCallbacks*, VkSemaphore* out) {
            assert(info->pNext && !driver.pending);
            ++creates;
            *out = object<VkSemaphore>(950);
            return VK_SUCCESS;
        };
        failed.vk.destroySemaphore = [](VkDevice, VkSemaphore handle, const VkAllocationCallbacks*) {
            assert(!driver.pending);
            if (handle == object<VkSemaphore>(950)) ++destroys;
        };
        failed.vk.getSemaphoreFd = [](VkDevice, const VkSemaphoreGetFdInfoKHR*, int* out) {
            assert(driver.pending);
            int fds[2] {};
            assert(pipe(fds) == 0 && close(fds[1]) == 0);
            *out = lastFd = fds[0];
            ++exports;
            return VK_SUCCESS;
        };
        driver.waits = {VK_ERROR_OUT_OF_DEVICE_MEMORY};
        assert(!failed.presentFrame(256, 224, 4.0F/3.0F, true, &fd) &&
               fd == VulkanBackend::NoBridgeWriteFence);
        errno = 0;
        assert(lastFd >= 0 && fcntl(lastFd, F_GETFD) == -1 && errno == EBADF);
        assert(failed.hasFatalError() && failed.frameSlot.inFlight && destroys == 0);
        failed.shutdown();
        assert(destroys == 1);
    }
    std::puts("PASS reverse Vulkan SYNC_FD signal ordering, export, CPU fallback and error retirement");
}

void duplicateFrameContracts() {
    for (bool commands : {false, true}) {
        for (bool signal : {false, true}) {
            VulkanBackend backend;
            setup(backend);
            const auto originalImage = backend.pendingImage.image;
            backend.pendingImage.waitSemaphores = {object<VkSemaphore>(901)};
            if (commands) backend.pendingCommandBuffers = {object<VkCommandBuffer>(902), object<VkCommandBuffer>(903)};
            if (signal) backend.pendingSignalSemaphore = object<VkSemaphore>(904);
            const auto expected = backend.pendingCommandBuffers;
            const auto expectedSignal = backend.pendingSignalSemaphore;
            assert(backend.completeDuplicateFrame());
            assert(driver.submits == unsigned(commands || signal));
            assert(driver.submittedCommands == expected && driver.submittedWaits.empty());
            if (signal) assert(driver.submittedSignals == std::vector<VkSemaphore>{expectedSignal});
            else assert(driver.submittedSignals.empty());
            assert(backend.pendingCommandBuffers.empty() && backend.pendingImage.waitSemaphores.empty());
            assert(backend.pendingSignalSemaphore == VK_NULL_HANDLE);
            assert(backend.pendingImage.image == originalImage && !driver.pending && !backend.frameSlot.inFlight);
            assert(driver.waitCalls == driver.submits && driver.resets == driver.submits);
            assert(!driver.commandResets && !driver.begins && !driver.presents && !driver.cachePrepares);
            assert(backend.completeDuplicateFrame() && driver.submits == unsigned(commands || signal));
            backend.shutdown();
        }
    }
    {
        VulkanBackend backend;
        setup(backend);
        backend.pendingSignalSemaphore = object<VkSemaphore>(905);
        driver.submit = VK_ERROR_DEVICE_LOST;
        assert(!backend.completeDuplicateFrame() && backend.hasFatalError() && !backend.frameSlot.inFlight);
        assert(backend.pendingSignalSemaphore == VK_NULL_HANDLE && driver.submits == 1);
        assert(!backend.completeDuplicateFrame() && driver.submits == 1);
        backend.shutdown();
    }
    {
        VulkanBackend backend;
        setup(backend);
        backend.pendingSignalSemaphore = object<VkSemaphore>(906);
        driver.waits = {VK_TIMEOUT, VK_SUCCESS};
        assert(backend.completeDuplicateFrame() && driver.waitCalls == 2 && driver.resets == 1);
        assert(!driver.pending && !backend.frameSlot.inFlight);
        backend.shutdown();
    }
    {
        VulkanBackend backend;
        setup(backend);
        backend.pendingSignalSemaphore = object<VkSemaphore>(907);
        driver.waits = {VK_ERROR_OUT_OF_DEVICE_MEMORY};
        assert(!backend.completeDuplicateFrame() && backend.hasFatalError() && backend.frameSlot.inFlight);
        assert(!backend.completeDuplicateFrame() && driver.submits == 1);
        backend.shutdown();
    }
    std::puts("PASS duplicate frames consume command buffers and core signals once, without image or WSI work");
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

namespace {
struct FakeGameTraceBackend {
    static inline bool active = false;
    static inline unsigned begins = 0, ends = 0, depth = 0;
    static bool enabled() noexcept { return active; }
    static void begin(const char*) noexcept { ++begins; ++depth; }
    static void end() noexcept { assert(depth > 0); ++ends; --depth; }
};

void traceScopeContracts() {
    using Scope = libretrodroid::BasicGameTraceScope<FakeGameTraceBackend>;
    { Scope disabled("disabled"); }
    assert(FakeGameTraceBackend::begins == 0 && FakeGameTraceBackend::ends == 0);
    FakeGameTraceBackend::active = true;
    {
        Scope outer("outer");
        try {
            Scope inner("inner");
            throw std::runtime_error("test unwind");
        } catch (const std::runtime_error&) {
            assert(FakeGameTraceBackend::depth == 1);
        }
        FakeGameTraceBackend::active = false;
    }
    assert(FakeGameTraceBackend::begins == 2 && FakeGameTraceBackend::ends == 2);
    assert(FakeGameTraceBackend::depth == 0);
    assert(libretrodroid::traceGameCall("host return", [] { return 42; }) == 42);
    std::puts("PASS opt-in native trace scopes balance on exception and tracing toggle");
}
}

int main() {
    traceScopeContracts();
    frameSlotAllocationContracts();
    bridgeBudgetAdmissionContracts();
    slotFenceAndClearContracts();
    dualSlotRetirementContracts();
    slotRetirementPreflightContracts();
    timeoutsPreserveWork();
    errorsStopWorkAndResize();
    bridgeContextReplacementRetires();
    bridgeImportFailureLatches();
    resetAndSubmitFailures();
    safeCallbackAndTeardown();
    nativeSurfacePolicyAndSuccess();
    earlyNativePresentContracts();
    filteredPresetSwitchRetainsSwapchain();
    reusableSubmitStorage();
    readFenceQueueContracts();
    writeFenceQueueContracts();
    duplicateFrameContracts();
    managedBoundary();
    std::puts("PASS production Vulkan synchronization and presentation fault regression");
}
