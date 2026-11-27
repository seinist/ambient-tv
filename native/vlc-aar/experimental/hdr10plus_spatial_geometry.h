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
    float t=fmaxf(0.f,fminf(1.f,(radius-m->rotation[2])/fmaxf(1.f-m->rotation[2],1e-6f)));
    return 0.5f+0.5f*cosf(3.14159265358979323846f*t);
}
/* ST 2094-40 sections 7.2/8.2 and ST 2094-1: row axis is
 * FractionBrightPixels, column axis AverageMaxRGB. Never use pixel x/y.
 * A zero fraction indicates unavailable statistics (section 8.6). */
static inline bool ambient_hdrplus_grid_factor(const struct ambient_hdrplus_grid *g,
    const struct ambient_hdrplus_window *w, float *factor)
{
    if (!g || !w || !factor) return false;
    *factor=1.f;
    if (!g->rows && !g->cols) return true;
    if (g->rows<2 || g->rows>25 || g->cols<2 || g->cols>25 ||
        !(w->fraction>=0.f && w->fraction<=1.f) ||
        !(w->average>=0.f && w->average<=1.f)) return false;
    for(unsigned i=0;i<g->rows;++i) for(unsigned j=0;j<g->cols;++j)
        if(g->values[i][j]>15) return false;
    if(w->fraction==0.f) return true;
    float x=w->fraction*(g->rows-1),y=w->average*(g->cols-1);
    unsigned i=(unsigned)x,j=(unsigned)y;
    unsigned i1=i+1<g->rows?i+1:i,j1=j+1<g->cols?j+1:j;
    float a=x-i,b=y-j;
    float p=g->values[i][j]*(1.f-b)+g->values[i][j1]*b;
    float q=g->values[i1][j]*(1.f-b)+g->values[i1][j1]*b;
    *factor=(p*(1.f-a)+q*a)/15.f;
    return *factor>0.f;
}
/* ST 2094-40:2020 section 8.7.4, equations 1, 3-5. A stable de
 * Casteljau evaluation handles degree 1 through 16 and exact knee endpoints.
 * This is the normalized specified curve, not a display adaptation policy. */
static inline float ambient_hdrplus_bezier(const struct ambient_hdrplus_window *w,float s)
{
    if(!w||!w->tone_mapping||w->anchor_count>15||
       !(w->knee_x>=0.f&&w->knee_x<=1.f)||
       !(w->knee_y>=0.f&&w->knee_y<=1.f)||!isfinite(s))return NAN;
    s=fmaxf(0.f,fminf(1.f,s));
    if(w->knee_x==1.f)return w->knee_y*s;
    if(s<w->knee_x)return w->knee_y*s/w->knee_x;
    float t=(s-w->knee_x)/(1.f-w->knee_x);
    float v[17]={0};
    unsigned degree=w->anchor_count+1;
    v[degree]=1.f;
    for(unsigned i=0;i<w->anchor_count;i++){
        if(!(w->anchors[i]>=0.f&&w->anchors[i]<=1.f))return NAN;
        v[i+1]=w->anchors[i];
    }
    for(unsigned n=degree;n>0;n--)
        for(unsigned i=0;i<n;i++)v[i]=v[i]+t*(v[i+1]-v[i]);
    return w->knee_y+(1.f-w->knee_y)*v[0];
}

/* A monotone Bezier curve need not have sorted control points. Check
 * the derivative in Bernstein form: nonnegative coefficients prove
 * nonnegativity over that interval; subdivide ambiguous intervals.
 * Bound recursion and reject unproven curves rather than assuming monotonicity. */
static inline bool ambient_hdrplus_nonnegative_bernstein(const double *p,
    unsigned n,unsigned depth)
{
    bool positive=true;
    for(unsigned i=0;i<=n;i++)if(p[i]<0.0){positive=false;break;}
    if(positive)return true;
    if(p[0]<0.0||p[n]<0.0||!depth)return false;
    double v[16],left[16],right[16];
    memcpy(v,p,(n+1)*sizeof(*p));
    left[0]=v[0];right[n]=v[n];
    for(unsigned k=1;k<=n;k++){
        for(unsigned j=0;j<=n-k;j++)v[j]=(v[j]+v[j+1])*0.5;
        left[k]=v[0];right[n-k]=v[n-k];
    }
    return ambient_hdrplus_nonnegative_bernstein(left,n,depth-1)&&
           ambient_hdrplus_nonnegative_bernstein(right,n,depth-1);
}
static inline bool ambient_hdrplus_curve_monotone(const struct ambient_hdrplus_window *w)
{
    if(!w||w->anchor_count>15)return false;
    double delta[16],last=0.0;
    for(unsigned i=0;i<w->anchor_count;i++){
        double x=w->anchors[i];
        if(!(x>=0.0&&x<=1.0))return false;
        delta[i]=x-last;last=x;
    }
    delta[w->anchor_count]=1.0-last;
    return ambient_hdrplus_nonnegative_bernstein(delta,w->anchor_count,10);
}

/* Section 8.7.4 permits a percentile source normalization when MaxSCL
 * is zero. Reserved v1 distribution markers are never scene statistics. */
static inline float ambient_hdrplus_source_peak(const struct ambient_hdrplus_window *w,unsigned version)
{
    if(!w||w->histogram_count>15)return 0.f;
    float peak=0.f;
    for(unsigned i=0;i<3;i++){
        if(!(w->maxscl[i]>=0.f&&w->maxscl[i]<=1.f))return 0.f;
        peak=fmaxf(peak,w->maxscl[i]);
    }
    if(peak>0.f)return peak;
    bool reserved=version==1&&w->histogram_count==9&&
        w->histogram[1].percentage==5&&w->histogram[2].percentage==10;
    for(unsigned i=0;i<w->histogram_count;i++){
        if(reserved&&(i==1||i==2))continue;
        float v=w->histogram[i].percentile;
        if(!(v>=0.f&&v<=1.f))return 0.f;
        peak=fmaxf(peak,v);
    }
    return peak;
}
static inline bool ambient_hdrplus_spatial_supported(const struct ambient_hdrplus *m,
    unsigned width, unsigned height, struct ambient_hdrplus_mask masks[2], bool *layering)
{
    if (!m || !masks || !layering || m->window_count<1 || m->window_count>3 ||
        !isfinite(m->target_nits) || m->target_nits<0.f || m->target_nits>10000.f) return false;
    memset(masks,0,2*sizeof(*masks));
    *layering=m->window_count>1 && m->windows[1].geometry.overlap==1;
    for (unsigned i=0;i<m->window_count;++i) {
        const struct ambient_hdrplus_window *w=&m->windows[i];
        // A missing authored OOTF uses scene-statistics-based mapping.
        float source_factor,target_factor;
        if (!ambient_hdrplus_grid_factor(&m->mastering_grid,w,&source_factor) ||
            !ambient_hdrplus_grid_factor(&m->targeted_grid,w,&target_factor) ||
            (w->saturation_mapping && w->saturation_weight>63) ||
            ambient_hdrplus_source_peak(w,m->version)<=0.f) return false;
        if(w->tone_mapping && (m->target_nits<=0.f || w->anchor_count>15 ||
            !(w->knee_x>=0.f && w->knee_x<=1.f) ||
            !(w->knee_y>=0.f && w->knee_y<=1.f))) return false;
        if(w->tone_mapping && !ambient_hdrplus_curve_monotone(w))return false;
        if (i && (w->geometry.overlap!=*layering ||
            !ambient_hdrplus_mask_init(&masks[i-1],&w->geometry,width,height))) return false;
    }
    return true;
}
