/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Private mp_image attachment, never exported as FFmpeg side data.
 * Both original BL/EL pairs keep their timestamps until VO reconstruction.
 */
#ifndef AMBIENTSVP_MPV_FEL_PAIR_H
#define AMBIENTSVP_MPV_FEL_PAIR_H
#include <math.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <libavutil/buffer.h>
#include "video/mp_image.h"
#include "ambientsvp_quality.h"
#define SVP_FEL_PHASES 8
struct svp_fel_pair {
    struct mp_image *source[4]; // center A/B, optional past/future
    uint64_t ids[4];
    float phase;
    AmbientSvpQuality quality;
};
static inline const struct svp_fel_pair *svp_fel_get(const struct mp_image *img)
{
    if(!img||!img->ambient_svp_pair||img->ambient_svp_pair->size!=sizeof(struct svp_fel_pair))return NULL;
    return (const struct svp_fel_pair *)img->ambient_svp_pair->data;
}
static inline void svp_fel_pair_free(void *opaque,uint8_t *data)
{
    (void)opaque;
    struct svp_fel_pair *p=(struct svp_fel_pair *)data;
    for(unsigned i=0;i<4;i++)mp_image_unrefp(&p->source[i]);free(p);
}
static inline bool svp_fel_eligible(const struct mp_image *a)
{
#if PL_API_VER >= 367 && defined(PL_HAVE_DOVI) && defined(PL_HAVE_VULKAN)
    if(!a||!a->enhancement_layer||!a->dovi||a->ambient_svp_pair||a->film_grain||
        a->icc_profile||a->params.no_dovi||a->params.no_enhancement_layer||
        a->params.repr.sys!=PL_COLOR_SYSTEM_DOLBYVISION||!a->params.repr.dovi||
        !a->params.repr.dovi->nlq_active||a->params.rotate||a->params.vflip||
        a->fields&MP_IMGFIELD_INTERLACED||!isfinite(a->pts)||a->pts==MP_NOPTS_VALUE||
        a->w<32||a->h<32||((a->w|a->h)&1)||(uint64_t)a->w*a->h>1920u*1080u||
        a->params.crop.x0||a->params.crop.y0||
        (a->params.crop.x1&&a->params.crop.x1!=a->w)||
        (a->params.crop.y1&&a->params.crop.y1!=a->h))return false;
    const struct mp_image *el=a->enhancement_layer;
    // Use only pairs matched by MPV's pinned f_enhancement_pair.
    return el->w>0 && el->h>0 && !el->enhancement_layer&&!el->ambient_svp_pair&&!el->film_grain&&
        !(el->fields&MP_IMGFIELD_INTERLACED)&&isfinite(el->pts)&&fabs(a->pts-el->pts)<=1e-6&&
        ((el->w==a->w&&el->h==a->h)||(el->w*2==a->w&&el->h*2==a->h));
#else
    (void)a;return false;
#endif
}
struct svp_fel_schedule {
    struct mp_image *previous,*older,*lookahead,*outputs[SVP_FEL_PHASES];
    uint64_t previous_id,older_id,lookahead_id,tick,output_ticks;
    double origin;
    unsigned next,count;
};
static inline void svp_fel_reset(struct svp_fel_schedule *s)
{
    mp_image_unrefp(&s->previous);mp_image_unrefp(&s->older);mp_image_unrefp(&s->lookahead);
    for(unsigned i=0;i<SVP_FEL_PHASES;i++)mp_image_unrefp(&s->outputs[i]);
    s->previous_id=s->older_id=s->lookahead_id=s->tick=0;s->next=s->count=0;
}
static inline struct mp_image *svp_fel_take(struct svp_fel_schedule *s)
{
    if(s->next>=s->count)return NULL;
    struct mp_image *out=s->outputs[s->next];
    s->outputs[s->next++]=NULL;s->output_ticks++;
    if(s->next==s->count)s->next=s->count=0;
    return out;
}
static inline struct mp_image *svp_fel_previous(struct svp_fel_schedule *s)
{
    struct mp_image *out=s->previous;s->previous=s->lookahead;s->previous_id=s->lookahead_id;
    s->lookahead=NULL;s->lookahead_id=0;mp_image_unrefp(&s->older);s->older_id=0;
    if(!s->previous)s->tick=0;
    return out;
}
static inline bool svp_fel_push(struct svp_fel_schedule *s,struct mp_image *img,double source,double target,const AmbientSvpQuality *quality)
{
    static atomic_uint_fast64_t ids=1;
    if(!quality||!svp_fel_eligible(img)||s->count||!isfinite(source)||!isfinite(target)||
        !(source>0&&target>source&&target/source<=SVP_FEL_PHASES))return false;
    uint64_t id=atomic_fetch_add_explicit(&ids,1,memory_order_relaxed);
    if(!id)return false;
    if(!s->previous){
        s->previous=img;s->previous_id=id;s->origin=img->pts;s->tick=0;return true;
    }
    struct mp_image *last=s->lookahead?s->lookahead:s->previous;
    double delta=img->pts-last->pts;
    if(last->w!=img->w||last->h!=img->h||delta<=0||fabs(delta-1/source)>fmax(.003,.25/source))return false;
    bool extended=ambient_svp_quality_extended(quality);
    if(extended&&!s->lookahead){s->lookahead=img;s->lookahead_id=id;return true;}
    if(!extended&&s->lookahead)return false;
    struct mp_image *prev=s->previous,*next=extended?s->lookahead:img;
    uint64_t next_id=extended?s->lookahead_id:id;
    delta=next->pts-prev->pts;
    uint64_t tick=s->tick;
    unsigned count=0;
    while(tick<UINT64_C(1000000000)){
        double pts=s->origin+(double)tick/target;
        if(pts>=next->pts-1e-8)break;
        double phase=(pts-prev->pts)/delta;
        if(count==SVP_FEL_PHASES||phase<-.25||phase>1)goto fail;
        struct mp_image *out=mp_image_new_ref(prev);
        if(!out)goto fail;
        struct svp_fel_pair *p=calloc(1,sizeof(*p));
        if(!p){mp_image_unrefp(&out);goto fail;}
        p->source[0]=mp_image_new_ref(prev);p->source[1]=mp_image_new_ref(next);
        p->ids[0]=s->previous_id;p->ids[1]=next_id;p->phase=(float)fmax(0,phase);p->quality=*quality;
        if(extended&&s->older){
            p->source[2]=mp_image_new_ref(s->older);p->source[3]=mp_image_new_ref(img);
            p->ids[2]=s->older_id;p->ids[3]=id;
            if(!p->source[2]||!p->source[3]){
                svp_fel_pair_free(NULL,(uint8_t *)p);mp_image_unrefp(&out);goto fail;
            }
        }
        if(!p->source[0]||!p->source[1]){
            svp_fel_pair_free(NULL,(uint8_t *)p);mp_image_unrefp(&out);goto fail;
        }
        out->ambient_svp_pair=av_buffer_create((uint8_t *)p,sizeof(*p),svp_fel_pair_free,NULL,AV_BUFFER_FLAG_READONLY);
        if(!out->ambient_svp_pair){
            svp_fel_pair_free(NULL,(uint8_t *)p);mp_image_unrefp(&out);goto fail;
        }
        out->pts=pts;out->pkt_duration=1/target;out->nominal_fps=target;
        s->outputs[count++]=out;tick++;
    }
    if(!count)goto fail;
    if(extended){
        mp_image_unrefp(&s->older);s->older=prev;s->older_id=s->previous_id;
        s->previous=next;s->previous_id=next_id;s->lookahead=img;s->lookahead_id=id;
    }else{mp_image_unrefp(&s->previous);s->previous=img;s->previous_id=id;}
    s->tick=tick;s->count=count;s->next=0;
    return true;
fail:
    for(unsigned i=0;i<count;i++)mp_image_unrefp(&s->outputs[i]);
    return false;
}
#endif
