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
#include "svp_placebo_quality.h"
#include "svp_hdr_curve.h"
#include <stdio.h>
struct ambient_svp_hdr_source {
    uint64_t id;
    pl_tex texture;
    struct pl_color_space color;
    bool aligned,has_hdrplus;
    struct ambient_hdrplus hdrplus;
};
struct ambient_svp_hdr_display {
    struct ambient_svp_hdr_source sources[4];
    struct ambient_fel_render fel;
    pl_dispatch dp;
    pl_tex output;
    struct svp_placebo_quality quality;
    struct ambient_hdrplus metadata;
    bool has_metadata,metadata_aligned;
    struct ambient_svp_scene_config *scene;
    AmbientSvpHdrPass *scene_pass;
    void *scene_lib;
    int (*scene_interval)(const AmbientSvpHdrPass *,uint64_t,uint64_t *);
    int (*scene_lookup)(AmbientSvpHdrPass *,uint64_t,const uint32_t (*)[9],AmbientSvpHdrSceneResult *);
    int (*scene_finish)(AmbientSvpHdrPass *);
    void (*scene_destroy)(AmbientSvpHdrPass *);
    bool scene_finished;
    unsigned scene_version;
    int (*scene_lookup_legacy)(AmbientSvpHdrPass *,uint64_t,AmbientSvpHdrLegacyScene *);
};
static inline void ambient_svp_hdr_scene_close(pl_gpu gpu,struct ambient_svp_hdr_display *s,bool abort)
{
    if(abort&&!s->scene_finished){ambient_svp_scene_config_abort(s->scene);s->quality.capture_failed=true;}
    svp_placebo_quality_retire_texture(gpu,&s->quality,NULL);
    s->quality.capture_failed=false;
    if(s->scene_pass)s->scene_destroy(s->scene_pass);
    if(s->scene_lib)dlclose(s->scene_lib);
    ambient_svp_scene_config_release(s->scene);
    s->scene=NULL;s->scene_pass=NULL;s->scene_lib=NULL;s->scene_finished=false;
    s->scene_interval=NULL;s->scene_lookup=NULL;s->scene_lookup_legacy=NULL;
    s->scene_finish=NULL;s->scene_destroy=NULL;
}
static inline bool ambient_svp_hdr_scene_open(pl_gpu gpu,struct ambient_svp_hdr_display *s,
    const struct ambient_svp_pair *pair,int w,int h,unsigned version)
{
    if(s->scene!=pair->scene){
        ambient_svp_hdr_scene_close(gpu,s,true);
        if(!pair->scene)return true;
        s->scene=pair->scene;ambient_svp_scene_config_hold(s->scene);
        if(pair->scene_index!=0||version>1||atomic_load(&s->scene->aborted))goto fail;
        s->scene_version=version;
        pl_vulkan vk=pl_vulkan_get(gpu);
        if(!vk||!vk->get_proc_addr)goto fail;
        PFN_vkGetPhysicalDeviceProperties props=(PFN_vkGetPhysicalDeviceProperties)vk->get_proc_addr(vk->instance,"vkGetPhysicalDeviceProperties");
        if(!props)goto fail;
        VkPhysicalDeviceProperties device;props(vk->phys_device,&device);
        const AmbientSvpQuality *q=&pair->quality;
        char profile[384];
        int n=snprintf(profile,sizeof(profile),"vlc:1:%u:%u:%u:%lld:%lld:%u:%u:%u:%u:%u:%u:%u:%u",
            device.vendorID,device.deviceID,device.driverVersion,
            (long long)llround(s->scene->source*1000000.0),(long long)llround(s->scene->target*1000000.0),
            q->preset,q->block,q->overlap,q->pel,q->search,q->artifacts,q->scene,q->algorithm);
        if(n<=0||(size_t)n>=sizeof(profile))goto fail;
        s->scene_lib=dlopen("libambientsvp.so",RTLD_NOW|RTLD_LOCAL);
        if(!s->scene_lib)goto fail;
        AmbientSvpHdrPass *(*open)(uint32_t,uint32_t,const char *,const char *,const char *,const char *,uint32_t,uint32_t)=
            dlsym(s->scene_lib,"ambient_svp_hdr_pass_open_version");
        s->scene_lookup_legacy=dlsym(s->scene_lib,"ambient_svp_hdr_pass_lookup_legacy");
        s->scene_interval=dlsym(s->scene_lib,"ambient_svp_hdr_pass_interval");
        s->scene_lookup=dlsym(s->scene_lib,"ambient_svp_hdr_pass_lookup_v1");
        s->scene_finish=dlsym(s->scene_lib,"ambient_svp_hdr_pass_finish");
        s->scene_destroy=dlsym(s->scene_lib,"ambient_svp_hdr_pass_destroy");
        if(!open||!s->scene_lookup_legacy||!s->scene_interval||!s->scene_lookup||!s->scene_finish||!s->scene_destroy)goto fail;
        s->scene_pass=open(s->scene->mode,version,s->scene->plan,s->scene->cache,s->scene->key,profile,w,h);
        if(!s->scene_pass)goto fail;
    }
    return !s->scene||(s->scene_version==version&&s->scene_pass&&!s->scene_finished&&!atomic_load(&s->scene->aborted));
fail:
    ambient_svp_hdr_scene_close(gpu,s,true);return false;
}
static inline bool ambient_svp_hdr_scene_end(pl_gpu gpu,struct ambient_svp_hdr_display *s,const struct ambient_svp_pair *pair)
{
    if(s->quality.capture_failed)return false;
    if(!pair->scene_final)return true;
    svp_placebo_quality_retire_texture(gpu,&s->quality,NULL);
    if(s->quality.capture_failed)return false;
    if(!atomic_load(&s->scene->ended)||atomic_load(&s->scene->aborted)||s->scene_finish(s->scene_pass)!=1)return false;
    s->scene_finished=true;return true;
}
static inline void ambient_svp_hdr_display_destroy(pl_gpu gpu,struct ambient_svp_hdr_display *s)
{
    ambient_svp_hdr_scene_close(gpu,s,true);
    svp_placebo_quality_retire_texture(gpu,&s->quality,NULL);
    ambient_fel_render_destroy(gpu,&s->fel);
    for(unsigned i=0;i<4;i++){
        pl_tex_destroy(gpu,&s->sources[i].texture);
    }
    pl_tex_destroy(gpu,&s->output);svp_placebo_quality_destroy(gpu,&s->quality);pl_dispatch_destroy(&s->dp);
    *s=(struct ambient_svp_hdr_display){0};
}
static inline bool ambient_svp_hdr_target(pl_gpu gpu,struct svp_placebo_quality *s,pl_tex *tex,int w,int h)
{
    return svp_placebo_quality_target(gpu,s,tex,w,h,false);
}
static inline bool ambient_svp_hdr_copy(pl_gpu gpu,struct svp_placebo_quality *s,pl_dispatch dp,const struct pl_frame *img,
    pl_tex *out,bool raw)
{
    pl_tex input=img->planes[0].texture;
    if(img->num_planes!=1||!input||input==*out||
        !ambient_svp_hdr_target(gpu,s,out,input->params.w,input->params.h))return false;
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
    svp_placebo_quality_source_changed(gpu,&s->quality,dst->texture);
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
    if(ok)ok=ambient_svp_hdr_copy(gpu,&s->quality,s->dp,&img,&dst->texture,raw_copy);
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
            if(g->angle>180||k->angle>180||g->overlap!=k->overlap)return false;
            int delta=(int)k->angle-(int)g->angle;
            if(delta>90)delta-=180;
            if(delta< -90)delta+=180;
            o->geometry.angle=(uint8_t)(((int)lroundf(g->angle+t*delta)+180)%180);
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
static inline void ambient_svp_hdr_rect(uint32_t r[4],const struct ambient_hdrplus_geometry *g)
{
    r[0]=g->left;r[1]=g->top;r[2]=(uint32_t)g->right+1;r[3]=(uint32_t)g->bottom+1;
}
static inline bool ambient_svp_hdr_prepare_stats(struct svp_placebo_hdr *job,
    const struct ambient_hdrplus *m,const struct ambient_hdrplus *a,const struct ambient_hdrplus *b,int w,int h)
{
    if(w<2||h<2||w>65536||h>65536||m->window_count<1||m->window_count>3)return false;
    *job=(struct svp_placebo_hdr){.count=m->window_count};
    for(unsigned i=0;i<job->count;i++){
        const struct ambient_hdrplus_window *p=&m->windows[i];
        if(p->histogram_count>15)return false;
        job->windows[i].rect[2]=w;job->windows[i].rect[3]=h;
        job->windows[i].count=p->histogram_count;
        for(unsigned j=0;j<p->histogram_count;j++)job->windows[i].percentages[j]=p->histogram[j].percentage;
        if(i){
            const struct ambient_hdrplus_geometry *g=&p->geometry;
            ambient_svp_hdr_rect(job->windows[i].rect,g);
            ambient_svp_hdr_rect(job->tracks[i].a,&a->windows[i].geometry);
            ambient_svp_hdr_rect(job->tracks[i].b,&b->windows[i].geometry);
            job->tracks[i].ellipse[0]=g->center_x;job->tracks[i].ellipse[1]=g->center_y;
            job->axes[i][0]=g->semimajor_outer;job->axes[i][1]=g->semiminor_outer;job->axes[i][2]=g->angle;
        }
    }
    return true;
}
static inline bool ambient_svp_hdr_apply_geometry(struct ambient_hdrplus *m,
    const struct svp_placebo_hdr *job,int w,int h)
{
    if(job->count!=m->window_count)return false;
    struct ambient_hdrplus updated=*m;
    for(unsigned i=1;i<job->count;i++){
        struct ambient_hdrplus_window *p=&updated.windows[i];
        const AmbientSvpHdrResult *r=&job->results[i];
        if(!r->pixels)return false;
        {
            struct ambient_hdrplus_geometry *g=&p->geometry;
            const uint32_t *box=job->rects[i],*e=job->ellipses[i];
            int x=(int)g->center_x+job->shifts[i][0],y=(int)g->center_y+job->shifts[i][1];
            if(box[0]+1>=box[2]||box[1]+1>=box[3]||box[2]>(uint32_t)w||box[3]>(uint32_t)h||
                x<0||y<0||x>=w||y>=h||!e[0]||e[0]>65535||!e[1]||e[1]>e[0]||e[2]>180||
                !g->semimajor_outer||(uint64_t)(box[2]-box[0])*(box[3]-box[1])<r->pixels)return false;
            unsigned inner=(unsigned)lround((double)g->semimajor_inner*e[0]/g->semimajor_outer);
            if(!inner||inner>e[0])return false;
            g->left=box[0];g->top=box[1];g->right=box[2]-1;g->bottom=box[3]-1;
            g->center_x=x;g->center_y=y;g->semimajor_inner=inner;g->semimajor_outer=e[0];g->semiminor_outer=e[1];g->angle=e[2];
        }
    }
    *m=updated;return true;
}
static inline bool ambient_svp_hdr_apply_stats(struct ambient_hdrplus *m,
    const struct svp_placebo_hdr *job,int w,int h)
{
    if(job->count!=m->window_count)return false;
    for(unsigned i=0;i<job->count;i++){
        struct ambient_hdrplus_window *p=&m->windows[i];
        const AmbientSvpHdrResult *r=&job->results[i];
        if(!r->pixels||r->average>100000||r->brightest>r->pixels)return false;
        if(i){
            struct ambient_hdrplus_geometry *g=&p->geometry;
            const uint32_t *box=job->rects[i],*e=job->ellipses[i];
            int x=(int)g->center_x+job->shifts[i][0],y=(int)g->center_y+job->shifts[i][1];
            if(box[0]+1>=box[2]||box[1]+1>=box[3]||box[2]>(uint32_t)w||box[3]>(uint32_t)h||
                x<0||y<0||x>=w||y>=h||!e[0]||e[0]>65535||!e[1]||e[1]>e[0]||e[2]>180||
                !g->semimajor_outer||(uint64_t)(box[2]-box[0])*(box[3]-box[1])<r->pixels)return false;
            unsigned inner=(unsigned)lround((double)g->semimajor_inner*e[0]/g->semimajor_outer);
            if(!inner||inner>e[0])return false;
            g->left=box[0];g->top=box[1];g->right=box[2]-1;g->bottom=box[3]-1;
            g->center_x=x;g->center_y=y;g->semimajor_inner=inner;g->semimajor_outer=e[0];g->semiminor_outer=e[1];g->angle=e[2];
        }
        uint32_t peak=0;
        for(unsigned c=0;c<3;c++){
            if(r->maxscl[c]>100000)return false;
            if(r->maxscl[c]>peak)peak=r->maxscl[c];
            p->maxscl[c]=r->maxscl[c]/100000.f;
        }
        if(r->average>peak)return false;
        p->average=r->average/100000.f;p->fraction=0.f;
        uint32_t highlight=peak;
        bool reserved=ambient_hdrplus_reserved_markers(p,m->version);
        for(unsigned j=0;j<p->histogram_count;j++){
            if(r->percentiles[j]>peak||(j&&r->percentiles[j]<r->percentiles[j-1]))return false;
            p->histogram[j].percentile=reserved&&(j==1||j==2)?(j==1?0.f:255.f/100000.f):r->percentiles[j]/100000.f;
            if(p->histogram[j].percentage>=90)highlight=r->percentiles[j];
        }
        SvpHdrCurve curve;
        if(!svp_hdr_curve(peak,r->average,highlight,m->target_nits,&curve))return false;
        p->tone_mapping=curve.count!=0;p->anchor_count=curve.count;
        p->knee_x=curve.knee_x/4095.f;p->knee_y=curve.knee_y/4095.f;
        memset(p->anchors,0,sizeof(p->anchors));
        for(unsigned j=0;j<curve.count;j++)p->anchors[j]=curve.anchors[j]/1023.f;
        if(!ambient_hdrplus_distribution_ordered(p,m->version)||
            (p->tone_mapping&&!ambient_hdrplus_usable_curve(p,m->target_nits)))return false;
    }
    return true;
}
static inline bool ambient_svp_hdr_scene_compatible(const struct ambient_hdrplus *m)
{
    if(!m->metadata_constraints_ok||m->version>1||m->window_count<1||m->window_count>3)return false;
    if(m->version==0)return true;
    if(m->window_count!=1||m->windows[0].histogram_count!=9)return false;
    for(unsigned j=0;j<9;j++)if(m->windows[0].histogram[j].percentage>100||
        (j&&m->windows[0].histogram[j].percentage<=m->windows[0].histogram[j-1].percentage))return false;
    return true;
}
static inline bool ambient_svp_hdr_apply_scene(struct ambient_hdrplus *m,const AmbientSvpHdrSceneResult *r)
{
    if(!ambient_svp_hdr_scene_compatible(m)||m->version!=1||!r)return false;
    struct ambient_hdrplus_window *p=&m->windows[0];
    uint32_t schema[9];for(unsigned j=0;j<9;j++)schema[j]=p->histogram[j].percentage;
    if(!svp_hdr_scene_values_schema(r->maxscl,r->average,r->percentiles,r->fraction,schema))return false;
    uint32_t peak=0;
    for(unsigned j=0;j<3;j++){if(r->maxscl[j]>100000)return false;p->maxscl[j]=r->maxscl[j]/100000.f;if(r->maxscl[j]>peak)peak=r->maxscl[j];}
    p->average=r->average/100000.f;p->fraction=r->fraction/1000.f;
    for(unsigned j=0;j<9;j++)p->histogram[j].percentile=r->percentiles[j]/100000.f;
    SvpHdrCurve curve={0};
    if(!isfinite(m->target_nits)||m->target_nits<0||(!m->target_nits&&p->tone_mapping)||
        (m->target_nits&&!svp_hdr_curve(peak,r->average,r->percentiles[8],m->target_nits,&curve)))return false;
    p->tone_mapping=curve.count!=0;p->anchor_count=curve.count;
    p->knee_x=curve.knee_x/4095.f;p->knee_y=curve.knee_y/4095.f;
    memset(p->anchors,0,sizeof(p->anchors));
    for(unsigned j=0;j<curve.count;j++)p->anchors[j]=curve.anchors[j]/1023.f;
    return ambient_hdrplus_distribution_ordered(p,m->version)&&
        (!p->tone_mapping||ambient_hdrplus_usable_curve(p,m->target_nits));
}
static inline bool ambient_svp_hdr_apply_legacy_scene(struct ambient_hdrplus *m,const AmbientSvpHdrLegacyScene *r)
{
    if(!ambient_svp_hdr_scene_compatible(m)||m->version!=0||!r||r->count!=m->window_count)return false;
    struct ambient_hdrplus updated=*m;
    for(unsigned w=0;w<r->count;w++){
        struct ambient_hdrplus_window *p=&updated.windows[w];
        const AmbientSvpHdrLegacyWindow *v=&r->windows[w];
        if(v->count>15||v->count!=p->histogram_count||v->fraction)return false;
        uint32_t peak=0,last=0;
        for(unsigned c=0;c<3;c++){
            if(v->maxscl[c]>100000)return false;
            if(v->maxscl[c]>peak)peak=v->maxscl[c];
            p->maxscl[c]=v->maxscl[c]/100000.f;
        }
        if(v->average>peak)return false;
        p->average=v->average/100000.f;p->fraction=0.f;
        for(unsigned j=0;j<v->count;j++){
            if(v->percentages[j]!=p->histogram[j].percentage||v->percentiles[j]<last||v->percentiles[j]>peak)return false;
            last=v->percentiles[j];p->histogram[j].percentile=last/100000.f;
        }
        SvpHdrCurve curve={0};
        if(!isfinite(updated.target_nits)||updated.target_nits<0||
            (updated.target_nits&&!svp_hdr_curve(peak,v->average,v->count?last:peak,updated.target_nits,&curve)))return false;
        p->tone_mapping=curve.count!=0;p->anchor_count=curve.count;
        p->knee_x=curve.knee_x/4095.f;p->knee_y=curve.knee_y/4095.f;
        memset(p->anchors,0,sizeof(p->anchors));
        for(unsigned j=0;j<curve.count;j++)p->anchors[j]=curve.anchors[j]/1023.f;
        if(!ambient_hdrplus_distribution_ordered(p,0)||(p->tone_mapping&&!ambient_hdrplus_usable_curve(p,updated.target_nits)))return false;
    }
    *m=updated;return true;
}
static inline bool ambient_svp_hdr_scene_replay(struct ambient_svp_hdr_display *s,uint64_t index)
{
    if(s->scene_version==0){
        AmbientSvpHdrLegacyScene r;
        return s->scene_lookup_legacy(s->scene_pass,index,&r)==1&&ambient_svp_hdr_apply_legacy_scene(&s->metadata,&r);
    }
    AmbientSvpHdrSceneResult r;
    uint32_t schema[9];for(unsigned j=0;j<9;j++)schema[j]=s->metadata.windows[0].histogram[j].percentage;
    return s->scene_lookup(s->scene_pass,index,&schema,&r)==1&&ambient_svp_hdr_apply_scene(&s->metadata,&r);
}
static inline bool ambient_svp_hdr_render_pair(pl_gpu gpu,pl_log log,
    struct ambient_svp_hdr_display *s,struct ambient_ahb_owner *owner,
    const struct ambient_svp_pair *pair,bool raw,bool dovi,struct pl_frame *img)
{
    if(!pair||!owner||!pair->a||!pair->b||!isfinite(pair->phase)||pair->phase<0.f||pair->phase>1.f)return false;
    if(!s->dp)s->dp=pl_dispatch_create(log,gpu);
    if(!s->dp)return false;
    pl_dispatch_reset_frame(s->dp);
    // Reorder the cached reconstructed textures using immutable source
    // IDs, including the two adjacent pictures for algorithm 23.
    bool extended=ambient_svp_quality_extended(&pair->quality)&&pair->past&&pair->future;
    uint64_t ids[4]={pair->a_id,pair->b_id,pair->past_id,pair->future_id};
    memcpy(s->quality.source_ids,ids,sizeof(ids));
    for(unsigned i=0;i<(extended?4u:2u);i++){
        for(unsigned j=i+1;j<4&&s->sources[i].id!=ids[i];j++){
            if(s->sources[j].id!=ids[i])continue;
            struct ambient_svp_hdr_source tmp=s->sources[i];s->sources[i]=s->sources[j];s->sources[j]=tmp;break;
        }
    }
    struct ambient_svp_hdr_source *a=&s->sources[0],*b=&s->sources[1];
    if(!ambient_svp_hdr_source_load(gpu,log,s,owner,a,pair->a,pair->a_id,raw,dovi)){ambient_svp_scene_config_abort(pair->scene);ambient_svp_hdr_scene_close(gpu,s,true);return false;}
    bool same=pair->a_id==pair->b_id||pair->phase==0.f;
    if(!same&&!ambient_svp_hdr_source_load(gpu,log,s,owner,b,pair->b,pair->b_id,raw,dovi)){
        ambient_svp_scene_config_abort(pair->scene);ambient_svp_hdr_scene_close(gpu,s,true);return false;
    }
    s->has_metadata=a->has_hdrplus;s->metadata_aligned=a->aligned;
    if(s->has_metadata)s->metadata=a->hdrplus;
    int w=a->texture->params.w,h=a->texture->params.h;
    bool scene=pair->scene!=NULL;
    if(!ambient_svp_hdr_scene_open(gpu,s,pair,w,h,a->has_hdrplus?a->hdrplus.version:2)||s->quality.capture_failed)goto original;
    if(scene){
        uint64_t boundary;
        if(s->scene_interval(s->scene_pass,pair->scene_index,&boundary)!=1||
            pair->scene_pair_end<=pair->scene_index||pair->scene_pair_end>boundary)goto original;
        if(pair->scene_pair_end==boundary)same=true;
    }
    if(scene&&(!s->has_metadata||!a->aligned||dovi||a->color.transfer!=PL_COLOR_TRC_PQ||
        a->color.primaries!=PL_COLOR_PRIM_BT_2020||!ambient_svp_hdr_scene_compatible(&a->hdrplus)||
        (!same&&(!b->has_hdrplus||!b->aligned||b->color.transfer!=a->color.transfer||
            b->color.primaries!=a->color.primaries||!ambient_svp_hdr_scene_compatible(&b->hdrplus)||
            b->hdrplus.version!=s->scene_version))))goto original;
    if(same){
        *img=ambient_pl_rgb_frame(a->texture,a->color);
        if(scene){
            if(s->scene->mode==1){
                struct svp_placebo_hdr job;
                if(!ambient_svp_hdr_prepare_stats(&job,&s->metadata,&a->hdrplus,&a->hdrplus,w,h)||
                    !svp_placebo_quality_render_pass(gpu,&s->quality,a->texture,a->texture,NULL,NULL,
                        &s->output,0.f,&pair->quality,&job,s->scene_pass,pair->scene_index,true))goto original;
                *img=ambient_pl_rgb_frame(s->output,a->color);
            }else{
                if(s->scene_version==0){
                    struct svp_placebo_hdr job;
                    if(!ambient_svp_hdr_prepare_stats(&job,&s->metadata,&a->hdrplus,&a->hdrplus,w,h)||
                        !svp_placebo_quality_render_pass(gpu,&s->quality,a->texture,a->texture,NULL,NULL,
                            &s->output,0.f,&pair->quality,&job,s->scene_pass,pair->scene_index,false)||
                        !ambient_svp_hdr_apply_geometry(&s->metadata,&job,w,h))goto original;
                    *img=ambient_pl_rgb_frame(s->output,a->color);
                }
                if(!ambient_svp_hdr_scene_replay(s,pair->scene_index))goto original;
                vlc_video_hdr_dynamic_metadata_t global;
                ambient_hdrplus_project_global(&global,&s->metadata);
                vlc_placebo_HdrMetadata(&global,&img->color.hdr);
            }
            if(!ambient_svp_hdr_scene_end(gpu,s,pair))goto original;
        }
        return true;
    }
    if(a->texture->params.w!=b->texture->params.w||a->texture->params.h!=b->texture->params.h||
        a->color.transfer!=b->color.transfer||a->color.primaries!=b->color.primaries||
        a->has_hdrplus!=b->has_hdrplus||
        (a->has_hdrplus&&(!a->aligned||!b->aligned||
            ((!scene||s->scene_version==0)&&!ambient_svp_hdrplus_lerp(&s->metadata,&a->hdrplus,&b->hdrplus,pair->phase,
                a->texture->params.w,a->texture->params.h))))){
        // Keep exact source pixels/metadata on incompatible geometry,
        // window topology, or color contracts.
        goto original;
    }
    if(extended){
        extended=ambient_svp_hdr_source_load(gpu,log,s,owner,&s->sources[2],pair->past,pair->past_id,raw,dovi)&&
            ambient_svp_hdr_source_load(gpu,log,s,owner,&s->sources[3],pair->future,pair->future_id,raw,dovi);
        for(unsigned i=2;extended&&i<4;i++)
            extended=s->sources[i].color.transfer==a->color.transfer&&s->sources[i].color.primaries==a->color.primaries;
    }
    struct svp_placebo_hdr job;
    bool capture=scene&&s->scene->mode==1;
    bool per_frame=s->has_metadata&&!scene;
    bool legacy=scene&&s->scene_version==0;
    if((s->has_metadata&&(a->color.transfer!=PL_COLOR_TRC_PQ||a->color.primaries!=PL_COLOR_PRIM_BT_2020||
            !ambient_svp_hdr_prepare_stats(&job,&s->metadata,&a->hdrplus,scene&&!legacy?&a->hdrplus:&b->hdrplus,w,h)))||
        !svp_placebo_quality_render_pass(gpu,&s->quality,a->texture,b->texture,
        extended?s->sources[2].texture:NULL,extended?s->sources[3].texture:NULL,
        &s->output,pair->phase,&pair->quality,(capture||per_frame||legacy)?&job:NULL,(capture||legacy)?s->scene_pass:NULL,pair->scene_index,capture)||
        (per_frame&&!ambient_svp_hdr_apply_stats(&s->metadata,&job,w,h))){
        // Preserve the exact reconstructed picture and its metadata
        // if the renderer cannot share the native Vulkan queue.
        goto original;
    }
    if(scene){
        if(!capture){
            if(legacy&&!ambient_svp_hdr_apply_geometry(&s->metadata,&job,w,h))goto original;
            if(!ambient_svp_hdr_scene_replay(s,pair->scene_index))goto original;
        }
        if(!ambient_svp_hdr_scene_end(gpu,s,pair))goto original;
    }
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
original:
    if(scene){ambient_svp_scene_config_abort(pair->scene);ambient_svp_hdr_scene_close(gpu,s,true);}
    if(a->has_hdrplus)s->metadata=a->hdrplus;
    *img=ambient_pl_rgb_frame(a->texture,a->color);return true;
}
#endif
