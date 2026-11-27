#include "conversion_key.h"
#include "rgb_target.h"
#include <cassert>
using namespace ambient::vlc_ahb;

namespace {
int image_creates;
VkImageFormatProperties image_limits{};
VkResult format_result = VK_SUCCESS;
VKAPI_ATTR void VKAPI_CALL format_properties(VkPhysicalDevice, VkFormat, VkFormatProperties *out) {
    out->optimalTilingFeatures = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
}
VKAPI_ATTR VkResult VKAPI_CALL image_format_properties(VkPhysicalDevice, VkFormat, VkImageType type,
    VkImageTiling tiling, VkImageUsageFlags usage, VkImageCreateFlags flags, VkImageFormatProperties *out) {
    assert(type == VK_IMAGE_TYPE_2D && tiling == VK_IMAGE_TILING_OPTIMAL && usage == RgbTarget::usage && flags == 0);
    *out = image_limits;
    return format_result;
}
VKAPI_ATTR void VKAPI_CALL memory_properties(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties *) {}
VKAPI_ATTR VkResult VKAPI_CALL create_image(VkDevice, const VkImageCreateInfo *, const VkAllocationCallbacks *, VkImage *) {
    ++image_creates;
    return VK_ERROR_OUT_OF_DEVICE_MEMORY;
}
VKAPI_ATTR void VKAPI_CALL destroy_image(VkDevice, VkImage, const VkAllocationCallbacks *) {}
VKAPI_ATTR void VKAPI_CALL memory_requirements(VkDevice, VkImage, VkMemoryRequirements *) {}
VKAPI_ATTR VkResult VKAPI_CALL allocate_memory(VkDevice, const VkMemoryAllocateInfo *, const VkAllocationCallbacks *, VkDeviceMemory *) {
    assert(false); return VK_ERROR_INITIALIZATION_FAILED;
}
VKAPI_ATTR void VKAPI_CALL free_memory(VkDevice, VkDeviceMemory, const VkAllocationCallbacks *) {}
VKAPI_ATTR VkResult VKAPI_CALL bind_image(VkDevice, VkImage, VkDeviceMemory, VkDeviceSize) {
    assert(false); return VK_ERROR_INITIALIZATION_FAILED;
}
VKAPI_ATTR VkResult VKAPI_CALL create_view(VkDevice, const VkImageViewCreateInfo *, const VkAllocationCallbacks *, VkImageView *) {
    assert(false); return VK_ERROR_INITIALIZATION_FAILED;
}
VKAPI_ATTR void VKAPI_CALL destroy_view(VkDevice, VkImageView, const VkAllocationCallbacks *) {}
void test_target_extent() {
    TargetDispatch d{format_properties,memory_properties,create_image,destroy_image,memory_requirements,
        allocate_memory,free_memory,bind_image,create_view,destroy_view,image_format_properties};
    RgbTarget target(reinterpret_cast<VkPhysicalDevice>(1),reinterpret_cast<VkDevice>(1),d);
    image_limits.maxExtent = {3840,2160,1};
    image_limits.maxMipLevels = image_limits.maxArrayLayers = 1;
    image_limits.sampleCounts = VK_SAMPLE_COUNT_1_BIT;
    assert(target.ensure(3841,2160,VK_FORMAT_R16G16B16A16_UNORM) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    assert(target.ensure(3840,2161,VK_FORMAT_R16G16B16A16_UNORM) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    image_limits.sampleCounts = VK_SAMPLE_COUNT_4_BIT;
    assert(target.ensure(3840,2160,VK_FORMAT_R16G16B16A16_UNORM) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    format_result = VK_ERROR_FORMAT_NOT_SUPPORTED;
    assert(target.ensure(1920,1080,VK_FORMAT_R16G16B16A16_UNORM) == VK_ERROR_FORMAT_NOT_SUPPORTED);
    assert(image_creates == 0);
    format_result = VK_SUCCESS;
    image_limits.sampleCounts = VK_SAMPLE_COUNT_1_BIT;
    assert(target.ensure(3840,2160,VK_FORMAT_R16G16B16A16_UNORM) == VK_ERROR_OUT_OF_DEVICE_MEMORY);
    assert(image_creates == 1);
}
}
int main() {
    test_target_extent();
    VkAndroidHardwareBufferFormatPropertiesANDROID a{};
    a.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    a.format = VK_FORMAT_UNDEFINED;
    a.externalFormat = 1234;
    a.formatFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    a.samplerYcbcrConversionComponents = { VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY };
    a.suggestedYcbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
    a.suggestedYcbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
    a.suggestedXChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    a.suggestedYChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    auto b = a;
    assert(same_conversion_format(a, b));
    b.externalFormat++;
    assert(!same_conversion_format(a, b));
    b = a; b.suggestedYcbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
    assert(!same_conversion_format(a, b));
    b = a; b.suggestedYcbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
    assert(!same_conversion_format(a, b));
    b = a; b.samplerYcbcrConversionComponents.b = VK_COMPONENT_SWIZZLE_R;
    assert(!same_conversion_format(a, b));
    b = a; b.suggestedXChromaOffset = VK_CHROMA_LOCATION_COSITED_EVEN;
    assert(!same_conversion_format(a, b));
    b = a; b.formatFeatures |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    assert(!same_conversion_format(a, b));
    b = a; b.format = VK_FORMAT_R8G8B8A8_UNORM;
    assert(!same_conversion_format(a, b));
    b = a; a.externalFormat = b.externalFormat = 0;
    assert(!same_conversion_format(a, b));
    a.externalFormat = b.externalFormat = 1234;
    a.format = b.format = VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16;
    assert(same_conversion_format(a, b));
    assert(supported_decoder_format(a.format, true));
    assert(same_native_p010_conversion(a, b));
    b.externalFormat = 0;
    b.suggestedYcbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
    b.suggestedYcbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
    b.samplerYcbcrConversionComponents.b = VK_COMPONENT_SWIZZLE_R;
    assert(same_native_p010_conversion(a, b));
    assert(!same_conversion_format(a, b));
    b.suggestedYChromaOffset = VK_CHROMA_LOCATION_COSITED_EVEN;
    assert(!same_native_p010_conversion(a, b));
    b = a;
    assert(!native_p010_descriptor_count_valid(0));
    assert(native_p010_descriptor_count_valid(1));
    assert(native_p010_descriptor_count_valid(2));
    assert(!native_p010_descriptor_count_valid(3));
    assert(!native_p010_descriptor_count_valid(UINT32_MAX));
    b.format = VK_FORMAT_G8_B8R8_2PLANE_420_UNORM;
    assert(!same_conversion_format(a, b));
    assert(supported_decoder_format(b.format, false));
    assert(!supported_decoder_format(b.format, true));
    assert(!supported_decoder_format(VK_FORMAT_R8G8B8A8_UNORM, false));
    assert(supported_decoder_format(VK_FORMAT_UNDEFINED, true));
    assert(raw_p010_format(VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16));
    assert(!raw_p010_format(VK_FORMAT_UNDEFINED));
    assert(!raw_p010_format(VK_FORMAT_G8_B8R8_2PLANE_420_UNORM));
    assert(raw_p010_extent_valid(1920, 1088));
    assert(raw_p010_extent_valid(3840, 2160));
    assert(!raw_p010_extent_valid(0, 1080));
    assert(!raw_p010_extent_valid(1920, 0));
    assert(!raw_p010_extent_valid(1919, 1080));
    assert(!raw_p010_extent_valid(1920, 1079));
    const auto features = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT;
    assert(raw_p010_features(features, VK_CHROMA_LOCATION_MIDPOINT, VK_CHROMA_LOCATION_MIDPOINT));
    assert(!raw_p010_features(features, VK_CHROMA_LOCATION_MIDPOINT, VK_CHROMA_LOCATION_COSITED_EVEN));
}
