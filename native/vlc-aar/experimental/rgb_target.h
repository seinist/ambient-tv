#pragma once
#include <vulkan/vulkan.h>

namespace ambient::vlc_ahb {

struct TargetDispatch {
    PFN_vkGetPhysicalDeviceFormatProperties format_properties{};
    PFN_vkGetPhysicalDeviceMemoryProperties memory_properties{};
    PFN_vkCreateImage create_image{};
    PFN_vkDestroyImage destroy_image{};
    PFN_vkGetImageMemoryRequirements memory_requirements{};
    PFN_vkAllocateMemory allocate_memory{};
    PFN_vkFreeMemory free_memory{};
    PFN_vkBindImageMemory bind_image{};
    PFN_vkCreateImageView create_view{};
    PFN_vkDestroyImageView destroy_view{};
    bool valid() const {
        return format_properties && memory_properties && create_image && destroy_image &&
            memory_requirements && allocate_memory && free_memory && bind_image &&
            create_view && destroy_view;
    }
};

// One reusable device-local conversion output. No host mapping or staging.
// Owner must retire both the external submission AND libplacebo's use before
// recreating/destroying this target. It is not safe to destroy a held wrapper's image.
class RgbTarget {
public:
    static constexpr VkImageUsageFlags usage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;

    RgbTarget(VkPhysicalDevice physical, VkDevice device, TargetDispatch dispatch)
        : physical_(physical), device_(device), d_(dispatch) {}
    RgbTarget(const RgbTarget &) = delete;
    RgbTarget &operator=(const RgbTarget &) = delete;
    ~RgbTarget() { reset(); }

    // Only call on a fully retired slot. Identical dimensions reuse the image.
    VkResult ensure(uint32_t width, uint32_t height, VkFormat format) {
        if (!physical_ || !device_ || !d_.valid() || !width || !height)
            return VK_ERROR_INITIALIZATION_FAILED;
        if (format != VK_FORMAT_R8G8B8A8_UNORM && format != VK_FORMAT_B8G8R8A8_UNORM &&
            format != VK_FORMAT_R16G16B16A16_UNORM && format != VK_FORMAT_R16G16B16A16_SFLOAT)
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
        if (image_ && width_ == width && height_ == height && format_ == format)
            return VK_SUCCESS;
        VkFormatProperties props{};
        d_.format_properties(physical_, format, &props);
        constexpr VkFormatFeatureFlags needed = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT |
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        if ((props.optimalTilingFeatures & needed) != needed)
            return VK_ERROR_FORMAT_NOT_SUPPORTED;

        reset();
        VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ci.imageType = VK_IMAGE_TYPE_2D;
        ci.format = format;
        ci.extent = {width, height, 1};
        ci.mipLevels = ci.arrayLayers = 1;
        ci.samples = VK_SAMPLE_COUNT_1_BIT;
        ci.tiling = VK_IMAGE_TILING_OPTIMAL;
        ci.usage = usage;
        ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        VkResult result = d_.create_image(device_, &ci, nullptr, &image_);
        if (result != VK_SUCCESS) return fail(result);
        VkMemoryRequirements req{};
        d_.memory_requirements(device_, image_, &req);
        VkPhysicalDeviceMemoryProperties memory{};
        d_.memory_properties(physical_, &memory);
        uint32_t index = memory.memoryTypeCount;
        for (uint32_t i = 0; i < memory.memoryTypeCount && i < VK_MAX_MEMORY_TYPES; ++i) {
            if ((req.memoryTypeBits & (uint32_t{1} << i)) &&
                (memory.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) &&
                !(memory.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_PROTECTED_BIT | VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT))) {
                index = i;
                break;
            }
        }
        if (index >= memory.memoryTypeCount || index >= VK_MAX_MEMORY_TYPES)
            return fail(VK_ERROR_FEATURE_NOT_PRESENT);
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.image = image_;
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.pNext = &dedicated;
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = index;
        result = d_.allocate_memory(device_, &ai, nullptr, &memory_);
        if (result != VK_SUCCESS) return fail(result);
        result = d_.bind_image(device_, image_, memory_, 0);
        if (result != VK_SUCCESS) return fail(result);
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = image_;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = format;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        result = d_.create_view(device_, &vi, nullptr, &view_);
        if (result != VK_SUCCESS) return fail(result);
        width_ = width;
        height_ = height;
        format_ = format;
        return VK_SUCCESS;
    }
    VkImage image() const { return image_; }
    VkImageView view() const { return view_; }
    VkFormat format() const { return format_; }
private:
    VkResult fail(VkResult result) { reset(); return result; }
    void reset() {
        if (view_) d_.destroy_view(device_, view_, nullptr);
        if (image_) d_.destroy_image(device_, image_, nullptr);
        if (memory_) d_.free_memory(device_, memory_, nullptr);
        view_ = VK_NULL_HANDLE;
        image_ = VK_NULL_HANDLE;
        memory_ = VK_NULL_HANDLE;
        width_ = height_ = 0;
        format_ = VK_FORMAT_UNDEFINED;
    }
    VkPhysicalDevice physical_{};
    VkDevice device_{};
    TargetDispatch d_{};
    VkImage image_{};
    VkDeviceMemory memory_{};
    VkImageView view_{};
    uint32_t width_{}, height_{};
    VkFormat format_ = VK_FORMAT_UNDEFINED;
};
} // namespace ambient::vlc_ahb
