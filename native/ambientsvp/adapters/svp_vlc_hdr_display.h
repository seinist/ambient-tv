/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Staged only in the PR #510 experimental libplacebo display.
 * Source AHB leases end after the canonical PQ RGB GPU copy; original
 * timestamps are used for FEL composition, never the synthesized tick.
 */
#ifndef AMBIENTSVP_VLC_HDR_DISPLAY_H
#define AMBIENTSVP_VLC_HDR_DISPLAY_H
#include "svp_vlc_hdr_pair.h"
#include "display_bridge.h"
#include "dovi_fel_render.h"
#include "hdr10plus_spatial.h"
#include "hdr10plus_vlc_bridge.h"
#include <libplacebo/shaders/custom.h>
#include <libplacebo/shaders/colorspace.h>
struct ambient_svp_hdr_source {
    uint64_t id;
    pl_tex texture;
    struct pl_color_space color;
    bool aligned,has_hdrplus;
    struct ambient_hdrplus hdrplus;
};
struct ambient_svp_hdr_display {
    struct ambient_svp_hdr_source sources[2];
    struct ambient_fel_render fel;
    pl_dispatch dp;
    pl_tex fields[2],output;
    uint64_t field_a,field_b;
    struct ambient_hdrplus metadata;
    bool has_metadata,metadata_aligned;
};
static inline void ambient_svp_hdr_display_destroy(pl_gpu gpu,struct ambient_svp_hdr_display *s)
{
    ambient_fel_render_destroy(gpu,&s->fel);
    for(unsigned i=0;i<2;i++){
        pl_tex_destroy(gpu,&s->sources[i].texture);
        pl_tex_destroy(gpu,&s->fields[i]);
    }
    pl_tex_destroy(gpu,&s->output);pl_dispatch_destroy(&s->dp);
    *s=(struct ambient_svp_hdr_display){0};
}
static inline bool ambient_svp_hdr_target(pl_gpu gpu,pl_tex *tex,int w,int h)
{
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,16,0,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);
    return w>0&&h>0&&fmt&&pl_tex_recreate(gpu,tex,pl_tex_params(
        .w=w,.h=h,.format=fmt,.sampleable=true,.renderable=true));
}
static inline bool ambient_svp_hdr_copy(pl_gpu gpu,pl_dispatch dp,const struct pl_frame *img,
    pl_tex *out,bool raw)
{
    pl_tex input=img->planes[0].texture;
    if(img->num_planes!=1||!input||input==*out||
        !ambient_svp_hdr_target(gpu,out,input->params.w,input->params.h))return false;
    const struct pl_shader_desc d={.desc={.name="svp_input",.type=PL_DESC_SAMPLED_TEX},.binding={.object=input}};
    pl_shader sh=pl_dispatch_begin(dp);
    if(!sh)return false;
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
        .descriptors=&d,.num_descriptors=1,.output_w=input->params.w,.output_h=input->params.h,
        .body=raw?"color=texelFetch(svp_input,ivec2(gl_FragCoord.xy),0).gbra;":
                  "color=texelFetch(svp_input,ivec2(gl_FragCoord.xy),0);",
    }))goto fail;
    if(raw){
        struct pl_color_repr repr=img->repr;
        pl_shader_decode_color(sh,&repr,NULL);
    }
    if(!pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=*out)))return false;
    return true;
fail:
    pl_dispatch_abort(dp,&sh);return false;
}
static inline bool ambient_svp_hdr_source_load(pl_gpu gpu,pl_log log,
    struct ambient_svp_hdr_display *s,struct ambient_ahb_owner *owner,
    struct ambient_svp_hdr_source *dst,picture_t *pic,uint64_t id,bool raw,bool dovi)
{
    if(dst->id==id&&dst->texture)return true;
    dst->id=0;
    struct ambient_ahb_output lease={0};
    if(ambient_display_prepare(owner,pic,&lease,raw,dovi)!=VK_SUCCESS)return false;
    struct pl_color_space color=vlc_placebo_ColorSpace(&pic->format);
    struct pl_frame img=lease.raw_yuv?
        ambient_pl_raw_p010_frame((pl_tex)lease.texture,color,
            pic->format.color_range==COLOR_RANGE_FULL?PL_COLOR_LEVELS_FULL:PL_COLOR_LEVELS_LIMITED):
        ambient_pl_rgb_frame((pl_tex)lease.texture,color);
    bool raw_copy=lease.raw_yuv;
    bool ok=true;
    struct pl_dovi_metadata mapping={0};
    if(dovi){
        if(pic->format.dovi.profile==7){
            struct vlc_ancillary *el=picture_GetAncillary(pic,AMBIENT_FEL_ID);
            ok=el&&ambient_fel_render_picture_ex(gpu,log,&s->fel,&img,
                vlc_ancillary_GetData(el),pic->date,pic->format.i_x_offset,pic->format.i_y_offset,
                lease.fel_coded_confirmed?pic->format.i_width:0,
                lease.fel_coded_confirmed?pic->format.i_height:0);
            raw_copy=false;
        }else{
            vlc_placebo_frame_DoviMetadata(&img,pic,&mapping);
            ok=img.repr.sys==PL_COLOR_SYSTEM_DOLBYVISION&&img.repr.dovi;
        }
    }
    if(ok)ok=ambient_svp_hdr_copy(gpu,s->dp,&img,&dst->texture,raw_copy);
    // Even on a failed shader, retire the source via #510's proven GPU
    // consumer fence. The cache owns only its copied texture, not AImage.
    if(!ambient_ahb_finish(owner,lease))ok=false;
    if(!ok)return false;
    dst->color=img.color;dst->aligned=lease.metadata_origin_aligned;
    struct vlc_ancillary *full=picture_GetAncillary(pic,AMBIENT_HDRPLUS_FULL_ID);
    dst->has_hdrplus=full!=NULL;
    if(full){
        dst->hdrplus=*(const struct ambient_hdrplus *)vlc_ancillary_GetData(full);
        vlc_video_hdr_dynamic_metadata_t global;
        ambient_hdrplus_project_global(&global,&dst->hdrplus);
        vlc_placebo_HdrMetadata(&global,&dst->color.hdr);
    }
    dst->id=id;
    return true;
}
static const char ambient_svp_hdr_motion_header[]=
    "int svp_y(sampler2D t,ivec2 p){return int(round(clamp(dot(texelFetch(t,p,0).rgb,vec3(.2126,.7152,.0722)),0.,1.)*1023.));}"
    "int svp_cost(ivec2 base,ivec2 mv,int bound){ivec2 size=textureSize(svp_a,0);int sad=0,n=0;"
    "for(int y=0;y<16;y+=2){for(int x=0;x<16;x+=2){ivec2 p=base+ivec2(x,y);if(any(greaterThanEqual(p,size)))continue;"
    "ivec2 q=p+mv;n++;if(any(lessThan(q,ivec2(0)))||any(greaterThanEqual(q,size)))sad+=1023;"
    "else sad+=abs(svp_y(svp_a,p)-svp_y(svp_b,q));}if(sad*128+abs(mv.x)+abs(mv.y)>=bound)return bound;}"
    "return sad*64/max(n,1)*128+abs(mv.x)+abs(mv.y);}";
static inline bool ambient_svp_hdr_motion(pl_gpu gpu,pl_dispatch dp,pl_tex a,pl_tex b,pl_tex *field)
{
    int w=(a->params.w+15)/16,h=(a->params.h+15)/16;
    if(!ambient_svp_hdr_target(gpu,field,w,h))return false;
    struct pl_shader_desc d[]={
        {.desc={.name="svp_a",.type=PL_DESC_SAMPLED_TEX},.binding={.object=a}},
        {.desc={.name="svp_b",.type=PL_DESC_SAMPLED_TEX},.binding={.object=b}},
    };
    pl_shader sh=pl_dispatch_begin(dp);
    if(!sh)return false;
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,.descriptors=d,.num_descriptors=2,
        .header=ambient_svp_hdr_motion_header,.output_w=w,.output_h=h,
        .body="ivec2 base=ivec2(gl_FragCoord.xy)*16,mv=ivec2(0);int best=svp_cost(base,mv,2147483647);"
            "if(best!=0){for(int y=-16;y<=16;y+=4)for(int x=-16;x<=16;x+=4){ivec2 v=ivec2(x,y);"
            "int c=svp_cost(base,v,best);if(c<best){best=c;mv=v;}}ivec2 pred=mv;"
            "for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++){ivec2 v=pred+ivec2(x,y);"
            "int c=svp_cost(base,v,best);if(c<best){best=c;mv=v;}}}"
            "color=vec4(vec2(mv),float(best/512),1.);",
    })){pl_dispatch_abort(dp,&sh);return false;}
    return pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=*field));
}
static inline bool ambient_svp_hdr_synth(pl_gpu gpu,struct ambient_svp_hdr_display *s,
    pl_tex a,pl_tex b,float phase)
{
    int w=a->params.w,h=a->params.h;
    if(!ambient_svp_hdr_target(gpu,&s->output,w,h))return false;
    struct pl_shader_desc d[]={
        {.desc={.name="svp_a",.type=PL_DESC_SAMPLED_TEX},.binding={.object=a}},
        {.desc={.name="svp_b",.type=PL_DESC_SAMPLED_TEX},.binding={.object=b}},
        {.desc={.name="svp_f",.type=PL_DESC_SAMPLED_TEX},.binding={.object=s->fields[0]}},
        {.desc={.name="svp_r",.type=PL_DESC_SAMPLED_TEX},.binding={.object=s->fields[1]}},
    };
    struct pl_shader_var v={.var=pl_var_float("svp_phase"),.data=&phase,.dynamic=true};
    pl_shader sh=pl_dispatch_begin(s->dp);
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
    })){pl_dispatch_abort(s->dp,&sh);return false;}
    return pl_dispatch_finish(s->dp,pl_dispatch_params(.shader=&sh,.target=s->output));
}
static inline float ambient_svp_quant_lerp(float a,float b,float t,float scale)
{
    return roundf((a+(b-a)*t)*scale)/scale;
}
static inline bool ambient_svp_hdrplus_lerp(struct ambient_hdrplus *out,
    const struct ambient_hdrplus *a,const struct ambient_hdrplus *b,float t,int w,int h)
{
    if(!a->metadata_constraints_ok||!b->metadata_constraints_ok||a->version!=b->version||
        !a->window_count||a->window_count>3||a->window_count!=b->window_count)return false;
    *out=*a;
    out->target_nits=ambient_svp_quant_lerp(a->target_nits,b->target_nits,t,1);
    for(unsigned i=0;i<a->window_count;i++){
        struct ambient_hdrplus_window *o=&out->windows[i];
        const struct ambient_hdrplus_window *x=&a->windows[i],*y=&b->windows[i];
        if(x->histogram_count!=y->histogram_count||x->histogram_count>15||
            x->tone_mapping!=y->tone_mapping||x->anchor_count!=y->anchor_count||x->anchor_count>15||
            x->saturation_mapping!=y->saturation_mapping)return false;
        if(i){
            const struct ambient_hdrplus_geometry *g=&x->geometry,*k=&y->geometry;
            if(g->angle!=k->angle||g->overlap!=k->overlap)return false;
#define G(field) o->geometry.field=(uint16_t)roundf(g->field+(k->field-(float)g->field)*t)
            G(left);G(top);G(right);G(bottom);G(center_x);G(center_y);
            G(semimajor_inner);G(semimajor_outer);G(semiminor_outer);
#undef G
            if(o->geometry.left>=o->geometry.right||o->geometry.top>=o->geometry.bottom||
                o->geometry.right>=w||o->geometry.bottom>=h||o->geometry.center_x>=w||
                o->geometry.center_y>=h||!o->geometry.semimajor_inner||!o->geometry.semiminor_outer||
                o->geometry.semimajor_outer<o->geometry.semimajor_inner||
                o->geometry.semimajor_outer<o->geometry.semiminor_outer)return false;
        }
#define F(field,scale) o->field=ambient_svp_quant_lerp(x->field,y->field,t,scale)
        for(unsigned c=0;c<3;c++){F(maxscl[c],100000);}
        F(average,100000);F(fraction,1000);
        for(unsigned j=0;j<x->histogram_count;j++){
            if(x->histogram[j].percentage!=y->histogram[j].percentage)return false;
            F(histogram[j].percentile,100000);
        }
        if(x->tone_mapping){
            F(knee_x,4095);F(knee_y,4095);
            for(unsigned j=0;j<x->anchor_count;j++){F(anchors[j],1023);}
        }
        if(x->saturation_mapping){o->saturation_weight=(uint8_t)roundf(x->saturation_weight+
            (y->saturation_weight-(float)x->saturation_weight)*t);}
#undef F
        if(!ambient_hdrplus_distribution_ordered(o,out->version)||
            (o->tone_mapping&&!ambient_hdrplus_usable_curve(o,out->target_nits)))return false;
    }
    struct ambient_hdrplus_grid *grids[]={&out->targeted_grid,&out->mastering_grid};
    const struct ambient_hdrplus_grid *ag[]={&a->targeted_grid,&a->mastering_grid},
        *bg[]={&b->targeted_grid,&b->mastering_grid};
    for(unsigned i=0;i<2;i++){
        const struct ambient_hdrplus_grid *x=ag[i],*y=bg[i];
        if(x->rows!=y->rows||x->cols!=y->cols||x->rows>25||x->cols>25||
            (!!x->rows!=!!x->cols)||(x->rows&&x->rows<2)||(x->cols&&x->cols<2))return false;
        for(unsigned j=0;j<x->rows;j++)for(unsigned k=0;k<x->cols;k++){
            if(x->values[j][k]>15||y->values[j][k]>15)return false;
            grids[i]->values[j][k]=(uint8_t)roundf(x->values[j][k]+
                (y->values[j][k]-(float)x->values[j][k])*t);
        }
    }
    return true;
}
static inline bool ambient_svp_hdr_render_pair(pl_gpu gpu,pl_log log,
    struct ambient_svp_hdr_display *s,struct ambient_ahb_owner *owner,
    const struct ambient_svp_pair *pair,bool raw,bool dovi,struct pl_frame *img)
{
    if(!pair||!owner||!pair->a||!pair->b||!isfinite(pair->phase)||pair->phase<0.f||pair->phase>1.f)return false;
    if(!s->dp)s->dp=pl_dispatch_create(log,gpu);
    if(!s->dp)return false;
    pl_dispatch_reset_frame(s->dp);
    // Reuse the adjacent picture by its monotonic source ID. No cached
    // decoder lease or equality based on recycled picture addresses.
    if(s->sources[1].id==pair->a_id&&s->sources[0].id!=pair->a_id){
        struct ambient_svp_hdr_source tmp=s->sources[0];s->sources[0]=s->sources[1];s->sources[1]=tmp;
    }
    struct ambient_svp_hdr_source *a=&s->sources[0],*b=&s->sources[1];
    if(!ambient_svp_hdr_source_load(gpu,log,s,owner,a,pair->a,pair->a_id,raw,dovi))return false;
    bool same=pair->a_id==pair->b_id||pair->phase==0.f;
    if(!same&&!ambient_svp_hdr_source_load(gpu,log,s,owner,b,pair->b,pair->b_id,raw,dovi))return false;
    s->has_metadata=a->has_hdrplus;s->metadata_aligned=a->aligned;
    if(s->has_metadata)s->metadata=a->hdrplus;
    if(same){*img=ambient_pl_rgb_frame(a->texture,a->color);return true;}
    if(a->texture->params.w!=b->texture->params.w||a->texture->params.h!=b->texture->params.h||
        a->color.transfer!=b->color.transfer||a->color.primaries!=b->color.primaries||
        a->has_hdrplus!=b->has_hdrplus||
        (a->has_hdrplus&&(!a->aligned||!b->aligned||
            !ambient_svp_hdrplus_lerp(&s->metadata,&a->hdrplus,&b->hdrplus,pair->phase,
                a->texture->params.w,a->texture->params.h)))){
        // Keep exact source pixels/metadata on incompatible geometry,
        // window topology, or color contracts.
        if(a->has_hdrplus)s->metadata=a->hdrplus;
        *img=ambient_pl_rgb_frame(a->texture,a->color);return true;
    }
    if(s->field_a!=a->id||s->field_b!=b->id){
        s->field_a=s->field_b=0;
        if(!ambient_svp_hdr_motion(gpu,s->dp,a->texture,b->texture,&s->fields[0])||
            !ambient_svp_hdr_motion(gpu,s->dp,b->texture,a->texture,&s->fields[1]))return false;
        s->field_a=a->id;s->field_b=b->id;
    }
    if(!ambient_svp_hdr_synth(gpu,s,a->texture,b->texture,pair->phase))return false;
    *img=ambient_pl_rgb_frame(s->output,a->color);
    if(s->has_metadata){
        vlc_video_hdr_dynamic_metadata_t global;
        ambient_hdrplus_project_global(&global,&s->metadata);
        vlc_placebo_HdrMetadata(&global,&img->color.hdr);
    }else if(dovi){
        img->color.hdr.max_luma=fmaxf(a->color.hdr.max_luma,b->color.hdr.max_luma);
        img->color.hdr.min_luma=fminf(a->color.hdr.min_luma,b->color.hdr.min_luma);
    }
    return true;
}
#endif
