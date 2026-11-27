#pragma once

// C adapter for the pinned libplacebo 5.264.1 display module. This is intentionally
// restricted to a single graphics queue; concurrent queue families need additional
// target-image sharing and explicit synchronization before they can be enabled.
#include <libplacebo/vulkan.h>
#include <libplacebo/renderer.h>
#include <vulkan/vulkan_android.h>
#include <string.h>

static inline bool ambient_pl_single_queue(pl_vulkan vk, VkDevice device, uint32_t family)
{
    return vk && device && vk->device == device && vk->num_queues == 1 &&
           vk->queue_graphics.index == family && vk->queue_graphics.count == 1 &&
           vk->queues && vk->queues[0].index == family && vk->queues[0].count == 1 &&
           vk->lock_queue && vk->unlock_queue;
}

// A physical-device probe is not evidence that these extensions/features
// were enabled on the logical device that owns the swapchain.
static inline bool ambient_pl_extension_enabled(pl_vulkan vk, const char *name)
{
    if (!vk || !name || !vk->extensions) return false;
    for (int i = 0; i < vk->num_extensions; ++i)
        if (vk->extensions[i] && strcmp(vk->extensions[i], name) == 0)
            return true;
    return false;
}

static inline bool ambient_pl_ycbcr_enabled(pl_vulkan vk)
{
    if (!vk || !vk->features) return false;
    const VkBaseInStructure *feature = (const VkBaseInStructure *)vk->features->pNext;
    for (; feature; feature = feature->pNext) {
        if (feature->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES)
            return ((const VkPhysicalDeviceSamplerYcbcrConversionFeatures *)feature)->samplerYcbcrConversion;
        if (feature->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES)
            return ((const VkPhysicalDeviceVulkan11Features *)feature)->samplerYcbcrConversion;
    }
    return false;
}

// The actual physical device must support the sync-fd import operation, not
// merely advertise the logical-device extension. The display never exports
// ready semaphores (same-device handoff), so exportability is not required.
static inline bool ambient_pl_sync_fd_importable(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr || !vk->phys_device) return false;
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties get =
        (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)
        vk->get_proc_addr(vk->instance, "vkGetPhysicalDeviceExternalSemaphoreProperties");
    if (!get) return false;
    VkPhysicalDeviceExternalSemaphoreInfo info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
    };
    VkExternalSemaphoreProperties props = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES,
    };
    get(vk->phys_device, &info, &props);
    return (props.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) &&
        (props.compatibleHandleTypes & VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
}

static inline uint32_t ambient_pl_descriptor_budget(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr || !vk->phys_device) return 16;
    PFN_vkGetPhysicalDeviceProperties2 get =
        (PFN_vkGetPhysicalDeviceProperties2)
        vk->get_proc_addr(vk->instance, "vkGetPhysicalDeviceProperties2");
    if (!get) return 16;
    VkPhysicalDeviceProperties2 props = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2 };
    get(vk->phys_device, &props);
#if defined(VK_KHR_maintenance6)
    if (props.properties.apiVersion >= VK_MAKE_API_VERSION(0, 1, 4, 0) ||
        ambient_pl_extension_enabled(vk, VK_KHR_MAINTENANCE_6_EXTENSION_NAME)) {
        VkPhysicalDeviceMaintenance6PropertiesKHR maintenance = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_PROPERTIES_KHR,
        };
        props.pNext = &maintenance;
        get(vk->phys_device, &props);
        if (maintenance.maxCombinedImageSamplerDescriptorCount)
            return maintenance.maxCombinedImageSamplerDescriptorCount;
    }
#endif
    // Older drivers cannot directly query the external-format descriptor count.
    // Allocate a conservative pool bounded by the device's actual sampler limit.
    // Pool allocation does not increase the descriptor count of the layout.
    uint32_t budget = props.properties.limits.maxPerStageDescriptorSamplers;
    if (budget < 16) budget = 16;
    if (budget > 256) budget = 256;
    return budget;
}

static inline bool ambient_pl_ahb_device_enabled(pl_vulkan vk)
{
    return vk && vk->api_version >= VK_API_VERSION_1_1 &&
        ambient_pl_single_queue(vk, vk->device, vk->queue_graphics.index) &&
        ambient_pl_extension_enabled(vk, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) &&
        ambient_pl_extension_enabled(vk, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) &&
        ambient_pl_extension_enabled(vk, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME) &&
        ambient_pl_ycbcr_enabled(vk) && ambient_pl_sync_fd_importable(vk);
}

// Wrap exactly once while held by the external owner. No cross-device
// handle import or CPU download. Both sides use graphics queue index zero.
static inline pl_tex ambient_pl_wrap_rgb_held(pl_gpu gpu, VkDevice device,
                                                   uint32_t family, VkImage image,
                                                   VkFormat format, int width, int height,
                                                   VkImageUsageFlags usage)
{
    if (!gpu || !image || width <= 0 || height <= 0 ||
        !ambient_pl_single_queue(pl_vulkan_get(gpu), device, family) ||
        (usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)) !=
        (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT))
        return NULL;
    if (format != VK_FORMAT_R8G8B8A8_UNORM && format != VK_FORMAT_B8G8R8A8_UNORM &&
        format != VK_FORMAT_R16G16B16A16_UNORM && format != VK_FORMAT_R16G16B16A16_SFLOAT)
        return NULL;
    struct pl_vulkan_wrap_params wrap = {0};
    wrap.image = image;
    wrap.width = width;
    wrap.height = height;
    wrap.format = format;
    wrap.usage = usage;
    pl_tex texture = pl_vulkan_wrap(gpu, &wrap);
    if (texture && !texture->params.sampleable)
        pl_tex_destroy(gpu, &texture);
    return texture;
}

// Signal may be pending: libplacebo waits before the first texture use.
// Retain this binary semaphore until the consumer hold has completed.
static inline void ambient_pl_release_rgb(pl_gpu gpu, pl_tex texture, uint32_t family,
                                          VkSemaphore conversion_ready)
{
    struct pl_vulkan_release_params release = {0};
    release.tex = texture;
    release.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    release.qf = family;
    release.semaphore.sem = conversion_ready;
    pl_vulkan_release_ex(gpu, &release);
}

// Synchronous fallback for a caller that has already proved conversion completion.
static inline pl_tex ambient_pl_wrap_completed_rgb(pl_gpu gpu, VkDevice device,
                                                   uint32_t family, VkImage image,
                                                   VkFormat format, int width, int height,
                                                   VkImageUsageFlags usage)
{
    pl_tex texture = ambient_pl_wrap_rgb_held(gpu, device, family, image, format, width, height, usage);
    if (texture) ambient_pl_release_rgb(gpu, texture, family, VK_NULL_HANDLE);
    return texture;
}

// Driver YCbCr conversion has already produced RGB. Never run YCbCr matrix or
// limited-range expansion twice. The caller supplies verified source primaries
// and transfer (including PQ/HLG), and applies the existing VLC orientation and destination crop.
// The input crop was already applied in the conversion pass.
static inline struct pl_frame ambient_pl_rgb_frame(pl_tex texture, struct pl_color_space color)
{
    struct pl_frame frame = {0};
    if (!texture) return frame;
    frame.num_planes = 1;
    frame.planes[0].texture = texture;
    frame.planes[0].components = 3;
    frame.planes[0].component_mapping[0] = 0;
    frame.planes[0].component_mapping[1] = 1;
    frame.planes[0].component_mapping[2] = 2;
    frame.planes[0].component_mapping[3] = -1;
    frame.repr.sys = PL_COLOR_SYSTEM_RGB;
    frame.repr.levels = PL_COLOR_LEVELS_FULL;
    frame.color = color;
    frame.crop.x1 = texture->params.w;
    frame.crop.y1 = texture->params.h;
    return frame;
}

// A native P010 identity sampler returns (Cr,Y,Cb), not RGB. Values are
// normalized 10-bit codewords stored in a 16-bit UNORM target (not shifted
// 10-bit integers in a 16-bit plane). Map components accordingly; libplacebo
// applies the source range/matrix exactly once, or an eventual RPU reshape.
static inline struct pl_frame ambient_pl_raw_p010_frame(pl_tex texture,
                                                        struct pl_color_space color,
                                                        enum pl_color_levels levels)
{
    struct pl_frame frame = ambient_pl_rgb_frame(texture, color);
    if (!texture) return frame;
    frame.planes[0].component_mapping[0] = 2;
    frame.planes[0].component_mapping[1] = 0;
    frame.planes[0].component_mapping[2] = 1;
    frame.repr.sys = PL_COLOR_SYSTEM_BT_2020_NC;
    frame.repr.levels = levels;
    frame.repr.bits.sample_depth = 10;
    frame.repr.bits.color_depth = 10;
    return frame;
}

// Once no more rendering calls reference the texture, request ownership back.
// The supplied binary semaphore must remain alive; the owner waits for a
// submission consuming it and proves completion BEFORE reusing or freeing the
// raw VkImage or destroying the wrapper. No implicit global device wait.
static inline bool ambient_pl_hold_rgb(pl_gpu gpu, pl_tex texture, uint32_t family,
                                      VkSemaphore semaphore)
{
    if (!gpu || !texture || !semaphore) return false;
    pl_vulkan vk = pl_vulkan_get(gpu);
    if (!vk || !ambient_pl_single_queue(vk, vk->device, family)) return false;
    struct pl_vulkan_hold_params hold = {0};
    hold.tex = texture;
    hold.layout = VK_IMAGE_LAYOUT_GENERAL;
    hold.qf = family;
    hold.semaphore.sem = semaphore;
    return pl_vulkan_hold_ex(gpu, &hold);
}
