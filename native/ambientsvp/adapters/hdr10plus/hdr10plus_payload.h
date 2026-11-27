/* Shared renderer implementation from PR #510 (3c4f95d), staged independently until the PRs are combined. */
#pragma once
/* ST 2094-40 application 4 parser. Preserve all decoded local geometry,
 * luminance grids, distributions and tone/saturation parameters. A separate
 * adapter projects window zero into VLC's current global renderer contract.
 * All reads are bounded and the caller receives a result only on success. */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

enum ambient_hdrplus_status { AMBIENT_HDRPLUS_NONE, AMBIENT_HDRPLUS_VALID, AMBIENT_HDRPLUS_UNSUPPORTED };
struct ambient_hdrplus_geometry {
    uint16_t left, top, right, bottom, center_x, center_y;
    uint16_t semimajor_inner, semimajor_outer, semiminor_outer;
    uint8_t angle, overlap;
};
struct ambient_hdrplus_grid {
    uint8_t rows, cols;
    uint8_t values[25][25]; /* raw 4-bit values, normalized by 15 */
};
struct ambient_hdrplus_window {
    struct ambient_hdrplus_geometry geometry; /* unused for window zero */
    float maxscl[3], average, fraction;
    uint8_t histogram_count;
    struct { uint8_t percentage; float percentile; } histogram[15];
    bool tone_mapping;
    float knee_x, knee_y;
    uint8_t anchor_count;
    float anchors[15];
    bool saturation_mapping;
    uint8_t saturation_weight; /* raw 6-bit value, normalized by 8 */
};
struct ambient_hdrplus {
    uint8_t version, window_count;
    bool global_projection; /* metadata beyond VLC/libplacebo's global contract */
    bool metadata_constraints_ok; /* checkable ST2094-40 constraints, both versions */
    bool version0_recommended_features; /* section 9.3 recommendations */
    bool version0_recommended_fraction; /* section 10.3: zero for new metadata */
    bool version1_constraints_ok; /* ST 2094-40:2020 section 9.4 and 8.5.4/8.7.3.2 */
    bool version1_recommended_distribution; /* Table 1: recommended, not mandatory */
    float target_nits;
    struct ambient_hdrplus_window windows[3];
    struct ambient_hdrplus_grid targeted_grid, mastering_grid;
};
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

/* Validate actual distribution values, not the v1 reserved markers. */
static inline bool ambient_hdrplus_reserved_markers(const struct ambient_hdrplus_window *w,unsigned version)
{
    return version==1 && w->histogram_count==9 &&
        w->histogram[1].percentage==5 && w->histogram[2].percentage==10;
}
static inline bool ambient_hdrplus_distribution_ordered(const struct ambient_hdrplus_window *w,unsigned version)
{
    if(!w||w->histogram_count>15)return false;
    bool reserved=ambient_hdrplus_reserved_markers(w,version);
    if(reserved&&(w->histogram[1].percentile!=0.f||w->histogram[2].percentile!=255.f/100000.f))
        return false;
    float last=0.f;
    for(unsigned i=0;i<w->histogram_count;i++){
        if(w->histogram[i].percentage>100 || (i&&w->histogram[i].percentage<=w->histogram[i-1].percentage))
            return false;
        if(reserved&&(i==1||i==2))continue;
        float v=w->histogram[i].percentile;
        if(!(v>=last&&v<=1.f))return false;
        last=v;
    }
    return true;
}
/* A syntactically valid legacy payload can still contain an unusable
 * authored curve. Keep it in the full snapshot, but never send it to a
 * fallback renderer that assumes a valid monotone OOTF. */
static inline bool ambient_hdrplus_usable_curve(const struct ambient_hdrplus_window *w,
    float target_nits)
{
    return w && w->tone_mapping && target_nits>0.f && target_nits<=10000.f &&
        w->knee_x>=0.f && w->knee_x<=1.f &&
        w->knee_y>=0.f && w->knee_y<=1.f &&
        ambient_hdrplus_curve_monotone(w);
}

/* Zero MaxSCL means absent scene peak. Only the final ordered CFD
 * percentile may substitute; reserved markers must not become peaks. */
static inline float ambient_hdrplus_cfd_peak(const struct ambient_hdrplus_window *w,
    unsigned version)
{
    if(!w||!w->histogram_count||!ambient_hdrplus_distribution_ordered(w,version))
        return 0.f;
    return w->histogram[w->histogram_count-1].percentile;
}

/* The public VLC global ancillary cannot carry grids or local windows.
 * Preserve the original private snapshot and sanitize only the projected
 * fallback: unsafe OOTF and invalid CFD must not reach libplacebo. */
static inline bool ambient_hdrplus_global_projection(const struct ambient_hdrplus *m,
    struct ambient_hdrplus_window *out)
{
    if(!m||!out||m->window_count<1||m->window_count>3)return false;
    struct ambient_hdrplus_window w=m->windows[0];
    if(!ambient_hdrplus_distribution_ordered(&w,m->version)){
        w.histogram_count=0;
        memset(w.histogram,0,sizeof(w.histogram));
    }
    if(!w.maxscl[0]&&!w.maxscl[1]&&!w.maxscl[2]){
        float peak=ambient_hdrplus_cfd_peak(&w,m->version);
        for(unsigned i=0;i<3;i++)w.maxscl[i]=peak;
    }
    // The pinned global fitter divides by the knee coordinates and
    // treats zero anchors as absent metadata. Endpoint curves remain
    // available to the exact spatial renderer, not this fitter.
    if(!ambient_hdrplus_usable_curve(&w,m->target_nits)||
       w.knee_x<=0.f||w.knee_x>=1.f||w.knee_y<=0.f||w.knee_y>=1.f){
        w.tone_mapping=false;w.knee_x=w.knee_y=0.f;w.anchor_count=0;
        memset(w.anchors,0,sizeof(w.anchors));
    }else if(!w.anchor_count){
        // Degree elevation of B(t)=t is exact: quadratic [0,1/2,1].
        w.anchor_count=1;w.anchors[0]=0.5f;
    }
    *out=w;
    return true;
}
struct ambient_hdrplus_bits { const uint8_t *data; size_t bits, pos; };
static inline bool ambient_hdrplus_read(struct ambient_hdrplus_bits *r, unsigned n, uint32_t *value)
{
    if (n > 32 || r->pos > r->bits || n > r->bits - r->pos) return false;
    uint32_t v = 0;
    for (unsigned i = 0; i < n; ++i, ++r->pos)
        v = (v << 1) | ((r->data[r->pos / 8] >> (7 - r->pos % 8)) & 1);
    *value = v;
    return true;
}
static inline bool ambient_hdrplus_grid(struct ambient_hdrplus_bits *r,
                                        struct ambient_hdrplus_grid *g)
{
    uint32_t rows, cols, value;
    if (!ambient_hdrplus_read(r, 5, &rows) || !ambient_hdrplus_read(r, 5, &cols) ||
        rows < 2 || rows > 25 || cols < 2 || cols > 25) return false;
    g->rows = rows; g->cols = cols;
    for (uint32_t y = 0; y < rows; ++y)
        for (uint32_t x = 0; x < cols; ++x) {
            if (!ambient_hdrplus_read(r, 4, &value)) return false;
            g->values[y][x] = value;
        }
    return true;
}
static inline bool ambient_hdrplus_geometry(struct ambient_hdrplus_bits *r,
                                            struct ambient_hdrplus_geometry *g)
{
    uint32_t left, top, right, bottom, cx, cy, angle, inner, outer, minor, overlap;
    if (!ambient_hdrplus_read(r, 16, &left) ||
        !ambient_hdrplus_read(r, 16, &top) ||
        !ambient_hdrplus_read(r, 16, &right) ||
        !ambient_hdrplus_read(r, 16, &bottom) ||
        right < left || bottom < top ||
        !ambient_hdrplus_read(r, 16, &cx) ||
        !ambient_hdrplus_read(r, 16, &cy) ||
        !ambient_hdrplus_read(r, 8, &angle) || angle > 180 ||
        !ambient_hdrplus_read(r, 16, &inner) ||
        !ambient_hdrplus_read(r, 16, &outer) ||
        !ambient_hdrplus_read(r, 16, &minor) ||
        !inner || !outer || !minor || inner > outer || minor > outer ||
        !ambient_hdrplus_read(r, 1, &overlap)) return false;
    *g = (struct ambient_hdrplus_geometry){left, top, right, bottom, cx, cy,
        inner, outer, minor, angle, overlap};
    return true;
}
static inline bool ambient_hdrplus_parse(const uint8_t *data, size_t size, struct ambient_hdrplus *out)
{
    static const uint8_t header[] = {0xb5, 0x00, 0x3c, 0x00, 0x01, 0x04};
    if (!data || !out || size <= sizeof(header) || size > 1024 ||
        memcmp(data, header, sizeof(header))) return false;
    struct ambient_hdrplus_bits r = { data + sizeof(header), (size - sizeof(header)) * 8, 0 };
    struct ambient_hdrplus m = {0};
    uint32_t v;
#define READ(n) do { if (!ambient_hdrplus_read(&r, (n), &v)) return false; } while (0)
    READ(8); if (v > 1) return false; m.version = v;
    READ(2); if (!v || v > 3) return false; m.window_count = v;
    m.global_projection = m.window_count > 1;
    for (unsigned w = 1; w < m.window_count; ++w)
        if (!ambient_hdrplus_geometry(&r, &m.windows[w].geometry)) return false;
    READ(27); if (v > 10000) return false; m.target_nits = (float)v;
    READ(1); if (v) {
        m.global_projection = true;
        if (!ambient_hdrplus_grid(&r, &m.targeted_grid)) return false;
    }
    for (unsigned w = 0; w < m.window_count; ++w) {
        // Window zero covers the complete picture, other windows retain
        // independent distributions rather than overwriting the global data.
        struct ambient_hdrplus_window *p = &m.windows[w];
        for (unsigned i = 0; i < 3; ++i) {
            READ(17); if (v > 100000) return false; p->maxscl[i] = v / 100000.0f;
        }
        READ(17); if (v > 100000) return false; p->average = v / 100000.0f;
        READ(4); p->histogram_count = v;
        unsigned previous = 0;
        for (unsigned i = 0; i < p->histogram_count; ++i) {
            READ(7); if ((i && v <= previous) || v > 100) return false;
            previous = v; p->histogram[i].percentage = v;
            READ(17); if (v > 100000) return false; p->histogram[i].percentile = v / 100000.0f;
        }
        READ(10); if (v > 1000) return false; p->fraction = v / 1000.0f;
    }
    READ(1); if (v) {
        m.global_projection = true;
        if (!ambient_hdrplus_grid(&r, &m.mastering_grid)) return false;
    }
    for (unsigned w = 0; w < m.window_count; ++w) {
        struct ambient_hdrplus_window *p = &m.windows[w];
        READ(1); p->tone_mapping = v != 0;
        if (p->tone_mapping) {
            if (m.target_nits <= 0) return false;
            READ(12); p->knee_x = v / 4095.0f;
            READ(12); p->knee_y = v / 4095.0f;
            READ(4); p->anchor_count = v;
            for (unsigned i = 0; i < p->anchor_count; ++i) {
                READ(10); p->anchors[i] = v / 1023.0f;
            }
        }
        READ(1); p->saturation_mapping = v != 0;
        if (p->saturation_mapping) {
            m.global_projection = true;
            READ(6); p->saturation_weight = v;
        }
    }
    if (r.bits - r.pos > 7) return false;
    while (r.pos < r.bits) { READ(1); if (v) return false; }
#undef READ
    // Syntax compatibility is independent of checkable metadata validity.
    // Statistics derived from the source pixels cannot be verified here.
    m.metadata_constraints_ok=true;
    m.version0_recommended_fraction=m.version==0;
    for(unsigned i=0;i<m.window_count;i++){
        const struct ambient_hdrplus_window *w=&m.windows[i];
        // Legacy v0/2016 values are allowed but separately identified.
        if(w->fraction!=0.f)m.version0_recommended_fraction=false;
        if((m.version==1&&i>0&&w->fraction!=0.f) ||
            !ambient_hdrplus_distribution_ordered(w,m.version) ||
            (w->tone_mapping&&!ambient_hdrplus_curve_monotone(w)))
            m.metadata_constraints_ok=false;
    }
    const struct ambient_hdrplus_window *p=&m.windows[0];
    bool no_optional=m.window_count==1&&!m.targeted_grid.rows&&
        !m.mastering_grid.rows&&!p->saturation_mapping;
    if(m.version==0)m.version0_recommended_features=no_optional;
    if(m.version==1){
        m.version1_constraints_ok=m.metadata_constraints_ok&&no_optional&&
            p->histogram_count==9&&p->anchor_count<=9;
        m.metadata_constraints_ok=m.version1_constraints_ok;
        static const uint8_t recommended[9]={1,5,10,25,50,75,90,95,99};
        m.version1_recommended_distribution=p->histogram_count==9;
        for(unsigned i=0;i<p->histogram_count&&i<9;i++)
            if(p->histogram[i].percentage!=recommended[i])
                m.version1_recommended_distribution=false;
    }
    *out = m;
    return true;
}
