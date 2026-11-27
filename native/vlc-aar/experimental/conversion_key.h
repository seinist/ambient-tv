#pragma once
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

namespace ambient::vlc_ahb {

// Known Vulkan formats also have a nonzero Android external format identifier.
 // Use the external-format import consistently (including P010); never assume
 // that an RGB/depth format contains decoder YCbCr samples.
inline unsigned known_yuv_depth(VkFormat format) {
    switch (format) {
        case VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM:
        case VK_FORMAT_G8_B8R8_2PLANE_420_UNORM:
        case VK_FORMAT_G8_B8_R8_3PLANE_422_UNORM:
        case VK_FORMAT_G8_B8R8_2PLANE_422_UNORM:
        case VK_FORMAT_G8_B8_R8_3PLANE_444_UNORM:
            return 8;
        case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_420_UNORM_3PACK16:
        case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16:
        case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_422_UNORM_3PACK16:
        case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_422_UNORM_3PACK16:
        case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_444_UNORM_3PACK16:
            return 10;
        case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_420_UNORM_3PACK16:
        case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_420_UNORM_3PACK16:
        case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_422_UNORM_3PACK16:
        case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_422_UNORM_3PACK16:
        case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_444_UNORM_3PACK16:
            return 12;
        case VK_FORMAT_G16_B16_R16_3PLANE_420_UNORM:
        case VK_FORMAT_G16_B16R16_2PLANE_420_UNORM:
        case VK_FORMAT_G16_B16_R16_3PLANE_422_UNORM:
        case VK_FORMAT_G16_B16R16_2PLANE_422_UNORM:
        case VK_FORMAT_G16_B16_R16_3PLANE_444_UNORM:
            return 16;
        default: return 0;
    }
}
inline bool supported_decoder_format(VkFormat format, bool high_precision) {
    if (format == VK_FORMAT_UNDEFINED) return true; // opaque depth not observable
    const unsigned depth = known_yuv_depth(format);
    return depth != 0 && (!high_precision || depth >= 10);
}

// The pre-reshape path deliberately accepts only a known P010 native format.
// Opaque external formats cannot establish component ordering or bit depth.
inline bool raw_p010_format(VkFormat format) {
    return format == VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16;
}
// Native 4:2:0 VkImage extents must be multiples of the chroma subsampling
// factors. This applies to the actual allocation, not the visible crop.
inline bool raw_p010_extent_valid(uint32_t width, uint32_t height) {
    return width != 0 && height != 0 && !(width & 1u) && !(height & 1u);
}
inline bool raw_p010_features(VkFormatFeatureFlags features, VkChromaLocation x, VkChromaLocation y) {
    if (!(features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)) return false;
    auto supported = [features](VkChromaLocation location) {
        return location == VK_CHROMA_LOCATION_MIDPOINT ?
            (features & VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT) != 0 :
            location == VK_CHROMA_LOCATION_COSITED_EVEN &&
            (features & VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT) != 0;
    };
    return supported(x) && supported(y);
}

// The native P010 route uses a fixed RGB_IDENTITY/full-range sampler,
// identity swizzles and nearest filtering. Only native format and chroma
// location vary; external-format identifiers are not part of this contract.
inline bool same_native_p010_conversion(const VkAndroidHardwareBufferFormatPropertiesANDROID &a,
                                        const VkAndroidHardwareBufferFormatPropertiesANDROID &b) {
    return raw_p010_format(a.format) && a.format == b.format &&
        a.suggestedXChromaOffset == b.suggestedXChromaOffset &&
        a.suggestedYChromaOffset == b.suggestedYChromaOffset;
}
inline bool native_p010_descriptor_count_valid(uint32_t count) {
    // Vulkan specifies 1..number of planes for a known native format.
    return count >= 1 && count <= 2;
}

// Identically defined external YCbCr conversion, independent of AHB identity
// and dimensions. Deliberately include all reported feature flags and component
// swizzles; only reuse when the driver-reported conversion contract is exact.
inline bool same_conversion_format(const VkAndroidHardwareBufferFormatPropertiesANDROID &a,
                                   const VkAndroidHardwareBufferFormatPropertiesANDROID &b) {
    return a.format == b.format && supported_decoder_format(a.format, false) &&
        a.externalFormat != 0 && a.externalFormat == b.externalFormat &&
        a.formatFeatures == b.formatFeatures &&
        a.samplerYcbcrConversionComponents.r == b.samplerYcbcrConversionComponents.r &&
        a.samplerYcbcrConversionComponents.g == b.samplerYcbcrConversionComponents.g &&
        a.samplerYcbcrConversionComponents.b == b.samplerYcbcrConversionComponents.b &&
        a.samplerYcbcrConversionComponents.a == b.samplerYcbcrConversionComponents.a &&
        a.suggestedYcbcrModel == b.suggestedYcbcrModel &&
        a.suggestedYcbcrRange == b.suggestedYcbcrRange &&
        a.suggestedXChromaOffset == b.suggestedXChromaOffset &&
        a.suggestedYChromaOffset == b.suggestedYChromaOffset;
}

} // namespace ambient::vlc_ahb
