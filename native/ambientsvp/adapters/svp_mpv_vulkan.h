/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Opt-in MPV native Vulkan SVP transport. Uses the decoder/renderer
 * VkDevice, actual AVVkFrame image views and FFmpeg timeline semaphores.
 * No CPU pixel transfer. This is intentionally restricted to compatible
 * 8-bit SDR or high-depth frames; static HDR and identical supported
 * HDR10+ metadata, or compatible single-layer Dolby Vision in raw YUV.
 * Included by vf_ambientsvp.c only with HAVE_VULKAN and libavutil 61.
 */
#ifndef SVP_MPV_VULKAN_H
#define SVP_MPV_VULKAN_H
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vulkan.h>
#include <libavutil/frame.h>
#include <libavutil/hdr_dynamic_metadata.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/version.h>
#include "ambientsvp_vulkan.h"
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdio.h>
#include "svp_hdr_curve.h"

#define SVP_VK_PHASES 8
#define SVP_VK_JOBS 4
#define SVP_VK_FRAMES (4 + SVP_VK_PHASES)
#define SVP_VK_IMAGES (12 + 3*SVP_VK_PHASES)
struct svp_vk_job {
    VkCommandPool pool;
    VkCommandBuffer cmd;
    VkFence fence;
    AmbientSvpVkPair *pair;
    AVFrame *frames[SVP_VK_FRAMES];
    VkImageView views[SVP_VK_IMAGES];
    unsigned nframes, nviews, capture_count;
    uint64_t capture_tick;
    bool submitted, sealed, metadata_pending, metadata_scene;
    unsigned metadata_count;
    uint64_t metadata_tick;
    uint32_t hdr_counts[SVP_VK_PHASES];
    bool tracked[SVP_VK_PHASES],affine[SVP_VK_PHASES],elliptic[SVP_VK_PHASES];
    float phases[SVP_VK_PHASES];
    struct mp_image *fallback;
};
struct svp_vk_output_batch {
    struct mp_image *outputs[SVP_VK_PHASES];
    struct svp_vk_job *metadata_job;
    unsigned next,count;
};
struct svp_vk_mpv {
    AmbientSvpVk *engine;
    AmbientSvpVk *(*create)(VkInstance,VkPhysicalDevice,VkDevice,PFN_vkGetInstanceProcAddr,uint32_t,uint32_t);
    AmbientSvpVk *(*create_format)(VkInstance,VkPhysicalDevice,VkDevice,PFN_vkGetInstanceProcAddr,uint32_t,uint32_t,uint32_t);
    void (*destroy)(AmbientSvpVk *);
    AmbientSvpVkPair *(*begin)(const AmbientSvpVk *,VkCommandBuffer,const AmbientSvpVkPicture *,const AmbientSvpVkPicture *);
    AmbientSvpVkPair *(*begin_extended)(const AmbientSvpVk *,VkCommandBuffer,const AmbientSvpVkPicture *,const AmbientSvpVkPicture *,const AmbientSvpVkPicture *,const AmbientSvpVkPicture *);
    AmbientSvpVkPair *(*begin_extended_cached)(const AmbientSvpVk *,VkCommandBuffer,const AmbientSvpVkPicture *,const AmbientSvpVkPicture *,const AmbientSvpVkPicture *,const AmbientSvpVkPicture *,VkQueue,const uint64_t (*)[4]);
    int (*render)(AmbientSvpVkPair *,float,uint32_t,VkImageView);
    int (*render_raw)(AmbientSvpVkPair *,float,const VkImageView *,uint32_t);
    int (*hdr_stats)(AmbientSvpVkPair *,uint32_t,const AmbientSvpHdrWindow *,uint32_t);
    int (*hdr_read)(const AmbientSvpVkPair *,uint32_t,AmbientSvpHdrResult *,uint32_t);
    int (*hdr_stats_track)(AmbientSvpVkPair *,uint32_t,const AmbientSvpHdrWindow *,const AmbientSvpHdrTrack *,float,uint32_t);
    int (*hdr_read_track)(const AmbientSvpVkPair *,uint32_t,AmbientSvpHdrResult *,int32_t (*)[2],uint32_t);
    int (*hdr_read_affine)(const AmbientSvpVkPair *,uint32_t,float (*)[4],uint32_t);
    int (*hdr_stats_ellipse)(AmbientSvpVkPair *,uint32_t,const AmbientSvpHdrWindow *,const AmbientSvpHdrTrack *,const uint32_t (*)[3],float,uint32_t);
    int (*hdr_stats_affine)(AmbientSvpVkPair *,uint32_t,const AmbientSvpHdrWindow *,const AmbientSvpHdrTrack *,float,uint32_t);
    int (*hdr_read_ellipses)(const AmbientSvpVkPair *,uint32_t,uint32_t (*)[3],uint32_t);
    int (*hdr_read_rectangles)(const AmbientSvpVkPair *,uint32_t,uint32_t (*)[4],uint32_t);
    AmbientSvpHdrPass *scene_pass;
    unsigned scene_mode,scene_version;
    bool capture_failed,scene_dynamic;
    int (*scene_is_dynamic)(const AmbientSvpHdrPass *);
    int (*scene_enable_dynamic)(AmbientSvpVkPair *);
    int (*scene_capture_group)(AmbientSvpHdrPass *,const AmbientSvpVkPair *,uint32_t,uint64_t,uint64_t);
    int (*scene_stats_legacy)(AmbientSvpVkPair *,uint32_t,const AmbientSvpHdrWindow *,const AmbientSvpHdrTrack *,const uint32_t (*)[3],float,uint32_t);
    int (*scene_verify_geometry)(AmbientSvpHdrPass *,const AmbientSvpVkPair *,uint32_t,uint64_t);
    int (*scene_lookup_legacy)(AmbientSvpHdrPass *,uint64_t,AmbientSvpHdrLegacyScene *);
    int (*scene_stats)(AmbientSvpVkPair *,uint32_t,const AmbientSvpHdrWindow *,uint32_t);
    int (*scene_interval)(const AmbientSvpHdrPass *,uint64_t,uint64_t *);
    int (*scene_capture)(AmbientSvpHdrPass *,const AmbientSvpVkPair *,uint32_t,uint64_t);
    int (*scene_lookup)(AmbientSvpHdrPass *,uint64_t,const uint32_t (*)[9],AmbientSvpHdrSceneResult *);
    int (*scene_finish)(AmbientSvpHdrPass *);
    void (*scene_destroy)(AmbientSvpHdrPass *);
    int (*seal)(AmbientSvpVkPair *,VkFence);
    int (*release)(AmbientSvpVkPair *);
    int (*cancel)(AmbientSvpVkPair *);
    AVBufferRef *device_ref, *output_hwframes;
    AVHWDeviceContext *avdev;
    AVVulkanDeviceContext *vkdev;
    VkDevice device;
    VkQueue queue;
    uint32_t family, width, height, mode;
    VkFormat output_format;
    PFN_vkCreateCommandPool create_pool;
    PFN_vkDestroyCommandPool destroy_pool;
    PFN_vkResetCommandPool reset_pool;
    PFN_vkResetFences reset_fences;
    PFN_vkAllocateCommandBuffers allocate_commands;
    PFN_vkBeginCommandBuffer begin_commands;
    PFN_vkEndCommandBuffer end_commands;
    PFN_vkCreateFence create_fence;
    PFN_vkDestroyFence destroy_fence;
    PFN_vkWaitForFences wait_fences;
    PFN_vkCreateImageView create_view;
    PFN_vkDestroyImageView destroy_view;
    PFN_vkCmdPipelineBarrier2 barrier;
    PFN_vkQueueSubmit submit;
    struct svp_vk_job *jobs[SVP_VK_JOBS];
    struct svp_vk_job *idle_commands[SVP_VK_JOBS];
    unsigned idle_count;
    struct svp_vk_job *metadata_job;
    struct mp_image *previous,*older,*lookahead;
    unsigned algorithm;
    struct mp_image *outputs[SVP_VK_PHASES];
    unsigned next_output, output_count;
    struct svp_vk_output_batch queued[SVP_VK_JOBS-1];
    unsigned queued_count;
    double origin, source_rate, target_rate;
    uint64_t tick, input_frames, output_frames, generated_frames;
    uint64_t previous_id,older_id,lookahead_id;
};
struct svp_vk_ref {
    AVHWFramesContext *fc;
    AVVulkanFramesContext *vfc;
    AVVkFrame *frame;
    bool write;
    unsigned count;
};

static int svp_vk_matrix(struct mp_image *img)
{
    int matrix;
    switch (img->params.repr.sys) {
    case PL_COLOR_SYSTEM_BT_601: matrix=0; break;
    case PL_COLOR_SYSTEM_BT_709: matrix=1; break;
    case PL_COLOR_SYSTEM_BT_2020_NC: matrix=4; break;
    case PL_COLOR_SYSTEM_DOLBYVISION: matrix=0; break; /* raw-only session */
    case PL_COLOR_SYSTEM_RGB: return img->params.repr.levels==PL_COLOR_LEVELS_FULL?3:-1;
    default: return -1;
    }
    switch (img->params.repr.levels) {
    case PL_COLOR_LEVELS_LIMITED: return matrix;
    case PL_COLOR_LEVELS_FULL: return matrix+2;
    default: return -1;
    }
}
static int svp_vk_chroma(struct mp_image *img)
{
    if (img->params.repr.sys==PL_COLOR_SYSTEM_RGB) return 0;
    switch (img->params.chroma_location) {
    case PL_CHROMA_TOP_LEFT: return 0;
    case PL_CHROMA_LEFT: return 1;
    case PL_CHROMA_CENTER: return 2;
    default: return -1;
    }
}
static int svp_vk_mode(enum AVPixelFormat format)
{
    switch (format) {
    case AV_PIX_FMT_RGBA: case AV_PIX_FMT_NV12: case AV_PIX_FMT_YUV420P: return 0;
    case AV_PIX_FMT_P010: return 1;
    case AV_PIX_FMT_YUV420P10: return 2;
    case AV_PIX_FMT_RGBAF16: return 3;
    default: return -1;
    }
}
/* Only a single validated ST 2094-40 window can be passed through
 * MPV's current global libplacebo mapping. Compare the complete decoded
 * payload, not just MaxSCL or the projected OOTF. An unsupported window
 * or metadata transition keeps the original source images unchanged. */
static const AVBufferRef *svp_vk_hdr10plus(const struct mp_image *img)
{
    for (int i=0;i<img->num_ff_side_data;i++)
        if (img->ff_side_data[i].type==AV_FRAME_DATA_DYNAMIC_HDR_PLUS)
            return img->ff_side_data[i].buf;
    return NULL;
}
static bool svp_vk_hdrplus_same(const struct mp_image *a,const struct mp_image *b)
{
    const AVBufferRef *x=svp_vk_hdr10plus(a),*y=svp_vk_hdr10plus(b);
    if (!x || !y) return !x && !y;
    if (x->size!=sizeof(AVDynamicHDRPlus) || y->size!=sizeof(AVDynamicHDRPlus)) return false;
    const AVDynamicHDRPlus *p=(const AVDynamicHDRPlus *)x->data;
    const AVDynamicHDRPlus *q=(const AVDynamicHDRPlus *)y->data;
    if (!p || !q || p->num_windows!=1 || q->num_windows!=1 ||
        p->targeted_system_display_actual_peak_luminance_flag ||
        q->targeted_system_display_actual_peak_luminance_flag ||
        p->mastering_display_actual_peak_luminance_flag ||
        q->mastering_display_actual_peak_luminance_flag) return false;
    // Decoded structs may contain padding: byte equality is a conservative
    // sufficient condition. A false mismatch merely repeats source frames.
    return !memcmp(x->data,y->data,x->size);
}
/* Temporal estimates for a synthesized image, not measured image statistics.
 * Interpolate only like-for-like window topology and quantize back to the
 * ST 2094-40 field units. No invented windows or percentile indices. */
static bool svp_hdr_lerp_q(AVRational *out,AVRational a,AVRational b,double t,int unit,double limit)
{
    if (a.den<=0 || b.den<=0 || a.num<0 || b.num<0) return false;
    double x=(double)a.num/a.den,y=(double)b.num/b.den;
    if (x>limit || y>limit) return false;
    double value=round((x+(y-x)*t)*unit);
    if (!isfinite(value) || value<0 || value>INT32_MAX) return false;
    *out=(AVRational){(int)value,unit};return true;
}
static bool svp_hdr_window_lerp(AVHDRPlusColorTransformParams *o,
    const AVHDRPlusColorTransformParams *a,const AVHDRPlusColorTransformParams *b,double t,bool local,int width,int height)
{
    if (a->num_distribution_maxrgb_percentiles!=b->num_distribution_maxrgb_percentiles ||
        a->num_distribution_maxrgb_percentiles>15 || a->tone_mapping_flag!=b->tone_mapping_flag ||
        a->tone_mapping_flag>1 || a->num_bezier_curve_anchors!=b->num_bezier_curve_anchors ||
        a->num_bezier_curve_anchors>15 || a->color_saturation_mapping_flag!=b->color_saturation_mapping_flag ||
        a->color_saturation_mapping_flag>1) return false;
#define Q(field,unit,limit) if(!svp_hdr_lerp_q(&o->field,a->field,b->field,t,unit,limit))return false
    if (local) {
        if (a->overlap_process_option!=b->overlap_process_option || a->overlap_process_option>1 ||
            a->rotation_angle>180 || b->rotation_angle>180) return false;
        if(width<2||height<2)return false;
        // Ellipse orientation is axial (period 180), not directional.
        int delta=(int)b->rotation_angle-(int)a->rotation_angle;
        if(delta>90)delta-=180;
        if(delta< -90)delta+=180;
        o->rotation_angle=(uint8_t)(((int)lround(a->rotation_angle+t*delta)+180)%180);
        Q(window_upper_left_corner_x,width-1,1);Q(window_upper_left_corner_y,height-1,1);
        Q(window_lower_right_corner_x,width-1,1);Q(window_lower_right_corner_y,height-1,1);
        if (o->window_upper_left_corner_x.num>=o->window_lower_right_corner_x.num ||
            o->window_upper_left_corner_y.num>=o->window_lower_right_corner_y.num) return false;
#define U(field) o->field=(uint16_t)round(a->field+(b->field-(double)a->field)*t)
        U(center_of_ellipse_x);U(center_of_ellipse_y);
        U(semimajor_axis_internal_ellipse);U(semimajor_axis_external_ellipse);U(semiminor_axis_external_ellipse);
#undef U
        if (o->center_of_ellipse_x>=width || o->center_of_ellipse_y>=height ||
            !o->semimajor_axis_internal_ellipse || !o->semiminor_axis_external_ellipse ||
            o->semimajor_axis_external_ellipse<o->semimajor_axis_internal_ellipse ||
            o->semimajor_axis_external_ellipse<o->semiminor_axis_external_ellipse) return false;
    }
    for (unsigned i=0;i<3;i++){Q(maxscl[i],100000,1);}
    Q(average_maxrgb,100000,1);Q(fraction_bright_pixels,1000,1);
    for (unsigned i=0;i<a->num_distribution_maxrgb_percentiles;i++){
        if (a->distribution_maxrgb[i].percentage!=b->distribution_maxrgb[i].percentage ||
            a->distribution_maxrgb[i].percentage>100 ||
            (i && a->distribution_maxrgb[i].percentage<=a->distribution_maxrgb[i-1].percentage)) return false;
        Q(distribution_maxrgb[i].percentile,100000,1);
    }
    if (a->tone_mapping_flag){
        Q(knee_point_x,4095,1);Q(knee_point_y,4095,1);
        uint16_t anchors[15]={0};
        for(unsigned i=0;i<a->num_bezier_curve_anchors;i++){
            Q(bezier_curve_anchors[i],1023,1);
            anchors[i]=(uint16_t)o->bezier_curve_anchors[i].num;
        }
        if(!svp_hdr_anchors_monotone(anchors,o->num_bezier_curve_anchors))return false;
    }
    if(a->color_saturation_mapping_flag){Q(color_saturation_weight,8,7.875);}
#undef Q
    return true;
}
static bool svp_hdrplus_lerp(AVDynamicHDRPlus *out,const AVBufferRef *x,const AVBufferRef *y,double t,int width,int height)
{
    if(!out||!x||!y||!x->data||!y->data||x->size!=sizeof(*out)||y->size!=sizeof(*out)||
        !isfinite(t)||t<0||t>1)return false;
    const AVDynamicHDRPlus *a=(const AVDynamicHDRPlus *)x->data,*b=(const AVDynamicHDRPlus *)y->data;
    if(a->num_windows<1||a->num_windows>3||a->num_windows!=b->num_windows||
        a->itu_t_t35_country_code!=0xb5||b->itu_t_t35_country_code!=0xb5||
        a->application_version>1||a->application_version!=b->application_version||
        a->targeted_system_display_actual_peak_luminance_flag!=b->targeted_system_display_actual_peak_luminance_flag||
        a->mastering_display_actual_peak_luminance_flag!=b->mastering_display_actual_peak_luminance_flag)return false;
    // ST 2094-40:2020 section 9.4 prohibits local windows and
    // display grids for application version 1. Do not silently downgrade.
    if(a->application_version==1 &&
       (a->num_windows!=1 || a->targeted_system_display_actual_peak_luminance_flag ||
        a->mastering_display_actual_peak_luminance_flag ||
        a->params[0].color_saturation_mapping_flag || b->params[0].color_saturation_mapping_flag ||
        a->params[0].num_distribution_maxrgb_percentiles!=9 ||
        b->params[0].num_distribution_maxrgb_percentiles!=9 ||
        a->params[0].num_bezier_curve_anchors>9 || b->params[0].num_bezier_curve_anchors>9))
        return false;
    *out=*a;
    if(!svp_hdr_lerp_q(&out->targeted_system_display_maximum_luminance,
        a->targeted_system_display_maximum_luminance,b->targeted_system_display_maximum_luminance,t,1,10000))return false;
    for(unsigned i=0;i<a->num_windows;i++)if(!svp_hdr_window_lerp(&out->params[i],&a->params[i],&b->params[i],t,i!=0,width,height))return false;
#define GRID(prefix) do{ \
    if(a->prefix##_flag){ \
        unsigned rows=a->num_rows_##prefix,cols=a->num_cols_##prefix; \
        if(a->prefix##_flag!=1||rows<2||rows>25||cols<2||cols>25|| \
            rows!=b->num_rows_##prefix||cols!=b->num_cols_##prefix)return false; \
        for(unsigned row=0;row<rows;row++)for(unsigned col=0;col<cols;col++) \
            if(!svp_hdr_lerp_q(&out->prefix[row][col],a->prefix[row][col],b->prefix[row][col],t,15,1))return false; \
    } \
}while(0)
    GRID(targeted_system_display_actual_peak_luminance);
    GRID(mastering_display_actual_peak_luminance);
#undef GRID
    return true;
}
static const AVBufferRef *svp_vk_side_data(const struct mp_image *img,int type)
{
    for (int i=0;i<img->num_ff_side_data;i++)
        if (img->ff_side_data[i].type==type) return img->ff_side_data[i].buf;
    return NULL;
}
static bool svp_vk_buffer_equal(const AVBufferRef *a,const AVBufferRef *b)
{
    if (!a || !b) return !a && !b;
    return a->data && b->data && a->size==b->size && a->size<=1024*1024 &&
        !memcmp(a->data,b->data,a->size);
}
static bool svp_vk_dovi_valid(const struct mp_image *img)
{
    // The pinned MPV/libplacebo has already mapped the FFmpeg RPU to
    // its renderer-owned Dolby representation. Never use a BL-only
    // approximation when residual reconstruction is required.
    const AVBufferRef *buf=svp_vk_side_data(img,AV_FRAME_DATA_DOVI_METADATA);
    if (!img->dovi || img->params.repr.sys!=PL_COLOR_SYSTEM_DOLBYVISION ||
        img->enhancement_layer || !buf || !buf->data || buf->size<sizeof(AVDOVIMetadata) ||
        buf->size>1024*1024) return false;
    const AVDOVIMetadata *m=(const AVDOVIMetadata *)buf->data;
    if (m->header_offset<sizeof(*m) || m->header_offset>buf->size ||
        sizeof(AVDOVIRpuDataHeader)>buf->size-m->header_offset ||
        m->mapping_offset<sizeof(*m) || m->mapping_offset>buf->size ||
        sizeof(AVDOVIDataMapping)>buf->size-m->mapping_offset ||
        m->color_offset<sizeof(*m) || m->color_offset>buf->size ||
        sizeof(AVDOVIColorMetadata)>buf->size-m->color_offset ||
        m->header_offset%_Alignof(AVDOVIRpuDataHeader) ||
        m->mapping_offset%_Alignof(AVDOVIDataMapping) ||
        m->color_offset%_Alignof(AVDOVIColorMetadata) ||
        m->num_ext_blocks<0 || m->num_ext_blocks>AV_DOVI_MAX_EXT_BLOCKS) return false;
    if (m->num_ext_blocks && (m->ext_block_offset<sizeof(*m) || m->ext_block_offset>buf->size ||
        m->ext_block_size<sizeof(AVDOVIDmData) ||
        (size_t)m->num_ext_blocks>(buf->size-m->ext_block_offset)/m->ext_block_size)) return false;
    const AVDOVIRpuDataHeader *h=av_dovi_get_header(m);
    const AVDOVIDataMapping *map=av_dovi_get_mapping(m);
    return h->bl_bit_depth==10 && h->disable_residual_flag==1 &&
        map->nlq_method_idc==AV_DOVI_NLQ_NONE;
}
static int svp_vk_image_mode(const struct mp_image *img,enum AVPixelFormat format)
{
    int mode=svp_vk_mode(format);
    if (img->params.repr.sys==PL_COLOR_SYSTEM_DOLBYVISION)
        return svp_vk_dovi_valid(img) && (mode==1 || mode==2)?mode+3:-1;
    // RPU metadata without the renderer's mapped representation is not
    // a supported raw Dolby input. Preserve the normal renderer path.
    if (img->dovi || svp_vk_side_data(img,AV_FRAME_DATA_DOVI_METADATA) ||
        svp_vk_side_data(img,AV_FRAME_DATA_DOVI_RPU_BUFFER)) return -1;
    return mode;
}
static bool svp_vk_hdr_metadata_same(const struct mp_image *a,const struct mp_image *b)
{
    return pl_hdr_metadata_equal(&a->params.color.hdr,&b->params.color.hdr) &&
        svp_vk_hdrplus_same(a,b) && svp_vk_buffer_equal(a->dovi,b->dovi) &&
        svp_vk_buffer_equal(svp_vk_side_data(a,AV_FRAME_DATA_DOVI_METADATA),svp_vk_side_data(b,AV_FRAME_DATA_DOVI_METADATA)) &&
        svp_vk_buffer_equal(svp_vk_side_data(a,AV_FRAME_DATA_DOVI_RPU_BUFFER),svp_vk_side_data(b,AV_FRAME_DATA_DOVI_RPU_BUFFER));
}
/* The static display contract must remain constant even when dynamic
 * tone curves change. Full metadata is retained on every output; the
 * pinned MPV libplacebo API additionally maps window zero for rendering. */
static bool svp_vk_hdrplus_pair(const struct mp_image *a,const struct mp_image *b)
{
    if(a->params.color.transfer!=PL_COLOR_TRC_PQ || b->params.color.transfer!=PL_COLOR_TRC_PQ ||
       a->dovi||b->dovi)return false;
    struct pl_hdr_metadata x=a->params.color.hdr,y=b->params.color.hdr;
    memset(x.scene_max,0,sizeof(x.scene_max));memset(y.scene_max,0,sizeof(y.scene_max));
    x.scene_avg=y.scene_avg=x.max_pq_y=y.max_pq_y=x.avg_pq_y=y.avg_pq_y=0;
    memset(&x.ootf,0,sizeof(x.ootf));memset(&y.ootf,0,sizeof(y.ootf));
    AVDynamicHDRPlus scratch;
    return pl_hdr_metadata_equal(&x,&y) &&
        svp_hdrplus_lerp(&scratch,svp_vk_hdr10plus(a),svp_vk_hdr10plus(b),0.5,a->w,a->h);
}
static bool svp_vk_hdrplus_apply(struct mp_image *out,const struct mp_image *a,const struct mp_image *b,float phase)
{
    AVDynamicHDRPlus metadata;
    if(!svp_hdrplus_lerp(&metadata,svp_vk_hdr10plus(a),svp_vk_hdr10plus(b),phase,a->w,a->h))return false;
    for(int i=0;i<out->num_ff_side_data;i++){
        if(out->ff_side_data[i].type!=AV_FRAME_DATA_DYNAMIC_HDR_PLUS)continue;
        AVBufferRef *ref=av_buffer_alloc(sizeof(metadata));
        if(!ref)return false;
        memcpy(ref->data,&metadata,sizeof(metadata));
        av_buffer_unref(&out->ff_side_data[i].buf);
        out->ff_side_data[i].buf=ref;
        struct pl_hdr_metadata *hdr=&out->params.color.hdr;
        memset(hdr->scene_max,0,sizeof(hdr->scene_max));hdr->scene_avg=hdr->max_pq_y=hdr->avg_pq_y=0;
        memset(&hdr->ootf,0,sizeof(hdr->ootf));
        pl_map_hdr_metadata(hdr,&(struct pl_av_hdr_metadata){.dhp=&metadata});
        return true;
    }
    return false;
}
static bool svp_vk_hdrplus_windows(const struct mp_image *img,AmbientSvpHdrWindow windows[3],uint32_t *count)
{
    const AVBufferRef *ref=svp_vk_hdr10plus(img);
    if(!ref||!ref->data||ref->size!=sizeof(AVDynamicHDRPlus)||img->w<2||img->h<2)return false;
    const AVDynamicHDRPlus *m=(const AVDynamicHDRPlus *)ref->data;
    if(m->num_windows<1||m->num_windows>3)return false;
    *count=m->num_windows;
    memset(windows,0,3*sizeof(*windows));
    for(unsigned i=0;i<*count;i++){
        const AVHDRPlusColorTransformParams *p=&m->params[i];
        AmbientSvpHdrWindow *w=&windows[i];
        w->rect[2]=img->w;w->rect[3]=img->h;
        if(i){
            const AVRational q[]={p->window_upper_left_corner_x,p->window_upper_left_corner_y,
                p->window_lower_right_corner_x,p->window_lower_right_corner_y};
            for(unsigned k=0;k<4;k++){
                if(q[k].den<=0||q[k].num<0||q[k].num>q[k].den)return false;
                w->rect[k]=(uint32_t)llround((double)q[k].num*(k%2?img->h-1:img->w-1)/q[k].den)+(k>=2);
            }
            if(w->rect[0]>=w->rect[2]||w->rect[1]>=w->rect[3])return false;
        }
        w->count=p->num_distribution_maxrgb_percentiles;
        if(w->count>15)return false;
        for(unsigned j=0;j<w->count;j++)w->percentages[j]=p->distribution_maxrgb[j].percentage;
    }
    return true;
}
static bool svp_vk_hdrplus_tracks(const struct mp_image *a,const struct mp_image *b,
    const struct mp_image *out,AmbientSvpHdrTrack tracks[3],uint32_t count)
{
    AmbientSvpHdrWindow aw[3],bw[3];uint32_t ac,bc;
    if(!svp_vk_hdrplus_windows(a,aw,&ac)||!svp_vk_hdrplus_windows(b,bw,&bc)||ac!=count||bc!=count)return false;
    const AVBufferRef *ref=svp_vk_hdr10plus(out);
    if(!ref||ref->size!=sizeof(AVDynamicHDRPlus))return false;
    const AVDynamicHDRPlus *m=(const AVDynamicHDRPlus *)ref->data;
    memset(tracks,0,3*sizeof(*tracks));
    for(unsigned w=1;w<count;w++){
        memcpy(tracks[w].a,aw[w].rect,sizeof(aw[w].rect));
        memcpy(tracks[w].b,bw[w].rect,sizeof(bw[w].rect));
        tracks[w].ellipse[0]=m->params[w].center_of_ellipse_x;
        tracks[w].ellipse[1]=m->params[w].center_of_ellipse_y;
    }
    return true;
}
static bool svp_vk_hdrplus_shift(AVHDRPlusColorTransformParams *p,const int32_t shift[2],int width,int height)
{
    if(!shift||(!shift[0]&&!shift[1]))return true;
    if(width<2||height<2||abs(shift[0])>16||abs(shift[1])>16)return false;
    AVRational *q[]={&p->window_upper_left_corner_x,&p->window_upper_left_corner_y,
        &p->window_lower_right_corner_x,&p->window_lower_right_corner_y};
    for(unsigned k=0;k<4;k++){
        int extent=(k%2?height:width)-1;
        if(q[k]->den<=0||q[k]->num<0||q[k]->num>q[k]->den)return false;
        int n=(int)llround((double)q[k]->num*extent/q[k]->den)+shift[k%2];
        if(n<0||n>extent)return false;
        *q[k]=(AVRational){n,extent};
    }
    int x=(int)p->center_of_ellipse_x+shift[0],y=(int)p->center_of_ellipse_y+shift[1];
    if(x<0||x>=width||y<0||y>=height)return false;
    p->center_of_ellipse_x=x;p->center_of_ellipse_y=y;
    return true;
}
/* Transform ellipse covariance with a bounded phase-weighted affine
 * correction. The optional affine statistics ABI supplies the bounding rectangle.
 * The authored ellipse is retained if quantization cannot represent it. */
static void svp_vk_hdrplus_affine(AVHDRPlusColorTransformParams *p,const float g[4],float phase)
{
    double t=4.0*phase*(1.0-phase)*0.5;
    for(unsigned i=0;i<4;i++)if(!isfinite(g[i])||fabs(g[i])>0.5)return;
    if(t<=0||(!g[0]&&!g[1]&&!g[2]&&!g[3]))return;
    double a=1+t*g[0],b=t*g[2],c=t*g[1],d=1+t*g[3];
    double angle=p->rotation_angle*3.14159265358979323846/180.0;
    double u=p->semimajor_axis_external_ellipse,v=p->semiminor_axis_external_ellipse;
    double x=u*(a*cos(angle)+b*sin(angle)),y=u*(c*cos(angle)+d*sin(angle));
    double z=v*(-a*sin(angle)+b*cos(angle)),w=v*(-c*sin(angle)+d*cos(angle));
    double xx=x*x+z*z,yy=y*y+w*w,xy=x*y+z*w;
    double disc=hypot(xx-yy,2*xy);
    double major=sqrt(fmax(0,(xx+yy+disc)*0.5)),minor=sqrt(fmax(0,(xx+yy-disc)*0.5));
    if(!isfinite(major)||!isfinite(minor)||major>65535||minor<1||u<1)return;
    long m=lround(major),n=lround(minor),inner=lround(p->semimajor_axis_internal_ellipse*major/u);
    if(inner<1||inner>m||n<1||n>m)return;
    long rotation=disc>1e-8?lround(atan2(2*xy,xx-yy)*90.0/3.14159265358979323846):p->rotation_angle;
    p->rotation_angle=(uint8_t)((rotation+180)%180);
    p->semimajor_axis_external_ellipse=(uint16_t)m;
    p->semiminor_axis_external_ellipse=(uint16_t)n;
    p->semimajor_axis_internal_ellipse=(uint16_t)inner;
}
static bool svp_vk_scene_metadata(struct mp_image *img,const AmbientSvpHdrSceneResult *r)
{
    if(!r)return false;
    for(int i=0;i<img->num_ff_side_data;i++){
        if(img->ff_side_data[i].type!=AV_FRAME_DATA_DYNAMIC_HDR_PLUS)continue;
        AVBufferRef *ref=img->ff_side_data[i].buf;
        if(!ref||ref->size!=sizeof(AVDynamicHDRPlus)||!av_buffer_is_writable(ref))return false;
        AVDynamicHDRPlus *m=(AVDynamicHDRPlus *)ref->data;
        if(m->application_version!=1||m->num_windows!=1||m->targeted_system_display_actual_peak_luminance_flag||
            m->mastering_display_actual_peak_luminance_flag||m->params[0].color_saturation_mapping_flag)return false;
        AVHDRPlusColorTransformParams *p=&m->params[0];
        if(p->num_distribution_maxrgb_percentiles!=9)return false;
        uint32_t schema[9];for(unsigned j=0;j<9;j++)schema[j]=p->distribution_maxrgb[j].percentage;
        if(!svp_hdr_scene_values_schema(r->maxscl,r->average,r->percentiles,r->fraction,schema))return false;
        uint32_t peak=0;
        for(unsigned j=0;j<3;j++){p->maxscl[j]=(AVRational){r->maxscl[j],100000};if(r->maxscl[j]>peak)peak=r->maxscl[j];}
        p->average_maxrgb=(AVRational){r->average,100000};
        p->fraction_bright_pixels=(AVRational){r->fraction,1000};
        for(unsigned j=0;j<9;j++)p->distribution_maxrgb[j].percentile=(AVRational){r->percentiles[j],100000};
        SvpHdrCurve curve={0};
        AVRational target=m->targeted_system_display_maximum_luminance;
        if(target.den<=0||target.num<0||(!target.num&&p->tone_mapping_flag)||
            (target.num&&!svp_hdr_curve(peak,r->average,r->percentiles[8],(double)target.num/target.den,&curve)))return false;
        p->tone_mapping_flag=curve.count!=0;p->num_bezier_curve_anchors=curve.count;
        p->knee_point_x=(AVRational){curve.knee_x,4095};p->knee_point_y=(AVRational){curve.knee_y,4095};
        memset(p->bezier_curve_anchors,0,sizeof(p->bezier_curve_anchors));
        for(unsigned j=0;j<curve.count;j++)p->bezier_curve_anchors[j]=(AVRational){curve.anchors[j],1023};
        struct pl_hdr_metadata *hdr=&img->params.color.hdr;
        memset(hdr->scene_max,0,sizeof(hdr->scene_max));hdr->scene_avg=hdr->max_pq_y=hdr->avg_pq_y=0;
        memset(&hdr->ootf,0,sizeof(hdr->ootf));
        pl_map_hdr_metadata(hdr,&(struct pl_av_hdr_metadata){.dhp=m});
        img->ambient_svp_hdr_generated=true;return true;
    }
    return false;
}
static bool svp_vk_scene_compatible(const struct mp_image *img)
{
    const AVBufferRef *ref=svp_vk_hdr10plus(img);
    if(!ref||!ref->data||ref->size!=sizeof(AVDynamicHDRPlus)||img->dovi||
        img->params.color.transfer!=PL_COLOR_TRC_PQ||img->params.color.primaries!=PL_COLOR_PRIM_BT_2020)return false;
    const AVDynamicHDRPlus *m=(const AVDynamicHDRPlus *)ref->data;
    AVDynamicHDRPlus checked;
    if(!svp_hdrplus_lerp(&checked,ref,ref,0,img->w,img->h))return false;
    if(m->application_version==0)return true;
    if(m->application_version!=1||m->num_windows!=1||m->params[0].num_distribution_maxrgb_percentiles!=9)return false;
    for(unsigned j=0;j<9;j++)if(m->params[0].distribution_maxrgb[j].percentage>100||
        (j&&m->params[0].distribution_maxrgb[j].percentage<=m->params[0].distribution_maxrgb[j-1].percentage))return false;
    return true;
}
static bool svp_vk_scene_version(const struct mp_image *img,unsigned version)
{
    if(!svp_vk_scene_compatible(img))return false;
    return ((const AVDynamicHDRPlus *)svp_vk_hdr10plus(img)->data)->application_version==version;
}
static bool svp_vk_scene_legacy_metadata(struct mp_image *img,const AmbientSvpHdrLegacyScene *r)
{
    if(!r||!svp_vk_scene_version(img,0))return false;
    for(int i=0;i<img->num_ff_side_data;i++){
        if(img->ff_side_data[i].type!=AV_FRAME_DATA_DYNAMIC_HDR_PLUS)continue;
        AVBufferRef *ref=img->ff_side_data[i].buf;
        if(!av_buffer_is_writable(ref))return false;
        AVDynamicHDRPlus *m=(AVDynamicHDRPlus *)ref->data;
        if(r->count!=m->num_windows||r->count<1||r->count>3)return false;
        AVDynamicHDRPlus updated=*m;
        for(unsigned w=0;w<r->count;w++){
            AVHDRPlusColorTransformParams *p=&updated.params[w];
            const AmbientSvpHdrLegacyWindow *v=&r->windows[w];
            if(v->count>15||v->count!=p->num_distribution_maxrgb_percentiles||v->fraction)return false;
            uint32_t peak=0,last=0;
            for(unsigned c=0;c<3;c++){
                if(v->maxscl[c]>100000)return false;
                if(v->maxscl[c]>peak)peak=v->maxscl[c];
                p->maxscl[c]=(AVRational){v->maxscl[c],100000};
            }
            if(v->average>peak)return false;
            p->average_maxrgb=(AVRational){v->average,100000};p->fraction_bright_pixels=(AVRational){0,1000};
            for(unsigned j=0;j<v->count;j++){
                if(v->percentages[j]!=p->distribution_maxrgb[j].percentage||v->percentiles[j]<last||v->percentiles[j]>peak)return false;
                last=v->percentiles[j];p->distribution_maxrgb[j].percentile=(AVRational){last,100000};
            }
            // Keep authored optional grids and saturation, but avoid
            // publishing a curve derived from incomplete old statistics.
            SvpHdrCurve curve={0};AVRational target=updated.targeted_system_display_maximum_luminance;
            if(target.den<=0||target.num<0||(target.num&&!svp_hdr_curve(peak,v->average,v->count?last:peak,(double)target.num/target.den,&curve)))return false;
            p->tone_mapping_flag=curve.count!=0;p->num_bezier_curve_anchors=curve.count;
            p->knee_point_x=(AVRational){curve.knee_x,4095};p->knee_point_y=(AVRational){curve.knee_y,4095};
            memset(p->bezier_curve_anchors,0,sizeof(p->bezier_curve_anchors));
            for(unsigned j=0;j<curve.count;j++)p->bezier_curve_anchors[j]=(AVRational){curve.anchors[j],1023};
        }
        *m=updated;
        struct pl_hdr_metadata *hdr=&img->params.color.hdr;
        memset(hdr->scene_max,0,sizeof(hdr->scene_max));hdr->scene_avg=hdr->max_pq_y=hdr->avg_pq_y=0;
        memset(&hdr->ootf,0,sizeof(hdr->ootf));
        pl_map_hdr_metadata(hdr,&(struct pl_av_hdr_metadata){.dhp=m});
        img->ambient_svp_hdr_generated=true;return true;
    }
    return false;
}
// Update only the geometry used by the measured pixel selector; cached
// scene statistics are applied separately and must not be overwritten.
static bool svp_vk_scene_geometry(struct mp_image *img,const AmbientSvpHdrResult results[3],
    const int32_t (*shifts)[2],const uint32_t (*rectangles)[4],const uint32_t (*ellipses)[3],uint32_t count)
{
    const AVBufferRef *ref=svp_vk_hdr10plus(img);
    if(!ref||!ref->data||ref->size!=sizeof(AVDynamicHDRPlus)||!av_buffer_is_writable(ref))return false;
    AVDynamicHDRPlus *m=(AVDynamicHDRPlus *)ref->data,updated=*m;
    if(count!=m->num_windows||count<1||count>3)return false;
    for(unsigned w=1;w<count;w++){
        const AmbientSvpHdrResult *r=&results[w];
        AVHDRPlusColorTransformParams *p=&updated.params[w];
        const bool elliptic=true;
            if(shifts&&!svp_vk_hdrplus_shift(p,shifts[w],img->w,img->h))return false;
            if(rectangles){
                const uint32_t *box=rectangles[w];
                if(img->w<2||img->h<2||box[0]+1>=box[2]||box[1]+1>=box[3]||
                    box[2]>(uint32_t)img->w||box[3]>(uint32_t)img->h||
                    (uint64_t)(box[2]-box[0])*(box[3]-box[1])<r->pixels||
                    (!elliptic&&(uint64_t)(box[2]-box[0])*(box[3]-box[1])!=r->pixels))return false;
                p->window_upper_left_corner_x=(AVRational){box[0],img->w-1};
                p->window_upper_left_corner_y=(AVRational){box[1],img->h-1};
                p->window_lower_right_corner_x=(AVRational){box[2]-1,img->w-1};
                p->window_lower_right_corner_y=(AVRational){box[3]-1,img->h-1};
            }
            if(ellipses){
                const uint32_t *e=ellipses[w];
                if(!e[0]||e[0]>65535||!e[1]||e[1]>e[0]||e[2]>180||!p->semimajor_axis_external_ellipse)return false;
                uint32_t inner=(uint32_t)lround((double)p->semimajor_axis_internal_ellipse*e[0]/p->semimajor_axis_external_ellipse);
                if(!inner||inner>e[0])return false;
                p->semimajor_axis_internal_ellipse=inner;
                p->semimajor_axis_external_ellipse=e[0];
                p->semiminor_axis_external_ellipse=e[1];
                p->rotation_angle=e[2];
            }

    }
    *m=updated;return true;
}
static bool svp_vk_hdrplus_measured(struct mp_image *img,const AmbientSvpHdrResult results[3],
    const int32_t (*shifts)[2],const float (*gradients)[4],const uint32_t (*rectangles)[4],const uint32_t (*ellipses)[3],bool elliptic,float phase,uint32_t count)
{
    for(int i=0;i<img->num_ff_side_data;i++){
        if(img->ff_side_data[i].type!=AV_FRAME_DATA_DYNAMIC_HDR_PLUS)continue;
        AVBufferRef *ref=img->ff_side_data[i].buf;
        if(!ref||!ref->data||ref->size!=sizeof(AVDynamicHDRPlus)||!av_buffer_is_writable(ref))return false;
        AVDynamicHDRPlus *m=(AVDynamicHDRPlus *)ref->data;
        if(count!=m->num_windows||count<1||count>3)return false;
        for(unsigned w=0;w<count;w++){
            const AmbientSvpHdrResult *r=&results[w];
            AVHDRPlusColorTransformParams *p=&m->params[w];
            if(w&&shifts&&!svp_vk_hdrplus_shift(p,shifts[w],img->w,img->h))return false;
            if(w&&rectangles){
                const uint32_t *box=rectangles[w];
                if(img->w<2||img->h<2||box[0]+1>=box[2]||box[1]+1>=box[3]||
                    box[2]>(uint32_t)img->w||box[3]>(uint32_t)img->h||
                    (uint64_t)(box[2]-box[0])*(box[3]-box[1])<r->pixels||
                    (!elliptic&&(uint64_t)(box[2]-box[0])*(box[3]-box[1])!=r->pixels))return false;
                p->window_upper_left_corner_x=(AVRational){box[0],img->w-1};
                p->window_upper_left_corner_y=(AVRational){box[1],img->h-1};
                p->window_lower_right_corner_x=(AVRational){box[2]-1,img->w-1};
                p->window_lower_right_corner_y=(AVRational){box[3]-1,img->h-1};
            }
            if(w&&ellipses){
                const uint32_t *e=ellipses[w];
                if(!e[0]||e[0]>65535||!e[1]||e[1]>e[0]||e[2]>180||!p->semimajor_axis_external_ellipse)return false;
                uint32_t inner=(uint32_t)lround((double)p->semimajor_axis_internal_ellipse*e[0]/p->semimajor_axis_external_ellipse);
                if(!inner||inner>e[0])return false;
                p->semimajor_axis_internal_ellipse=inner;
                p->semimajor_axis_external_ellipse=e[0];
                p->semiminor_axis_external_ellipse=e[1];
                p->rotation_angle=e[2];
            }else if(w&&gradients)svp_vk_hdrplus_affine(p,gradients[w],phase);
            if(!r->pixels||r->brightest>r->pixels||r->average>100000||p->num_distribution_maxrgb_percentiles>15)return false;
            for(unsigned c=0;c<3;c++){
                if(r->maxscl[c]>100000)return false;
                p->maxscl[c]=(AVRational){r->maxscl[c],100000};
            }
            p->average_maxrgb=(AVRational){r->average,100000};
            uint32_t peak=fmax(r->maxscl[0],fmax(r->maxscl[1],r->maxscl[2]));
            uint32_t highlight=peak;
            bool reserved=m->application_version==1&&p->num_distribution_maxrgb_percentiles==9&&
                p->distribution_maxrgb[1].percentage==5&&p->distribution_maxrgb[2].percentage==10;
            for(unsigned j=0;j<p->num_distribution_maxrgb_percentiles;j++){
                if(r->percentiles[j]>peak||(j&&r->percentiles[j]<r->percentiles[j-1]))return false;
                if(reserved&&(j==1||j==2)){
                    p->distribution_maxrgb[j].percentile=(AVRational){j==1?0:255,100000};continue;
                }
                p->distribution_maxrgb[j].percentile=(AVRational){r->percentiles[j],100000};
                if(p->distribution_maxrgb[j].percentage>=90)highlight=r->percentiles[j];
            }
            // Recompute the knee and shoulder from the actual rendered
            // pixels; target capability remains authored display metadata.
            AVRational target=m->targeted_system_display_maximum_luminance;
            SvpHdrCurve curve;
            if(target.den<=0||!svp_hdr_curve(peak,r->average,highlight,(double)target.num/target.den,&curve))return false;
            p->tone_mapping_flag=curve.count!=0;
            p->num_bezier_curve_anchors=curve.count;
            p->knee_point_x=(AVRational){curve.knee_x,4095};
            p->knee_point_y=(AVRational){curve.knee_y,4095};
            memset(p->bezier_curve_anchors,0,sizeof(p->bezier_curve_anchors));
            for(unsigned j=0;j<curve.count;j++)p->bezier_curve_anchors[j]=(AVRational){curve.anchors[j],1023};
            // Section 10 requires spatial smoothing and scene-wide
            // weighting. Peak-bin ties are not that measurement.
            // Zero explicitly signals an uncalculated value in both versions.
            p->fraction_bright_pixels=(AVRational){0,1000};
        }
        struct pl_hdr_metadata *hdr=&img->params.color.hdr;
        memset(hdr->scene_max,0,sizeof(hdr->scene_max));hdr->scene_avg=hdr->max_pq_y=hdr->avg_pq_y=0;
        memset(&hdr->ootf,0,sizeof(hdr->ootf));
        pl_map_hdr_metadata(hdr,&(struct pl_av_hdr_metadata){.dhp=m});
        img->ambient_svp_hdr_generated=true;
        return true;
    }
    return false;
}
static bool svp_vk_eligible(struct mp_image *img)
{
    if (!img || img->imgfmt!=IMGFMT_VULKAN || !img->hwctx
        || img->w<32 || img->h<32 || (img->w|img->h)&1
        || img->w>3840 || img->h>2160
        || ((uint64_t)img->w*img->h>1920u*1080u && !svp_vk_hdr10plus(img))
        || img->params.crop.x0 || img->params.crop.y0
        || (img->params.crop.x1 && img->params.crop.x1!=img->w)
        || (img->params.crop.y1 && img->params.crop.y1!=img->h)
        || img->fields&MP_IMGFIELD_INTERLACED || img->pts==MP_NOPTS_VALUE
        || !isfinite(img->pts) || img->enhancement_layer || img->film_grain
        || img->params.color.transfer==PL_COLOR_TRC_UNKNOWN
        || svp_vk_matrix(img)<0 || svp_vk_chroma(img)<0)
        return false;
    AVHWFramesContext *fc=(AVHWFramesContext *)img->hwctx->data;
    if (!fc || !fc->device_ctx || !fc->device_ref || !fc->hwctx
        || fc->device_ctx->type!=AV_HWDEVICE_TYPE_VULKAN
        || fc->format!=AV_PIX_FMT_VULKAN
        || svp_vk_image_mode(img,fc->sw_format)<0
        || fc->width<img->w || fc->height<img->h)
        return false;
    if ((fc->sw_format==AV_PIX_FMT_RGBA||fc->sw_format==AV_PIX_FMT_RGBAF16)!=(img->params.repr.sys==PL_COLOR_SYSTEM_RGB)) return false;
    int mode=svp_vk_image_mode(img,fc->sw_format);
    bool hdr=img->params.color.transfer==PL_COLOR_TRC_PQ || img->params.color.transfer==PL_COLOR_TRC_HLG;
    if (svp_vk_hdr10plus(img) && (!mode || mode>=4 || img->params.color.transfer!=PL_COLOR_TRC_PQ)) return false;
    if ((!mode && (hdr || svp_vk_matrix(img)>3)) || (mode<4 && hdr && img->params.repr.sys!=PL_COLOR_SYSTEM_BT_2020_NC && !(mode==3 && img->params.repr.sys==PL_COLOR_SYSTEM_RGB))) return false;
    AVVulkanFramesContext *vfc=fc->hwctx;
    return vfc->nb_layers==1 && vfc->tiling==VK_IMAGE_TILING_OPTIMAL
        && (vfc->usage&VK_IMAGE_USAGE_SAMPLED_BIT)
        && !(vfc->img_flags&VK_IMAGE_CREATE_PROTECTED_BIT)
        && vfc->lock_frame && vfc->unlock_frame;
}

static void svp_vk_job_free(struct svp_vk_mpv *s,struct svp_vk_job *j)
{
    // Only after completion/device loss, or before any successful submit.
    if (j->pool) s->destroy_pool(s->device,j->pool,NULL);
    if (j->pair && !j->submitted) s->cancel(j->pair);
    for (unsigned i=0;i<j->nviews;i++) s->destroy_view(s->device,j->views[i],NULL);
    for (unsigned i=0;i<j->nframes;i++) av_frame_free(&j->frames[i]);
    mp_image_unrefp(&j->fallback);
    if (j->fence) s->destroy_fence(s->device,j->fence,NULL);
    free(j);
}
static void svp_vk_scene_abort(struct svp_vk_mpv *s);
static unsigned svp_vk_reap(struct svp_vk_mpv *s,bool wait)
{
    unsigned outstanding=0;
    if (!s) return 0;
    if (wait) {
        VkFence fences[SVP_VK_JOBS];unsigned count=0;
        for (unsigned i=0;i<SVP_VK_JOBS;i++) if (s->jobs[i]) fences[count++]=s->jobs[i]->fence;
        if (count) s->wait_fences(s->device,count,fences,VK_TRUE,3000000000ULL);
    }
    // Consume capture jobs in output tick order, never slot order.
    while(s->scene_pass&&s->scene_mode==1){
        struct svp_vk_job *first=NULL;
        for(unsigned i=0;i<SVP_VK_JOBS;i++)if(s->jobs[i]&&s->jobs[i]->capture_count&&
            (!first||s->jobs[i]->capture_tick<first->capture_tick))first=s->jobs[i];
        if(!first)break;
        VkResult ready=s->wait_fences(s->device,1,&first->fence,VK_TRUE,0);
        if(ready==VK_TIMEOUT)break;
        bool ok=ready==VK_SUCCESS&&first->sealed;
        for(unsigned i=0;ok&&i<first->capture_count;i++)
            ok=(s->scene_dynamic?s->scene_capture_group(s->scene_pass,first->pair,i,first->capture_tick+i,first->capture_tick+first->capture_count):
                s->scene_capture(s->scene_pass,first->pair,i,first->capture_tick+i))==1;
        first->capture_count=0;
        if(!ok){s->capture_failed=true;svp_vk_scene_abort(s);break;}
    }
    for (unsigned i=0;i<SVP_VK_JOBS;i++) {
        struct svp_vk_job *j=s->jobs[i];
        if (!j) continue;
        if(j->metadata_pending||(s->scene_pass&&j->capture_count)){outstanding++;continue;}
        // A submitted pair must be released by its own fence check.
        if (j->sealed && s->release(j->pair)==1) {
            j->pair=NULL;
            s->jobs[i]=NULL;
            // Never pool after device loss or failed fence status.
            if(s->idle_count<SVP_VK_JOBS&&s->wait_fences(s->device,1,&j->fence,VK_TRUE,0)==VK_SUCCESS){
                for(unsigned k=0;k<j->nviews;k++)s->destroy_view(s->device,j->views[k],NULL);
                for(unsigned k=0;k<j->nframes;k++)av_frame_free(&j->frames[k]);
                mp_image_unrefp(&j->fallback);
                VkCommandPool pool=j->pool;VkCommandBuffer cmd=j->cmd;VkFence fence=j->fence;
                *j=(struct svp_vk_job){.pool=pool,.cmd=cmd,.fence=fence};
                s->idle_commands[s->idle_count++]=j;
            }else svp_vk_job_free(s,j);
        } else outstanding++;
    }
    return outstanding;
}
static void svp_vk_scene_abort(struct svp_vk_mpv *s)
{
    if(s&&s->scene_pass){s->scene_destroy(s->scene_pass);s->scene_pass=NULL;s->scene_mode=0;}
    if(s)for(unsigned i=0;i<SVP_VK_JOBS;i++)if(s->jobs[i])s->jobs[i]->capture_count=0;
}
static bool svp_vk_scene_complete(struct svp_vk_mpv *s)
{
    if(!s||!s->scene_pass)return false;
    svp_vk_reap(s,true);
    bool ok=s->scene_pass&&!s->capture_failed;
    for(unsigned i=0;i<SVP_VK_JOBS;i++)if(s->jobs[i]&&s->jobs[i]->capture_count)ok=false;
    if(ok)ok=s->scene_finish(s->scene_pass)==1;
    svp_vk_scene_abort(s);return ok;
}
static void svp_vk_reset_history(struct svp_vk_mpv *s)
{
    if (!s) return;
    svp_vk_scene_abort(s);
    if(s->metadata_job){s->metadata_job->metadata_pending=false;s->metadata_job=NULL;}
    mp_image_unrefp(&s->previous);mp_image_unrefp(&s->older);mp_image_unrefp(&s->lookahead);
    for (unsigned i=0;i<s->output_count;i++) mp_image_unrefp(&s->outputs[i]);
    s->next_output=s->output_count=0;s->tick=0;
    for(unsigned b=0;b<s->queued_count;b++){
        struct svp_vk_output_batch *q=&s->queued[b];
        if(q->metadata_job)q->metadata_job->metadata_pending=false;
        for(unsigned i=0;i<q->count;i++)mp_image_unrefp(&q->outputs[i]);
    }
    memset(s->queued,0,sizeof(s->queued));s->queued_count=0;
    svp_vk_reap(s,false);
}
static bool svp_vk_destroy_state(struct svp_vk_mpv *s)
{
    if (!s) return true;
    svp_vk_reset_history(s);
    // Never destroy an external device or free in-flight descriptors.
    // On a stuck driver the owner must retain this state and library.
    if (svp_vk_reap(s,true)) return false;
    while(s->idle_count)svp_vk_job_free(s,s->idle_commands[--s->idle_count]);
    if (s->engine) s->destroy(s->engine);
    av_buffer_unref(&s->output_hwframes);
    av_buffer_unref(&s->device_ref);
    free(s);return true;
}
static bool svp_vk_sync_features(const AVVulkanDeviceContext *ctx)
{
    bool timeline=false,sync2=false;
    for (const VkBaseInStructure *p=ctx->device_features.pNext;p;p=p->pNext) {
        switch (p->sType) {
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES:
            timeline|=((const VkPhysicalDeviceVulkan12Features *)p)->timelineSemaphore;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES:
            sync2|=((const VkPhysicalDeviceVulkan13Features *)p)->synchronization2;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES:
            timeline|=((const VkPhysicalDeviceTimelineSemaphoreFeatures *)p)->timelineSemaphore;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES:
            sync2|=((const VkPhysicalDeviceSynchronization2Features *)p)->synchronization2;break;
        default: break;
        }
    }
    return timeline && sync2;
}
static struct svp_vk_mpv *svp_vk_new(void *library,struct mp_image *img,double source,double target,const AmbientSvpQuality *quality)
{
    if (!library || !svp_vk_eligible(img) || !(source>0 && target>source && target/source<=SVP_VK_PHASES))
        return NULL;
    struct svp_vk_mpv *s=calloc(1,sizeof(*s));
    if (!s) return NULL;
#define LOAD_API(name) s->name=dlsym(library,"ambient_svp_vk_" #name); if(!s->name) goto fail
    LOAD_API(create); LOAD_API(destroy); LOAD_API(begin); LOAD_API(render);
    LOAD_API(seal); LOAD_API(release); LOAD_API(cancel);
    s->hdr_stats=dlsym(library,"ambient_svp_vk_hdr_stats");
    s->hdr_read=dlsym(library,"ambient_svp_vk_hdr_read");
    s->hdr_stats_track=dlsym(library,"ambient_svp_vk_hdr_stats_track");
    s->hdr_read_track=dlsym(library,"ambient_svp_vk_hdr_read_track");
    s->hdr_read_affine=dlsym(library,"ambient_svp_vk_hdr_read_affine");
    s->hdr_stats_ellipse=dlsym(library,"ambient_svp_vk_hdr_stats_ellipse");
    s->hdr_stats_affine=dlsym(library,"ambient_svp_vk_hdr_stats_affine");
    s->hdr_read_ellipses=dlsym(library,"ambient_svp_vk_hdr_read_ellipses");
    s->hdr_read_rectangles=dlsym(library,"ambient_svp_vk_hdr_read_rectangles");
#undef LOAD_API
    AVHWFramesContext *fc=(AVHWFramesContext *)img->hwctx->data;
    s->device_ref=av_buffer_ref(fc->device_ref);
    if (!s->device_ref) goto fail;
    s->avdev=fc->device_ctx;
    s->vkdev=s->avdev->hwctx;
    if (!s->vkdev || !s->vkdev->get_proc_addr || !s->vkdev->act_dev
        || (s->vkdev->queue_flags&VK_DEVICE_QUEUE_CREATE_PROTECTED_BIT)
        || !svp_vk_sync_features(s->vkdev)) goto fail;
    s->device=s->vkdev->act_dev;
    bool found=false;
    for (int i=0;i<s->vkdev->nb_qf && i<64;i++) {
        AVVulkanDeviceQueueFamily q=s->vkdev->qf[i];
        if (q.idx>=0 && q.num>0 && (q.flags&VK_QUEUE_COMPUTE_BIT)) {
            s->family=q.idx;found=true;break;
        }
    }
    if (!found) goto fail;
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
    if (!s->vkdev->lock_queue || !s->vkdev->unlock_queue) goto fail;
#pragma GCC diagnostic pop
    PFN_vkGetDeviceProcAddr get=(PFN_vkGetDeviceProcAddr)s->vkdev->get_proc_addr(s->vkdev->inst,"vkGetDeviceProcAddr");
    if (!get) goto fail;
#define LOAD_VK(member,name) s->member=(PFN_vk##name)get(s->device,"vk" #name); if(!s->member) goto fail
    LOAD_VK(create_pool,CreateCommandPool);LOAD_VK(destroy_pool,DestroyCommandPool);
    LOAD_VK(reset_pool,ResetCommandPool);LOAD_VK(reset_fences,ResetFences);
    LOAD_VK(allocate_commands,AllocateCommandBuffers);LOAD_VK(begin_commands,BeginCommandBuffer);
    LOAD_VK(end_commands,EndCommandBuffer);LOAD_VK(create_fence,CreateFence);
    LOAD_VK(destroy_fence,DestroyFence);LOAD_VK(wait_fences,WaitForFences);
    LOAD_VK(create_view,CreateImageView);LOAD_VK(destroy_view,DestroyImageView);
    LOAD_VK(barrier,CmdPipelineBarrier2);LOAD_VK(submit,QueueSubmit);
#undef LOAD_VK
    PFN_vkGetDeviceQueue2 get_queue=(PFN_vkGetDeviceQueue2)get(s->device,"vkGetDeviceQueue2");
    if (!get_queue) goto fail;
    VkDeviceQueueInfo2 qi={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_INFO_2,.flags=s->vkdev->queue_flags,
        .queueFamilyIndex=s->family,.queueIndex=0};
    get_queue(s->device,&qi,&s->queue);
    if (!s->queue) goto fail;
    s->width=img->w;s->height=img->h;s->source_rate=source;s->target_rate=target;
    s->mode=svp_vk_image_mode(img,fc->sw_format);
    if (s->mode>=4) {
        if (!s->vkdev->device_features.features.shaderStorageImageExtendedFormats) goto fail;
        s->render_raw=dlsym(library,"ambient_svp_vk_render_raw");
        if (!s->render_raw) goto fail;
    }
    s->output_format=s->mode?VK_FORMAT_R16G16B16A16_SFLOAT:VK_FORMAT_R8G8B8A8_UNORM;
    if(quality){
        AmbientSvpVk *(*create_quality)(VkInstance,VkPhysicalDevice,VkDevice,PFN_vkGetInstanceProcAddr,
            uint32_t,uint32_t,uint32_t,const AmbientSvpQuality *)=dlsym(library,"ambient_svp_vk_create_quality");
        if(!create_quality)goto fail;
        s->engine=create_quality(s->vkdev->inst,s->vkdev->phys_dev,s->device,s->vkdev->get_proc_addr,
            s->width,s->height,s->mode,quality);
    }else if (s->mode) {
        s->create_format=dlsym(library,"ambient_svp_vk_create_format");
        if (!s->create_format) goto fail;
        s->engine=s->create_format(s->vkdev->inst,s->vkdev->phys_dev,s->device,s->vkdev->get_proc_addr,s->width,s->height,s->mode);
    } else s->engine=s->create(s->vkdev->inst,s->vkdev->phys_dev,s->device,s->vkdev->get_proc_addr,s->width,s->height);
    if (!s->engine) goto fail;
    if(quality){
        s->algorithm=quality->algorithm?quality->algorithm:quality->preset==3?23:13;
        s->begin_extended=dlsym(library,"ambient_svp_vk_begin_extended");
        s->begin_extended_cached=dlsym(library,"ambient_svp_vk_begin_extended_cached");
    }
    s->output_hwframes=av_hwframe_ctx_alloc(s->device_ref);
    if (!s->output_hwframes) goto fail;
    AVHWFramesContext *out=(AVHWFramesContext *)s->output_hwframes->data;
    out->format=AV_PIX_FMT_VULKAN;out->sw_format=s->mode>=4?fc->sw_format:s->mode?AV_PIX_FMT_RGBAF16:AV_PIX_FMT_RGBA;
    out->width=s->width;out->height=s->height;
    AVVulkanFramesContext *vfc=out->hwctx;
    vfc->usage=VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
    vfc->flags=AV_VK_FRAME_FLAG_DISABLE_MULTIPLANE;
    vfc->nb_layers=1;
    if (av_hwframe_ctx_init(s->output_hwframes)<0 || !vfc->lock_frame || !vfc->unlock_frame) goto fail;
    return s;
fail:
    svp_vk_destroy_state(s);return NULL;
}

static bool svp_vk_scene_open(struct svp_vk_mpv *s,void *lib,const struct mp_image *img,
    const AmbientSvpQuality *q,unsigned mode,const char *plan,const char *cache,const char *key)
{
    if(!s||!lib||!img||!q||!mode||!plan||!cache||!key||s->mode<1||s->mode>=4||
       img->params.color.transfer!=PL_COLOR_TRC_PQ||img->params.color.primaries!=PL_COLOR_PRIM_BT_2020)return false;
    if(!svp_vk_scene_compatible(img))return false;
    s->scene_version=((const AVDynamicHDRPlus *)svp_vk_hdr10plus(img)->data)->application_version;
    AmbientSvpHdrPass *(*open)(uint32_t,uint32_t,const char *,const char *,const char *,const char *,uint32_t,uint32_t)=
        dlsym(lib,"ambient_svp_hdr_pass_open_version");
    s->scene_stats_legacy=dlsym(lib,"ambient_svp_vk_hdr_stats_scene_legacy_tracked");
    s->scene_lookup_legacy=dlsym(lib,"ambient_svp_hdr_pass_lookup_legacy");
    s->scene_verify_geometry=dlsym(lib,"ambient_svp_hdr_pass_verify_geometry");
    s->scene_stats=dlsym(lib,"ambient_svp_vk_hdr_stats_scene");
    s->scene_interval=dlsym(lib,"ambient_svp_hdr_pass_interval");
    s->scene_capture=dlsym(lib,"ambient_svp_hdr_pass_capture");
    s->scene_is_dynamic=dlsym(lib,"ambient_svp_hdr_pass_is_dynamic");
    s->scene_enable_dynamic=dlsym(lib,"ambient_svp_vk_enable_dynamic_scene");
    s->scene_capture_group=dlsym(lib,"ambient_svp_hdr_pass_capture_group");
    s->scene_lookup=dlsym(lib,"ambient_svp_hdr_pass_lookup_v1");
    s->scene_finish=dlsym(lib,"ambient_svp_hdr_pass_finish");
    s->scene_destroy=dlsym(lib,"ambient_svp_hdr_pass_destroy");
    if(!open||!s->scene_verify_geometry||!s->scene_stats_legacy||!s->scene_lookup_legacy||!s->scene_stats||!s->scene_interval||!s->scene_capture||!s->scene_lookup||!s->scene_finish||!s->scene_destroy)return false;
    PFN_vkGetPhysicalDeviceProperties props=(PFN_vkGetPhysicalDeviceProperties)s->vkdev->get_proc_addr(s->vkdev->inst,"vkGetPhysicalDeviceProperties");
    if(!props)return false;
    VkPhysicalDeviceProperties device;props(s->vkdev->phys_dev,&device);
    char profile[384];
    int n=snprintf(profile,sizeof(profile),"mpv:1:%u:%u:%u:%u:%lld:%lld:%lld:%u:%u:%u:%u:%u:%u:%u:%u",
        device.vendorID,device.deviceID,device.driverVersion,s->mode,
        (long long)llround(s->source_rate*1000000.0),(long long)llround(s->target_rate*1000000.0),
        (long long)llround(img->pts*1000000.0),q->preset,q->block,q->overlap,q->pel,q->search,q->artifacts,q->scene,q->algorithm);
    if(n<=0||(size_t)n>=sizeof(profile))return false;
    s->scene_pass=open(mode,s->scene_version,plan,cache,key,profile,s->width,s->height);
    s->scene_dynamic=s->scene_pass&&s->scene_is_dynamic&&s->scene_is_dynamic(s->scene_pass)==1;
    if(s->scene_dynamic&&(!s->scene_enable_dynamic||!s->scene_capture_group)){svp_vk_scene_abort(s);return false;}
    s->capture_failed=false;
    s->scene_mode=s->scene_pass?mode:0;return s->scene_pass!=NULL;
}
static bool svp_vk_matches(struct svp_vk_mpv *s,struct mp_image *img)
{
    if (!svp_vk_eligible(img) || img->w!=(int)s->width || img->h!=(int)s->height) return false;
    AVHWFramesContext *fc=(AVHWFramesContext *)img->hwctx->data;
    return fc->device_ctx==s->avdev && svp_vk_image_mode(img,fc->sw_format)==(int)s->mode;
}
static bool svp_vk_ref(AVFrame *frame,bool write,struct svp_vk_ref *out)
{
    if (!frame || !frame->hw_frames_ctx || !frame->data[0]) return false;
    AVHWFramesContext *fc=(AVHWFramesContext *)frame->hw_frames_ctx->data;
    AVVulkanFramesContext *vfc=fc->hwctx;
    AVVkFrame *vkf=(AVVkFrame *)frame->data[0];
    unsigned count=0;
    while(count<AV_NUM_DATA_POINTERS && vkf->img[count]) count++;
    unsigned expected=(fc->sw_format==AV_PIX_FMT_RGBA||fc->sw_format==AV_PIX_FMT_RGBAF16)?1:(fc->sw_format==AV_PIX_FMT_NV12||fc->sw_format==AV_PIX_FMT_P010)?2:3;
    if (!count || (count!=1 && count!=expected) || !vfc->lock_frame || !vfc->unlock_frame)
        return false;
    *out=(struct svp_vk_ref){fc,vfc,vkf,write,count};
    return true;
}
static VkImageView svp_vk_view(struct svp_vk_mpv *s,struct svp_vk_job *j,VkImage img,VkFormat format,VkImageAspectFlags aspect)
{
    if (j->nviews>=SVP_VK_IMAGES) return VK_NULL_HANDLE;
    VkImageView view=VK_NULL_HANDLE;
    VkImageViewCreateInfo info={.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,.image=img,
        .viewType=VK_IMAGE_VIEW_TYPE_2D,.format=format,
        .subresourceRange={.aspectMask=aspect,.levelCount=1,.layerCount=1}};
    if (s->create_view(s->device,&info,NULL,&view)!=VK_SUCCESS) return VK_NULL_HANDLE;
    j->views[j->nviews++]=view;return view;
}
static bool svp_vk_picture(struct svp_vk_mpv *s,struct svp_vk_job *j,
    struct svp_vk_ref *r,int chroma,AmbientSvpVkPicture *p)
{
    if (r->fc->sw_format==AV_PIX_FMT_RGBA || r->fc->sw_format==AV_PIX_FMT_RGBAF16) {
        VkFormat format=r->fc->sw_format==AV_PIX_FMT_RGBAF16?VK_FORMAT_R16G16B16A16_SFLOAT:VK_FORMAT_R8G8B8A8_UNORM;
        if (r->count!=1 || r->vfc->format[0]!=format) return false;
        VkImageView view=svp_vk_view(s,j,r->frame->img[0],format,VK_IMAGE_ASPECT_COLOR_BIT);
        if (!view) return false;
        *p=(AmbientSvpVkPicture){view,VK_NULL_HANDLE,VK_NULL_HANDLE,2,0};
        return true;
    }
    unsigned planes=(r->fc->sw_format==AV_PIX_FMT_NV12||r->fc->sw_format==AV_PIX_FMT_P010)?2:3;
    // Initially accept only explicit separate R16 planes.
    if (s->mode && r->count!=planes) return false;
    VkFormat expected_multi=planes==2?VK_FORMAT_G8_B8R8_2PLANE_420_UNORM:VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM;
    if (r->count==1 && r->vfc->format[0]!=expected_multi) return false;
    VkImageView views[3]={0};
    for (unsigned i=0;i<planes;i++) {
        VkFormat format=s->mode?(i==1 && planes==2?VK_FORMAT_R16G16_UNORM:VK_FORMAT_R16_UNORM):
            (i==1 && planes==2?VK_FORMAT_R8G8_UNORM:VK_FORMAT_R8_UNORM);
        unsigned index=r->count==1?0:i;
        VkImageAspectFlags aspect=r->count==1?(VK_IMAGE_ASPECT_PLANE_0_BIT<<i):VK_IMAGE_ASPECT_COLOR_BIT;
        if (r->count!=1 && r->vfc->format[index]!=format) return false;
        views[i]=svp_vk_view(s,j,r->frame->img[index],format,aspect);
        if (!views[i]) return false;
    }
    *p=(AmbientSvpVkPicture){views[0],views[1],views[2],planes==2,(uint32_t)chroma};
    return true;
}

/* Record and submit actual hardware frames. All descriptors and frame refs
 * are retained by a bounded job until its fence signals. The output AVVkFrame
 * timeline semaphore is advanced before returning it to MPV's renderer. */
static bool svp_vk_complete_metadata(struct svp_vk_mpv *s,struct svp_vk_job *j,struct mp_image **outputs)
{
    unsigned count=j->metadata_count;
    {
        if(!j->sealed||(j->metadata_scene&&!s->scene_pass)||
            (j->metadata_pending&&s->wait_fences(s->device,1,&j->fence,VK_TRUE,250000000ULL)!=VK_SUCCESS))return false;
        if(s->scene_pass){
            for(unsigned i=0;i<count;i++){
                AmbientSvpHdrSceneResult result;
                if(s->scene_mode==1){if(s->scene_capture(s->scene_pass,j->pair,i,j->metadata_tick+i)!=1)return false;}
                else if(s->scene_version==0){
                    AmbientSvpHdrLegacyScene legacy;
                    if(s->scene_verify_geometry(s->scene_pass,j->pair,i,j->metadata_tick+i)!=1||
                        s->scene_lookup_legacy(s->scene_pass,j->metadata_tick+i,&legacy)!=1||
                        !svp_vk_scene_legacy_metadata(outputs[i],&legacy))return false;
                }else{
                    const AVDynamicHDRPlus *m=(const AVDynamicHDRPlus *)svp_vk_hdr10plus(outputs[i])->data;
                    uint32_t schema[9];for(unsigned k=0;k<9;k++)schema[k]=m->params[0].distribution_maxrgb[k].percentage;
                    if(s->scene_lookup(s->scene_pass,j->metadata_tick+i,&schema,&result)!=1||!svp_vk_scene_metadata(outputs[i],&result))return false;
                }
            }
        }
        for(unsigned i=0;i<count;i++)if(j->hdr_counts[i]){
            AmbientSvpHdrResult results[3];
            int32_t shifts[3][2]={{0}};
            int rc=j->tracked[i]?s->hdr_read_track(j->pair,i,results,shifts,j->hdr_counts[i]):
                s->hdr_read(j->pair,i,results,j->hdr_counts[i]);
            float gradients[3][4]={{0}};
            if(rc!=1)return false;
            if(j->tracked[i]&&s->hdr_read_affine&&s->hdr_read_affine(j->pair,i,gradients,j->hdr_counts[i])!=1)return false;
            uint32_t rectangles[3][4]={{0}};
            if(j->affine[i]&&s->hdr_read_rectangles(j->pair,i,rectangles,j->hdr_counts[i])!=1)return false;
            uint32_t ellipses[3][3]={{0}};
            if(j->elliptic[i]&&s->hdr_read_ellipses(j->pair,i,ellipses,j->hdr_counts[i])!=1)return false;
            if(s->scene_pass){
                if(s->scene_version==0&&s->scene_mode==2&&j->hdr_counts[i]>1&&
                    !svp_vk_scene_geometry(outputs[i],results,shifts,rectangles,ellipses,j->hdr_counts[i]))return false;
            }else if(!svp_vk_hdrplus_measured(outputs[i],results,j->tracked[i]?shifts:NULL,gradients,j->affine[i]?rectangles:NULL,j->elliptic[i]?ellipses:NULL,j->elliptic[i],j->phases[i],j->hdr_counts[i]))return false;
        }
    }
    return true;
}

static bool svp_vk_interpolate(struct svp_vk_mpv *s,struct mp_image *a,struct mp_image *b,
    struct mp_image *past,struct mp_image *future,const double *times,const float *phases,unsigned count,struct mp_image **outputs)
{
    if (!count || count>SVP_VK_PHASES || !svp_vk_matches(s,a) || !svp_vk_matches(s,b)
        || (a->params.repr.sys==PL_COLOR_SYSTEM_RGB)!=(b->params.repr.sys==PL_COLOR_SYSTEM_RGB)
        || svp_vk_matrix(a)!=svp_vk_matrix(b) || a->params.color.transfer!=b->params.color.transfer
        || a->params.color.primaries!=b->params.color.primaries) return false;
    if (s->mode>=4 && a->params.chroma_location!=b->params.chroma_location) return false;
    bool dynamic=s->mode>0 && s->mode<4 && (s->scene_pass?svp_vk_scene_version(a,s->scene_version)&&svp_vk_scene_version(b,s->scene_version):svp_vk_hdrplus_pair(a,b));
    if(dynamic && (!s->hdr_stats||!s->hdr_read))return false;
    if(s->scene_pass&&(!dynamic||!svp_vk_scene_version(a,s->scene_version)||!svp_vk_scene_version(b,s->scene_version)))return false;
    uint32_t hdr_counts[SVP_VK_PHASES]={0};
    bool tracked[SVP_VK_PHASES]={0},affine[SVP_VK_PHASES]={0},elliptic[SVP_VK_PHASES]={0};
    if (s->mode && (s->mode>=4 || a->params.color.transfer==PL_COLOR_TRC_PQ || a->params.color.transfer==PL_COLOR_TRC_HLG) &&
        !dynamic && !svp_vk_hdr_metadata_same(a,b)) return false;
    if(s->metadata_job)return false;
    bool was_scene=s->scene_pass!=NULL;
    svp_vk_reap(s,false);
    if(was_scene&&!s->scene_pass)return false;
    if(s->scene_pass&&s->scene_mode==1){
        unsigned used=0;for(unsigned i=0;i<SVP_VK_JOBS;i++)used+=s->jobs[i]!=NULL;
        if(used==SVP_VK_JOBS){svp_vk_reap(s,true);if(!s->scene_pass)return false;}
    }
    unsigned slot=0;while(slot<SVP_VK_JOBS && s->jobs[slot]) slot++;
    if (slot==SVP_VK_JOBS) return false;
    struct svp_vk_job *j=s->idle_count?s->idle_commands[--s->idle_count]:calloc(1,sizeof(*j));
    if (!j) return false;
    if(j->pool&&(s->reset_pool(s->device,j->pool,0)!=VK_SUCCESS||
        s->reset_fences(s->device,1,&j->fence)!=VK_SUCCESS)){svp_vk_job_free(s,j);return false;}
    if(dynamic&&(!s->scene_pass||s->scene_version==0)&&(!s->scene_pass||s->scene_mode!=1)){
        j->fallback=mp_image_new_ref(a);
        if(!j->fallback){svp_vk_job_free(s,j);return false;}
    }
    struct svp_vk_ref refs[SVP_VK_FRAMES];
    unsigned order[SVP_VK_FRAMES], unique=0, locked=0;
    VkCommandBuffer cmd=VK_NULL_HANDLE;
    VkImageView dst[SVP_VK_PHASES][3]={{0}};
    AmbientSvpVkPicture src[4];
    VkSemaphore sems[SVP_VK_IMAGES];
    uint64_t waits[SVP_VK_IMAGES],signals[SVP_VK_IMAGES];
    VkPipelineStageFlags stages[SVP_VK_IMAGES];
    VkImageMemoryBarrier2 barriers[SVP_VK_IMAGES];
    unsigned nsem=0,nbar=0;

    j->frames[j->nframes++]=mp_image_to_av_frame(a);
    j->frames[j->nframes++]=mp_image_to_av_frame(b);
    for (unsigned i=0;i<2;i++) {
        if (!svp_vk_ref(j->frames[i],false,&refs[i])
            || !svp_vk_picture(s,j,&refs[i],svp_vk_chroma(i?b:a),&src[i])) goto fail;
    }
    bool extended=s->algorithm==23&&s->begin_extended&&past&&future&&
        svp_vk_matches(s,past)&&svp_vk_matches(s,future)&&
        svp_vk_matrix(past)==svp_vk_matrix(a)&&svp_vk_matrix(future)==svp_vk_matrix(b)&&
        past->params.color.transfer==a->params.color.transfer&&future->params.color.transfer==b->params.color.transfer&&
        past->params.color.primaries==a->params.color.primaries&&future->params.color.primaries==b->params.color.primaries&&
        svp_vk_chroma(past)==svp_vk_chroma(a)&&svp_vk_chroma(future)==svp_vk_chroma(b);
    if(extended){
        struct mp_image *adjacent[]={past,future};
        for(unsigned i=0;i<2;i++){
            unsigned k=j->nframes++;
            j->frames[k]=mp_image_to_av_frame(adjacent[i]);
            if(!svp_vk_ref(j->frames[k],false,&refs[k])||
               !svp_vk_picture(s,j,&refs[k],svp_vk_chroma(adjacent[i]),&src[2+i]))goto fail;
        }
    }
    unsigned input_count=j->nframes;
    for (unsigned i=0;i<count;i++) {
        AVFrame *frame=av_frame_alloc();
        j->frames[j->nframes++]=frame;
        if (!frame || av_hwframe_get_buffer(s->output_hwframes,frame,0)<0
            || !svp_vk_ref(frame,true,&refs[input_count+i])) goto fail;
        if (s->mode>=4) {
            AmbientSvpVkPicture target;
            if (!svp_vk_picture(s,j,&refs[input_count+i],svp_vk_chroma(a),&target)) goto fail;
            dst[i][0]=target.y;dst[i][1]=target.u;dst[i][2]=target.v;
        } else {
            if (refs[input_count+i].count!=1 || refs[input_count+i].vfc->format[0]!=s->output_format) goto fail;
            dst[i][0]=svp_vk_view(s,j,refs[input_count+i].frame->img[0],s->output_format,VK_IMAGE_ASPECT_COLOR_BIT);
            if (!dst[i][0]) goto fail;
        }
        outputs[i]=mp_image_from_av_frame(frame);
        if (!outputs[i]) goto fail;
        mp_image_set_size(outputs[i],s->width,s->height);
        struct pl_bit_encoding output_bits=outputs[i]->params.repr.bits;
        mp_image_copy_attributes(outputs[i],a);
        outputs[i]->params.repr.bits=output_bits;
        if (s->mode>=4) {
            // Preserve the original mapped RPU representation and chroma
            // siting. The renderer applies the Dolby transformation once.
            outputs[i]->params.repr=a->params.repr;
            outputs[i]->params.chroma_location=a->params.chroma_location;
        } else {
            outputs[i]->params.repr.sys=PL_COLOR_SYSTEM_RGB;
            outputs[i]->params.repr.levels=PL_COLOR_LEVELS_FULL;
            outputs[i]->params.repr.alpha=PL_ALPHA_NONE;
            outputs[i]->params.chroma_location=PL_CHROMA_UNKNOWN;
        }
        if(dynamic && (phases[i]>0.0f||s->scene_pass)){
            bool hold=s->scene_pass&&(s->scene_version==1||phases[i]==0.0f);
            if(!svp_vk_hdrplus_apply(outputs[i],a,hold?a:b,hold?0.0f:phases[i]))goto fail;
        }
        outputs[i]->pts=times[i];outputs[i]->pkt_duration=1.0/s->target_rate;
        outputs[i]->nominal_fps=s->target_rate;
    }
    VkCommandPoolCreateInfo pi={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags=VK_COMMAND_POOL_CREATE_TRANSIENT_BIT,.queueFamilyIndex=s->family};
    if(!j->pool){
        if (s->create_pool(s->device,&pi,NULL,&j->pool)!=VK_SUCCESS) goto fail;
        VkCommandBufferAllocateInfo ci={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,.commandPool=j->pool,
            .level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
        if (s->allocate_commands(s->device,&ci,&j->cmd)!=VK_SUCCESS) goto fail;
        VkFenceCreateInfo fi={.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (s->create_fence(s->device,&fi,NULL,&j->fence)!=VK_SUCCESS) goto fail;
    }
    cmd=j->cmd;
    VkCommandBufferBeginInfo bi={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    if (s->begin_commands(cmd,&bi)!=VK_SUCCESS) goto fail;
    // Lock distinct frames in stable address order, exactly around recording
    // barriers and submission. No waits for completion while holding locks.
    for (unsigned i=0;i<j->nframes;i++) {
        unsigned k=0;
        while(k<unique && (uintptr_t)refs[order[k]].frame<(uintptr_t)refs[i].frame) k++;
        if (k<unique && refs[order[k]].frame==refs[i].frame) {
            if (refs[i].write || refs[order[k]].write) goto fail;
            continue;
        }
        memmove(order+k+1,order+k,(unique-k)*sizeof(*order));order[k]=i;unique++;
    }
    for (;locked<unique;locked++) {
        struct svp_vk_ref *r=&refs[order[locked]];
        r->vfc->lock_frame(r->fc,r->frame);
    }
    for (unsigned k=0;k<unique;k++) {
        struct svp_vk_ref *r=&refs[order[k]];
        for (unsigned i=0;i<r->count;i++) {
            AVVkFrame *v=r->frame;
            if ((v->queue_family[i]!=VK_QUEUE_FAMILY_IGNORED && v->queue_family[i]!=s->family)
                || !v->sem[i] || v->sem_value[i]==UINT64_MAX
                || (!r->write && v->layout[i]==VK_IMAGE_LAYOUT_UNDEFINED)
                || nbar>=SVP_VK_IMAGES) goto fail;
            for (unsigned n=0;n<nbar;n++) if (barriers[n].image==v->img[i]) goto fail;
            unsigned sem=0;while(sem<nsem && sems[sem]!=v->sem[i]) sem++;
            if (sem==nsem) {
                if (nsem>=SVP_VK_IMAGES) goto fail;
                sems[sem]=v->sem[i];waits[sem]=v->sem_value[i];
                stages[sem]=VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;nsem++;
            } else if (waits[sem]<v->sem_value[i]) waits[sem]=v->sem_value[i];
            signals[sem]=waits[sem]+1;
            barriers[nbar++]=(VkImageMemoryBarrier2){.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2,
                .srcStageMask=VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                // The timeline wait provides producer availability.
                // Do not put video-decode-only access flags on a compute queue.
                .srcAccessMask=0,
                .dstStageMask=VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                .dstAccessMask=r->write?VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT:VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                .oldLayout=v->layout[i],.newLayout=VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,
                .image=v->img[i],.subresourceRange={.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT,.levelCount=1,.layerCount=1}};
        }
    }
    VkDependencyInfo di={.sType=VK_STRUCTURE_TYPE_DEPENDENCY_INFO,.imageMemoryBarrierCount=nbar,.pImageMemoryBarriers=barriers};
    s->barrier(cmd,&di);
    if(extended){
        uint64_t ids[4]={s->older_id,s->previous_id,s->lookahead_id,s->input_frames+1};
        if(s->begin_extended_cached)j->pair=s->begin_extended_cached(s->engine,cmd,&src[2],&src[0],&src[1],&src[3],s->queue,&ids);
        else j->pair=s->begin_extended(s->engine,cmd,&src[2],&src[0],&src[1],&src[3]);
    }
    if(!j->pair)j->pair=s->begin(s->engine,cmd,&src[0],&src[1]);
    if (!j->pair) goto fail;
    if(s->scene_pass&&s->scene_dynamic&&s->scene_enable_dynamic(j->pair)!=0)goto fail;
    for (unsigned i=0;i<count;i++) {
        int rc=s->mode>=4?s->render_raw(j->pair,phases[i],dst[i],s->mode==4?2:3):
            s->render(j->pair,phases[i],svp_vk_matrix(a),dst[i][0]);
        if (rc) goto fail;
        if(s->scene_pass&&(s->scene_mode==1||s->scene_version==0)){
            AmbientSvpHdrWindow windows[3];uint32_t n;
            if(!svp_vk_hdrplus_windows(outputs[i],windows,&n))goto fail;
            if(s->scene_version==0){
                AmbientSvpHdrTrack tracks[3];uint32_t axes[3][3]={{0}};
                if(!svp_vk_hdrplus_tracks(a,phases[i]==0.0f?a:b,outputs[i],tracks,n))goto fail;
                const AVDynamicHDRPlus *m=(const AVDynamicHDRPlus *)svp_vk_hdr10plus(outputs[i])->data;
                for(unsigned w=1;w<n;w++){
                    axes[w][0]=m->params[w].semimajor_axis_external_ellipse;
                    axes[w][1]=m->params[w].semiminor_axis_external_ellipse;
                    axes[w][2]=m->params[w].rotation_angle;
                }
                if(s->scene_stats_legacy(j->pair,i,windows,tracks,axes,phases[i],n))goto fail;
                hdr_counts[i]=n;tracked[i]=affine[i]=elliptic[i]=n>1;
            }else if(n!=1||s->scene_stats(j->pair,i,windows,1))goto fail;
        }else if(!s->scene_pass&&dynamic && phases[i]>0.0f){
            AmbientSvpHdrWindow windows[3];
            if(!svp_vk_hdrplus_windows(outputs[i],windows,&hdr_counts[i]))goto fail;
            if(hdr_counts[i]>1&&s->hdr_stats_track&&s->hdr_read_track){
                AmbientSvpHdrTrack tracks[3];
                affine[i]=s->hdr_stats_affine&&s->hdr_read_rectangles&&s->hdr_read_affine;
                if(!svp_vk_hdrplus_tracks(a,b,outputs[i],tracks,hdr_counts[i]))goto fail;
                elliptic[i]=affine[i]&&s->hdr_stats_ellipse&&s->hdr_read_ellipses;
                if(elliptic[i]){
                    const AVBufferRef *ref=svp_vk_hdr10plus(outputs[i]);
                    if(!ref||ref->size!=sizeof(AVDynamicHDRPlus))goto fail;
                    const AVDynamicHDRPlus *m=(const AVDynamicHDRPlus *)ref->data;
                    uint32_t axes[3][3]={{0}};
                    for(unsigned w=1;w<hdr_counts[i];w++){
                        axes[w][0]=m->params[w].semimajor_axis_external_ellipse;
                        axes[w][1]=m->params[w].semiminor_axis_external_ellipse;
                        axes[w][2]=m->params[w].rotation_angle;
                    }
                    if(s->hdr_stats_ellipse(j->pair,i,windows,tracks,axes,phases[i],hdr_counts[i]))goto fail;
                }else if((affine[i]?s->hdr_stats_affine:s->hdr_stats_track)(j->pair,i,windows,tracks,phases[i],hdr_counts[i]))goto fail;
                tracked[i]=true;
            }else if(s->hdr_stats(j->pair,i,windows,hdr_counts[i]))goto fail;
        }
    }
    if (s->end_commands(cmd)!=VK_SUCCESS) goto fail;
    VkTimelineSemaphoreSubmitInfo ti={.sType=VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
        .waitSemaphoreValueCount=nsem,.pWaitSemaphoreValues=waits,.signalSemaphoreValueCount=nsem,.pSignalSemaphoreValues=signals};
    VkSubmitInfo si={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.pNext=&ti,.waitSemaphoreCount=nsem,
        .pWaitSemaphores=sems,.pWaitDstStageMask=stages,.commandBufferCount=1,.pCommandBuffers=&cmd,
        .signalSemaphoreCount=nsem,.pSignalSemaphores=sems};
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
    s->vkdev->lock_queue(s->avdev,s->family,0);
    VkResult submitted=s->submit(s->queue,1,&si,j->fence);
    s->vkdev->unlock_queue(s->avdev,s->family,0);
#pragma GCC diagnostic pop
    if (submitted!=VK_SUCCESS) goto fail;
    j->submitted=true;
    j->sealed=s->seal(j->pair,j->fence)==0;
    // The GPU now owns these resources. Publish its exact synchronization
    // state while FFmpeg's locks are still held.
    for (unsigned k=0;k<unique;k++) {
        struct svp_vk_ref *r=&refs[order[k]];
        for (unsigned i=0;i<r->count;i++) {
            AVVkFrame *v=r->frame;
            unsigned sem=0;while(sem<nsem && sems[sem]!=v->sem[i]) sem++;
            v->sem_value[i]=signals[sem];
            v->layout[i]=VK_IMAGE_LAYOUT_GENERAL;
            v->access[i]=r->write?VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT:VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
        }
    }
    while(locked) {struct svp_vk_ref *r=&refs[order[--locked]];r->vfc->unlock_frame(r->fc,r->frame);}
    s->jobs[slot]=j;
    if(s->scene_pass&&s->scene_mode==1){
        if(!j->sealed)goto submitted_fail;
        j->capture_tick=s->tick;j->capture_count=count;
        return true;
    }
    if(dynamic){
        j->metadata_scene=s->scene_pass!=NULL;
        j->metadata_count=count;j->metadata_tick=s->tick;
        memcpy(j->hdr_counts,hdr_counts,sizeof(hdr_counts));
        memcpy(j->tracked,tracked,sizeof(tracked));memcpy(j->affine,affine,sizeof(affine));
        memcpy(j->elliptic,elliptic,sizeof(elliptic));memcpy(j->phases,phases,count*sizeof(*phases));
        // V1 replay needs only cached metadata. Other outputs remain private
        // until their own fence and readback are complete at the pull boundary.
        if(s->scene_pass&&s->scene_version==1){
            if(!svp_vk_complete_metadata(s,j,outputs))goto submitted_fail;
        }else{
            j->metadata_pending=true;s->metadata_job=j;
        }
    }
    return true;
submitted_fail:
    svp_vk_scene_abort(s);
    // Submitted resources remain in jobs[] until their actual fence retires.
    for(unsigned i=0;i<count;i++)mp_image_unrefp(&outputs[i]);
    return false;
fail:
    svp_vk_scene_abort(s);
    while(locked) {struct svp_vk_ref *r=&refs[order[--locked]];r->vfc->unlock_frame(r->fc,r->frame);}
    for (unsigned i=0;i<count;i++) mp_image_unrefp(&outputs[i]);
    svp_vk_job_free(s,j);
    return false;
}

static void svp_vk_promote_output(struct svp_vk_mpv *s)
{
    if(s->output_count||!s->queued_count)return;
    const struct svp_vk_output_batch *q=&s->queued[0];
    memcpy(s->outputs,q->outputs,sizeof(s->outputs));
    s->metadata_job=q->metadata_job;s->next_output=q->next;s->output_count=q->count;
    --s->queued_count;
    memmove(s->queued,s->queued+1,s->queued_count*sizeof(*s->queued));
    memset(&s->queued[s->queued_count],0,sizeof(*s->queued));
}
static struct mp_image *svp_vk_take_output(struct svp_vk_mpv *s)
{
    if(!s)return NULL;
    svp_vk_promote_output(s);
    if(s->next_output==s->output_count)return NULL;
    if(s->metadata_job){
        struct svp_vk_job *j=s->metadata_job;
        bool ok=svp_vk_complete_metadata(s,j,s->outputs);
        if(!ok){
            // Preserve exact original pixels and metadata, not partially
            // updated synthetic output. The submitted job retains GPU refs.
            for(unsigned i=0;i<s->output_count;i++){
                struct mp_image *old=s->outputs[i];
                struct mp_image *original=j->fallback?mp_image_new_ref(j->fallback):NULL;
                if(original&&old){original->pts=old->pts;original->pkt_duration=old->pkt_duration;original->nominal_fps=old->nominal_fps;}
                mp_image_unrefp(&s->outputs[i]);s->outputs[i]=original;
            }
            s->capture_failed=j->metadata_scene;
            svp_vk_scene_abort(s);
        }
        j->metadata_pending=false;s->metadata_job=NULL;
        svp_vk_reap(s,false);
    }
    while(s->next_output<s->output_count&&!s->outputs[s->next_output])s->next_output++;
    if(s->next_output==s->output_count){s->next_output=s->output_count=0;return svp_vk_take_output(s);}
    struct mp_image *img=s->outputs[s->next_output];
    s->outputs[s->next_output++]=NULL;
    if(s->next_output==s->output_count){s->next_output=s->output_count=0;svp_vk_promote_output(s);}
    s->output_frames++;return img;
}
static struct mp_image *svp_vk_take_previous(struct svp_vk_mpv *s)
{
    if (!s) return NULL;
    struct mp_image *img=s->previous;s->previous=s->lookahead;s->lookahead=NULL;
    mp_image_unrefp(&s->older);return img;
}
static bool svp_vk_pair_frames(struct svp_vk_mpv *s,struct mp_image *next,double end,struct mp_image *future)
{
    double times[SVP_VK_PHASES];float phases[SVP_VK_PHASES];unsigned count=0;
    double duration=end-s->previous->pts;
    if (s->output_count || !(duration>0) || !isfinite(duration)) return false;
    uint64_t tick=s->tick;
    while (s->origin+(double)tick/s->target_rate<end-0.000001) {
        if (count==SVP_VK_PHASES || tick>=UINT64_C(1000000000)) return false;
        double time=s->origin+(double)tick++/s->target_rate;
        double phase=(time-s->previous->pts)/duration;
        if (phase< -0.25 || phase>1.0) return false;
        times[count]=time;phases[count++]=(float)(s->algorithm?round(fmax(0.0,phase)*256.0)/256.0:fmax(0.0,phase));
    }
    if (!count) return false;
    if(s->scene_pass){
        uint64_t boundary;
        if(s->scene_interval(s->scene_pass,s->tick,&boundary)!=1||tick>boundary){svp_vk_scene_abort(s);return false;}
        if(tick==boundary)for(unsigned i=0;i<count;i++)phases[i]=0.0f;
    }
    const struct mp_image *a=s->previous;
    bool hdr=s->mode>=4 || a->params.color.transfer==PL_COLOR_TRC_PQ || a->params.color.transfer==PL_COLOR_TRC_HLG;
    bool compatible=a->params.color.transfer==next->params.color.transfer &&
        a->params.color.primaries==next->params.color.primaries &&
        svp_vk_matrix(s->previous)==svp_vk_matrix(next);
    bool has_hdrplus=svp_vk_hdr10plus(a)||svp_vk_hdr10plus(next);
    bool measured_hdrplus=s->mode>0 && s->mode<4 && s->hdr_stats && s->hdr_read &&
        (s->scene_pass?svp_vk_scene_version(a,s->scene_version)&&svp_vk_scene_version(next,s->scene_version):svp_vk_hdrplus_pair(a,next));
    if (s->mode && hdr && compatible && ((has_hdrplus && !measured_hdrplus) ||
        (!svp_vk_hdr_metadata_same(a,next) && !measured_hdrplus) ||
        (s->mode>=4 && a->params.chroma_location!=next->params.chroma_location))) {
        if(s->scene_pass){svp_vk_scene_abort(s);return false;}
        // Do not interpolate across dynamic/static HDR metadata changes.
        // Repeat the exact preceding source until the next source tick;
        // retain all its original pixels, representation and side data.
        for (unsigned i=0;i<count;i++) {
            struct mp_image *out=mp_image_new_ref(s->previous);
            if (!out) {
                for (unsigned k=0;k<i;k++) mp_image_unrefp(&s->outputs[k]);
                return false;
            }
            out->pts=times[i];out->pkt_duration=1.0/s->target_rate;
            out->nominal_fps=s->target_rate;
            s->outputs[i]=out;
        }
    } else {
        if (!svp_vk_interpolate(s,s->previous,next,s->older,future,times,phases,count,s->outputs)) return false;
        s->generated_frames+=count;
    }
    s->tick=tick;s->output_count=count;s->next_output=0;
    return true;
}
// On success consumes img; on rejection caller still owns it.
static bool svp_vk_feed(struct svp_vk_mpv *s,struct mp_image *img)
{
    if (!s || !svp_vk_matches(s,img) || s->output_count) return false;
    if (!s->previous) {
        s->previous=img;s->origin=img->pts;s->tick=0;s->previous_id=++s->input_frames;return true;
    }
    double expected=1.0/s->source_rate;
    struct mp_image *last=s->lookahead?s->lookahead:s->previous;
    double delta=img->pts-last->pts;
    if(fabs(delta-expected)>fmax(0.003,expected*0.25)){svp_vk_scene_abort(s);return false;}
    if(s->algorithm==23&&s->begin_extended){
        if(!s->lookahead){s->lookahead=img;s->lookahead_id=++s->input_frames;return true;}
        if(!svp_vk_pair_frames(s,s->lookahead,s->lookahead->pts,img)){svp_vk_scene_abort(s);return false;}
        mp_image_unrefp(&s->older);s->older=s->previous;s->older_id=s->previous_id;
        s->previous=s->lookahead;s->previous_id=s->lookahead_id;
        s->lookahead=img;s->lookahead_id=++s->input_frames;return true;
    }
    if(!svp_vk_pair_frames(s,img,img->pts,NULL)){svp_vk_scene_abort(s);return false;}
    mp_image_unrefp(&s->previous);s->previous=img;s->previous_id=++s->input_frames;return true;
}
// Only prefetch while the oldest HDR batch is awaiting its fence and
// the source filter already has a decoded picture available. Bounded by
// the native job budget; never allow a later batch to overtake the head.
static bool svp_vk_can_prefetch(struct svp_vk_mpv *s)
{
    if(!s||!s->metadata_job||s->queued_count>=SVP_VK_JOBS-1||!s->previous||
        s->capture_failed||s->wait_fences(s->device,1,&s->metadata_job->fence,VK_TRUE,0)!=VK_TIMEOUT)return false;
    for(unsigned i=0;i<SVP_VK_JOBS;i++)if(!s->jobs[i])return true;
    return false;
}
static bool svp_vk_prefetch(struct svp_vk_mpv *s,struct mp_image *img)
{
    if(!svp_vk_can_prefetch(s)||!svp_vk_matches(s,img))return false;
    struct svp_vk_output_batch head={.metadata_job=s->metadata_job,.next=s->next_output,.count=s->output_count};
    memcpy(head.outputs,s->outputs,sizeof(head.outputs));
    memset(s->outputs,0,sizeof(s->outputs));s->metadata_job=NULL;s->next_output=s->output_count=0;
    bool ok=svp_vk_feed(s,img);
    if(ok&&s->output_count){
        struct svp_vk_output_batch *q=&s->queued[s->queued_count++];
        memcpy(q->outputs,s->outputs,sizeof(q->outputs));q->metadata_job=s->metadata_job;
        q->count=s->output_count;q->next=s->next_output;
    }
    memcpy(s->outputs,head.outputs,sizeof(s->outputs));
    s->metadata_job=head.metadata_job;s->next_output=head.next;s->output_count=head.count;
    return ok;
}
static void svp_vk_eof(struct svp_vk_mpv *s)
{
    if (!s || !s->previous || s->output_count) return;
    if(s->lookahead){
        if(svp_vk_pair_frames(s,s->lookahead,s->lookahead->pts,NULL)){
            mp_image_unrefp(&s->previous);s->previous=s->lookahead;s->lookahead=NULL;
            mp_image_unrefp(&s->older);return;
        }
        svp_vk_scene_abort(s);
        s->outputs[0]=svp_vk_take_previous(s);s->output_count=1;s->next_output=0;return;
    }
    if (svp_vk_pair_frames(s,s->previous,s->previous->pts+1.0/s->source_rate,NULL))
        mp_image_unrefp(&s->previous);
    else {
        s->outputs[0]=svp_vk_take_previous(s);s->output_count=1;s->next_output=0;
    }
    mp_image_unrefp(&s->older);
}
#endif
