/* SPDX-License-Identifier: Apache-2.0
 * Experimental native Vulkan resident path. No wgpu or CPU pixel copy.
 * This header is separate from the portable ABI-2 software interface.
 */
#ifndef AMBIENTSVP_VULKAN_H
#define AMBIENTSVP_VULKAN_H
#include <stdint.h>
#include "ambientsvp_quality.h"
#include <vulkan/vulkan.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct VulkanResident AmbientSvpVk;
typedef struct VulkanPair AmbientSvpVkPair;
typedef struct {
    VkImageView y, u, v;
    uint32_t nv12; /* 0 I420, 1 NV12, 2 SDR RGBA8 in y (u/v ignored) */
    uint32_t chroma_location; /* 0 top-left, 1 left, 2 center */
} AmbientSvpVkPicture;
enum {
    AMBIENT_SVP_VK_BT601_LIMITED=0, AMBIENT_SVP_VK_BT709_LIMITED=1,
    AMBIENT_SVP_VK_BT601_FULL=2, AMBIENT_SVP_VK_BT709_FULL=3,
    AMBIENT_SVP_VK_BT2020_LIMITED=4, AMBIENT_SVP_VK_BT2020_FULL=6
};
/* Borrow the player's actual instance, physical device, logical device.
 * Do not destroy them until all sessions and pairs have been released.
 * Requires 8-bit SDR planes (R8_UNORM/R8G8_UNORM) or two RGBA8_UNORM inputs,
 * no mixed RGB/YUV pair. The matrix parameter is ignored for RGB pairs.
 * even visible dimensions and <=1920x1080 pixels. Native RGBA8_UNORM
 * output needs STORAGE usage and a nonaliasing distinct allocation.
 * Padding beyond visible width/height is permitted, nonzero crop origin is not.
 * No protected memory, HDR, foreign queue ownership or
 * Android external-format conversion is implicit in this contract.
 * Caller must establish correct views, layout GENERAL, ownership,
 * producer waits and output barriers; use the same device throughout.
 * Calls per session/pair must be serialized. */
AmbientSvpVk *ambient_svp_vk_create(VkInstance,VkPhysicalDevice,VkDevice,
    PFN_vkGetInstanceProcAddr,uint32_t width,uint32_t height);
/* Optional format-specific ABI; legacy create remains unchanged.
 * Mode 1: P010 high ten bits in separate R16/RG16 UNORM views (nv12=1).
 * Mode 2: planar low ten bits in separate R16 UNORM views (nv12=0).
 * Mode 3: RGBA16F encoded RGB (nv12=2), also output RGBA16F.
 * Modes 1/2 convert BT2020 NCL; mode 3 does not repeat YUV conversion.
 * Mode 4: raw P010 output (R16/RG16); mode 5: raw I420-10 output (3 R16).
 * Modes 4/5 require enabled shaderStorageImageExtendedFormats and
 * matching chroma siting, no RGB conversion, preserve 10-bit packing.
 * No EOTF or tone mapping; encoded RGB and headroom are preserved.
 * Caller must preserve compatible transfer/primaries/static metadata.
 * Motion uses ten-bit luma SAD. Renderer owns dynamic HDR/Dolby metadata;
 * raw modes retain the YUV representation for downstream RPU reshaping.
 * The legacy picture struct and render call remain unchanged. */
enum { AMBIENT_SVP_VK_FORMAT_SDR8=0, AMBIENT_SVP_VK_FORMAT_P010=1,
       AMBIENT_SVP_VK_FORMAT_I420_10=2, AMBIENT_SVP_VK_FORMAT_RGB16F=3,
       AMBIENT_SVP_VK_FORMAT_P010_RAW=4, AMBIENT_SVP_VK_FORMAT_I420_10_RAW=5 };
AmbientSvpVk *ambient_svp_vk_create_format(VkInstance,VkPhysicalDevice,VkDevice,
    PFN_vkGetInstanceProcAddr,uint32_t width,uint32_t height,uint32_t mode);
/* Optional quality configuration, explicit pixel overlap and pel units.
 * Uses full-block metrics, subpixel refinement and GPU scene gate.
 * Legacy create/create_format remain available and unchanged. */
typedef struct {
    uint32_t block,overlap,pel,radius,satd,chroma,lambda,pnew,scene,artifacts;
} AmbientSvpVkTuning;
AmbientSvpVk *ambient_svp_vk_create_config(VkInstance,VkPhysicalDevice,VkDevice,
    PFN_vkGetInstanceProcAddr,uint32_t width,uint32_t height,uint32_t mode,const AmbientSvpVkTuning *);
/* Weighted quality ABI, independent of the legacy tuning struct.
 * filter: 0=upstream rounded bilinear, 1=Catmull-Rom, 2=Wiener.
 * lambda/lsad are unscaled 8-bit block parameters. plevel_q16
 * expresses the level factor; level is zero for the finest stage.
 * Relative penalties are signed in units of 1/256; sad_gate 0/1.
 * Visible texture boundaries are clamped, not packed-canvas spills. */
typedef struct {
    uint32_t filter,lambda,lsad,plevel_q16,levels,level;
    int32_t pnew,pzero,pglobal,pnbour,preverse;
    uint32_t sad_gate;
} AmbientSvpVkSearchWeights;
AmbientSvpVk *ambient_svp_vk_create_weighted(VkInstance,VkPhysicalDevice,VkDevice,
    PFN_vkGetInstanceProcAddr,uint32_t width,uint32_t height,uint32_t mode,
    const AmbientSvpVkTuning *,const AmbientSvpVkSearchWeights *);
/* Optional reference synthesis selection. Set before any in-flight pair.
 * Algorithms 1,2,11,13,21,22,23; 23 requires adjacent fields.
 * begin_extended retains three native field bundles until the center
 * pair's actual fence retires. Caller retains all four image views.
 * Failure before recording permits ordinary begin (23 -> 21). */
int ambient_svp_vk_set_synthesis(const AmbientSvpVk *,uint32_t algorithm);
AmbientSvpVkPair *ambient_svp_vk_begin_extended(const AmbientSvpVk *,VkCommandBuffer,
    const AmbientSvpVkPicture *previous,const AmbientSvpVkPicture *a,
    const AmbientSvpVkPicture *b,const AmbientSvpVkPicture *next);
AmbientSvpVk *ambient_svp_vk_create_quality(VkInstance,VkPhysicalDevice,VkDevice,
    PFN_vkGetInstanceProcAddr,uint32_t,uint32_t,uint32_t,const AmbientSvpQuality *);
/* Held canonical RGBA16F images: [A,B,out] or [A,B,past,next,out].
 * Wait on count binary semaphores, signal a caller-owned timeline value.
 * Caller serializes the queue and holds images through the call.
 * No host pixel transfer; completes synchronously. */
int ambient_svp_vk_render_held(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
    const VkSemaphore *,VkSemaphore,uint64_t,float);
void ambient_svp_vk_destroy(AmbientSvpVk *);
/* Record motion analysis into an already-recording command buffer
 * outside a render pass; no submit. Pair retains its private resources.
 * The caller must keep both pictures and their views alive until release.
 * At most four outstanding pairs per session. NULL on rejection. */
AmbientSvpVkPair *ambient_svp_vk_begin(const AmbientSvpVk *,VkCommandBuffer,
    const AmbientSvpVkPicture *,const AmbientSvpVkPicture *);
/* Record up to eight output phases in [0,1] into the same command buffer.
 * Each output requires its own live nonaliasing GENERAL storage view.
 * Caller orders repeated uses of the same output via image barriers.
 * Output is encoded full-range RGB, in the session's configured format.
 * 0 success, -1 rejected. */
int ambient_svp_vk_render(AmbientSvpVkPair *,float phase,uint32_t matrix,VkImageView output);
/* Raw-mode output: exact plane count (2/3), all distinct nonaliasing
 * GENERAL storage views, same visible geometry and packing as input. */
int ambient_svp_vk_render_raw(AmbientSvpVkPair *,float,const VkImageView *,uint32_t count);
/* Optional PQ RGB statistics on a recorded high-depth output phase.
 * Rectangles are inclusive left/top, exclusive right/bottom. Window 0
 * covers the complete visible frame. Percentile indices strictly ordered.
 * All luminance results are normalized linear RGB in 1/100000 units.
 * Counts refer to actual pixels, brightest counts ties in quantized MaxRGB.
 * GPU histogram stays resident; only the compact result is host-visible.
 * The caller guarantees PQ encoded BT2020 RGB and distinct output views.
 * Record before sealing; read returns 1 complete, 0 pending, -1 rejected.
 * Read before releasing the pair. No pixel readback or implicit submit. */
typedef struct {
    uint32_t rect[4],count,percentages[15];
} AmbientSvpHdrWindow;
typedef struct {
    uint32_t maxscl[3],average,percentiles[15],pixels,brightest;
} AmbientSvpHdrResult;
int ambient_svp_vk_hdr_stats(AmbientSvpVkPair *,uint32_t phase,const AmbientSvpHdrWindow *,uint32_t count);
int ambient_svp_vk_hdr_read(const AmbientSvpVkPair *,uint32_t phase,AmbientSvpHdrResult *,uint32_t count);
/* Optional tracked statistics: source endpoint rectangles plus interpolated
 * ellipse center; shifts in pixels are bounded to +/-16 and frame edges.
 * The native motion field is used before collecting the actual pixels.
 * Existing non-tracked functions and structs are unchanged. */
typedef struct { uint32_t a[4],b[4],ellipse[2]; } AmbientSvpHdrTrack;
int ambient_svp_vk_hdr_stats_track(AmbientSvpVkPair *,uint32_t,const AmbientSvpHdrWindow *,
    const AmbientSvpHdrTrack *,float,uint32_t);
int ambient_svp_vk_hdr_read_track(const AmbientSvpVkPair *,uint32_t,AmbientSvpHdrResult *,int32_t (*)[2],uint32_t);
/* After SUCCESSFUL submission of that command buffer, register its
 * unsignaled-at-submit fence. Caller must not destroy/reset the fence
 * until release returns 1. No more recording after sealing. */
int ambient_svp_vk_seal(AmbientSvpVkPair *,VkFence);
/* Nonblocking retirement: 1 destroyed, 0 still in flight, -1 error.
 * On error keep all resources alive until explicit device teardown.
 * Never use a pair again after return 1. */
int ambient_svp_vk_release(AmbientSvpVkPair *);
/* Cancel only if the command buffer has been discarded/reset and has
 * NEVER been submitted. No effect on already sealed pairs. */
int ambient_svp_vk_cancel(AmbientSvpVkPair *);
#ifdef __cplusplus
}
#endif
#endif
