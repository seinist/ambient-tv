/* SPDX-License-Identifier: LGPL-2.1-or-later
 * VLC's PRIVATE AImageReader pictures -> resident Vulkan -> compositor
 * AHardwareBuffer pictures. Uses the existing pre-clock temporal filter
 * chain and Android SurfaceControl output. No second decoder consumer.
 */
#ifndef SVP_VLC_ANDROID_H
#define SVP_VLC_ANDROID_H
#include "../video_output/android/utils.h"
#include "ambientsvp_android_stream.h"
#include <unistd.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>
#include <vlc_ancillary.h>

#define SVP_ANDROID_PHASES 8
// The library must outlive the last picture lease, even after filter close.
typedef struct {
    atomic_uint refs;
    void *lib;
    void (*release)(AmbientSvpOutputLease *,int);
} SvpRecycle;
static void svp_recycle_unref(SvpRecycle *r)
{
    if (r && atomic_fetch_sub(&r->refs,1)==1) { dlclose(r->lib);free(r); }
}
typedef struct {
    SvpApi api;
    AmbientSvpAndroidStream *stream;
    AmbientSvpAndroidStream *(*create)(uint32_t,uint32_t);
    int (*push)(AmbientSvpAndroidStream *,AHardwareBuffer *,int,int64_t,
        uint32_t,uint32_t,uint32_t,uint32_t,void *,AmbientSvpSourceRelease,
        const float *,uint32_t,AmbientSvpDisplayOutput *);
    int (*drain)(AmbientSvpAndroidStream *,const float *,uint32_t,AmbientSvpDisplayOutput *);
    void (*reset)(AmbientSvpAndroidStream *);
    int (*destroy)(AmbientSvpAndroidStream *);
    AmbientSvpOutputLease *(*lease_output)(AmbientSvpAndroidStream *,AHardwareBuffer *,int);
    SvpRecycle *recycle;
    picture_t *previous;
    double source,target;
    vlc_tick_t origin;
    uint64_t tick;
    bool bypass;
    unsigned mode; /* 0 SDR8, 4/6 BT2020 P010 */
} SvpAndroid;
static void svp_android_source_release(void *context,int fd)
{
    picture_t *pic=context;
    struct android_picture_ctx *p=container_of(pic->context,struct android_picture_ctx,s);
    // The exported GPU release fence includes the decoder acquire wait.
    // Replace the input fence, not only its eventual destruction fence:
    // on a later fallback SurfaceControl must wait for GPU ownership too.
    if (fd>=0) {
        if (p->fence_fd>=0) close(p->fence_fd);
        p->fence_fd=fd;
    }
    picture_Release(pic);
}
static void svp_android_output_destroy(picture_context_t *context)
{
    struct android_picture_ctx *p=container_of(context,struct android_picture_ctx,s);
    if (!vlc_atomic_rc_dec(&p->rc)) return;
    if (p->fence_fd>=0) close(p->fence_fd);
    if (p->read_fence_fd>=0) close(p->read_fence_fd);
    // Only the explicit release fence of exactly one ASC presentation
    // can qualify this allocation. No fence (including teardown) means
    // discard rather than guessing that SurfaceControl is done.
    int consumer=atomic_exchange(&p->svp_consumer_fd,-1);
    if (atomic_load(&p->svp_present_count)!=1 && consumer>=0) {
        close(consumer);consumer=-1;
    }
    SvpRecycle *recycle=p->svp_recycle;
    if (p->svp_lease && recycle) recycle->release(p->svp_lease,consumer);
    else if (consumer>=0) close(consumer);
    AHardwareBuffer_release(p->svp_buffer);
    svp_recycle_unref(recycle);
    free(p);
}
static picture_context_t *svp_android_output_copy(picture_context_t *context)
{
    struct android_picture_ctx *p=container_of(context,struct android_picture_ctx,s);
    vlc_atomic_rc_inc(&p->rc);vlc_video_context_Hold(p->s.vctx);
    return context;
}
static void svp_android_discard(AmbientSvpDisplayOutput *o)
{
    if (o->fence_fd>=0) close(o->fence_fd);
    if (o->buffer) AHardwareBuffer_release(o->buffer);
    *o=(AmbientSvpDisplayOutput){NULL,-1};
}
static picture_t *svp_android_chain(filter_t *f,picture_t *reference,
    AmbientSvpDisplayOutput *outputs,const vlc_tick_t *dates,unsigned count)
{
    picture_t *head=NULL,**tail=&head;
    for (unsigned i=0;i<count;i++) {
        picture_t *out=picture_NewFromFormat(&f->fmt_out.video);
        struct android_picture_ctx *p=calloc(1,sizeof(*p));
        if (!out || !p || !outputs[i].buffer) {
            if (out) picture_Release(out);
            free(p);
            for (unsigned n=i;n<count;n++) svp_android_discard(&outputs[n]);
            while (head) {picture_t *next=head->p_next;head->p_next=NULL;picture_Release(head);head=next;}
            return NULL;
        }
        p->svp_buffer=outputs[i].buffer;p->fence_fd=outputs[i].fence_fd;
        p->read_fence_fd=-1;vlc_atomic_rc_init(&p->rc);
        atomic_init(&p->svp_present_count,0);
        atomic_init(&p->svp_consumer_fd,-1);
        SvpAndroid *s=f->p_sys;
        if (s->recycle && s->lease_output) {
            p->svp_lease=s->lease_output(s->stream,p->svp_buffer,p->fence_fd);
            if (p->svp_lease) {
                atomic_fetch_add(&s->recycle->refs,1);
                p->svp_recycle=s->recycle;
            }
        }
        p->s=(picture_context_t){svp_android_output_destroy,svp_android_output_copy,f->vctx_out};
        outputs[i]=(AmbientSvpDisplayOutput){NULL,-1};
        out->context=&p->s;vlc_video_context_Hold(p->s.vctx);
        picture_CopyProperties(out,reference);
        // The compositor receives encoded full-range RGB, with source
        // transfer/primaries and static metadata retained per picture.
        out->format.primaries=reference->format.primaries;
        out->format.transfer=reference->format.transfer;
        out->format.space=reference->format.space;
        out->format.color_range=COLOR_RANGE_FULL;
        out->format.mastering=reference->format.mastering;
        out->format.lighting=reference->format.lighting;
        out->date=dates[i];out->p_next=NULL;
        *tail=out;tail=&out->p_next;
    }
    return head;
}
static void svp_android_reset_history(SvpAndroid *s)
{
    s->reset(s->stream);
    if (s->previous) picture_Release(s->previous);
    s->previous=NULL;s->tick=0;
}
static void svp_android_flush(filter_t *f)
{
    SvpAndroid *s=f->p_sys;svp_android_reset_history(s);
}
static void svp_android_close(filter_t *f)
{
    SvpAndroid *s=f->p_sys;svp_android_reset_history(s);
    if (s->destroy(s->stream)!=1) {
        // Rare stuck driver: keep native callbacks and retained input
        // picture references alive. Never unload in-flight Vulkan code.
        msg_Warn(f,"SVP Android compositor stream did not retire; retaining native library");
    } else dlclose(s->api.lib);
    svp_recycle_unref(s->recycle);s->recycle=NULL;
    vlc_video_context_Release(f->vctx_out);f->vctx_out=NULL;
}
static unsigned svp_android_phases(SvpAndroid *s,vlc_tick_t end,
    float phases[SVP_ANDROID_PHASES],vlc_tick_t dates[SVP_ANDROID_PHASES],uint64_t *next)
{
    double duration=(double)(end-s->previous->date);
    if (!(duration>0)) return 0;
    uint64_t tick=s->tick;unsigned count=0;
    while (tick<UINT64_C(1000000000)) {
        vlc_tick_t time=s->origin+VLC_TICK_FROM_US(llround((double)tick*1000000.0/s->target));
        if (time>=end) break;
        if (count==SVP_ANDROID_PHASES) return 0;
        double phase=(double)(time-s->previous->date)/duration;
        if (phase< -0.25 || phase>1.0) return 0;
        dates[count]=time;phases[count++]=(float)fmax(0.0,phase);tick++;
    }
    *next=tick;return count;
}
static picture_t *svp_android_passthrough(SvpAndroid *s,picture_t *pic,bool drop)
{
    picture_t *old=s->previous;s->previous=NULL;
    s->reset(s->stream);s->tick=0;
    if (drop && pic) {picture_Release(pic);pic=NULL;}
    if (old) {old->p_next=pic;return old;}
    return pic;
}
static bool svp_android_sdr_color(const video_format_t *v)
{
    return (v->transfer==TRANSFER_FUNC_BT709 || v->transfer==TRANSFER_FUNC_SRGB) &&
        (v->primaries==COLOR_PRIMARIES_BT709 || v->primaries==COLOR_PRIMARIES_BT601_625 ||
         v->primaries==COLOR_PRIMARIES_BT601_525);
}
static unsigned svp_android_hdr_mode(const video_format_t *v)
{
    if (v->primaries!=COLOR_PRIMARIES_BT2020 || v->space!=COLOR_SPACE_BT2020 ||
        (v->transfer!=TRANSFER_FUNC_SMPTE_ST2084 && v->transfer!=TRANSFER_FUNC_HLG) ||
        v->dovi.profile || v->dovi.rpu_present || v->dovi.el_present) return 0;
    return v->color_range==COLOR_RANGE_FULL?6:v->color_range==COLOR_RANGE_LIMITED?4:0;
}
static bool svp_android_input(SvpAndroid *s,picture_t *pic,AHardwareBuffer **buffer,
    struct android_picture_ctx **ctx)
{
    if (!pic->context || !pic->b_progressive || pic->date==VLC_TICK_INVALID ||
        (s->mode?svp_android_hdr_mode(&pic->format)!=s->mode:!svp_android_sdr_color(&pic->format)) || pic->format.orientation!=ORIENT_NORMAL ||
        pic->format.projection_mode!=PROJECTION_MODE_RECTANGULAR ||
        vlc_video_context_GetType(pic->context->vctx)!=VLC_VIDEO_CONTEXT_AWINDOW)
        return false;
    android_video_context_t *v=vlc_video_context_GetPrivate(pic->context->vctx,VLC_VIDEO_CONTEXT_AWINDOW);
    if (picture_GetAncillary(pic,VLC_ANCILLARY_ID_HDR10PLUS) || picture_GetAncillary(pic,VLC_ANCILLARY_ID_DOVI)) return false;
    if (!v || !v->svp_surface || atomic_load(&v->svp_dynamic_hdr) || (s->mode!=0)!=v->svp_hdr10 || !v->air || !v->air_api || pic->format.i_x_offset || pic->format.i_y_offset)
        return false;
    *ctx=container_of(pic->context,struct android_picture_ctx,s);
    if ((*ctx)->svp_buffer || !(*ctx)->image || (*ctx)->read_fence_fd>=0) return false;
    return v->air_api->AImage.getHardwareBuffer((*ctx)->image,buffer)==0 && *buffer;
}
static picture_t *svp_android_filter(filter_t *f,picture_t *pic)
{
    SvpAndroid *s=f->p_sys;
    android_video_context_t *vctx=vlc_video_context_GetPrivate(f->vctx_in,VLC_VIDEO_CONTEXT_AWINDOW);
    if (!s->api.enabled() || s->bypass || !vctx || atomic_load(&vctx->svp_dynamic_hdr))
        return svp_android_passthrough(s,pic,false);
    float phases[SVP_ANDROID_PHASES]={0};vlc_tick_t dates[SVP_ANDROID_PHASES];
    AmbientSvpDisplayOutput outputs[SVP_ANDROID_PHASES];
    uint64_t next=s->tick;unsigned count=0;
    if (!pic) {
        if (!s->previous) return NULL;
        count=svp_android_phases(s,s->previous->date+VLC_TICK_FROM_US(llround(1000000.0/s->source)),
            phases,dates,&next);
        if (!count || s->drain(s->stream,phases,count,outputs))
            return svp_android_passthrough(s,NULL,false);
        picture_t *head=svp_android_chain(f,s->previous,outputs,dates,count);
        if (!head) return svp_android_passthrough(s,NULL,false);
        picture_Release(s->previous);s->previous=NULL;s->tick=0;
        return head;
    }
    AHardwareBuffer *buffer=NULL;struct android_picture_ctx *ctx=NULL;
    if (!svp_android_input(s,pic,&buffer,&ctx))
        return svp_android_passthrough(s,pic,false);
    if (s->previous) {
        const video_format_t *previous=&s->previous->format;
        const video_format_t *current=&pic->format;
        if (previous->transfer!=current->transfer || previous->primaries!=current->primaries ||
            previous->space!=current->space || previous->color_range!=current->color_range ||
            previous->i_visible_width!=current->i_visible_width || previous->i_visible_height!=current->i_visible_height ||
            (s->mode && (memcmp(&previous->mastering,&current->mastering,sizeof(previous->mastering)) ||
                         memcmp(&previous->lighting,&current->lighting,sizeof(previous->lighting)))))
            return svp_android_passthrough(s,pic,false);
        double expected=1000000.0/s->source;
        double delta=US_FROM_VLC_TICK(pic->date-s->previous->date);
        if (fabs(delta-expected)>fmax(3000.0,expected*0.25))
            return svp_android_passthrough(s,pic,false);
        count=svp_android_phases(s,pic->date,phases,dates,&next);
        if (!count) return svp_android_passthrough(s,pic,false);
    } else {s->origin=pic->date;s->tick=0;}
    const video_format_t *v=&pic->format;
    int rc=s->push(s->stream,buffer,ctx->fence_fd,NS_FROM_VLC_TICK(pic->date),
        v->i_x_offset,v->i_y_offset,v->i_visible_width,v->i_visible_height,
        picture_Hold(pic),svp_android_source_release,phases,count,outputs);
    if (rc) {
        s->bypass=true;msg_Warn(f,"SVP Android GPU transport failed (%d), falling back",rc);
        return svp_android_passthrough(s,pic,rc==-5);
    }
    if (!count) {s->previous=pic;return NULL;}
    picture_t *head=svp_android_chain(f,s->previous,outputs,dates,count);
    if (!head) {s->bypass=true;return svp_android_passthrough(s,pic,false);}
    picture_Release(s->previous);s->previous=pic;s->tick=next;
    return head;
}
static const struct vlc_filter_operations svp_android_ops={
    .filter_video=svp_android_filter,.flush=svp_android_flush,.close=svp_android_close,
};
static int svp_android_open(filter_t *f)
{
    const video_format_t *v=&f->fmt_in.video;
    if (!var_InheritBool(f,"ambientsvp-hwresident") || !f->vctx_in ||
        vlc_video_context_GetType(f->vctx_in)!=VLC_VIDEO_CONTEXT_AWINDOW ||
        v->i_chroma!=VLC_CODEC_ANDROID_OPAQUE || f->fmt_out.video.i_chroma!=v->i_chroma ||
        v->i_x_offset || v->i_y_offset || v->orientation!=ORIENT_NORMAL ||
        v->projection_mode!=PROJECTION_MODE_RECTANGULAR ||
        v->i_visible_width<32 || v->i_visible_height<32 ||
        (v->i_visible_width|v->i_visible_height)&1 ||
        (uint64_t)v->i_visible_width*v->i_visible_height>1920u*1080u ||
        (!svp_android_sdr_color(v) && !svp_android_hdr_mode(v)) ||
        !v->i_frame_rate || !v->i_frame_rate_base)
        return VLC_EGENERIC;
    android_video_context_t *ctx=vlc_video_context_GetPrivate(f->vctx_in,VLC_VIDEO_CONTEXT_AWINDOW);
    unsigned mode=svp_android_hdr_mode(v);
    if (!ctx || !ctx->svp_surface || atomic_load(&ctx->svp_dynamic_hdr) || (mode!=0)!=ctx->svp_hdr10 || !ctx->air || !ctx->asc_api || ctx->texture)
        return VLC_EGENERIC;
    SvpAndroid *s=vlc_obj_calloc(VLC_OBJECT(f),1,sizeof(*s));
    if (!s) return VLC_ENOMEM;
    s->source=(double)v->i_frame_rate/v->i_frame_rate_base;
    s->target=var_InheritFloat(f,"ambientsvp-fps");
    if (!(s->target>s->source && s->target/s->source<=SVP_ANDROID_PHASES) || !svp_load(&s->api))
        return VLC_EGENERIC;
#define LOAD(member) s->member=dlsym(s->api.lib,"ambient_svp_android_stream_" #member);if(!s->member) goto fail
    LOAD(create);LOAD(push);LOAD(drain);LOAD(reset);LOAD(destroy);
#undef LOAD
    s->mode=mode;
    if (mode) {
        AmbientSvpAndroidStream *(*create_format)(uint32_t,uint32_t,uint32_t)=
            dlsym(s->api.lib,"ambient_svp_android_stream_create_format");
        if (!create_format) goto fail;
        s->stream=create_format(v->i_visible_width,v->i_visible_height,mode);
    } else s->stream=s->create(v->i_visible_width,v->i_visible_height);
    if (!s->stream) goto fail;
    // Optional ABI extension: older engines retain the fresh-buffer path.
    s->lease_output=dlsym(s->api.lib,"ambient_svp_android_stream_lease_output");
    if (s->lease_output) {
        SvpRecycle *r=calloc(1,sizeof(*r));
        if (r) {
            r->lib=dlopen("libambientsvp.so",RTLD_NOW|RTLD_LOCAL);
            if (r->lib) r->release=dlsym(r->lib,"ambient_svp_android_output_release");
            if (r->release) { atomic_init(&r->refs,1);s->recycle=r; }
            else { if (r->lib) dlclose(r->lib);free(r); }
        }
    }
    f->p_sys=s;
    f->vctx_out=f->vctx_in;vlc_video_context_Hold(f->vctx_out);
    f->fmt_out.video.i_frame_rate=llround(s->target*1000.0);
    f->fmt_out.video.i_frame_rate_base=1000;
    f->ops=&svp_android_ops;
    msg_Info(f,"SVP Android AImageReader -> Vulkan -> SurfaceControl %.3f -> %.3f",s->source,s->target);
    return VLC_SUCCESS;
fail:
    dlclose(s->api.lib);return VLC_EGENERIC;
}
#endif
