#pragma once

// Experimental building block only. Not connected to VLC/libplacebo or shipped.
// Caller owns the VkDevice, enabled extensions/features and all GPU synchronization.
#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

namespace ambient::vlc_ahb {

struct Dispatch {
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID properties{};
    PFN_vkCreateImage create_image{};
    PFN_vkDestroyImage destroy_image{};
    PFN_vkAllocateMemory allocate_memory{};
    PFN_vkFreeMemory free_memory{};
    PFN_vkBindImageMemory bind_image{};
    PFN_vkCreateSamplerYcbcrConversion create_conversion{};
    PFN_vkDestroySamplerYcbcrConversion destroy_conversion{};
    PFN_vkCreateImageView create_view{};
    PFN_vkDestroyImageView destroy_view{};
    PFN_vkCreateSampler create_sampler{};
    PFN_vkDestroySampler destroy_sampler{};

    bool valid() const {
        return properties && create_image && destroy_image && allocate_memory && free_memory &&
            bind_image && create_conversion && destroy_conversion && create_view && destroy_view &&
            create_sampler && destroy_sampler;
    }
};

// One imported opaque Android image. No CPU readback or upload. Import is not permission
// to sample: AImage acquire fences, foreign queue ownership and GPU release completion
// must be handled by the actual renderer before the image can be accessed or retired.
class ImportedImage {
public:
    ImportedImage(VkDevice device, Dispatch dispatch) : device_(device), d_(dispatch) {}
    ImportedImage(const ImportedImage &) = delete;
    ImportedImage &operator=(const ImportedImage &) = delete;
    ~ImportedImage() { reset(); } // caller must guarantee GPU completion before destruction

    VkResult import(AHardwareBuffer *buffer) {
        if (!device_ || !d_.valid() || !buffer || buffer_) return VK_ERROR_INITIALIZATION_FAILED;
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer, &desc);
        if (!desc.width || !desc.height || desc.layers != 1 ||
            !(desc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) ||
            (desc.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT))
            return VK_ERROR_FORMAT_NOT_SUPPORTED;

        VkAndroidHardwareBufferFormatPropertiesANDROID format{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        properties.pNext = &format;
        VkResult result = d_.properties(device_, buffer, &properties);
        if (result != VK_SUCCESS) return result;
        // First milestone: opaque external-format buffers only. Explicit VkFormat and
        // multiplanar formats require independent format/plane and color metadata handling.
        if (format.format != VK_FORMAT_UNDEFINED || !format.externalFormat ||
            !(format.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ||
            !properties.memoryTypeBits || !properties.allocationSize)
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
        AHardwareBuffer_acquire(buffer);
        buffer_ = buffer;

        VkExternalFormatANDROID external{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
        external.externalFormat = format.externalFormat;
        VkExternalMemoryImageCreateInfo external_memory{
            VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        external_memory.pNext = &external;
        external_memory.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        image.pNext = &external_memory;
        image.imageType = VK_IMAGE_TYPE_2D;
        image.format = VK_FORMAT_UNDEFINED;
        image.extent = {desc.width, desc.height, 1};
        image.mipLevels = image.arrayLayers = 1;
        image.samples = VK_SAMPLE_COUNT_1_BIT;
        image.tiling = VK_IMAGE_TILING_OPTIMAL;
        image.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        image.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        result = d_.create_image(device_, &image, nullptr, &image_);
        if (result != VK_SUCCESS) return fail(result);

        // AHB properties supply the allocation size and permitted memory types. A dedicated
        // import is mandatory for images; do not query unbound image memory requirements.
        VkImportAndroidHardwareBufferInfoANDROID imported{
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
        imported.buffer = buffer_;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.pNext = &imported;
        dedicated.image = image_;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.pNext = &dedicated;
        allocation.allocationSize = properties.allocationSize;
        for (uint32_t i = 0; i < 32; ++i) {
            if (properties.memoryTypeBits & (uint32_t{1} << i)) {
                allocation.memoryTypeIndex = i;
                break;
            }
        }
        result = d_.allocate_memory(device_, &allocation, nullptr, &memory_);
        if (result != VK_SUCCESS) return fail(result);
        result = d_.bind_image(device_, image_, memory_, 0);
        if (result != VK_SUCCESS) return fail(result);

        VkSamplerYcbcrConversionCreateInfo conversion{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO};
        conversion.pNext = &external;
        conversion.format = VK_FORMAT_UNDEFINED;
        conversion.ycbcrModel = format.suggestedYcbcrModel;
        conversion.ycbcrRange = format.suggestedYcbcrRange;
        conversion.components = format.samplerYcbcrConversionComponents;
        conversion.xChromaOffset = format.suggestedXChromaOffset;
        conversion.yChromaOffset = format.suggestedYChromaOffset;
        conversion.chromaFilter = VK_FILTER_NEAREST;
        result = d_.create_conversion(device_, &conversion, nullptr, &conversion_);
        if (result != VK_SUCCESS) return fail(result);
        VkSamplerYcbcrConversionInfo conversion_info{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
        conversion_info.conversion = conversion_;
        VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        view.pNext = &conversion_info;
        view.image = image_;
        view.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view.format = VK_FORMAT_UNDEFINED;
        view.components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
        view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        result = d_.create_view(device_, &view, nullptr, &view_);
        if (result != VK_SUCCESS) return fail(result);

        VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        sampler.pNext = &conversion_info;
        sampler.magFilter = sampler.minFilter = VK_FILTER_NEAREST;
        sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampler.maxAnisotropy = 1.0f;
        result = d_.create_sampler(device_, &sampler, nullptr, &sampler_);
        if (result != VK_SUCCESS) return fail(result);
        model_ = format.suggestedYcbcrModel;
        range_ = format.suggestedYcbcrRange;
        return VK_SUCCESS;
    }

    bool color_matches(VkSamplerYcbcrModelConversion model, VkSamplerYcbcrRange range) const {
        return buffer_ && model_ == model && range_ == range;
    }

    VkImage image() const { return image_; }
    VkImageView view() const { return view_; }
    // Must be an immutable sampler in the combined-image-sampler descriptor layout.
    VkSampler sampler() const { return sampler_; }

private:
    VkResult fail(VkResult result) { reset(); return result; }
    void reset() {
        if (sampler_) d_.destroy_sampler(device_, sampler_, nullptr);
        if (view_) d_.destroy_view(device_, view_, nullptr);
        if (conversion_) d_.destroy_conversion(device_, conversion_, nullptr);
        if (image_) d_.destroy_image(device_, image_, nullptr);
        if (memory_) d_.free_memory(device_, memory_, nullptr);
        if (buffer_) AHardwareBuffer_release(buffer_);
        sampler_ = VK_NULL_HANDLE;
        view_ = VK_NULL_HANDLE;
        conversion_ = VK_NULL_HANDLE;
        image_ = VK_NULL_HANDLE;
        memory_ = VK_NULL_HANDLE;
        buffer_ = nullptr;
    }
    VkDevice device_{};
    Dispatch d_{};
    AHardwareBuffer *buffer_{};
    VkImage image_{};
    VkDeviceMemory memory_{};
    VkSamplerYcbcrConversion conversion_{};
    VkImageView view_{};
    VkSampler sampler_{};
    VkSamplerYcbcrModelConversion model_{};
    VkSamplerYcbcrRange range_{};
};
} // namespace ambient::vlc_ahb
