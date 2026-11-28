/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Private picture ancillary shared by the pre-clock filter and the
 * metadata-aware libplacebo display. Sources retain their original PTS.
 */
#ifndef AMBIENTSVP_VLC_HDR_PAIR_H
#define AMBIENTSVP_VLC_HDR_PAIR_H
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <vlc_common.h>
#include <vlc_picture.h>
#include <vlc_ancillary.h>
#include "ambientsvp_quality.h"
#define AMBIENT_SVP_PAIR_ID VLC_FOURCC('S','V','P','2')
/* Immutable pass configuration shared by the scheduler and every queued
 * picture. Only the display owns the native mutable pass handle. */
struct ambient_svp_scene_config {
    atomic_uint refs;
    atomic_bool aborted,ended;
    unsigned mode;
    double source,target;
    char *plan,*cache,*key;
};
static inline struct ambient_svp_scene_config *ambient_svp_scene_config_new(unsigned mode,double source,double target,
    const char *plan,const char *cache,const char *key)
{
    if(!mode||mode>2||!plan||!*plan||!cache||!*cache||!key||strlen(key)!=64)return NULL;
    struct ambient_svp_scene_config *c=calloc(1,sizeof(*c));
    if(!c)return NULL;
    c->plan=strdup(plan);c->cache=strdup(cache);c->key=strdup(key);
    if(!c->plan||!c->cache||!c->key){free(c->plan);free(c->cache);free(c->key);free(c);return NULL;}
    atomic_init(&c->refs,1);atomic_init(&c->aborted,false);atomic_init(&c->ended,false);
    c->mode=mode;c->source=source;c->target=target;return c;
}
static inline void ambient_svp_scene_config_hold(struct ambient_svp_scene_config *c){if(c)atomic_fetch_add(&c->refs,1);}
static inline void ambient_svp_scene_config_release(struct ambient_svp_scene_config *c){
    if(c&&atomic_fetch_sub(&c->refs,1)==1){free(c->plan);free(c->cache);free(c->key);free(c);}
}
static inline void ambient_svp_scene_config_abort(struct ambient_svp_scene_config *c){if(c)atomic_store(&c->aborted,true);}
struct ambient_svp_pair {
    picture_t *a,*b,*past,*future;
    uint64_t a_id,b_id,past_id,future_id;
    float phase;
    AmbientSvpQuality quality;
    struct ambient_svp_scene_config *scene;
    uint64_t scene_index,scene_pair_end;
    bool scene_final;
};
static inline void ambient_svp_pair_free(void *opaque)
{
    struct ambient_svp_pair *p=opaque;
    picture_Release(p->a);picture_Release(p->b);
    if(p->past)picture_Release(p->past);
    if(p->future)picture_Release(p->future);
    ambient_svp_scene_config_release(p->scene);
    free(p);
}
static inline bool ambient_svp_attach_pair(picture_t *out,picture_t *a,picture_t *b,
    uint64_t ai,uint64_t bi,float phase,const AmbientSvpQuality *quality,
    picture_t *past,uint64_t past_id,picture_t *future,uint64_t future_id)
{
    if(!out||!a||!b||!quality||!ai||!bi||!isfinite(phase)||phase<0.f||phase>1.f)return false;
    struct ambient_svp_pair *p=malloc(sizeof(*p));
    if(!p)return false;
    *p=(struct ambient_svp_pair){.a=picture_Hold(a),.b=picture_Hold(b),.a_id=ai,.b_id=bi,.phase=phase,.quality=*quality};
    if(past&&future&&past_id&&future_id){
        p->past=picture_Hold(past);p->future=picture_Hold(future);
        p->past_id=past_id;p->future_id=future_id;
    }
    struct vlc_ancillary *anc=vlc_ancillary_CreateWithFreeCb(p,AMBIENT_SVP_PAIR_ID,ambient_svp_pair_free);
    if(!anc){ambient_svp_pair_free(p);return false;}
    int rc=picture_AttachAncillary(out,anc);
    vlc_ancillary_Release(anc);
    return rc==VLC_SUCCESS;
}
static inline bool ambient_svp_attach_scene(picture_t *out,struct ambient_svp_scene_config *c,uint64_t index,bool final)
{
    if(!c)return true;
    struct vlc_ancillary *anc=picture_GetAncillary(out,AMBIENT_SVP_PAIR_ID);
    if(!anc)return false;
    struct ambient_svp_pair *p=vlc_ancillary_GetData(anc);
    if(!p||p->scene)return false;
    ambient_svp_scene_config_hold(c);p->scene=c;p->scene_index=index;p->scene_final=final;return true;
}
#endif
