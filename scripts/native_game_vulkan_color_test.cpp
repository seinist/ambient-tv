// SPDX-License-Identifier: GPL-3.0-or-later
// Executes the production encoded-color copy helper on a real Vulkan device.
#include "vulkan/vulkancolorcopy.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using libretrodroid::VulkanColorCopy;

static void require(bool value, const std::string& message) {
    if (!value) throw std::runtime_error(message);
}
static void check(VkResult result, const char* operation) {
    require(result == VK_SUCCESS, std::string(operation) + ": " + std::to_string(result));
}

struct Device {
    VkInstance instance {};
    VkPhysicalDevice physical {};
    VkDevice device {};
    VkQueue queue {};
    VkCommandPool pool {};
    VkCommandBuffer commands {};
    VkBuffer staging {};
    VkDeviceMemory stagingMemory {};
    void* mapped {};
    VulkanColorCopy color;
    unsigned cases = 0;
    unsigned unsupportedCases = 0;

    Device() {
        VkApplicationInfo app { VK_STRUCTURE_TYPE_APPLICATION_INFO };
        app.pApplicationName = "Ambient TV encoded color regression";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo instanceInfo { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
        instanceInfo.pApplicationInfo = &app;
        check(vkCreateInstance(&instanceInfo, nullptr, &instance), "create instance");
        uint32_t count = 0;
        check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "enumerate device count");
        require(count != 0, "no Vulkan device; install mesa-vulkan-drivers for lavapipe");
        std::vector<VkPhysicalDevice> devices(count);
        check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "enumerate devices");
        physical = devices.front();
        VkPhysicalDeviceProperties properties {};
        vkGetPhysicalDeviceProperties(physical, &properties);
        std::cout << "Vulkan device: " << properties.deviceName << '\n';
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
        std::vector<VkQueueFamilyProperties> families(count);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families.data());
        uint32_t family = 0;
        while (family < count && !(families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) ++family;
        require(family < count, "no graphics queue");
        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
        queueInfo.queueFamilyIndex = family;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        VkDeviceCreateInfo deviceInfo { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        check(vkCreateDevice(physical, &deviceInfo, nullptr, &device), "create device");
        vkGetDeviceQueue(device, family, 0, &queue);
        VkCommandPoolCreateInfo poolInfo { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = family;
        check(vkCreateCommandPool(device, &poolInfo, nullptr, &pool), "create command pool");
        VkCommandBufferAllocateInfo commandInfo { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
        commandInfo.commandPool = pool;
        commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commandInfo.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device, &commandInfo, &commands), "allocate command buffer");
        VkBufferCreateInfo bufferInfo { VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
        bufferInfo.size = 65536;
        bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        check(vkCreateBuffer(device, &bufferInfo, nullptr, &staging), "create staging buffer");
        VkMemoryRequirements requirements {};
        vkGetBufferMemoryRequirements(device, staging, &requirements);
        stagingMemory = allocate(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        check(vkBindBufferMemory(device, staging, stagingMemory, 0), "bind staging memory");
        check(vkMapMemory(device, stagingMemory, 0, VK_WHOLE_SIZE, 0, &mapped), "map staging memory");
        require(color.initialize(instance, physical, device, vkGetInstanceProcAddr), color.error());
    }
    ~Device() {
        if (device) {
            vkDeviceWaitIdle(device);
            color.release();
            if (mapped) vkUnmapMemory(device, stagingMemory);
            if (staging) vkDestroyBuffer(device, staging, nullptr);
            if (stagingMemory) vkFreeMemory(device, stagingMemory, nullptr);
            if (pool) vkDestroyCommandPool(device, pool, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (instance) vkDestroyInstance(instance, nullptr);
    }
    VkDeviceMemory allocate(const VkMemoryRequirements& requirements, VkMemoryPropertyFlags flags) {
        VkPhysicalDeviceMemoryProperties properties {};
        vkGetPhysicalDeviceMemoryProperties(physical, &properties);
        uint32_t type = 0;
        while (type < properties.memoryTypeCount &&
               (!(requirements.memoryTypeBits & (1u << type)) ||
                (properties.memoryTypes[type].propertyFlags & flags) != flags)) ++type;
        require(type < properties.memoryTypeCount, "no compatible memory type");
        VkMemoryAllocateInfo info { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
        info.allocationSize = requirements.size;
        info.memoryTypeIndex = type;
        VkDeviceMemory memory {};
        check(vkAllocateMemory(device, &info, nullptr, &memory), "allocate memory");
        return memory;
    }
    void begin() {
        check(vkResetCommandBuffer(commands, 0), "reset commands");
        VkCommandBufferBeginInfo info { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        check(vkBeginCommandBuffer(commands, &info), "begin commands");
    }
    void submit() {
        check(vkEndCommandBuffer(commands), "end commands");
        VkSubmitInfo submitInfo { VK_STRUCTURE_TYPE_SUBMIT_INFO };
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commands;
        check(vkQueueSubmit(queue, 1, &submitInfo, VK_NULL_HANDLE), "submit");
        check(vkQueueWaitIdle(queue), "wait for pixels");
    }
    void barrier(VkImage image, VkImageLayout before, VkImageLayout after,
                 VkImageSubresourceRange range = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }) {
        VkImageMemoryBarrier info { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        info.srcAccessMask = before == VK_IMAGE_LAYOUT_UNDEFINED ? 0u :
            VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
        info.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
        info.oldLayout = before;
        info.newLayout = after;
        info.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        info.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        info.image = image;
        info.subresourceRange = range;
        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &info);
    }
};

struct Image {
    Device& owner;
    VkImage image {};
    VkDeviceMemory memory {};
    VkImageView view {};
    Image(Device& owner, VkFormat backing, VkFormat viewFormat, uint32_t width, uint32_t height,
          VkImageSubresourceRange range = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
          VkComponentMapping mapping = {}) : owner(owner) {
        VkImageCreateInfo info { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
        info.flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
        info.imageType = VK_IMAGE_TYPE_2D;
        info.format = backing;
        info.extent = { width << range.baseMipLevel, height << range.baseMipLevel, 1 };
        info.mipLevels = 1 + range.baseMipLevel;
        info.arrayLayers = 1 + range.baseArrayLayer;
        info.samples = VK_SAMPLE_COUNT_1_BIT;
        info.tiling = VK_IMAGE_TILING_OPTIMAL;
        info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        check(vkCreateImage(owner.device, &info, nullptr, &image), "create image");
        VkMemoryRequirements requirements {};
        vkGetImageMemoryRequirements(owner.device, image, &requirements);
        memory = owner.allocate(requirements, 0);
        check(vkBindImageMemory(owner.device, image, memory, 0), "bind image memory");
        VkImageViewCreateInfo viewInfo { VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = viewFormat;
        viewInfo.subresourceRange = range;
        viewInfo.components = mapping;
        check(vkCreateImageView(owner.device, &viewInfo, nullptr, &view), "create actual core image view");
    }
    ~Image() {
        vkDestroyImageView(owner.device, view, nullptr);
        vkDestroyImage(owner.device, image, nullptr);
        vkFreeMemory(owner.device, memory, nullptr);
    }
};

static float halfFloat(uint16_t bits) {
    const float sign = bits & 0x8000 ? -1.0f : 1.0f;
    const int exponent = (bits >> 10) & 31;
    const int fraction = bits & 1023;
    if (exponent == 0) return sign * std::ldexp(float(fraction), -24);
    if (exponent == 31) return sign * INFINITY;
    return sign * std::ldexp(float(1024 + fraction), exponent - 25);
}

static std::vector<uint8_t> bytes(const std::vector<uint32_t>& values) {
    std::vector<uint8_t> result(values.size() * sizeof(uint32_t));
    std::memcpy(result.data(), values.data(), result.size());
    return result;
}

static std::vector<float> pixels(const void* data, VkFormat format, unsigned count) {
    std::vector<float> result(count * 4);
    for (unsigned i = 0; i < count; ++i) {
        if (format == VK_FORMAT_R16G16B16A16_SFLOAT) {
            auto source = static_cast<const uint16_t*>(data) + 4 * i;
            for (unsigned c = 0; c < 4; ++c) result[4*i+c] = halfFloat(source[c]);
        } else if (format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
                   format == VK_FORMAT_A2R10G10B10_UNORM_PACK32) {
            uint32_t word;
            std::memcpy(&word, static_cast<const uint8_t*>(data) + i*4, 4);
            result[4*i] = float(word & 1023) / 1023;
            result[4*i+1] = float((word >> 10) & 1023) / 1023;
            result[4*i+2] = float((word >> 20) & 1023) / 1023;
            result[4*i+3] = float(word >> 30) / 3;
            if (format == VK_FORMAT_A2R10G10B10_UNORM_PACK32) std::swap(result[4*i], result[4*i+2]);
        } else {
            auto source = static_cast<const uint8_t*>(data) + 4*i;
            for (unsigned c = 0; c < 4; ++c) result[4*i+c] = float(source[c]) / 255;
            if (format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB) {
                std::swap(result[4*i], result[4*i+2]);
            }
        }
    }
    return result;
}

static void run(Device& d, const std::string& name, VkFormat backing, VkFormat view,
                VkFormat target, const std::vector<uint8_t>& input, bool padded = false,
                VkComponentMapping mapping = {}, bool subresource = false, bool oldBlit = false,
                uint32_t width = 4) {
    const uint32_t height = 1;
    const uint32_t targetWidth = padded ? 8 : width;
    const uint32_t targetHeight = padded ? 3 : height;
    const VkImageSubresourceRange range { VK_IMAGE_ASPECT_COLOR_BIT, subresource ? 1u : 0u,
                                         1, subresource ? 1u : 0u, 1 };
    Image source(d, backing, view, width, height, range, mapping);
    Image destination(d, target, target, targetWidth, targetHeight);
    VulkanColorCopy::Source descriptor { view, mapping, VK_IMAGE_VIEW_TYPE_2D, range, width, height };
    if (!d.color.prepare(descriptor, target, targetWidth, targetHeight)) {
        VkFormatProperties sourceProperties {}, targetProperties {};
        const auto normalizedFormat = VulkanColorCopy::encodedFormat(view, mapping);
        vkGetPhysicalDeviceFormatProperties(d.physical, normalizedFormat, &sourceProperties);
        vkGetPhysicalDeviceFormatProperties(d.physical, target, &targetProperties);
        const VkFormatFeatureFlags required = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        const bool unsupportedConversion = (sourceProperties.optimalTilingFeatures & required) != required ||
            !(targetProperties.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT);
        require(unsupportedConversion && std::string(d.color.error()) == "normalized format conversion unavailable on device",
                name + ": " + d.color.error());
        ++d.unsupportedCases;
        return;
    }
    const auto normalized = d.color.preparedImage(destination.image);
    require(normalized.width == width && normalized.height == height, "source dimensions must exclude AHB padding");
    require(normalized.image != VK_NULL_HANDLE, "prepared source image missing");
    if (padded) require(d.color.usesCache() && !normalized.sharedBridge, "shrinking core must use tight cache");
    auto expected = pixels(input.data(), view, width);
    if (mapping.r == VK_COMPONENT_SWIZZLE_B && mapping.b == VK_COMPONENT_SWIZZLE_R) {
        for (uint32_t i = 0; i < width; ++i) std::swap(expected[4*i], expected[4*i+2]);
    }
    std::memcpy(d.mapped, input.data(), input.size());
    d.begin();
    d.barrier(source.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
    VkBufferImageCopy upload {};
    upload.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, range.baseMipLevel, range.baseArrayLayer, 1 };
    upload.imageExtent = { width, height, 1 };
    vkCmdCopyBufferToImage(d.commands, d.staging, source.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &upload);
    d.barrier(source.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, range);
    d.barrier(destination.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    if (oldBlit) {
        VkImageBlit blit {};
        blit.srcSubresource = upload.imageSubresource;
        blit.srcOffsets[1] = { int32_t(width), int32_t(height), 1 };
        blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
        blit.dstOffsets[1] = blit.srcOffsets[1];
        vkCmdBlitImage(d.commands, source.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.image,
                       VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
    } else {
        const auto recorded = d.color.record(d.commands, source.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                            descriptor, destination.image);
        require(recorded.image == normalized.image && recorded.format == normalized.format,
                "prepared and recorded source must agree");
    }
    if (oldBlit || !normalized.sharedBridge) {
        d.barrier(destination.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    }
    VkBufferImageCopy download {};
    download.bufferOffset = 4096;
    download.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    download.imageExtent = { width, height, 1 };
    vkCmdCopyImageToBuffer(d.commands, destination.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.staging, 1, &download);
    d.submit();
    auto actual = pixels(static_cast<const uint8_t*>(d.mapped) + 4096, target, width);
    if (oldBlit) {
        require(std::abs(actual[0] - expected[0]) > 0.2f, "old sRGB blit bug must be reproduced");
        std::cout << "Reproduced old backing-sRGB bug: gray128 -> " << std::lround(actual[0]*255) << '\n';
    } else {
        const float tolerance = target == VK_FORMAT_R16G16B16A16_SFLOAT ? 0.001f : 1.01f/255;
        for (unsigned i = 0; i < actual.size(); ++i) {
            const float value = target == VK_FORMAT_R8G8B8A8_UNORM ? std::clamp(expected[i], 0.0f, 1.0f) : expected[i];
            require(std::isfinite(actual[i]) && std::abs(actual[i]-value) <= tolerance,
                    name + " component " + std::to_string(i) + ": got " + std::to_string(actual[i]) +
                    ", expected " + std::to_string(value));
        }
        ++d.cases;
    }
}


using libretrodroid::VulkanColorCacheRetention;

static void testRetentionPolicy() {
    VulkanColorCacheRetention policy;
    for (uint32_t i = 0; i < 1000; ++i) {
        policy.reset();
        require(!policy.retainDirectFrame(0, true), "zero allocation retained");
        require(!policy.retainDirectFrame(1, false), "different extent retained");
        require(!policy.retainDirectFrame(VulkanColorCacheRetention::maxIdleBytes + 1, true),
                "oversize idle allocation retained");
        const uint64_t bytes = i % 2 == 0 ? 1 : VulkanColorCacheRetention::maxIdleBytes;
        for (uint32_t frame = 0; frame < VulkanColorCacheRetention::maxDirectFrames; ++frame) {
            require(policy.retainDirectFrame(bytes, true), "small warm cache expired too soon");
        }
        require(!policy.retainDirectFrame(bytes, true), "idle image did not expire");
        require(!policy.retainDirectFrame(bytes, true), "expired image was retained again");
        policy.reset();
        require(policy.retainDirectFrame(bytes, true), "normalization did not reset retention");
    }
}

// Count only allocations issued by the production helper, not the test's
// per-case input/output images. Handles alone can be recycled by the driver.
static struct CacheAllocationCalls {
    unsigned created = 0, destroyed = 0, allocated = 0, freed = 0;
    VkDeviceSize lastBytes = 0;
    unsigned formatQueries = 0;
    bool failNextAllocation = false;
} cacheCalls;

static VKAPI_ATTR VkResult VKAPI_CALL trackedCreateImage(VkDevice device,
        const VkImageCreateInfo* info, const VkAllocationCallbacks* allocator, VkImage* image) {
    const auto result = vkCreateImage(device, info, allocator, image);
    if (result == VK_SUCCESS) ++cacheCalls.created;
    return result;
}
static VKAPI_ATTR void VKAPI_CALL trackedDestroyImage(VkDevice device, VkImage image,
        const VkAllocationCallbacks* allocator) {
    vkDestroyImage(device, image, allocator);
    ++cacheCalls.destroyed;
}
static VKAPI_ATTR VkResult VKAPI_CALL trackedAllocateMemory(VkDevice device,
        const VkMemoryAllocateInfo* info, const VkAllocationCallbacks* allocator, VkDeviceMemory* memory) {
    if (cacheCalls.failNextAllocation) {
        cacheCalls.failNextAllocation = false;
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    const auto result = vkAllocateMemory(device, info, allocator, memory);
    if (result == VK_SUCCESS) {
        ++cacheCalls.allocated;
        cacheCalls.lastBytes = info->allocationSize;
    }
    return result;
}
static VKAPI_ATTR void VKAPI_CALL trackedFreeMemory(VkDevice device, VkDeviceMemory memory,
        const VkAllocationCallbacks* allocator) {
    vkFreeMemory(device, memory, allocator);
    ++cacheCalls.freed;
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL trackedGetDeviceProcAddr(VkDevice device, const char* name) {
    if (std::strcmp(name, "vkCreateImage") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(trackedCreateImage);
    if (std::strcmp(name, "vkDestroyImage") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(trackedDestroyImage);
    if (std::strcmp(name, "vkAllocateMemory") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(trackedAllocateMemory);
    if (std::strcmp(name, "vkFreeMemory") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(trackedFreeMemory);
    return vkGetDeviceProcAddr(device, name);
}
static VKAPI_ATTR void VKAPI_CALL trackedFormatProperties(VkPhysicalDevice gpu, VkFormat format,
        VkFormatProperties* properties) {
    ++cacheCalls.formatQueries;
    vkGetPhysicalDeviceFormatProperties(gpu, format, properties);
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL trackedGetInstanceProcAddr(VkInstance instance, const char* name) {
    if (std::strcmp(name, "vkGetDeviceProcAddr") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(trackedGetDeviceProcAddr);
    if (std::strcmp(name, "vkGetPhysicalDeviceFormatProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(trackedFormatProperties);
    return vkGetInstanceProcAddr(instance, name);
}

static void testFormatFeatureCache(Device& d) {
    d.color.release();
    cacheCalls = {};
    const std::array<VkFormat, 5> formats { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM,
        VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_A2R10G10B10_UNORM_PACK32,
        VK_FORMAT_R16G16B16A16_SFLOAT };
    for (unsigned generation = 0; generation < 2; ++generation) {
        require(d.color.initialize(d.instance, d.physical, d.device, trackedGetInstanceProcAddr), d.color.error());
        for (unsigned frame = 0; frame < 64; ++frame) {
            for (auto format : formats) {
                VkFormatProperties actual {};
                vkGetPhysicalDeviceFormatProperties(d.physical, format, &actual);
                require(d.color.optimalFeatures(format) == actual.optimalTilingFeatures, "cached format mask differs");
            }
            require(d.color.optimalFeatures(VK_FORMAT_UNDEFINED) == 0, "unknown format was accepted");
            // initialize is called by the real backend on every frame.
            require(d.color.initialize(d.instance, d.physical, d.device, trackedGetInstanceProcAddr), d.color.error());
        }
        require(cacheCalls.formatQueries == (generation + 1) * formats.size(), "repeated driver format queries");
        VulkanColorCopy::Source descriptor {};
        descriptor.viewFormat = VK_FORMAT_R8G8B8A8_UNORM;
        descriptor.width = 4;
        descriptor.height = 1;
        const bool supported = (d.color.optimalFeatures(formats[0]) &
            (VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT)) ==
            (VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT);
        require(d.color.prepare(descriptor, formats[0], 8, 3) == supported,
                "normalization feature checks changed");
        require(cacheCalls.formatQueries == (generation + 1) * formats.size(), "normalization bypassed feature cache");
        d.color.release();
        require(d.color.optimalFeatures(formats[0]) == 0, "released device exposed stale capabilities");
    }
}

static void testWarmNormalizationCache(Device& d, const std::vector<uint8_t>& rgba) {
    d.color.release(); // the previous run has already completed its queue work
    cacheCalls = {};
    require(d.color.initialize(d.instance, d.physical, d.device, trackedGetInstanceProcAddr), d.color.error());
    auto changingPixels = rgba;
    const auto normalize = [&] {
        changingPixels[0] ^= 31u;
        run(d, "warm normalized", VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_R8G8B8A8_UNORM, changingPixels, true);
        require(d.color.usesCache(), "normalization was bypassed");
    };
    const auto direct = [&] {
        changingPixels[4] ^= 127u;
        run(d, "warm direct", VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_R8G8B8A8_UNORM, changingPixels);
        require(!d.color.usesCache(), "direct frame selected retained image");
    };
    const auto counts = [&](unsigned creates, unsigned destroys) {
        require(cacheCalls.created == creates && cacheCalls.allocated == creates &&
                cacheCalls.destroyed == destroys && cacheCalls.freed == destroys,
                "unexpected normalization image/memory allocation or retirement");
    };
    normalize();
    counts(1, 0);
    require(cacheCalls.lastBytes > 0 && cacheCalls.lastBytes <= VulkanColorCacheRetention::maxIdleBytes,
            "tiny test image exceeds warm cache budget");
    for (unsigned i = 0; i < 12; ++i) {
        direct();
        normalize(); // overwrite every texel; readback verifies no stale source or color change
        counts(1, 0);
    }
    for (unsigned i = 0; i < VulkanColorCacheRetention::maxDirectFrames; ++i) {
        direct();
        counts(1, 0);
    }
    direct();
    counts(1, 1);
    direct();
    counts(1, 1);
    normalize();
    counts(2, 1);

    VulkanColorCopy::Source descriptor {};
    descriptor.viewFormat = VK_FORMAT_R8G8B8A8_UNORM;
    descriptor.width = 2;
    descriptor.height = 1;
    require(d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 2, 1), d.color.error());
    require(!d.color.usesCache(), "resized direct frame selected retained image");
    counts(2, 2);

    descriptor.width = 2048;
    descriptor.height = 1024;
    require(d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 2049, 1024), d.color.error());
    counts(3, 2);
    require(cacheCalls.lastBytes > VulkanColorCacheRetention::maxIdleBytes, "large image must exceed idle budget");
    require(d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 2048, 1024), d.color.error());
    counts(3, 3);
    normalize();
    counts(4, 3);
    direct();
    counts(4, 3);
    d.color.release(); // even an idle retained allocation must retire at device shutdown
    counts(4, 4);
    d.color.release();
    counts(4, 4);
}

static void testAlternateNormalizationCache(Device& d, const std::vector<uint8_t>& rgba) {
    d.color.release();
    cacheCalls = {};
    require(d.color.initialize(d.instance, d.physical, d.device, trackedGetInstanceProcAddr), d.color.error());
    auto changing = rgba;
    const auto normalize = [&](uint32_t width) {
        changing[0] ^= 31u;
        run(d, "alternate normalized", VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_R8G8B8A8_UNORM, changing, true, {}, false, false, width);
    };
    const auto counts = [&](unsigned creates, unsigned destroys, unsigned allocs, unsigned frees) {
        require(cacheCalls.created == creates && cacheCalls.destroyed == destroys &&
                cacheCalls.allocated == allocs && cacheCalls.freed == frees,
                "unexpected alternate-cache allocation/retirement count");
    };
    normalize(4);
    counts(1, 0, 1, 0);
    normalize(2);
    counts(2, 0, 2, 0);
    for (unsigned i = 0; i < 20; ++i) {
        normalize(4);
        normalize(2);
        counts(2, 0, 2, 0);
    }
    for (unsigned i = 0; i < libretrodroid::VulkanColorAlternatePolicy::maxIdleFrames; ++i) {
        normalize(2);
        counts(2, 0, 2, 0);
    }
    normalize(2);
    counts(2, 1, 2, 1);
    normalize(4);
    counts(3, 1, 3, 1);
    run(d, "alternate direct", VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM,
        VK_FORMAT_R8G8B8A8_UNORM, changing);
    counts(3, 2, 3, 2);
    d.color.release();
    counts(3, 3, 3, 3);

    // A retained predecessor must never turn a recoverable memory-pressure
    // failure into a rejected frame. Actual driver allocation succeeds on retry.
    cacheCalls = {};
    require(d.color.initialize(d.instance, d.physical, d.device, trackedGetInstanceProcAddr), d.color.error());
    normalize(4);
    cacheCalls.failNextAllocation = true;
    normalize(2);
    counts(3, 2, 2, 1); // failed image, evicted predecessor, successful retry
    d.color.release();
    counts(3, 3, 2, 2);
    d.color.release();
    counts(3, 3, 2, 2);
}

static void testSharedNormalizationBudget(Device& d) {
    // Use the actual driver allocation size for this exact image geometry.
    d.color.release();
    cacheCalls = {};
    VulkanColorCopy::Source source {};
    source.viewFormat = VK_FORMAT_R8G8B8A8_UNORM;
    source.width = 4;
    source.height = 1;
    const auto format = VK_FORMAT_R8G8B8A8_UNORM;
    require(d.color.initialize(d.instance, d.physical, d.device, trackedGetInstanceProcAddr), d.color.error());
    require(d.color.prepare(source, format, 8, 3), d.color.error());
    require(cacheCalls.allocated == 1 && cacheCalls.lastBytes > 0, "expected a driver-sized cache allocation");
    libretrodroid::VulkanColorAllocationBudget budget(cacheCalls.lastBytes), other;
    d.color.release();
    cacheCalls = {};
    VulkanColorCopy first, second;
    for (auto* copy : {&first, &second}) {
        require(copy->initialize(d.instance, d.physical, d.device, trackedGetInstanceProcAddr), copy->error());
        require(copy->bindAllocationBudget(budget), copy->error());
    }
    require(first.prepare(source, format, 8, 3), first.error());
    require(budget.available() == 0 && budget.used() == cacheCalls.lastBytes, "driver bytes not charged");
    require(first.bindAllocationBudget(budget), "same owner must remain valid");
    require(!first.bindAllocationBudget(other), "live cache changed accounting owner");
    require(!second.prepare(source, format, 8, 3), "two slots exceeded shared budget");
    require(second.preparedImage(VK_NULL_HANDLE).image == VK_NULL_HANDLE && cacheCalls.allocated == 1,
            "budget rejection allocated memory or published stale image");
    // The direct bridge needs no normalization allocation, even at full capacity.
    require(second.prepare(source, format, 4, 1) && !second.usesCache(), second.error());
    require(budget.available() == 0 && cacheCalls.allocated == 1, "direct path changed shared usage");
    first.release();
    require(budget.used() == 0 && cacheCalls.freed == 1, "first slot did not return its reservation");
    cacheCalls.failNextAllocation = true;
    require(!second.prepare(source, format, 8, 3), "injected allocation failure unexpectedly succeeded");
    require(budget.used() == 0 && cacheCalls.allocated == 1, "failed allocation leaked reservation");
    require(second.prepare(source, format, 8, 3), second.error());
    require(budget.available() == 0 && cacheCalls.allocated == 2, "retry did not reserve available capacity");
    second.release();
    second.release();
    require(budget.used() == 0 && cacheCalls.freed == 2 && cacheCalls.created == cacheCalls.destroyed,
            "shared cache leaked resources or charged bytes");
}

int main() {
    try {
        testRetentionPolicy();
        Device d;
        const std::vector<uint8_t> rgba {128,128,128,255, 224,32,16,255, 16,64,224,255, 64,192,32,128};
        const VkComponentMapping swap { VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_G,
                                        VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_A };
        const VkComponentMapping explicitIdentity { VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G,
                                                    VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_A };
        run(d, "old bug", VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_R8G8B8A8_UNORM, rgba, false, {}, false, true);
        const std::array<VkFormat,4> eightBit { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB,
                                               VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB };
        for (auto backing : eightBit) {
            for (auto view : eightBit) {
                for (auto target : { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT }) {
                    const std::string name = "8-bit backing=" + std::to_string(backing) + " view=" + std::to_string(view);
                    run(d, name, backing, view, target, rgba);
                    run(d, name + " R/B", backing, view, target, rgba, false, swap);
                    run(d, name + " padding/mip/layer", backing, view, target, rgba, true, explicitIdentity, true);
                }
            }
        }
        const auto packed = bytes({ (3u<<30)|(864u<<20)|(96u<<10)|512u,
                                    (3u<<30)|(64u<<20)|(160u<<10)|960u,
                                    (3u<<30)|(960u<<20)|(256u<<10)|64u,
                                    (1u<<30)|(128u<<20)|(768u<<10)|256u });
        for (auto format : { VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_A2R10G10B10_UNORM_PACK32 }) {
            for (auto target : { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT }) {
                run(d, "packed10", format, format, target, packed);
                run(d, "packed10 R/B", format, format, target, packed, true, swap, true);
            }
        }
        const std::vector<uint16_t> halves {0x3800,0x3400,0x3c00,0x3800, 0x3000,0x3a00,0,0x3c00,
                                            0x3e00,0xb400,0x3800,0x3c00, 0x3c00,0,0x3c00,0x3400};
        std::vector<uint8_t> floating(halves.size()*2);
        std::memcpy(floating.data(), halves.data(), floating.size());
        for (auto target : { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT }) {
            run(d, "FP16", VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT, target, floating);
        }
        VulkanColorCopy::Source descriptor {};
        descriptor.viewFormat = VK_FORMAT_R8G8B8A8_SRGB;
        descriptor.width = 4;
        descriptor.height = 1;
        require(d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 8, 3), d.color.error());
        const auto first = d.color.preparedImage(VK_NULL_HANDLE).image;
        require(d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 8, 3), d.color.error());
        require(first == d.color.preparedImage(VK_NULL_HANDLE).image, "same source extent reuses cache");
        descriptor.width = 2;
        require(d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 8, 3), d.color.error());
        require(d.color.preparedImage(VK_NULL_HANDLE).width == 2, "cache must shrink with source");
        require(d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 2, 1), d.color.error());
        require(!d.color.usesCache(), "matching RGBA8 must avoid cache and format blit");
        descriptor.components.a = VK_COMPONENT_SWIZZLE_ONE;
        require(!d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 2, 1), "unsupported component map accepted");
        require(d.color.preparedImage(VK_NULL_HANDLE).image == VK_NULL_HANDLE, "rejected prepare exposed stale cache");
        descriptor.components = {};
        descriptor.viewFormat = VK_FORMAT_R8G8B8A8_UINT;
        require(!d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 2, 1), "integer source accepted");
        descriptor.viewFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
        descriptor.components = swap;
        require(!d.color.prepare(descriptor, VK_FORMAT_R16G16B16A16_SFLOAT, 2, 1), "unsupported FP16 swizzle accepted");
        descriptor.viewFormat = VK_FORMAT_R8G8B8A8_UNORM;
        descriptor.components = {};
        descriptor.range.aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
        require(!d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 2, 1), "depth source accepted");
        descriptor.range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        descriptor.range.levelCount = 0;
        require(!d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 2, 1), "empty subresource accepted");
        descriptor.range.levelCount = 1;
        descriptor.width = descriptor.height = 8192;
        require(!d.color.prepare(descriptor, VK_FORMAT_R16G16B16A16_SFLOAT, 8192, 8192), "oversize cache accepted");
        descriptor.width = 0;
        require(!d.color.prepare(descriptor, VK_FORMAT_R8G8B8A8_UNORM, 8192, 8192), "zero width accepted");
        d.color.release();
        d.color.release();
        require(d.color.initialize(d.instance, d.physical, d.device, vkGetInstanceProcAddr), d.color.error());
        run(d, "reinitialize", VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_R8G8B8A8_UNORM, rgba, true);
        testFormatFeatureCache(d);
        testWarmNormalizationCache(d, rgba);
        testAlternateNormalizationCache(d, rgba);
        testSharedNormalizationBudget(d);
        std::cout << "PASS " << d.cases << " GPU color cases; " << d.unsupportedCases
                  << " optional device format cases safely rejected; cache lifecycle and unsupported-input guards\n";
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "FAIL: " << exception.what() << '\n';
        return 1;
    }
}
