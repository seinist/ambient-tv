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
#include <libavutil/hdr_dynamic_metadata.h>
enum { IMGFMT_VULKAN=1, MP_IMGFIELD_INTERLACED=1 };
#define MP_NOPTS_VALUE (-1e20)
enum { PL_COLOR_SYSTEM_BT_601=1,PL_COLOR_SYSTEM_BT_709,PL_COLOR_SYSTEM_RGB,PL_COLOR_SYSTEM_BT_2020_NC,PL_COLOR_SYSTEM_DOLBYVISION };
enum { PL_COLOR_LEVELS_LIMITED=1,PL_COLOR_LEVELS_FULL };
enum { PL_CHROMA_UNKNOWN=0,PL_CHROMA_LEFT,PL_CHROMA_CENTER,PL_CHROMA_TOP_LEFT };
enum { PL_COLOR_TRC_UNKNOWN=0,PL_COLOR_TRC_PQ,PL_COLOR_TRC_HLG };
enum { PL_COLOR_PRIM_BT_2020=1 };
enum { PL_ALPHA_NONE=3 };
struct pl_bit_encoding { int sample_depth,color_depth,bit_shift; };
struct mp_ff_side_data { int type; AVBufferRef *buf; };
struct pl_hdr_metadata { float min_luma,max_luma,scene_max[3],scene_avg,max_pq_y,avg_pq_y; struct { float target_luma; } ootf; };
struct pl_av_hdr_metadata { const AVDynamicHDRPlus *dhp; };
void pl_map_hdr_metadata(struct pl_hdr_metadata *,const struct pl_av_hdr_metadata *);
bool pl_hdr_metadata_equal(const struct pl_hdr_metadata *,const struct pl_hdr_metadata *);
struct mp_image {
    int imgfmt,w,h,fields;
    double pts,pkt_duration,nominal_fps;
    AVBufferRef *hwctx,*film_grain,*dovi;
    struct mp_ff_side_data *ff_side_data;
    int num_ff_side_data;
    struct mp_image *enhancement_layer;
    bool ambient_svp_hdr_generated;
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
    struct svp_vk_mpv *s=svp_vk_new(lib,img,24,60,NULL);
    if (!s) return;
    if (svp_vk_feed(s,img)) svp_vk_eof(s);
    struct mp_image *out=svp_vk_take_output(s);
    mp_image_unrefp(&out);
    svp_vk_destroy_state(s);
}

#ifdef SVP_METADATA_TEST
#include <assert.h>
#include <stddef.h>
int av_buffer_is_writable(const AVBufferRef *ref){return ref&&ref->data;}
void pl_map_hdr_metadata(struct pl_hdr_metadata *hdr,const struct pl_av_hdr_metadata *meta){
    if(meta&&meta->dhp)hdr->scene_avg=(float)meta->dhp->params[0].average_maxrgb.num/meta->dhp->params[0].average_maxrgb.den;
}
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
    SvpHdrCurve low,high,bright;
    assert(svp_hdr_curve(5000,1000,4000,1000,&low)&&low.count==0);
    assert(svp_hdr_curve(40000,1000,30000,1000,&high)&&high.count==9);
    assert(svp_hdr_curve(40000,20000,30000,1000,&bright)&&bright.count==9);
    assert(high.knee_x!=bright.knee_x&&high.knee_y!=bright.knee_y);
    for(unsigned j=1;j<high.count;j++)assert(high.anchors[j]>=high.anchors[j-1]);
    assert(!svp_hdr_curve(1000,2000,900,1000,&low));
    AVDynamicHDRPlus h={.num_windows=1},h2;
    memcpy(&h2,&h,sizeof(h));
    AVBufferRef x={.data=(uint8_t *)&h,.size=sizeof(h)},y={.data=(uint8_t *)&h2,.size=sizeof(h2)};
    struct mp_ff_side_data ax={AV_FRAME_DATA_DYNAMIC_HDR_PLUS,&x},ay={AV_FRAME_DATA_DYNAMIC_HDR_PLUS,&y};
    struct mp_image a={.ff_side_data=&ax,.num_ff_side_data=1},b={.ff_side_data=&ay,.num_ff_side_data=1};
    assert(svp_vk_hdr_metadata_same(&a,&b));
    h2.num_windows=2;assert(!svp_vk_hdr_metadata_same(&a,&b));
    h2=h;h2.targeted_system_display_actual_peak_luminance_flag=1;
    assert(!svp_vk_hdr_metadata_same(&a,&b));
    h2=h;y.size--;assert(!svp_vk_hdr_metadata_same(&a,&b));y.size++;
    b.num_ff_side_data=0;assert(!svp_vk_hdr_metadata_same(&a,&b));b.num_ff_side_data=1;
    // Three valid windows with matched topology, local geometry,
    // percentiles, OOTF anchors, saturation and peak grids.
    memset(&h,0,sizeof(h));h.itu_t_t35_country_code=0xb5;
    h.num_windows=3;h.targeted_system_display_maximum_luminance=(AVRational){1000,1};
    for(unsigned w=0;w<3;w++){
        AVHDRPlusColorTransformParams *p=&h.params[w];
        for(unsigned c=0;c<3;c++)p->maxscl[c]=(AVRational){10000,100000};
        p->average_maxrgb=(AVRational){5000,100000};
        p->fraction_bright_pixels=(AVRational){100,1000};
        p->num_distribution_maxrgb_percentiles=1;
        p->distribution_maxrgb[0]=(AVHDRPlusPercentile){99,{8000,100000}};
        p->tone_mapping_flag=1;p->knee_point_x=(AVRational){1000,4095};
        p->knee_point_y=(AVRational){1200,4095};p->num_bezier_curve_anchors=1;
        p->bezier_curve_anchors[0]=(AVRational){500,1023};
        p->color_saturation_mapping_flag=1;p->color_saturation_weight=(AVRational){8,8};
        if(w){
            p->window_upper_left_corner_x=(AVRational){20*w,127};
            p->window_upper_left_corner_y=(AVRational){8,63};
            p->window_lower_right_corner_x=(AVRational){20*w+30,127};
            p->window_lower_right_corner_y=(AVRational){50,63};
            p->center_of_ellipse_x=20*w+15;p->center_of_ellipse_y=29;
            p->semimajor_axis_internal_ellipse=10;p->semimajor_axis_external_ellipse=15;
            p->semiminor_axis_external_ellipse=8;
        }
    }
    h.targeted_system_display_actual_peak_luminance_flag=1;
    h.num_rows_targeted_system_display_actual_peak_luminance=2;
    h.num_cols_targeted_system_display_actual_peak_luminance=2;
    for(unsigned j=0;j<2;j++)for(unsigned k=0;k<2;k++)
        h.targeted_system_display_actual_peak_luminance[j][k]=(AVRational){4,15};
    memcpy(&h2,&h,sizeof(h));
    h2.params[1].maxscl[0]=(AVRational){30000,100000};
    h2.params[2].center_of_ellipse_x+=4;
    h2.params[2].bezier_curve_anchors[0]=(AVRational){700,1023};
    h2.targeted_system_display_actual_peak_luminance[1][1]=(AVRational){10,15};
    AVDynamicHDRPlus mid;
    assert(svp_hdrplus_lerp(&mid,&x,&y,.5,128,64));
    assert(mid.num_windows==3 && mid.params[1].maxscl[0].num==20000);
    assert(mid.params[2].center_of_ellipse_x==h.params[2].center_of_ellipse_x+2);
    assert(mid.params[2].bezier_curve_anchors[0].num==600);
    assert(mid.targeted_system_display_actual_peak_luminance[1][1].num==7);
    h2.params[1].overlap_process_option=AV_HDR_PLUS_OVERLAP_PROCESS_LAYERING;
    assert(!svp_hdrplus_lerp(&mid,&x,&y,.5,128,64));
    h2.params[1].overlap_process_option=h.params[1].overlap_process_option;
    h2.num_cols_targeted_system_display_actual_peak_luminance=3;
    assert(!svp_hdrplus_lerp(&mid,&x,&y,.5,128,64));
    // Version-zero two-pass replay preserves all selected geometries and
    // old optional display descriptors, while measuring every window.
    a.w=128;a.h=64;a.params.color.transfer=PL_COLOR_TRC_PQ;a.params.color.primaries=PL_COLOR_PRIM_BT_2020;
    assert(svp_vk_scene_compatible(&a));
    AmbientSvpHdrLegacyScene legacy={.first=0,.end=2,.count=3};
    for(unsigned w=0;w<3;w++){
        legacy.windows[w].count=1;legacy.windows[w].percentages[0]=99;
        legacy.windows[w].maxscl[0]=legacy.windows[w].maxscl[1]=legacy.windows[w].maxscl[2]=1000+100*w;
        legacy.windows[w].average=500+100*w;legacy.windows[w].percentiles[0]=900+100*w;
    }
    const unsigned center=h.params[1].center_of_ellipse_x;
    assert(svp_vk_scene_legacy_metadata(&a,&legacy));
    assert(h.num_windows==3&&h.application_version==0&&h.targeted_system_display_actual_peak_luminance_flag);
    assert(h.params[1].center_of_ellipse_x==center&&h.params[2].average_maxrgb.num==700);
    assert(h.params[0].fraction_bright_pixels.num==0&&h.params[1].fraction_bright_pixels.num==0);
    legacy.windows[1].fraction=1;
    assert(!svp_vk_scene_legacy_metadata(&a,&legacy));
    const uint16_t nonascending[]={600,400};
    const uint16_t invalid[]={1023,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    assert(svp_hdr_anchors_monotone(nonascending,2));
    assert(!svp_hdr_anchors_monotone(invalid,15));
    h.params[0].tone_mapping_flag=1;h.params[0].num_bezier_curve_anchors=2;
    h.params[0].bezier_curve_anchors[0]=(AVRational){600,1023};
    h.params[0].bezier_curve_anchors[1]=(AVRational){400,1023};
    h.params[0].knee_point_x=(AVRational){0,4095};
    h.params[0].knee_point_y=(AVRational){0,4095};
    assert(svp_vk_scene_compatible(&a));
    // Actual second-pass metadata mapping, including reserved values
    // and a Profile-A-style absent tone mapping target.
    memset(&h,0,sizeof(h));h.itu_t_t35_country_code=0xb5;
    h.application_version=1;h.num_windows=1;
    h.targeted_system_display_maximum_luminance=(AVRational){0,1};
    AVHDRPlusColorTransformParams *scene=&h.params[0];
    static const unsigned percentages[]={1,5,10,25,50,75,90,95,99};
    scene->num_distribution_maxrgb_percentiles=9;
    for(unsigned j=0;j<3;j++)scene->maxscl[j]=(AVRational){1000,100000};
    scene->average_maxrgb=(AVRational){500,100000};scene->fraction_bright_pixels=(AVRational){0,1000};
    for(unsigned j=0;j<9;j++)scene->distribution_maxrgb[j]=(AVHDRPlusPercentile){
        percentages[j],{j==1?0:j==2?255:500,100000}};
    a.w=128;a.h=64;a.params.color.transfer=PL_COLOR_TRC_PQ;a.params.color.primaries=PL_COLOR_PRIM_BT_2020;
    assert(svp_vk_scene_compatible(&a));
    AmbientSvpHdrSceneResult measured={.first=0,.end=2,.brightest_frame=1,
        .maxscl={1200,1100,1000},.average=600,.percentiles={100,0,255,200,400,600,800,900,1100},.fraction=321};
    assert(svp_vk_scene_metadata(&a,&measured));
    assert(scene->maxscl[0].num==1200&&scene->average_maxrgb.num==600&&scene->fraction_bright_pixels.num==321);
    assert(scene->distribution_maxrgb[2].percentile.num==255&&!scene->tone_mapping_flag&&a.ambient_svp_hdr_generated);
    scene->distribution_maxrgb[3].percentage=26;
    assert(svp_vk_scene_compatible(&a));
    assert(svp_vk_scene_metadata(&a,&measured));
    scene->distribution_maxrgb[3].percentage=10;
    assert(!svp_vk_scene_compatible(&a));
    assert(!svp_vk_scene_metadata(&a,&measured));
    struct dovi_fixture d=make_dovi(),d2;
    memcpy(&d2,&d,sizeof(d));
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
