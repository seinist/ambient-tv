#ifndef AMBIENT_SVP_PLANES_H
#define AMBIENT_SVP_PLANES_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

/* Strides are signed: mpv can expose vertically flipped pictures. */
static inline void svp_copy_plane(uint8_t *dst, ptrdiff_t dst_pitch,
                                  const uint8_t *src, ptrdiff_t src_pitch,
                                  size_t width, size_t height)
{
    if (dst_pitch == (ptrdiff_t)width && src_pitch == (ptrdiff_t)width) {
        memcpy(dst, src, width * height);
        return;
    }
    for (size_t y = 0; y < height; ++y)
        memcpy(dst + (ptrdiff_t)y * dst_pitch, src + (ptrdiff_t)y * src_pitch, width);
}

static inline void svp_split_uv(uint8_t *u, uint8_t *v, const uint8_t *uv, size_t n)
{
    size_t x = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
    for (; n - x >= 16; x += 16) {
        uint8x16x2_t p = vld2q_u8(uv + 2 * x);
        vst1q_u8(u + x, p.val[0]);
        vst1q_u8(v + x, p.val[1]);
    }
#endif
    for (; x < n; ++x) { u[x] = uv[2*x]; v[x] = uv[2*x+1]; }
}

static inline void svp_join_uv(uint8_t *uv, const uint8_t *u, const uint8_t *v, size_t n)
{
    size_t x = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
    for (; n - x >= 16; x += 16) {
        uint8x16x2_t p;
        p.val[0] = vld1q_u8(u + x); p.val[1] = vld1q_u8(v + x);
        vst2q_u8(uv + 2*x, p);
    }
#endif
    for (; x < n; ++x) { uv[2*x] = u[x]; uv[2*x+1] = v[x]; }
}
#endif
