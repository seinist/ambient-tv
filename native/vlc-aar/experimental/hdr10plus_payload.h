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
    bool version1_constraints_ok; /* ST 2094-40:2020 section 9.4 and 8.5.4/8.7.3.2 */
    float target_nits;
    struct ambient_hdrplus_window windows[3];
    struct ambient_hdrplus_grid targeted_grid, mastering_grid;
};
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
        !inner || !outer || !minor || inner > outer ||
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
    // Version 1 has a narrower metadata contract than the general syntax.
    // Preserve parseable legacy/nonconforming payloads for compatibility but
    // do not describe them as compliant. Version 0 does not use this flag.
    if (m.version==1) {
        const struct ambient_hdrplus_window *p=&m.windows[0];
        m.version1_constraints_ok=m.window_count==1 && !m.targeted_grid.rows &&
            !m.mastering_grid.rows && !p->saturation_mapping &&
            p->histogram_count==9 && p->anchor_count<=9;
        if (m.version1_constraints_ok && p->histogram[1].percentage==5 &&
            p->histogram[2].percentage==10 &&
            (p->histogram[1].percentile!=0.f || p->histogram[2].percentile!=255.f/100000.f))
            m.version1_constraints_ok=false;
    }
    *out = m;
    return true;
}
