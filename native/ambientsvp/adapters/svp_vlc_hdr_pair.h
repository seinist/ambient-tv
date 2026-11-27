/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Private picture ancillary shared by the pre-clock filter and the
 * metadata-aware libplacebo display. Sources retain their original PTS.
 */
#ifndef AMBIENTSVP_VLC_HDR_PAIR_H
#define AMBIENTSVP_VLC_HDR_PAIR_H
#include <math.h>
#include <stdlib.h>
#include <vlc_common.h>
#include <vlc_picture.h>
#include <vlc_ancillary.h>
#define AMBIENT_SVP_PAIR_ID VLC_FOURCC('S','V','P','2')
struct ambient_svp_pair {
    picture_t *a,*b;
    uint64_t a_id,b_id;
    float phase;
};
static inline void ambient_svp_pair_free(void *opaque)
{
    struct ambient_svp_pair *p=opaque;
    picture_Release(p->a);picture_Release(p->b);free(p);
}
static inline bool ambient_svp_attach_pair(picture_t *out,picture_t *a,picture_t *b,
    uint64_t ai,uint64_t bi,float phase)
{
    if(!out||!a||!b||!ai||!bi||!isfinite(phase)||phase<0.f||phase>1.f)return false;
    struct ambient_svp_pair *p=malloc(sizeof(*p));
    if(!p)return false;
    *p=(struct ambient_svp_pair){picture_Hold(a),picture_Hold(b),ai,bi,phase};
    struct vlc_ancillary *anc=vlc_ancillary_CreateWithFreeCb(p,AMBIENT_SVP_PAIR_ID,ambient_svp_pair_free);
    if(!anc){ambient_svp_pair_free(p);return false;}
    int rc=picture_AttachAncillary(out,anc);
    vlc_ancillary_Release(anc);
    return rc==VLC_SUCCESS;
}
#endif
