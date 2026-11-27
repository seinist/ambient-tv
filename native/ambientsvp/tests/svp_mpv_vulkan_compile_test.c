/* SPDX-License-Identifier: Apache-2.0
 * Syntax check of the native MPV Vulkan transport against the real pinned
 * FFmpeg Vulkan public structs and system Vulkan headers. MPV functions
 * are declarations only; a full renderer build and device test are separate.
 */
#include <stdint.h>
#include <stdbool.h>
#include <dlfcn.h>
#include <libavutil/hwcontext_vulkan.h>
#include <libavutil/frame.h>
enum { IMGFMT_VULKAN=1, MP_IMGFIELD_INTERLACED=1 };
#define MP_NOPTS_VALUE (-1e20)
enum { PL_COLOR_SYSTEM_BT_601=1,PL_COLOR_SYSTEM_BT_709,PL_COLOR_SYSTEM_RGB };
enum { PL_COLOR_LEVELS_LIMITED=1,PL_COLOR_LEVELS_FULL };
enum { PL_CHROMA_UNKNOWN=0,PL_CHROMA_LEFT,PL_CHROMA_CENTER,PL_CHROMA_TOP_LEFT };
enum { PL_COLOR_TRC_UNKNOWN=0,PL_COLOR_TRC_PQ,PL_COLOR_TRC_HLG };
enum { PL_ALPHA_NONE=3 };
struct mp_image {
    int imgfmt,w,h,fields;
    double pts,pkt_duration,nominal_fps;
    AVBufferRef *hwctx,*film_grain;
    struct mp_image *enhancement_layer;
    struct {
        struct { int x0,y0,x1,y1; } crop;
        struct { int sys,levels,alpha; } repr;
        struct { int transfer,primaries; } color;
        int chroma_location;
    } params;
};
void mp_image_unrefp(struct mp_image **);
AVFrame *mp_image_to_av_frame(struct mp_image *);
struct mp_image *mp_image_from_av_frame(AVFrame *);
void mp_image_set_size(struct mp_image *,int,int);
void mp_image_copy_attributes(struct mp_image *,struct mp_image *);
#include "../adapters/svp_mpv_vulkan.h"
// Keep entry points checked with the actual expected types.
void svp_mpv_compile_entrypoints(void *lib,struct mp_image *img)
{
    struct svp_vk_mpv *s=svp_vk_new(lib,img,24,60);
    if (!s) return;
    if (svp_vk_feed(s,img)) svp_vk_eof(s);
    struct mp_image *out=svp_vk_take_output(s);
    mp_image_unrefp(&out);
    svp_vk_destroy_state(s);
}
