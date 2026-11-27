#pragma once

// Experimental VLC display import; caller retains the picture and synchronizes GPU use.
// Caller owns the VkDevice, enabled extensions/features and all GPU synchronization.
#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include "conversion_key.h"

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
    PFN_vkGetPhysicalDeviceFormatProperties format_properties{};
    PFN_vkGetPhysicalDeviceImageFormatProperties2 image_format_properties{};

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
    ImportedImage(VkDevice device, Dispatch dispatch, VkPhysicalDevice physical = VK_NULL_HANDLE)
        : device_(device), d_(dispatch), physical_(physical) {}
    ImportedImage(const ImportedImage &) = delete;
    ImportedImage &operator=(const ImportedImage &) = delete;
    ~ImportedImage() { reset(); } // caller must guarantee GPU completion before destruction

    VkResult import(AHardwareBuffer *buffer, bool raw_yuv = false) {
        if (!device_ || !d_.valid() || !buffer || allocation_.buffer)
            return VK_ERROR_INITIALIZATION_FAILED;
        AHardwareBuffer_Desc desc{};
        VkAndroidHardwareBufferFormatPropertiesANDROID format{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        VkResult result = query(buffer, desc, format, properties, raw_yuv);
        if (result != VK_SUCCESS) return result;

        raw_yuv_ = raw_yuv;
        if (raw_yuv_ && !raw_supported(format, desc)) return fail(VK_ERROR_FORMAT_NOT_SUPPORTED);
        VkExternalFormatANDROID external{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
        external.externalFormat = format.externalFormat;
        VkSamplerYcbcrConversionCreateInfo conversion{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO};
        conversion.pNext = raw_yuv_ ? nullptr : &external;
        conversion.format = raw_yuv_ ? format.format : VK_FORMAT_UNDEFINED;
        // RGB_IDENTITY is Vulkan's *no conversion/no range expansion* model.
        // For explicit P010, the result is (Cr,Y,Cb), all normalized 10-bit
        // code values, not RGB. The renderer interprets those channels later.
        conversion.ycbcrModel = raw_yuv_ ? VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY : format.suggestedYcbcrModel;
        conversion.ycbcrRange = raw_yuv_ ? VK_SAMPLER_YCBCR_RANGE_ITU_FULL : format.suggestedYcbcrRange;
        conversion.components = raw_yuv_ ? VkComponentMapping{
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY} : format.samplerYcbcrConversionComponents;
        conversion.xChromaOffset = format.suggestedXChromaOffset;
        conversion.yChromaOffset = format.suggestedYChromaOffset;
        conversion.chromaFilter = VK_FILTER_NEAREST;
        result = d_.create_conversion(device_, &conversion, nullptr, &conversion_);
        if (result != VK_SUCCESS) return fail(result);
        VkSamplerYcbcrConversionInfo conversion_info{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
        conversion_info.conversion = conversion_;
        VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        sampler.pNext = &conversion_info;
        sampler.magFilter = sampler.minFilter = VK_FILTER_NEAREST;
        sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampler.maxAnisotropy = 1.0f;
        result = d_.create_sampler(device_, &sampler, nullptr, &sampler_);
        if (result != VK_SUCCESS) return fail(result);
        result = allocate_image(buffer, desc, format, properties, allocation_);
        if (result != VK_SUCCESS) return fail(result);
        format_ = format;
        return VK_SUCCESS;
    }

    // Only on a fully retired slot. Keep the immutable sampler and conversion
    // object alive across different buffers of identical external format.
    // Incompatible formats leave the old entry untouched. On an allocation
    // failure the old image and descriptor remain valid, allowing a safe retry.
    VkResult try_rebind(AHardwareBuffer *buffer, bool &reused) {
        reused = false;
        if (!buffer || !allocation_.buffer || !conversion_ || !sampler_)
            return VK_ERROR_INITIALIZATION_FAILED;
        if (buffer == allocation_.buffer) {
            reused = true;
            return VK_SUCCESS;
        }
        AHardwareBuffer_Desc desc{};
        VkAndroidHardwareBufferFormatPropertiesANDROID format{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        VkResult result = query(buffer, desc, format, properties, raw_yuv_);
        if (result != VK_SUCCESS) return result;
        if (!same_conversion_format(format_, format)) return VK_SUCCESS;
        if (raw_yuv_ && !raw_supported(format, desc)) return VK_ERROR_FORMAT_NOT_SUPPORTED;
        Allocation candidate{};
        result = allocate_image(buffer, desc, format, properties, candidate);
        if (result != VK_SUCCESS) {
            destroy_image(candidate);
            return result;
        }
        destroy_image(allocation_);
        allocation_ = candidate;
        reused = true;
        return VK_SUCCESS;
    }

    bool color_matches(VkSamplerYcbcrModelConversion model, VkSamplerYcbcrRange range, bool high_precision = false) const {
        return allocation_.buffer && !raw_yuv_ && supported_decoder_format(format_.format, high_precision) &&
            format_.suggestedYcbcrModel == model && format_.suggestedYcbcrRange == range;
    }

    bool raw_yuv() const { return raw_yuv_ && allocation_.buffer; }
    VkImage image() const { return allocation_.image; }
    VkImageView view() const { return allocation_.view; }
    // Must be an immutable sampler in the combined-image-sampler descriptor layout.
    VkSampler sampler() const { return sampler_; }

private:
    struct Allocation {
        AHardwareBuffer *buffer{};
        VkImage image{};
        VkDeviceMemory memory{};
        VkImageView view{};
    };

    VkResult query(AHardwareBuffer *buffer, AHardwareBuffer_Desc &desc,
                   VkAndroidHardwareBufferFormatPropertiesANDROID &format,
                   VkAndroidHardwareBufferPropertiesANDROID &properties, bool raw_yuv) const {
        AHardwareBuffer_describe(buffer, &desc);
        if (!desc.width || !desc.height || desc.layers != 1 ||
            !(desc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) ||
            (desc.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT))
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
        properties.pNext = &format;
        VkResult result = d_.properties(device_, buffer, &properties);
        if (result != VK_SUCCESS) return result;
        // Vulkan allows external-format images even when an equivalent VkFormat
        // exists. formatFeatures describes this external route, not the native
        // VkFormat. Keep the equivalent format for precision/identity checks.
        if (!properties.memoryTypeBits || !properties.allocationSize)
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
        if (raw_yuv) {
            // Native P010 is independently validated with physical-device
            // format/import queries. Do not require external-format features
            // for a route that does not use VkExternalFormatANDROID.
            if (!raw_p010_format(format.format)) return VK_ERROR_FORMAT_NOT_SUPPORTED;
        } else if (!supported_decoder_format(format.format, false) || !format.externalFormat ||
                   !(format.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)) {
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
        }
        return VK_SUCCESS;
    }

    bool raw_supported(const VkAndroidHardwareBufferFormatPropertiesANDROID &format,
                       const AHardwareBuffer_Desc &desc) const {
        if (!physical_ || !d_.format_properties || !d_.image_format_properties ||
            !raw_p010_format(format.format)) return false;
        VkFormatProperties format_props{};
        d_.format_properties(physical_, format.format, &format_props);
        if (!raw_p010_features(format_props.optimalTilingFeatures,
                format.suggestedXChromaOffset, format.suggestedYChromaOffset)) return false;
        VkPhysicalDeviceExternalImageFormatInfo external{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};
        external.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkPhysicalDeviceImageFormatInfo2 query_info{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};
        query_info.pNext = &external;
        query_info.format = format.format;
        query_info.type = VK_IMAGE_TYPE_2D;
        query_info.tiling = VK_IMAGE_TILING_OPTIMAL;
        query_info.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        VkExternalImageFormatProperties external_props{VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
        VkImageFormatProperties2 props{VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};
        props.pNext = &external_props;
        if (d_.image_format_properties(physical_, &query_info, &props) != VK_SUCCESS) return false;
        return (external_props.externalMemoryProperties.externalMemoryFeatures &
                VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) &&
               (external_props.externalMemoryProperties.compatibleHandleTypes &
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID) &&
               desc.width <= props.imageFormatProperties.maxExtent.width &&
               desc.height <= props.imageFormatProperties.maxExtent.height;
    }

    VkResult allocate_image(AHardwareBuffer *buffer, const AHardwareBuffer_Desc &desc,
                            const VkAndroidHardwareBufferFormatPropertiesANDROID &format,
                            const VkAndroidHardwareBufferPropertiesANDROID &properties,
                            Allocation &out) {
        AHardwareBuffer_acquire(buffer);
        out.buffer = buffer;
        VkExternalFormatANDROID external{VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID};
        external.externalFormat = format.externalFormat;
        VkExternalMemoryImageCreateInfo external_memory{
            VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        external_memory.pNext = raw_yuv_ ? nullptr : &external;
        external_memory.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        image.pNext = &external_memory;
        image.imageType = VK_IMAGE_TYPE_2D;
        image.format = raw_yuv_ ? format.format : VK_FORMAT_UNDEFINED;
        image.extent = {desc.width, desc.height, 1};
        image.mipLevels = image.arrayLayers = 1;
        image.samples = VK_SAMPLE_COUNT_1_BIT;
        image.tiling = VK_IMAGE_TILING_OPTIMAL;
        image.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        image.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        VkResult result = d_.create_image(device_, &image, nullptr, &out.image);
        if (result != VK_SUCCESS) return result;

        // AHB properties provide allocation size and permitted memory types;
        // dedicated allocation is required for imported images.
        VkImportAndroidHardwareBufferInfoANDROID imported{
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
        imported.buffer = out.buffer;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.pNext = &imported;
        dedicated.image = out.image;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.pNext = &dedicated;
        allocation.allocationSize = properties.allocationSize;
        for (uint32_t i = 0; i < 32; ++i) {
            if (properties.memoryTypeBits & (uint32_t{1} << i)) {
                allocation.memoryTypeIndex = i;
                break;
            }
        }
        result = d_.allocate_memory(device_, &allocation, nullptr, &out.memory);
        if (result != VK_SUCCESS) return result;
        result = d_.bind_image(device_, out.image, out.memory, 0);
        if (result != VK_SUCCESS) return result;
        VkSamplerYcbcrConversionInfo conversion_info{VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO};
        conversion_info.conversion = conversion_;
        VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        view.pNext = &conversion_info;
        view.image = out.image;
        view.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view.format = raw_yuv_ ? format.format : VK_FORMAT_UNDEFINED;
        view.components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
        view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        return d_.create_view(device_, &view, nullptr, &out.view);
    }

    VkResult fail(VkResult result) { reset(); return result; }
    void destroy_image(Allocation &a) {
        if (a.view) d_.destroy_view(device_, a.view, nullptr);
        if (a.image) d_.destroy_image(device_, a.image, nullptr);
        if (a.memory) d_.free_memory(device_, a.memory, nullptr);
        if (a.buffer) AHardwareBuffer_release(a.buffer);
        a = {};
    }
    void reset() {
        destroy_image(allocation_);
        if (sampler_) d_.destroy_sampler(device_, sampler_, nullptr);
        if (conversion_) d_.destroy_conversion(device_, conversion_, nullptr);
        sampler_ = VK_NULL_HANDLE;
        conversion_ = VK_NULL_HANDLE;
        format_ = {};
        raw_yuv_ = false;
    }
    VkDevice device_{};
    Dispatch d_{};
    VkPhysicalDevice physical_{};
    bool raw_yuv_{};
    Allocation allocation_{};
    VkSamplerYcbcrConversion conversion_{};
    VkSampler sampler_{};
    VkAndroidHardwareBufferFormatPropertiesANDROID format_{};

};
} // namespace ambient::vlc_ahb
