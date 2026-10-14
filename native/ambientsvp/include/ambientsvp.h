/* SPDX-License-Identifier: Apache-2.0 */
#ifndef AMBIENTSVP_H
#define AMBIENTSVP_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
#define AMBIENTSVP_ABI 1
typedef struct AmbientSvp AmbientSvp;
/* Clear temporal history after a seek; preserve GPU resources, lifetime
 * counters and safety bypass. Recreate the instance to retry after overload. */
int ambient_svp_reset(AmbientSvp *);
/* All frames are tightly packed 8-bit I420. Timestamps are microseconds.
 * Calls for an instance must be serialized. Input is copied, output is caller-owned.
 * backend: 0 auto, 1 CPU, 2 Vulkan; preset: 0 auto, 1 light, 2 balanced, 3 quality.
 * Every preset bypasses interpolation on sustained overload. Queue size is bounded.
 */
typedef struct {
    uint32_t abi, width, height, source_num, source_den, target_num, target_den;
    uint32_t preset, backend, threads;
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
/* 1 output, 0 needs input/end of drain, -1 failure. Always drain before push.
 * eof=1 closes the input and drains the finite tail without extrapolation. */
int ambient_svp_receive(AmbientSvp *svp, uint8_t *i420, size_t len, int64_t *pts_us, int eof);
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
