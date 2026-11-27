#pragma once
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

namespace ambient::vlc_ahb {

// Identically defined opaque YCbCr conversion, independent of AHB identity
// and dimensions. Deliberately include all reported feature flags and component
// swizzles; only reuse when the driver-reported conversion contract is exact.
inline bool same_conversion_format(const VkAndroidHardwareBufferFormatPropertiesANDROID &a,
                                   const VkAndroidHardwareBufferFormatPropertiesANDROID &b) {
    return a.format == VK_FORMAT_UNDEFINED && b.format == VK_FORMAT_UNDEFINED &&
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
