/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Included at the pinned vo_gpu_next map/unmap boundary. Uses MPV's
 * actual BL/EL mappers and the renderer's own GPU and synchronization.
 */
#ifndef AMBIENTSVP_MPV_FEL_VO_H
#define AMBIENTSVP_MPV_FEL_VO_H
#include "svp_mpv_fel_pair.h"
#include <libplacebo/shaders/custom.h>
#include <libplacebo/shaders/colorspace.h>
#include <libplacebo/tone_mapping.h>
#include <libplacebo/gamut_mapping.h>
#if defined(PL_HAVE_VULKAN)
#include <libplacebo/vulkan.h>
#include "svp_placebo_quality.h"
#endif
static bool map_frame(pl_gpu gpu,pl_tex *tex,const struct pl_source_frame *src,struct pl_frame *frame);
static void unmap_frame(pl_gpu gpu,struct pl_frame *frame,const struct pl_source_frame *src);
struct svp_fel_vo_cache {
    pl_renderer reconstruct;
    pl_tex sources[4];
    uint64_t ids[4];
#if defined(PL_HAVE_VULKAN)
    struct svp_placebo_quality quality;
#endif
};
static inline void svp_fel_vo_destroy(struct priv *p)
{
    struct svp_fel_vo_cache *s=p->svp_fel;
    if(!s)return;
#if defined(PL_HAVE_VULKAN)
    svp_placebo_quality_retire_texture(p->gpu,&s->quality,NULL);
#endif
    for(unsigned i=0;i<4;i++){
        pl_tex_destroy(p->gpu,&s->sources[i]);
    }
#if defined(PL_HAVE_VULKAN)
    svp_placebo_quality_destroy(p->gpu,&s->quality);
#endif
    pl_renderer_destroy(&s->reconstruct);
    free(s);p->svp_fel=NULL;
}
static inline void svp_fel_vo_retire_output(struct priv *p,pl_tex output)
{
#if defined(PL_HAVE_VULKAN)
    if(p->svp_fel&&output)svp_placebo_quality_retire_texture(p->gpu,&p->svp_fel->quality,output);
#else
    (void)p;(void)output;
#endif
}
#if PL_API_VER >= 367 && defined(PL_HAVE_DOVI) && defined(PL_HAVE_VULKAN)
static inline bool svp_fel_target(pl_gpu gpu,struct svp_placebo_quality *s,pl_tex *tex,int w,int h)
{
    return svp_placebo_quality_target(gpu,s,tex,w,h,false);
}
static inline struct pl_frame svp_fel_rgb(pl_tex tex)
{
    struct pl_frame frame={
        .num_planes=1,
        .planes={{.texture=tex,.components=4,.component_mapping={0,1,2,-1}}},
        .repr={.sys=PL_COLOR_SYSTEM_RGB,.levels=PL_COLOR_LEVELS_FULL,.alpha=PL_ALPHA_NONE},
        .color=pl_color_space_hdr10,
        .crop={0,0,tex->params.w,tex->params.h},
    };
    // Use the entire PQ container at the reconstruction boundary.
    // The final renderer, not this intermediate pass, maps to the display.
    frame.color.hdr.max_luma=10000.0f;frame.color.hdr.min_luma=0.0001f;
    return frame;
}
static inline bool svp_fel_vo_source(struct priv *p,struct vo *vo,struct svp_fel_vo_cache *s,
    struct mp_image *source,uint64_t id,unsigned slot)
{
    if(s->ids[slot]==id&&s->sources[slot])return true;
    s->ids[slot]=0;
    svp_placebo_quality_source_changed(p->gpu,&s->quality,s->sources[slot]);
    if(!svp_fel_eligible(source)||!svp_fel_target(p->gpu,&s->quality,&s->sources[slot],source->w,source->h))return false;
    struct mp_image *mpi=mp_image_new_ref(source);
    if(!mpi)return false;
    struct frame_priv *fp=talloc_zero(mpi,struct frame_priv);
    mpi->priv=fp;fp->vo=vo;
    struct pl_source_frame src={.frame_data=mpi,.pts=mpi->pts};
    struct pl_frame image={0};
    pl_tex uploads[4]={0};
    bool mapped=map_frame(p->gpu,uploads,&src,&image);
    bool ok=false;
    if(mapped){
        // No base-layer-only success: the pinned renderer sets a sampling
        // error if FEL reconstruction cannot be performed.
        if(image.enhancement_layer&&image.repr.sys==PL_COLOR_SYSTEM_DOLBYVISION&&
            image.repr.dovi&&image.repr.dovi->nlq_active){
            image.profile=(struct pl_icc_profile){0};image.icc=NULL;
            image.lut=NULL;image.num_overlays=0;
            struct pl_frame target=svp_fel_rgb(s->sources[slot]);
            struct pl_color_map_params color=pl_color_map_default_params;
            color.tone_mapping_function=&pl_tone_map_clip;
            color.gamut_mapping=&pl_gamut_map_clip;
            struct pl_render_params params=pl_render_fast_params;
            params.color_map_params=&color;params.peak_detect_params=NULL;
            params.dither_params=NULL;params.frame_mixer=NULL;
            pl_renderer_reset_errors(s->reconstruct,NULL);
            ok=pl_render_image(s->reconstruct,&image,&target,&params)&&
                !(pl_renderer_get_errors(s->reconstruct).errors&(PL_RENDER_ERR_SAMPLING|PL_RENDER_ERR_FBO));
        }
        unmap_frame(p->gpu,&image,&src);
    }
    for(unsigned i=0;i<4;i++)pl_tex_destroy(p->gpu,&uploads[i]);
    if(ok)s->ids[slot]=id;
    return ok;
}
#endif
static inline bool svp_fel_vo_map(struct priv *p,struct vo *vo,struct mp_image *mpi,struct pl_frame *out)
{
#if PL_API_VER >= 367 && defined(PL_HAVE_DOVI) && defined(PL_HAVE_VULKAN)
    const struct svp_fel_pair *pair=svp_fel_get(mpi);
    if(!pair||!pl_vulkan_get(p->gpu)||!pair->source[0]||!pair->source[1]||
        !isfinite(pair->phase)||pair->phase<0||pair->phase>1||
        mpi->w!=pair->source[0]->w||mpi->h!=pair->source[0]->h||
        mpi->w!=pair->source[1]->w||mpi->h!=pair->source[1]->h||mpi->params.rotate||mpi->params.vflip||
        mpi->params.crop.x0||mpi->params.crop.y0||
        (mpi->params.crop.x1&&mpi->params.crop.x1!=mpi->w)||
        (mpi->params.crop.y1&&mpi->params.crop.y1!=mpi->h))return false;
    if(!p->svp_fel)p->svp_fel=calloc(1,sizeof(*p->svp_fel));
    struct svp_fel_vo_cache *s=p->svp_fel;
    if(!s)return false;
    if(!s->reconstruct)s->reconstruct=pl_renderer_create(p->pllog,p->gpu);
    if(!s->reconstruct)return false;
    bool extended=ambient_svp_quality_extended(&pair->quality)&&pair->source[2]&&pair->source[3]&&
        pair->source[2]->w==mpi->w&&pair->source[2]->h==mpi->h&&
        pair->source[3]->w==mpi->w&&pair->source[3]->h==mpi->h;
    unsigned count=extended?4:2;
    // Rotate cached reconstruction textures to match the four immutable
    // source IDs before loading missing frames.
    for(unsigned i=0;i<count;i++){
        for(unsigned j=i+1;j<4&&s->ids[i]!=pair->ids[i];j++){
            if(s->ids[j]!=pair->ids[i])continue;
            pl_tex t=s->sources[i];s->sources[i]=s->sources[j];s->sources[j]=t;
            uint64_t id=s->ids[i];s->ids[i]=s->ids[j];s->ids[j]=id;break;
        }
    }
    for(unsigned i=0;i<count;i++){
        if(svp_fel_vo_source(p,vo,s,pair->source[i],pair->ids[i],i))continue;
        if(i<2)return false;
        extended=false;break;
    }
    struct frame_priv *fp=mpi->priv;
    // The full BL/EL reconstruction is complete. Interpolate only
    // canonical RGB; source endpoints or bridge failure keep exact
    // reconstructed originals, never the base layer alone.
    pl_tex output=pair->phase>=1.0f?s->sources[1]:s->sources[0];
    if(pair->phase>0&&pair->phase<1&&svp_placebo_quality_render(p->gpu,&s->quality,
        s->sources[0],s->sources[1],extended?s->sources[2]:NULL,extended?s->sources[3]:NULL,&fp->svp_fel_output,pair->phase,&pair->quality))
        output=fp->svp_fel_output;
    // Each queue frame must own its output. For source/failure frames
    // use normal MPV mapping instead of exposing a mutable cache slot.
    if(output!=fp->svp_fel_output)return false;
    *out=svp_fel_rgb(output);out->user_data=mpi;
    // No RPU or enhancement_layer attached to reconstructed RGB.
    return true;
#else
    (void)p;(void)vo;(void)mpi;(void)out;return false;
#endif
}
#endif
