// Compile-only check for the exact Android ABI headers, not a GPU execution test.
#include "ahb_import.h"
#include "decoder_image.h"
#include "queue_transfer.h"
#include "submission.h"
#include "sample_pass.h"
#include "rgb_target.h"
#include "slot_pool.h"
#include "conversion_cache.h"

using namespace ambient::vlc_ahb;

void compile_contract(VkPhysicalDevice physical, VkDevice device, Dispatch image_dispatch, QueueDispatch queue_dispatch,
                      PFN_vkCmdPipelineBarrier barrier, VkCommandBuffer cmd,
                      VkQueue queue, uint32_t family, AImage *image, int producer_fd,
                      SampleDispatch sample_dispatch, VkImageView output,
                      ShaderCode vert, ShaderCode frag, uint32_t descriptor_count) {
    // Deliberately never execute: demonstrates the types and call sequence.
    FrameLease lease(image, producer_fd, return_decoder_image);
    DecoderImageInfo metadata;
    if (read_decoder_image(image, metadata) != AMEDIA_OK) return;
    ImportedImage imported(device, image_dispatch, physical);
    if (imported.import(metadata.buffer) != VK_SUCCESS) return;
    if (!record_foreign_acquire(barrier, cmd, imported.image(), family)) return;
    SamplePass sample(device, sample_dispatch);
    if (sample.create(imported.sampler(), imported.view(), output,
                      VK_FORMAT_R8G8B8A8_UNORM, 1920, 1080,
                      descriptor_count, vert, frag) != VK_SUCCESS) return;
    const float crop[4] = {0, 0, 1, 1};
    if (!sample.record(cmd, crop)) return;
    if (!record_foreign_release(barrier, cmd, imported.image(), family)) return;
    // The real owner retains all objects asynchronously. Do not actually submit
    // from this compile-only function with stack-owned resources.
    (void)queue_dispatch;
    (void)queue;
}

// Instantiate the exact cached-resource path for every Android ABI. Never submit
// GPU work from this stack-owned compile-only fixture.
void compile_cache_contract(VkPhysicalDevice physical, VkDevice device, Dispatch image,
                            TargetDispatch target, SampleDispatch sample, ShaderCode vert,
                            ShaderCode frag, AHardwareBuffer *buffer, uint32_t descriptors) {
    ConversionCacheEntry cache(physical, device, image, target, sample, vert, frag);
    if (cache.prepare_retired(buffer, 1920, 1080, VK_FORMAT_R8G8B8A8_UNORM, descriptors) != VK_SUCCESS) return;
    if (!cache.matches(buffer, 1920, 1080, VK_FORMAT_R8G8B8A8_UNORM, descriptors)) return;
    (void)cache.imported();
    (void)cache.pass();
    (void)cache.target();
    cache.evict_import_retired();
}
