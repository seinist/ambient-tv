#pragma once
#include "hdr10plus_payload.h"
#include <math.h>

struct ambient_hdrplus_mask {
    float rect[4];   // inclusive absolute pixel-center indices
    float ellipse[4]; // center, reciprocal outer semiaxes
    float rotation[4]; // cos, sin, inner/outer, enabled
};
static inline bool ambient_hdrplus_mask_init(struct ambient_hdrplus_mask *out,
    const struct ambient_hdrplus_geometry *g, unsigned width, unsigned height)
{
    if (!out || !g || !width || !height || g->left > g->right || g->top > g->bottom ||
        g->right >= width || g->bottom >= height || g->center_x < g->left ||
        g->center_x > g->right || g->center_y < g->top || g->center_y > g->bottom ||
        !g->semimajor_inner || !g->semimajor_outer || !g->semiminor_outer ||
        g->semimajor_inner > g->semimajor_outer || g->semiminor_outer > g->semimajor_outer ||
        g->angle > 180 || g->overlap > 1) return false;
    float a = g->angle * (3.14159265358979323846f / 180.f);
    *out = (struct ambient_hdrplus_mask){
        .rect={g->left,g->top,g->right,g->bottom},
        .ellipse={g->center_x,g->center_y,1.f/g->semimajor_outer,1.f/g->semiminor_outer},
        .rotation={cosf(a),sinf(a),(float)g->semimajor_inner/g->semimajor_outer,1.f},
    };
    return true;
}
static inline float ambient_hdrplus_weight(const struct ambient_hdrplus_mask *m, float x, float y)
{
    if (!m->rotation[3] || x<m->rect[0] || y<m->rect[1] ||
        x>m->rect[2] || y>m->rect[3]) return 0.f;
    float dx=x-m->ellipse[0],dy=y-m->ellipse[1];
    float u=(m->rotation[0]*dx+m->rotation[1]*dy)*m->ellipse[2];
    float v=(-m->rotation[1]*dx+m->rotation[0]*dy)*m->ellipse[3];
    float radius=hypotf(u,v);
    if (radius<=m->rotation[2]) return 1.f;
    return fmaxf(0.f,fminf(1.f,(1.f-radius)/fmaxf(1.f-m->rotation[2],1e-6f)));
}
static inline bool ambient_hdrplus_spatial_supported(const struct ambient_hdrplus *m,
    unsigned width, unsigned height, struct ambient_hdrplus_mask masks[2], bool *layering)
{
    if (!m || !masks || !layering || m->window_count<2 || m->window_count>3 ||
        m->targeted_grid.rows || m->mastering_grid.rows ||
        !isfinite(m->target_nits) || m->target_nits<=0.f) return false;
    memset(masks,0,2*sizeof(*masks));
    *layering=m->windows[1].geometry.overlap==1;
    for (unsigned i=0;i<m->window_count;++i) {
        const struct ambient_hdrplus_window *w=&m->windows[i];
        // The current subset deliberately excludes grid/saturation rendering,
        // missing OOTFs and degenerate or nonmonotone curves.
        if (w->saturation_mapping || !w->tone_mapping || !w->anchor_count ||
            w->anchor_count>15 || !(w->knee_x>0.f && w->knee_x<1.f) ||
            !(w->knee_y>0.f && w->knee_y<1.f) ||
            !(w->maxscl[0]>0.f || w->maxscl[1]>0.f || w->maxscl[2]>0.f)) return false;
        float last=0.f;
        for (unsigned j=0;j<w->anchor_count;++j) {
            if (!(w->anchors[j]>=last && w->anchors[j]<=1.f)) return false;
            last=w->anchors[j];
        }
        if (i && (w->geometry.overlap!=*layering ||
            !ambient_hdrplus_mask_init(&masks[i-1],&w->geometry,width,height))) return false;
    }
    return true;
}
