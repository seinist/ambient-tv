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
enum { PL_COLOR_SYSTEM_BT_601=1,PL_COLOR_SYSTEM_BT_709,PL_COLOR_SYSTEM_RGB,PL_COLOR_SYSTEM_BT_2020_NC,PL_COLOR_SYSTEM_DOLBYVISION };
enum { PL_COLOR_LEVELS_LIMITED=1,PL_COLOR_LEVELS_FULL };
enum { PL_CHROMA_UNKNOWN=0,PL_CHROMA_LEFT,PL_CHROMA_CENTER,PL_CHROMA_TOP_LEFT };
enum { PL_COLOR_TRC_UNKNOWN=0,PL_COLOR_TRC_PQ,PL_COLOR_TRC_HLG };
enum { PL_ALPHA_NONE=3 };
struct pl_bit_encoding { int sample_depth,color_depth,bit_shift; };
struct mp_ff_side_data { int type; AVBufferRef *buf; };
struct pl_hdr_metadata { float min_luma,max_luma; };
bool pl_hdr_metadata_equal(const struct pl_hdr_metadata *,const struct pl_hdr_metadata *);
struct mp_image {
    int imgfmt,w,h,fields;
    double pts,pkt_duration,nominal_fps;
    AVBufferRef *hwctx,*film_grain,*dovi;
    struct mp_ff_side_data *ff_side_data;
    int num_ff_side_data;
    struct mp_image *enhancement_layer;
    struct {
        struct { int x0,y0,x1,y1; } crop;
        struct { int sys,levels,alpha; struct pl_bit_encoding bits; } repr;
        struct { int transfer,primaries; struct pl_hdr_metadata hdr; } color;
        int chroma_location;
    } params;
};
void mp_image_unrefp(struct mp_image **);
struct mp_image *mp_image_new_ref(struct mp_image *);
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

#ifdef SVP_METADATA_TEST
#include <assert.h>
bool pl_hdr_metadata_equal(const struct pl_hdr_metadata *a,const struct pl_hdr_metadata *b)
{
    return a->min_luma==b->min_luma && a->max_luma==b->max_luma;
}
struct dovi_fixture {
    AVDOVIMetadata metadata;
    AVDOVIRpuDataHeader header;
    AVDOVIDataMapping mapping;
    AVDOVIColorMetadata color;
};
static struct dovi_fixture make_dovi(void)
{
    struct dovi_fixture f={0};
    f.metadata.header_offset=offsetof(struct dovi_fixture,header);
    f.metadata.mapping_offset=offsetof(struct dovi_fixture,mapping);
    f.metadata.color_offset=offsetof(struct dovi_fixture,color);
    f.header.bl_bit_depth=10;f.header.disable_residual_flag=1;
    f.mapping.nlq_method_idc=AV_DOVI_NLQ_NONE;
    return f;
}
int main(void)
{
    AVDynamicHDRPlus h={.num_windows=1},h2=h;
    AVBufferRef x={.data=(uint8_t *)&h,.size=sizeof(h)},y={.data=(uint8_t *)&h2,.size=sizeof(h2)};
    struct mp_ff_side_data ax={AV_FRAME_DATA_DYNAMIC_HDR_PLUS,&x},ay={AV_FRAME_DATA_DYNAMIC_HDR_PLUS,&y};
    struct mp_image a={.ff_side_data=&ax,.num_ff_side_data=1},b={.ff_side_data=&ay,.num_ff_side_data=1};
    assert(svp_vk_hdr_metadata_same(&a,&b));
    h2.num_windows=2;assert(!svp_vk_hdr_metadata_same(&a,&b));
    h2=h;h2.targeted_system_display_actual_peak_luminance_flag=1;
    assert(!svp_vk_hdr_metadata_same(&a,&b));
    h2=h;y.size--;assert(!svp_vk_hdr_metadata_same(&a,&b));y.size++;
    b.num_ff_side_data=0;assert(!svp_vk_hdr_metadata_same(&a,&b));b.num_ff_side_data=1;
    struct dovi_fixture d=make_dovi(),d2=d;
    x.data=(uint8_t *)&d;x.size=sizeof(d);y.data=(uint8_t *)&d2;y.size=sizeof(d2);
    ax.type=ay.type=AV_FRAME_DATA_DOVI_METADATA;
    a.dovi=&x;b.dovi=&y;
    a.params.repr.sys=b.params.repr.sys=PL_COLOR_SYSTEM_DOLBYVISION;
    assert(svp_vk_dovi_valid(&a));
    assert(svp_vk_image_mode(&a,AV_PIX_FMT_P010)==4);
    assert(svp_vk_image_mode(&a,AV_PIX_FMT_YUV420P10)==5);
    assert(svp_vk_image_mode(&a,AV_PIX_FMT_RGBAF16)==-1);
    assert(svp_vk_hdr_metadata_same(&a,&b));
    d2.header.disable_residual_flag=0;
    assert(!svp_vk_dovi_valid(&b));assert(!svp_vk_hdr_metadata_same(&a,&b));
    d2=d;d2.metadata.header_offset=SIZE_MAX;assert(!svp_vk_dovi_valid(&b));
    d2=d;d2.mapping.nlq_method_idc=AV_DOVI_NLQ_LINEAR_DZ;assert(!svp_vk_dovi_valid(&b));
    d2=d;b.enhancement_layer=&a;assert(!svp_vk_dovi_valid(&b));
    b.enhancement_layer=NULL;b.dovi=NULL;assert(!svp_vk_dovi_valid(&b));
    return 0;
}
#endif
