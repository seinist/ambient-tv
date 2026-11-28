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
    picture_t *previous;
    uint64_t previous_id,tick;
    vlc_tick_t origin;
    double source,target;
    AmbientSvpQuality quality;
} SvpHdrSchedule;
static atomic_uint_fast64_t svp_hdr_next_id=1;
static inline uint64_t svp_hdr_new_id(void){return atomic_fetch_add(&svp_hdr_next_id,1);}
static void svp_hdr_flush(filter_t *f)
{
    SvpHdrSchedule *s=f->p_sys;
    if(s->previous)picture_Release(s->previous);
    s->previous=NULL;s->tick=0;s->previous_id=0;
}
static void svp_hdr_close(filter_t *f)
{
    SvpHdrSchedule *s=f->p_sys;
    svp_hdr_flush(f);
    dlclose(s->api.lib);
    vlc_video_context_Release(f->vctx_out);f->vctx_out=NULL;
}
static bool svp_hdr_eligible(picture_t *p)
{
    if(!p||!p->context||!p->b_progressive||p->date<=0||p->format.i_chroma!=VLC_CODEC_ANDROID_OPAQUE||
        p->format.orientation!=ORIENT_NORMAL||p->format.projection_mode!=PROJECTION_MODE_RECTANGULAR||
        picture_GetAncillary(p,AMBIENT_SVP_PAIR_ID))return false;
    return p->format.transfer==TRANSFER_FUNC_SMPTE_ST2084||p->format.transfer==TRANSFER_FUNC_HLG||
        p->format.dovi.profile!=0;
}
static picture_t *svp_hdr_fallback(SvpHdrSchedule *s,picture_t *pic)
{
    picture_t *old=s->previous;s->previous=NULL;s->tick=0;s->previous_id=0;
    if(old){old->p_next=pic;return old;}return pic;
}
static picture_t *svp_hdr_filter(filter_t *f,picture_t *pic)
{
    SvpHdrSchedule *s=f->p_sys;
    if(!s->api.enabled()||(pic&&!svp_hdr_eligible(pic)))return svp_hdr_fallback(s,pic);
    if(!s->previous){
        if(!pic)return NULL;
        s->previous=pic;s->previous_id=svp_hdr_new_id();s->origin=pic->date;s->tick=0;return NULL;
    }
    const video_format_t *a=&s->previous->format,*b=pic?&pic->format:a;
    if(a->transfer!=b->transfer||a->primaries!=b->primaries||a->space!=b->space||
        a->color_range!=b->color_range||a->dovi.profile!=b->dovi.profile||
        a->i_visible_width!=b->i_visible_width||a->i_visible_height!=b->i_visible_height||
        a->i_x_offset!=b->i_x_offset||a->i_y_offset!=b->i_y_offset)
        return svp_hdr_fallback(s,pic);
    double expected=1000000.0/s->source;
    vlc_tick_t end=pic?pic->date:s->previous->date+VLC_TICK_FROM_US(llround(expected));
    double delta=US_FROM_VLC_TICK(end-s->previous->date);
    if(delta<=0||fabs(delta-expected)>fmax(3000.0,expected*.25))
        return svp_hdr_fallback(s,pic);
    uint64_t next=s->tick,id=pic?svp_hdr_new_id():s->previous_id;
    picture_t *head=NULL,**tail=&head;
    unsigned count=0;
    while(next<UINT64_C(1000000000)){
        vlc_tick_t time=s->origin+VLC_TICK_FROM_US(llround((double)next*1000000.0/s->target));
        if(time>=end)break;
        double phase=(double)(time-s->previous->date)/(double)(end-s->previous->date);
        if(count==8||phase<-.25||phase>1.)goto fail;
        picture_t *out=picture_Clone(s->previous);
        if(!out)goto fail;
        if(!ambient_svp_attach_pair(out,s->previous,pic?pic:s->previous,s->previous_id,id,(float)fmax(0.,phase),&s->quality)){
            picture_Release(out);goto fail;
        }
        out->date=time;out->p_next=NULL;
        *tail=out;tail=&out->p_next;
        next++;count++;
    }
    if(!count)goto fail;
    picture_Release(s->previous);s->previous=pic;s->previous_id=id;s->tick=next;
    if(!pic)s->tick=s->previous_id=0;
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
        (uint64_t)v->i_visible_width*v->i_visible_height>1920u*1080u||
        !v->i_frame_rate||!v->i_frame_rate_base||
        (v->transfer!=TRANSFER_FUNC_SMPTE_ST2084&&v->transfer!=TRANSFER_FUNC_HLG&&!v->dovi.profile))
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
    f->p_sys=s;f->vctx_out=f->vctx_in;vlc_video_context_Hold(f->vctx_out);
    f->fmt_out.video.i_frame_rate=llround(s->target*1000.0);
    f->fmt_out.video.i_frame_rate_base=1000;f->ops=&svp_hdr_ops;
    msg_Info(f,"SVP metadata-aware renderer scheduling %.3f -> %.3f",s->source,s->target);
    return VLC_SUCCESS;
}
#endif
