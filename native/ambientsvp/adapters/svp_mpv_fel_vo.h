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
#endif
static bool map_frame(pl_gpu gpu,pl_tex *tex,const struct pl_source_frame *src,struct pl_frame *frame);
static void unmap_frame(pl_gpu gpu,struct pl_frame *frame,const struct pl_source_frame *src);
struct svp_fel_vo_cache {
    pl_renderer reconstruct;
    pl_dispatch dp;
    pl_tex sources[2],fields[2];
    uint64_t ids[2],field_ids[2];
};
static inline void svp_fel_vo_destroy(struct priv *p)
{
    struct svp_fel_vo_cache *s=p->svp_fel;
    if(!s)return;
    for(unsigned i=0;i<2;i++){
        pl_tex_destroy(p->gpu,&s->sources[i]);
        pl_tex_destroy(p->gpu,&s->fields[i]);
    }
    pl_dispatch_destroy(&s->dp);
    pl_renderer_destroy(&s->reconstruct);
    free(s);p->svp_fel=NULL;
}
#if PL_API_VER >= 367 && defined(PL_HAVE_DOVI) && defined(PL_HAVE_VULKAN)
static inline bool svp_fel_target(pl_gpu gpu,pl_tex *tex,int w,int h)
{
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,16,0,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);
    return w>0&&h>0&&fmt&&pl_tex_recreate(gpu,tex,pl_tex_params(
        .w=w,.h=h,.format=fmt,.sampleable=true,.renderable=true));
}
static const char svp_fel_motion_header[]=
    "int svp_y(sampler2D t,ivec2 p){return int(round(clamp(dot(texelFetch(t,p,0).rgb,vec3(.2126,.7152,.0722)),0.,1.)*1023.));}"
    "int svp_cost(ivec2 base,ivec2 mv,int bound){ivec2 size=textureSize(svp_a,0);int sad=0,n=0;"
    "for(int y=0;y<16;y+=2){for(int x=0;x<16;x+=2){ivec2 p=base+ivec2(x,y);if(any(greaterThanEqual(p,size)))continue;"
    "ivec2 q=p+mv;n++;if(any(lessThan(q,ivec2(0)))||any(greaterThanEqual(q,size)))sad+=1023;"
    "else sad+=abs(svp_y(svp_a,p)-svp_y(svp_b,q));}if(sad*128+abs(mv.x)+abs(mv.y)>=bound)return bound;}"
    "return sad*64/max(n,1)*128+abs(mv.x)+abs(mv.y);}";
static inline bool svp_fel_motion(pl_gpu gpu,pl_dispatch dp,pl_tex a,pl_tex b,pl_tex *field)
{
    int w=(a->params.w+15)/16,h=(a->params.h+15)/16;
    if(!svp_fel_target(gpu,field,w,h))return false;
    struct pl_shader_desc d[]={
        {.desc={.name="svp_a",.type=PL_DESC_SAMPLED_TEX},.binding={.object=a}},
        {.desc={.name="svp_b",.type=PL_DESC_SAMPLED_TEX},.binding={.object=b}},
    };
    pl_shader sh=pl_dispatch_begin(dp);
    if(!sh)return false;
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,.descriptors=d,.num_descriptors=2,
        .header=svp_fel_motion_header,.output_w=w,.output_h=h,
        .body="ivec2 base=ivec2(gl_FragCoord.xy)*16,mv=ivec2(0);int best=svp_cost(base,mv,2147483647);"
            "if(best!=0){for(int y=-16;y<=16;y+=4)for(int x=-16;x<=16;x+=4){ivec2 v=ivec2(x,y);"
            "int c=svp_cost(base,v,best);if(c<best){best=c;mv=v;}}ivec2 pred=mv;"
            "for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++){ivec2 v=pred+ivec2(x,y);"
            "int c=svp_cost(base,v,best);if(c<best){best=c;mv=v;}}}"
            "color=vec4(vec2(mv),float(best/512),1.);",
    })){pl_dispatch_abort(dp,&sh);return false;}
    return pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=*field));
}
static inline bool svp_fel_synth(pl_gpu gpu,pl_dispatch dp,pl_tex fields[2],pl_tex *output,
    pl_tex a,pl_tex b,float phase)
{
    int w=a->params.w,h=a->params.h;
    if(!svp_fel_target(gpu,output,w,h))return false;
    struct pl_shader_desc d[]={
        {.desc={.name="svp_a",.type=PL_DESC_SAMPLED_TEX},.binding={.object=a}},
        {.desc={.name="svp_b",.type=PL_DESC_SAMPLED_TEX},.binding={.object=b}},
        {.desc={.name="svp_f",.type=PL_DESC_SAMPLED_TEX},.binding={.object=fields[0]}},
        {.desc={.name="svp_r",.type=PL_DESC_SAMPLED_TEX},.binding={.object=fields[1]}},
    };
    struct pl_shader_var v={.var=pl_var_float("svp_phase"),.data=&phase,.dynamic=true};
    pl_shader sh=pl_dispatch_begin(dp);
    if(!sh)return false;
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,.descriptors=d,.num_descriptors=4,
        .variables=&v,.num_variables=1,.output_w=w,.output_h=h,
        .header="vec4 svp_sample(sampler2D t,vec2 p){ivec2 hi=textureSize(t,0)-1;"
            "p=clamp(p,vec2(0),vec2(hi));ivec2 q=ivec2(floor(p));vec2 f=fract(p);"
            "return mix(mix(texelFetch(t,q,0),texelFetch(t,min(q+ivec2(1,0),hi),0),f.x),"
            "mix(texelFetch(t,min(q+ivec2(0,1),hi),0),texelFetch(t,min(q+1,hi),0),f.x),f.y);}",
        .body="ivec2 p=ivec2(gl_FragCoord.xy),block=p/16;vec3 f=texelFetch(svp_f,block,0).xyz,r=texelFetch(svp_r,block,0).xyz;"
            "float t=svp_phase;if(t<=0.)color=texelFetch(svp_a,p,0);else if(t>=1.)color=texelFetch(svp_b,p,0);"
            "else if(f.z>3072.||r.z>3072.||abs(f.x+r.x)+abs(f.y+r.y)>6.)"
            "color=t<.5?texelFetch(svp_a,p,0):texelFetch(svp_b,p,0);"
            "else color=mix(svp_sample(svp_a,vec2(p)-f.xy*t),svp_sample(svp_b,vec2(p)-r.xy*(1.-t)),t);",
    })){pl_dispatch_abort(dp,&sh);return false;}
    return pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=*output));
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
    if(!svp_fel_eligible(source)||!svp_fel_target(p->gpu,&s->sources[slot],source->w,source->h))return false;
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
    if(!s->dp)s->dp=pl_dispatch_create(p->pllog,p->gpu);
    if(!s->reconstruct)s->reconstruct=pl_renderer_create(p->pllog,p->gpu);
    if(!s->dp||!s->reconstruct)return false;
    if(s->ids[1]==pair->ids[0]&&s->ids[0]!=pair->ids[0]){
        pl_tex t=s->sources[0];s->sources[0]=s->sources[1];s->sources[1]=t;
        uint64_t id=s->ids[0];s->ids[0]=s->ids[1];s->ids[1]=id;
    }
    if(!svp_fel_vo_source(p,vo,s,pair->source[0],pair->ids[0],0)||
        !svp_fel_vo_source(p,vo,s,pair->source[1],pair->ids[1],1))return false;
    pl_dispatch_reset_frame(s->dp);
    if(s->field_ids[0]!=pair->ids[0]||s->field_ids[1]!=pair->ids[1]){
        s->field_ids[0]=s->field_ids[1]=0;
        if(!svp_fel_motion(p->gpu,s->dp,s->sources[0],s->sources[1],&s->fields[0])||
            !svp_fel_motion(p->gpu,s->dp,s->sources[1],s->sources[0],&s->fields[1]))return false;
        s->field_ids[0]=pair->ids[0];s->field_ids[1]=pair->ids[1];
    }
    struct frame_priv *fp=mpi->priv;
    // Each queue frame owns its own synthesis texture. Cache slots may
    // be reused for later source pairs without overwriting queued output.
    if(!svp_fel_synth(p->gpu,s->dp,s->fields,&fp->svp_fel_output,
        s->sources[0],s->sources[1],pair->phase))return false;
    *out=svp_fel_rgb(fp->svp_fel_output);out->user_data=mpi;
    // No RPU or enhancement_layer attached to reconstructed RGB.
    return true;
#else
    (void)p;(void)vo;(void)mpi;(void)out;return false;
#endif
}
#endif
