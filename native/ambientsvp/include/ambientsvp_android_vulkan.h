/* SPDX-License-Identifier: Apache-2.0
 * Experimental Android MediaCodec -> Vulkan external-memory bridge.
 * API 29+, no CPU pixel mapping. Not an opaque MediaCodec pointer cast.
 */
#ifndef AMBIENTSVP_ANDROID_VULKAN_H
#define AMBIENTSVP_ANDROID_VULKAN_H
#include "ambientsvp_vulkan.h"
#include <jni.h>
#include <android/native_window.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct Reader AmbientSvpMediaReader;
typedef struct Frame AmbientSvpMediaFrame;
typedef struct Importer AmbientSvpAhbImporter;
typedef struct Job AmbientSvpAhbJob;
typedef struct {
    int64_t timestamp_ns;
    uint32_t crop_left,crop_top,width,height,buffer_width,buffer_height;
} AmbientSvpMediaInfo;
typedef struct {
    VkImage image;
    VkImageView view;
    VkDevice device;
    VkQueue queue;
    uint32_t family;
    AmbientSvpMediaInfo info;
} AmbientSvpAhbOutput;
/* Create the decoder producer Surface BEFORE configuring MediaCodec.
 * Reader uses PRIVATE and GPU_SAMPLED_IMAGE, maxImages=4. Stop the
 * decoder and discard queued output before destroying the public
 * reader handle. Outstanding leases keep the underlying reader alive.
 * Calls on all handles must be externally serialized. */
AmbientSvpMediaReader *ambient_svp_mediacodec_reader_create(uint32_t width,uint32_t height);
void ambient_svp_mediacodec_reader_destroy(AmbientSvpMediaReader *);
/* Borrowed ANativeWindow. For Java/FFmpeg MediaCodec the Surface is a
 * JNI local reference owned by caller. Keep the reader handle alive. */
ANativeWindow *ambient_svp_mediacodec_reader_window(const AmbientSvpMediaReader *);
jobject ambient_svp_mediacodec_reader_surface(const AmbientSvpMediaReader *,JNIEnv *);
/* Release each decoder output buffer to this Surface exactly once.
 * Acquire in original order; 1=lease, 0=not ready, -2=backpressure,
 * -1=invalid/unsupported frame. The producer's acquire fence remains
 * owned by the lease; the caller must not close or consume it. */
int ambient_svp_mediacodec_acquire(const AmbientSvpMediaReader *,AmbientSvpMediaFrame **);
int ambient_svp_mediacodec_frame_info(const AmbientSvpMediaFrame *,AmbientSvpMediaInfo *);
void ambient_svp_mediacodec_frame_discard(AmbientSvpMediaFrame *);
/* Borrow the renderer's actual Vulkan 1.1+ device and compute queue.
 * enabled_extensions MUST describe the actual enabled device extensions,
 * not merely available extensions. ycbcr_enabled=1 confirms that
 * samplerYcbcrConversion was enabled at device creation.
 * Required: VK_ANDROID_external_memory_android_hardware_buffer,
 * VK_EXT_queue_family_foreign, VK_KHR_external_semaphore_fd.
 * No protected queue or device destruction while jobs remain. */
AmbientSvpAhbImporter *ambient_svp_ahb_importer_create(VkInstance,VkPhysicalDevice,VkDevice,
    PFN_vkGetInstanceProcAddr,VkQueue,uint32_t family,const char *const *enabled_extensions,
    uint32_t count,uint32_t ycbcr_enabled);
void ambient_svp_ahb_importer_destroy(AmbientSvpAhbImporter *);
/* Import actual AHB memory, sample via immutable YCbCr conversion, and
 * dispatch into a device-local RGBA8 image. No CPU image copy or readback.
 * sdr8=1 is an EXPLICIT caller guarantee from the decoder's bit-depth/
 * transfer metadata; do not use for HDR, Dolby Vision, protected or
 * unknown streams. Opaque gralloc metadata alone cannot prove SDR8.
 *
 * The caller must hold the renderer's queue lock for this function.
 * 0: submission succeeded; *frame is consumed/null, *job owns GPU work.
 * -2: four outstanding jobs, retry after retirement.
 * -4: unavailable; *frame remains owned by caller, *job is null.
 * On success the original AImage is returned to the producer with the
 * exported release sync_file, or kept until conversion fence completion
 * if export fails. No queue idle or CPU fence wait in the success path.
 */
int ambient_svp_ahb_submit(const AmbientSvpAhbImporter *,AmbientSvpMediaFrame **frame,
    uint32_t sdr8,AmbientSvpAhbJob **job);
typedef struct {
    VkImage image; VkImageView view; VkSemaphore semaphore;
    uint64_t value; VkImageLayout layout; uint32_t family,width,height;
} AmbientSvpAhbDestination;
/* Write straight into a caller-owned RGBA8 storage image, exact visible
 * extent. Requires enabled timeline semaphores. Caller holds frame and
 * queue locks; on successful submit publish GENERAL, shader-write access,
 * semaphore value+1. Retain the supplied image/view until job release.
 * This avoids even a GPU copy into the player's output frame. */
int ambient_svp_ahb_submit_into(const AmbientSvpAhbImporter *,AmbientSvpMediaFrame **frame,
    uint32_t sdr8,const AmbientSvpAhbDestination *,AmbientSvpAhbJob **job);
/* Borrow a GENERAL-layout GPU image/view. Use ONLY on the same queue
 * after the conversion submission (ordered GPU barrier); no ownership
 * transfer to a different queue is implied. Preserve the job until all
 * renderer uses have completed. The image is SDR encoded RGB. */
int ambient_svp_ahb_output(const AmbientSvpAhbJob *,AmbientSvpAhbOutput *);
/* Record resident motion for two imported RGB textures. Requires the
 * same device, geometry and queue as both imports. Submit the supplied
 * command buffer on this queue, then seal/release the pair normally.
 * This pins both imported jobs through actual pair retirement. */
AmbientSvpVkPair *ambient_svp_vk_begin_ahb(const AmbientSvpVk *,VkCommandBuffer,VkQueue,
    const AmbientSvpAhbJob *,const AmbientSvpAhbJob *);
/* Nonblocking: 1 destroyed, 0 pending conversion/registered SVP pair/
 * final renderer fence, -1 error. consumer_fence may be null ONLY if
 * the output has not been used outside the registered resident pairs.
 * Otherwise it must correspond to the final successful consumer submit
 * and remain valid/unreset until retirement. Device loss is terminal.
 */
int ambient_svp_ahb_release(AmbientSvpAhbJob *,VkFence consumer_fence);
#ifdef __cplusplus
}
#endif
#endif
