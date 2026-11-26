// Original Ambient TV glue to librashader 0.12.1 (MPL-2.0 / GPL-3.0), Android Vulkan only.
// The upstream librashader.h is vendored separately under the MIT license.
#pragma once

#define LIBRA_RUNTIME_VULKAN
#include "librashader.h"
#include "vulkanslanggeometry.h"
#include <cstdint>
#include <chrono>
#include <string>

namespace libretrodroid {

class VulkanSlangPipeline {
public:
    VulkanSlangPipeline() = default;
    VulkanSlangPipeline(const VulkanSlangPipeline&) = delete;
    VulkanSlangPipeline& operator=(const VulkanSlangPipeline&) = delete;
    ~VulkanSlangPipeline();

    // All methods run on the emulation GL thread, except none: never share the chain across threads.
    // The path is app-private, selected from installed signed content or pinned built-in resources.
    void setPresetPath(const std::string& path);
    bool requested() const { return !presetPath.empty(); }
    bool ready() const { return chain != nullptr; }
    bool failed() const { return errorForThisPreset; }

    // Called only after a real Vulkan VkDevice and presentation queue exist, outside recording.
    bool ensure(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device,
                VkQueue graphicsQueue, PFN_vkGetInstanceProcAddr getInstanceProcAddr,
                const SlangFrameGeometry* geometry = nullptr);
    bool record(VkCommandBuffer commands, uint64_t frameCount,
                VkImage input, VkFormat inputFormat, uint32_t sourceWidth, uint32_t sourceHeight,
                VkImage target, VkFormat targetFormat, uint32_t targetWidth, uint32_t targetHeight,
                float viewportX, float viewportY, uint32_t viewportWidth, uint32_t viewportHeight,
                float sourceAspect, double coreFps);
    void releaseGpu();   // Must be called after the queue/device is idle (also on resize/shutdown).

private:
    bool loadLibrary();
    void logError(const char* label, libra_error_t error);
    void releaseLibrary();

    std::string presetPath;
    SlangGeometryPolicy::Budget geometryBudget = SlangGeometryPolicy::Budget::Ordinary;
    void* dynamicLibrary = nullptr;
    libra_vk_filter_chain_t chain = nullptr;
    bool errorForThisPreset = false;
    uint32_t maxImageDimension = 0;
    std::chrono::steady_clock::time_point previousFrameTime {};
    bool hasPreviousFrameTime = false;

    PFN_libra_instance_abi_version abiVersion = nullptr;
    PFN_libra_instance_api_version apiVersion = nullptr;
    PFN_libra_preset_create_with_options createPreset = nullptr;
    PFN_libra_preset_color_space presetColorSpace = nullptr;
    PFN_libra_preset_free freePreset = nullptr;
    PFN_libra_vk_filter_chain_create createChain = nullptr;
    PFN_libra_vk_filter_chain_frame render = nullptr;
    PFN_libra_vk_filter_chain_free freeChain = nullptr;
    PFN_libra_error_free freeError = nullptr;
    PFN_libra_error_write writeError = nullptr;
    PFN_libra_error_free_string freeErrorString = nullptr;
};
}
