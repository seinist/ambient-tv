// Compile-only check for the exact Android ABI headers, not a GPU execution test.
#include "ahb_import.h"
#include "decoder_image.h"
#include "queue_transfer.h"
#include "submission.h"

using namespace ambient::vlc_ahb;

void compile_contract(VkDevice device, Dispatch image_dispatch, QueueDispatch queue_dispatch,
                      PFN_vkCmdPipelineBarrier barrier, VkCommandBuffer cmd,
                      VkQueue queue, uint32_t family, AImage *image, int producer_fd) {
    // Deliberately never execute: demonstrates the types and call sequence.
    FrameLease lease(image, producer_fd, return_decoder_image);
    DecoderImageInfo metadata;
    if (read_decoder_image(image, metadata) != AMEDIA_OK) return;
    ImportedImage imported(device, image_dispatch);
    if (imported.import(metadata.buffer) != VK_SUCCESS) return;
    if (!record_foreign_acquire(barrier, cmd, imported.image(), family)) return;
    // Real integration records the immutable-sampler sampling pass here.
    if (!record_foreign_release(barrier, cmd, imported.image(), family)) return;
    // The real owner retains all objects asynchronously. Do not actually submit
    // from this compile-only function with stack-owned resources.
    (void)queue_dispatch;
    (void)queue;
}
