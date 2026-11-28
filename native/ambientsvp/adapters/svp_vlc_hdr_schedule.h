/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Pre-clock temporal scheduling for the PR #510 libplacebo HDR renderer.
 * No conversion, copying or RPU/EL timestamp rewriting at this boundary.
 */
#ifndef AMBIENTSVP_VLC_HDR_SCHEDULE_H
#define AMBIENTSVP_VLC_HDR_SCHEDULE_H
#include "svp_vlc_hdr_pair.h"
#include "../video_output/android/utils.h"
#include <stdatomic.h>
typedef struct {
    SvpApi api;
    picture_t *previous,*older,*lookahead;
    uint64_t previous_id,older_id,lookahead_id,tick;
    vlc_tick_t origin;
    double source,target;
    AmbientSvpQuality quality;
    struct ambient_svp_scene_config *scene;
} SvpHdrSchedule;
static atomic_uint_fast64_t svp_hdr_next_id=1;
static inline uint64_t svp_hdr_new_id(void){return atomic_fetch_add(&svp_hdr_next_id,1);}
static void svp_hdr_flush(filter_t *f)
{
    SvpHdrSchedule *s=f->p_sys;
    ambient_svp_scene_config_abort(s->scene);
    if(s->previous)picture_Release(s->previous);
    if(s->older)picture_Release(s->older);
    if(s->lookahead)picture_Release(s->lookahead);
    s->previous=s->older=s->lookahead=NULL;s->tick=0;
    s->previous_id=s->older_id=s->lookahead_id=0;
}
static void svp_hdr_close(filter_t *f)
{
    SvpHdrSchedule *s=f->p_sys;
    if(s->scene&&atomic_load(&s->scene->ended)){
        ambient_svp_scene_config_release(s->scene);s->scene=NULL;
    }
    svp_hdr_flush(f);
    ambient_svp_scene_config_release(s->scene);s->scene=NULL;
    dlclose(s->api.lib);
    vlc_video_context_Release(f->vctx_out);f->vctx_out=NULL;
}
// Match the actual AHB import contract, not every nominal SDR transfer.
// Ambiguous colorimetry must use the existing copy-back path.
static bool svp_bridge_sdr(const video_format_t *v)
{
    return v->primaries==COLOR_PRIMARIES_BT709&&v->transfer==TRANSFER_FUNC_BT709&&
        v->space==COLOR_SPACE_BT709&&(v->color_range==COLOR_RANGE_FULL||v->color_range==COLOR_RANGE_LIMITED)&&
        !v->mastering.max_luminance&&!v->lighting.MaxCLL&&!v->lighting.MaxFALL&&
        !v->dovi.profile&&!v->dovi.rpu_present&&!v->dovi.el_present;
}
static bool svp_hdr_eligible(picture_t *p)
{
    if(!p||!p->context||!p->b_progressive||p->date<=0||p->format.i_chroma!=VLC_CODEC_ANDROID_OPAQUE||
        p->format.orientation!=ORIENT_NORMAL||p->format.projection_mode!=PROJECTION_MODE_RECTANGULAR||
        picture_GetAncillary(p,AMBIENT_SVP_PAIR_ID))return false;
    if(svp_bridge_sdr(&p->format))
        return !picture_GetAncillary(p,VLC_ANCILLARY_ID_HDR10PLUS)&&!picture_GetAncillary(p,VLC_ANCILLARY_ID_DOVI);
    return p->format.transfer==TRANSFER_FUNC_SMPTE_ST2084||p->format.transfer==TRANSFER_FUNC_HLG||
        p->format.dovi.profile!=0;
}
static picture_t *svp_hdr_fallback(SvpHdrSchedule *s,picture_t *pic)
{
    ambient_svp_scene_config_abort(s->scene);
    picture_t *head=s->previous,**tail=&head;
    if(head)tail=&head->p_next;
    if(s->lookahead){*tail=s->lookahead;tail=&s->lookahead->p_next;}
    *tail=pic;
    if(s->older)picture_Release(s->older);
    s->previous=s->older=s->lookahead=NULL;s->tick=0;
    s->previous_id=s->older_id=s->lookahead_id=0;
    return head;
}
static bool svp_hdr_compatible(picture_t *old,picture_t *next)
{
    const video_format_t *a=&old->format,*b=&next->format;
    return a->transfer==b->transfer&&a->primaries==b->primaries&&a->space==b->space&&
        a->color_range==b->color_range&&a->dovi.profile==b->dovi.profile&&
        a->i_visible_width==b->i_visible_width&&a->i_visible_height==b->i_visible_height&&
        a->i_x_offset==b->i_x_offset&&a->i_y_offset==b->i_y_offset;
}
static picture_t *svp_hdr_filter(filter_t *f,picture_t *pic)
{
    SvpHdrSchedule *s=f->p_sys;
    if(!s->api.enabled()||(pic&&!svp_hdr_eligible(pic)))return svp_hdr_fallback(s,pic);
    bool extended=ambient_svp_quality_extended(&s->quality);
    if(!s->previous){
        if(!pic)return NULL;
        s->previous=pic;s->previous_id=svp_hdr_new_id();s->origin=pic->date;s->tick=0;return NULL;
    }
    // Drain both queued originals in timestamp order when no future
    // picture can be obtained. Never lose a delayed decoder lease.
    if(extended&&!pic&&!s->scene)return svp_hdr_fallback(s,NULL);
    if(extended&&!pic&&!s->lookahead)extended=false;
    double expected=1000000.0/s->source;
    picture_t *last=s->lookahead?s->lookahead:s->previous;
    if(pic){
        double delta=US_FROM_VLC_TICK(pic->date-last->date);
        if(!svp_hdr_compatible(last,pic)||delta<=0||fabs(delta-expected)>fmax(3000.0,expected*.25))
            return svp_hdr_fallback(s,pic);
    }
    uint64_t id=pic?svp_hdr_new_id():s->previous_id;
    if(extended&&!s->lookahead&&pic){s->lookahead=pic;s->lookahead_id=id;return NULL;}
    picture_t *center=extended?s->lookahead:pic;
    uint64_t center_id=extended?s->lookahead_id:id;
    vlc_tick_t end=center?center->date:s->previous->date+VLC_TICK_FROM_US(llround(expected));
    double delta=US_FROM_VLC_TICK(end-s->previous->date);
    if(delta<=0||fabs(delta-expected)>fmax(3000.0,expected*.25))return svp_hdr_fallback(s,pic);
    uint64_t next=s->tick;
    picture_t *head=NULL,**tail=&head;
    unsigned count=0;
    while(next<UINT64_C(1000000000)){
        vlc_tick_t time=s->origin+VLC_TICK_FROM_US(llround((double)next*1000000.0/s->target));
        // Match the Android scene planner and MPV's microsecond boundary
        // tolerance. Leave ordinary single-pass scheduling unchanged.
        if(time>=end-(s->scene?VLC_TICK_FROM_US(1):0))break;
        double phase=(double)(time-s->previous->date)/(double)(end-s->previous->date);
        if(count==8||phase<-.25||phase>1.)goto fail;
        picture_t *out=picture_Clone(s->previous);
        if(!out)goto fail;
        if(!ambient_svp_attach_pair(out,s->previous,center?center:s->previous,s->previous_id,center_id,
            (float)fmax(0.,phase),&s->quality,extended?s->older:NULL,s->older_id,extended?pic:NULL,id)){
            picture_Release(out);goto fail;
        }
        if(!ambient_svp_attach_scene(out,s->scene,next,false)){picture_Release(out);goto fail;}
        out->date=time;out->p_next=NULL;
        *tail=out;tail=&out->p_next;next++;count++;
    }
    if(!count)goto fail;
    float phases[8]={0};
    unsigned slot=0;
    for(picture_t *out=head;out;out=out->p_next){
        struct vlc_ancillary *anc=picture_GetAncillary(out,AMBIENT_SVP_PAIR_ID);
        struct ambient_svp_pair *pair=anc?vlc_ancillary_GetData(anc):NULL;
        if(!pair||slot>=count)goto fail;
        phases[slot++]=pair->phase;
    }
    if(slot!=count)goto fail;
    slot=0;
    for(picture_t *out=head;out;out=out->p_next){
        struct ambient_svp_pair *pair=vlc_ancillary_GetData(picture_GetAncillary(out,AMBIENT_SVP_PAIR_ID));
        pair->group_first=s->tick;pair->phase_slot=slot++;pair->phase_count=count;
        memcpy(pair->group_phases,phases,sizeof(phases));
        if(s->scene){
            pair->scene_pair_end=next;
            pair->scene_final=!pic&&!center&&!out->p_next;
        }
    }
    if(extended){
        if(s->older)picture_Release(s->older);
        s->older=s->previous;s->older_id=s->previous_id;
        s->previous=center;s->previous_id=center_id;s->lookahead=pic;s->lookahead_id=id;
    }else{
        picture_Release(s->previous);s->previous=pic;s->previous_id=id;
    }
    s->tick=next;
    if(!pic&&s->previous){
        // Drain the last interval too, without a future reference.
        picture_t *rest=svp_hdr_filter(f,NULL);
        *tail=rest;return head;
    }
    if(!pic){
        if(s->scene)atomic_store(&s->scene->ended,true);
        s->tick=s->previous_id=0;
    }
    return head;
fail:
    while(head){picture_t *nextpic=head->p_next;head->p_next=NULL;picture_Release(head);head=nextpic;}
    return svp_hdr_fallback(s,pic);
}
static const struct vlc_filter_operations svp_hdr_ops={
    .filter_video=svp_hdr_filter,.flush=svp_hdr_flush,.close=svp_hdr_close,
};
static int svp_android_open(filter_t *f)
{
    const video_format_t *v=&f->fmt_in.video;
    if(!var_InheritBool(f,"ambientsvp-hwresident")||!var_InheritBool(f,"vk-ambient-ahb")||
        !f->vctx_in||vlc_video_context_GetType(f->vctx_in)!=VLC_VIDEO_CONTEXT_AWINDOW||
        v->i_chroma!=VLC_CODEC_ANDROID_OPAQUE||f->fmt_out.video.i_chroma!=v->i_chroma||
        v->orientation!=ORIENT_NORMAL||v->projection_mode!=PROJECTION_MODE_RECTANGULAR||
        v->i_visible_width<32||v->i_visible_height<32||
        v->i_visible_width>3840||v->i_visible_height>2160||
        ((uint64_t)v->i_visible_width*v->i_visible_height>1920u*1080u&&!var_InheritInteger(f,"ambientsvp-hdr-pass"))||
        !v->i_frame_rate||!v->i_frame_rate_base||
        (!svp_bridge_sdr(v)&&v->transfer!=TRANSFER_FUNC_SMPTE_ST2084&&v->transfer!=TRANSFER_FUNC_HLG&&!v->dovi.profile)||
        (svp_bridge_sdr(v)&&var_InheritInteger(f,"ambientsvp-hdr-pass")))
        return VLC_EGENERIC;
    android_video_context_t *ctx=vlc_video_context_GetPrivate(f->vctx_in,VLC_VIDEO_CONTEXT_AWINDOW);
    if(!ctx||!ctx->air||!ctx->air_api)return VLC_EGENERIC;
    SvpHdrSchedule *s=vlc_obj_calloc(VLC_OBJECT(f),1,sizeof(*s));
    if(!s)return VLC_ENOMEM;
    s->source=(double)v->i_frame_rate/v->i_frame_rate_base;
    s->target=var_InheritFloat(f,"ambientsvp-fps");
    if(!(s->target>s->source&&s->target/s->source<=8)||!svp_load(&s->api))return VLC_EGENERIC;
    s->quality=(AmbientSvpQuality){.preset=var_InheritInteger(f,"ambientsvp-preset"),
        .block=var_InheritInteger(f,"ambientsvp-block"),.overlap=var_InheritInteger(f,"ambientsvp-overlap"),
        .pel=var_InheritInteger(f,"ambientsvp-pel"),.search=var_InheritInteger(f,"ambientsvp-search"),
        .artifacts=var_InheritInteger(f,"ambientsvp-artifacts"),.scene=var_InheritInteger(f,"ambientsvp-scene"),
        .algorithm=var_InheritInteger(f,"ambientsvp-algo")};
    unsigned mode=var_InheritInteger(f,"ambientsvp-hdr-pass");
    if(mode){
        char *plan=var_InheritString(f,"ambientsvp-hdr-plan");
        char *cache=var_InheritString(f,"ambientsvp-hdr-cache");
        char *key=var_InheritString(f,"ambientsvp-hdr-key");
        s->scene=ambient_svp_scene_config_new(mode,s->source,s->target,plan,cache,key);
        free(plan);free(cache);free(key);
        if(!s->scene){dlclose(s->api.lib);return VLC_EGENERIC;}
    }
    f->p_sys=s;f->vctx_out=f->vctx_in;vlc_video_context_Hold(f->vctx_out);
    f->fmt_out.video.i_frame_rate=llround(s->target*1000.0);
    f->fmt_out.video.i_frame_rate_base=1000;f->ops=&svp_hdr_ops;
    msg_Info(f,"SVP metadata-aware renderer scheduling %.3f -> %.3f",s->source,s->target);
    return VLC_SUCCESS;
}
#endif
