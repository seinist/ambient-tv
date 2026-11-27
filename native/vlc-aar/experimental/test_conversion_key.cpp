#include "conversion_key.h"
#include <cassert>
using namespace ambient::vlc_ahb;

int main() {
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
}
