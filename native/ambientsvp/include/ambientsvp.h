/* SPDX-License-Identifier: Apache-2.0 */
#ifndef AMBIENTSVP_H
#define AMBIENTSVP_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
#define AMBIENTSVP_ABI 2
typedef struct AmbientSvp AmbientSvp;
/* Clear temporal history after a seek; preserve GPU resources, lifetime
 * counters and safety bypass. Recreate the instance to retry after overload. */
int ambient_svp_reset(AmbientSvp *);
/* All frames are tightly packed 8-bit I420. Timestamps are microseconds.
 * Calls for an instance must be serialized. Input is copied, output is caller-owned.
 * backend: 0 auto, 1 CPU, 2 Vulkan; preset: 0 auto, 1 light, 2 balanced, 3 quality, 4 adaptive.
 * Every preset bypasses interpolation on sustained overload. Queue size is bounded.
 */
typedef struct {
    uint32_t abi, width, height, source_num, source_den, target_num, target_den;
    uint32_t preset, backend, threads;
    /* 0 inherits preset. overlap: 1..4 maps to upstream 0..3. */
    uint32_t artifacts, scene, algo, block, overlap, pel, search;
} AmbientSvpConfig;
typedef struct {
    uint64_t input_frames, output_frames, interpolated_frames;
    uint64_t analysis_us, synthesis_us;
    uint32_t queued_frames, backend, bypass_reason, preset;
} AmbientSvpStats;
/* NULL on unsupported configuration or unavailable explicitly selected backend. */
AmbientSvp *ambient_svp_create(const AmbientSvpConfig *config);
void ambient_svp_destroy(AmbientSvp *svp);
/* 0 success, -1 invalid/error, -2 full (receive before pushing more input).
 * Discontinuous timestamps return -3; reset/recreate then resubmit the frame. */
int ambient_svp_push(AmbientSvp *svp, const uint8_t *i420, size_t len, int64_t pts_us);
/* Optional direct-fill entry point. The writer is called synchronously only
 * after input timing/capacity checks. It must fill exactly len bytes of I420,
 * return 0 on success, not retain dst and never re-enter this engine. */
typedef int (*AmbientSvpFill)(void *context, uint8_t *dst, size_t len);
int ambient_svp_push_fill(AmbientSvp *, int64_t pts_us, void *context, AmbientSvpFill);
 /* 1 output, 0 needs input/end of drain, -1 failure. Always drain before push.
 * eof=1 closes the input and drains the finite tail without extrapolation. */
int ambient_svp_receive(AmbientSvp *svp, uint8_t *i420, size_t len, int64_t *pts_us, int eof);
/* Optional allocation preflight: 1 may produce output, 0 needs input/end, -1 error. */
int ambient_svp_ready(AmbientSvp *, int eof);
/* Optional direct output visitor. Called synchronously exactly once for
 * each output, never for a need-input result. The borrowed packed I420
 * pointer is read-only and valid only during the callback; do not retain
 * it, re-enter SVP, or unwind. The destination must be ready beforehand. */
typedef void (*AmbientSvpVisit)(void *context, const uint8_t *src, size_t len);
int ambient_svp_receive_visit(AmbientSvp *, void *, AmbientSvpVisit, int64_t *, int);
/* Android API 29+ optional CPU-mappable YUV420 hardware buffers.
 * The caller owns a valid AHardwareBuffer reference throughout the call.
 * x/y select the even-aligned visible region of the configured dimensions.
 * acquire_fence is borrowed: -1 means the caller has synchronized access,
 * otherwise it must already be signaled; the engine never closes it.
 * -4 means unsupported/unready hardware buffer; no frame was consumed.
 * Protected, opaque and non-8-bit buffers are deliberately rejected.
 * Input copies directly into the engine's I420 allocation and unlocks before
 * analysis. Output writes directly into the caller's hardware allocation.
 * If release_fence is non-NULL, caller owns any returned fd (even on error)
 * and must wait/close it before reusing or presenting the buffer.
 * NULL requests synchronous unlock. Not Vulkan external-memory zero-copy.
 * Output -1 may have consumed an output: do not retry that timestamp.
 * Both symbols are optional; discover via dlsym on Android. */
struct AHardwareBuffer;
int ambient_svp_push_ahardwarebuffer(AmbientSvp *, struct AHardwareBuffer *,
                                    uint32_t x, uint32_t y, int acquire_fence, int64_t pts);
int ambient_svp_receive_ahardwarebuffer(AmbientSvp *, struct AHardwareBuffer *,
                                    uint32_t x, uint32_t y, int acquire_fence,
                                    int *release_fence, int64_t *pts, int eof);
int ambient_svp_stats(const AmbientSvp *svp, AmbientSvpStats *stats);
/* Explicit runtime protection (thermal pressure / player dropped frames). */
void ambient_svp_bypass(AmbientSvp *svp, uint32_t reason);
uint32_t ambient_svp_abi(void);
int ambient_svp_enabled(void);
void ambient_svp_set_enabled(int enabled);
#ifdef __cplusplus
}
#endif
#endif
