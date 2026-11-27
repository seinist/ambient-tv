#pragma once
#include "dovi_fel_api.h"
#include "dovi_fel_gpu.h"
#include "dovi_fel_metadata.h"
#include <libplacebo/renderer.h>
#include <libplacebo/tone_mapping.h>
#include <string.h>

/* Persistent renderer-owned upload and reconstruction resources. The picture
 * carries a single malloc-owned EL snapshot and matching RPU. No FFmpeg
 * object or private decoder state is referenced from the display module. */
struct ambient_fel_render {
    struct ambient_fel_planes planes;
    pl_dispatch dispatch;
    pl_tex output;
};
static inline void ambient_fel_render_destroy(pl_gpu gpu,struct ambient_fel_render *s)
{
    ambient_fel_planes_destroy(gpu,&s->planes);
    pl_tex_destroy(gpu,&s->output);
    pl_dispatch_destroy(&s->dispatch);
}
static inline bool ambient_fel_render_picture(pl_gpu gpu,pl_log log,
    struct ambient_fel_render *s,struct pl_frame *img,
    const struct ambient_fel_picture *pic,int64_t pts,uint32_t bl_x,uint32_t bl_y)
{
    if(!gpu||!s||!img||!pic||pic->pts!=pts||pts<=0||
        img->num_planes!=1||!img->planes[0].texture||
        !pic->width||!pic->height||pic->width>1920||pic->height>1088||
        pic->rpu.source_min_pq>pic->rpu.source_max_pq||
        pic->rpu.source_max_pq>4095)return false;
    pl_tex bl=img->planes[0].texture;
    unsigned w=pic->width,h=pic->height,cw=(w+1)/2,ch=(h+1)/2;
    if(bl==s->output||bl->params.w<=0||bl->params.h<=0||
       !ambient_fel_crop_axis(bl->params.w,w,bl_x,pic->crop_x)||
       !ambient_fel_crop_axis(bl->params.h,h,bl_y,pic->crop_y))return false;
    struct pl_dovi_metadata reshape;
    struct ambient_fel_nlq nlq;
    if(!ambient_fel_metadata(&pic->rpu,&reshape,&nlq))return false;
    const bool zero=ambient_fel_nlq_zero(&nlq);
    const size_t expected=(size_t)w*h+2*(size_t)cw*ch;
    // A compact snapshot may only be used with a matching zero-residual
    // RPU. Validate its length before forming any flexible-array pointers.
    if(pic->sample_count!=expected && !(zero&&pic->sample_count==0))return false;
    if(!zero){
        const uint16_t *data[]={pic->samples,pic->samples+(size_t)w*h,
            pic->samples+(size_t)w*h+(size_t)cw*ch};
        const size_t pitches[]={w*2u,cw*2u,cw*2u};
        if(!ambient_fel_planes_upload(gpu,&s->planes,w,h,data,pitches,
            pic->chroma_x,pic->chroma_y))return false;
    }
    if(!s->dispatch)s->dispatch=pl_dispatch_create(log,gpu);
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,16,0,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_LINEAR);
    if(!s->dispatch||!fmt||!pl_tex_recreate(gpu,&s->output,pl_tex_params(
        .w=bl->params.w,.h=bl->params.h,.format=fmt,.sampleable=true,.renderable=true)))
        return false;
    if(!ambient_fel_compose_ex(gpu,s->dispatch,bl,NULL,s->output,&reshape,&nlq,
        true,true,zero?NULL:&s->planes))return false;
    // The prepass has produced nonlinear BT.2020 PQ RGB. Ordinary rendering
    // now handles target gamut, tone mapping, scaling, overlays and dither.
    img->planes[0]=(struct pl_plane){.texture=s->output,.components=3,
        .component_mapping={0,1,2,-1}};
    img->repr=pl_color_repr_rgb;
    img->color=pl_color_space_hdr10;
    img->color.hdr.min_luma=pl_hdr_rescale(PL_HDR_PQ,PL_HDR_NITS,pic->rpu.source_min_pq/4095.f);
    img->color.hdr.max_luma=pl_hdr_rescale(PL_HDR_PQ,PL_HDR_NITS,pic->rpu.source_max_pq/4095.f);
    return true;
}
