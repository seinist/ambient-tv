#include "vulkan/vulkanformatfeaturecache.h"
#include <cassert>
#include <cstdio>
using libretrodroid::VulkanFormatFeatureCache;
int main() {
    VulkanFormatFeatureCache cache;
    for (unsigned generation = 0; generation < 10000; ++generation) {
        unsigned queries = 0;
        cache.reset();
        for (unsigned frame = 0; frame < 16; ++frame) {
            for (std::size_t index = 0; index < VulkanFormatFeatureCache::size; ++index) {
                const auto expected = index == 0 ? 0u : (generation + 1) << index;
                assert(cache.get(index, [&] { ++queries; return expected; }) == expected);
            }
            assert(cache.get(VulkanFormatFeatureCache::size, [&] { ++queries; return 99u; }) == 0);
        }
        assert(queries == VulkanFormatFeatureCache::size);
    }
    std::puts("PASS: 10000 device generations, cached zero masks, repeated formats and invalid slots");
}
