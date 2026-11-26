// SPDX-License-Identifier: GPL-3.0-or-later
// FFI host for the pinned librashader Vulkan C API; no GL, Metal or D3D runtimes.
#include "vulkanslangpipeline.h"
#include <dlfcn.h>
#include <android/log.h>
#include <sys/stat.h>
#include <cstring>
#include <algorithm>
#include <cmath>
#include <utility>

namespace libretrodroid {

namespace {
    constexpr const char* TAG = "AmbientLibretroSlang";
    constexpr size_t MAX_PRESET_PATH_BYTES = 4096;
    bool isRegular(const std::string& path) {
        struct stat info {};
        return !path.empty() && path.size() < MAX_PRESET_PATH_BYTES &&
            path[0] == '/' && path.find("..") == std::string::npos &&
            lstat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode) &&
            path.size() >= 7 && path.compare(path.size() - 7, 7, ".slangp") == 0 &&
            info.st_size > 0 && info.st_size <= 64 * 1024;
    }
}
VulkanSlangPipeline::~VulkanSlangPipeline() {
    // Native backend must call releaseGpu BEFORE destroying VkDevice, then this destructor.
    releaseGpu();
    releaseLibrary();
}

void VulkanSlangPipeline::setPresetPath(const std::string& path) {
    if (presetPath == path) return;
    releaseGpu();
    presetPath = path;
    geometryBudget = SlangGeometryPolicy::classify(presetPath);
    errorForThisPreset = false;
}

void VulkanSlangPipeline::logError(const char* label, libra_error_t error) {
    if (error == nullptr) return;
    char* description = nullptr;
    if (writeError != nullptr && freeErrorString != nullptr &&
        writeError(error, &description) == 0 && description != nullptr) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s: %.320s", label, description);
        freeErrorString(&description);
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s: librashader error", label);
    }
    if (freeError != nullptr) freeError(&error);
}

bool VulkanSlangPipeline::loadLibrary() {
    if (dynamicLibrary != nullptr) return true;
    dynamicLibrary = dlopen("liblibrashader_capi.so", RTLD_NOW | RTLD_LOCAL);
    if (dynamicLibrary == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Vulkan Slang runtime unavailable: %s", dlerror());
        return false;
    }
#define LOAD(name) do { name = reinterpret_cast<PFN_libra_##name>(dlsym(dynamicLibrary, "libra_" #name)); \
    if (name == nullptr) { __android_log_print(ANDROID_LOG_WARN, TAG, "Missing librashader symbol: %s", #name); \
        releaseLibrary(); return false; } } while(0)
    // Named function pointer members do not share the bare names of exported symbols.
#define LOAD_MEMBER(member, name) do { member = reinterpret_cast<PFN_libra_##name>(dlsym(dynamicLibrary, "libra_" #name)); \
    if (member == nullptr) { __android_log_print(ANDROID_LOG_WARN, TAG, "Missing librashader symbol: %s", #name); \
        releaseLibrary(); return false; } } while(0)
    LOAD_MEMBER(abiVersion, instance_abi_version);
    LOAD_MEMBER(apiVersion, instance_api_version);
    LOAD_MEMBER(createPreset, preset_create_with_options);
    LOAD_MEMBER(presetColorSpace, preset_color_space);
    LOAD_MEMBER(freePreset, preset_free);
    LOAD_MEMBER(createChain, vk_filter_chain_create);
    LOAD_MEMBER(render, vk_filter_chain_frame);
    LOAD_MEMBER(freeChain, vk_filter_chain_free);
    LOAD_MEMBER(freeError, error_free);
    LOAD_MEMBER(writeError, error_write);
    LOAD_MEMBER(freeErrorString, error_free_string);
#undef LOAD_MEMBER
#undef LOAD
    if (abiVersion() != LIBRASHADER_CURRENT_ABI || apiVersion() < LIBRASHADER_CURRENT_VERSION) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Incompatible librashader ABI or API");
        releaseLibrary();
        return false;
    }
    return true;
}

bool VulkanSlangPipeline::ensure(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device,
                                 VkQueue graphicsQueue, PFN_vkGetInstanceProcAddr getInstanceProcAddr,
                                 const SlangFrameGeometry* geometry) {
    if (chain != nullptr) return !geometry ||
        SlangGeometryPolicy::allows(geometryBudget, *geometry, maxImageDimension);
    if (!requested() || errorForThisPreset) return false;
    if (instance == VK_NULL_HANDLE || physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE ||
        graphicsQueue == VK_NULL_HANDLE || getInstanceProcAddr == nullptr) {
        errorForThisPreset = true;
        return false;
    }
    const auto getProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
        getInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    if (getProperties == nullptr) {
        errorForThisPreset = true;
        return false;
    }
    VkPhysicalDeviceProperties properties {};
    getProperties(physicalDevice, &properties);
    maxImageDimension = std::min(SlangGeometryPolicy::maxDimension, properties.limits.maxImageDimension2D);
    // Reject a known oversized or invalid frame before parsing, loading or
    // compiling its filter chain. Geometry rejection is not a preset failure.
    if (geometry && !SlangGeometryPolicy::allows(geometryBudget, *geometry, maxImageDimension)) return false;
    if (!isRegular(presetPath) || !loadLibrary()) {
        errorForThisPreset = true;
        return false;
    }
    libra_shader_preset_t preset = nullptr;
    libra_preset_opt_t parseOptions {};
    parseOptions.version = LIBRASHADER_CURRENT_VERSION;
    parseOptions.original_aspect_uniforms = true;
    parseOptions.frametime_uniforms = true;
    libra_error_t error = createPreset(presetPath.c_str(), nullptr, &parseOptions, &preset);
    if (error != nullptr || preset == nullptr) {
        logError("preset parsing", error);
        errorForThisPreset = true;
        return false;
    }

    LIBRA_COLOR_SPACE sourceColor = LIBRA_COLOR_SPACE_SDR;
    error = presetColorSpace(&preset, &sourceColor);
    if (error != nullptr || sourceColor != LIBRA_COLOR_SPACE_SDR) {
        logError("preset color-space query", error);
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Only color-managed SDR Vulkan Slang output is enabled");
        freePreset(&preset);
        errorForThisPreset = true;
        return false;
    }
    filter_chain_vk_opt_t options {};
    options.version = LIBRASHADER_CURRENT_VERSION;
    // Keep one temporal chain across both bridge slots. The backend's history
    // ownership ledger waits the last shader submission before recording again.
    options.frames_in_flight = 1;
    options.force_no_mipmaps = false;
    options.use_dynamic_rendering = false;  // Vulkan 1.0 renderpass-compatible devices
    options.disable_cache = true;           // no writable executable shader cache

    libra_device_vk_t vkDevice { physicalDevice, instance, device, graphicsQueue, getInstanceProcAddr };
    error = createChain(&preset, vkDevice, &options, &chain);
    // Preset is consumed by createChain, including error handling in its C API contract.
    if (error != nullptr || chain == nullptr) {
        logError("Vulkan filter-chain creation", error);
        if (preset != nullptr) freePreset(&preset);
        errorForThisPreset = true;
        return false;
    }
    return true;
}

bool VulkanSlangPipeline::record(VkCommandBuffer commands, uint64_t frameCount,
                                  VkImage input, VkFormat inputFormat,
                                  uint32_t sourceWidth, uint32_t sourceHeight,
                                  VkImage target, VkFormat targetFormat,
                                  uint32_t targetWidth, uint32_t targetHeight,
                                  float viewportX, float viewportY,
                                  uint32_t viewportWidth, uint32_t viewportHeight,
                                  float sourceAspect, double coreFps) {
    const SlangFrameGeometry geometry {sourceWidth, sourceHeight, targetWidth, targetHeight,
        viewportX, viewportY, viewportWidth, viewportHeight};
    if (chain == nullptr || errorForThisPreset || commands == VK_NULL_HANDLE ||
        input == VK_NULL_HANDLE || target == VK_NULL_HANDLE ||
        !SlangGeometryPolicy::allows(geometryBudget, geometry, maxImageDimension)) return false;

    libra_image_vk_t source { input, inputFormat, sourceWidth, sourceHeight };
    libra_image_vk_t output { target, targetFormat, targetWidth, targetHeight };
    libra_viewport_t viewport { viewportX, viewportY, viewportWidth, viewportHeight };
    frame_vk_opt_t options {};
    options.version = LIBRASHADER_CURRENT_VERSION;
    options.frame_direction = 1;
    options.total_subframes = 1;
    options.current_subframe = 1;
    options.aspect_ratio = sourceAspect;
    // Read libretro core timing instead of hardcoding 60 fps for the Slang uniforms.
    options.frames_per_second = std::isfinite(coreFps) && coreFps >= 1.0 &&
        coreFps <= 240.0 ? static_cast<float>(coreFps) : 60.0f;
    const auto now = std::chrono::steady_clock::now();
    if (hasPreviousFrameTime) {
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - previousFrameTime).count();
        options.frametime_delta = static_cast<uint32_t>(std::clamp<int64_t>(elapsed, 0, 250));
    }
    previousFrameTime = now;
    hasPreviousFrameTime = true;
    options.color_space = LIBRA_COLOR_SPACE_SDR;
    options.brightness_nits = 200.0f;

    libra_error_t error = render(&chain, commands, static_cast<size_t>(frameCount),
                                 source, output, &viewport, nullptr, &options);
    if (error != nullptr) {
        logError("Vulkan frame shader", error);
        errorForThisPreset = true;
        return false;
    }
    return true;
}

void VulkanSlangPipeline::releaseGpu() {
    if (chain != nullptr && freeChain != nullptr) {
        logError("Vulkan filter-chain cleanup", freeChain(&chain));
    }
    chain = nullptr;
    hasPreviousFrameTime = false;
}

void VulkanSlangPipeline::releaseLibrary() {
    // No GL, Vulkan device, or queue callbacks are accessed after the library is unloaded.
    if (dynamicLibrary != nullptr) dlclose(dynamicLibrary);
    dynamicLibrary = nullptr;
    abiVersion = nullptr;
    apiVersion = nullptr;
    createPreset = nullptr;
    presetColorSpace = nullptr;
    freePreset = nullptr;
    createChain = nullptr;
    render = nullptr;
    freeChain = nullptr;
    freeError = nullptr;
    writeError = nullptr;
    freeErrorString = nullptr;
}
}
