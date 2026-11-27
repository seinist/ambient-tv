#pragma once
/* ST 2094-40 application 4 parser. Validate every window and optional grid;
 * project window zero to VLC/libplacebo's global metadata contract. Like VLC's
 * avcodec mapper, the renderer cannot apply spatial window/saturation mapping.
 * All reads are bounded and the caller receives a result only on success. */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

enum ambient_hdrplus_status { AMBIENT_HDRPLUS_NONE, AMBIENT_HDRPLUS_VALID, AMBIENT_HDRPLUS_UNSUPPORTED };
struct ambient_hdrplus {
    uint8_t version, window_count;
    bool global_projection; /* metadata beyond VLC/libplacebo's global contract */
    float target_nits, maxscl[3], average, fraction;
    uint8_t histogram_count;
    struct { uint8_t percentage; float percentile; } histogram[15];
    bool tone_mapping;
    float knee_x, knee_y;
    uint8_t anchor_count;
    float anchors[15];
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
static inline bool ambient_hdrplus_grid(struct ambient_hdrplus_bits *r)
{
    uint32_t rows, cols, value;
    if (!ambient_hdrplus_read(r, 5, &rows) || !ambient_hdrplus_read(r, 5, &cols) ||
        rows < 2 || rows > 25 || cols < 2 || cols > 25) return false;
    for (uint32_t i = 0; i < rows * cols; ++i)
        if (!ambient_hdrplus_read(r, 4, &value)) return false;
    return true;
}
static inline bool ambient_hdrplus_geometry(struct ambient_hdrplus_bits *r)
{
    // Geometry belongs to local windows only; preserve bit alignment and
    // validate ordering/rotation without claiming a spatial tone map.
    uint32_t left, top, right, bottom, cx, cy, angle, inner, outer, minor, overlap;
    return ambient_hdrplus_read(r, 16, &left) &&
        ambient_hdrplus_read(r, 16, &top) &&
        ambient_hdrplus_read(r, 16, &right) &&
        ambient_hdrplus_read(r, 16, &bottom) &&
        right >= left && bottom >= top &&
        ambient_hdrplus_read(r, 16, &cx) &&
        ambient_hdrplus_read(r, 16, &cy) &&
        ambient_hdrplus_read(r, 8, &angle) && angle <= 180 &&
        ambient_hdrplus_read(r, 16, &inner) &&
        ambient_hdrplus_read(r, 16, &outer) &&
        ambient_hdrplus_read(r, 16, &minor) &&
        inner <= outer &&
        ambient_hdrplus_read(r, 1, &overlap);
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
    READ(8); if (v != 1) return false; m.version = v;
    READ(2); if (!v || v > 3) return false; m.window_count = v;
    m.global_projection = m.window_count > 1;
    for (unsigned w = 1; w < m.window_count; ++w)
        if (!ambient_hdrplus_geometry(&r)) return false;
    READ(27); if (v > 10000) return false; m.target_nits = (float)v;
    READ(1); if (v) {
        m.global_projection = true;
        if (!ambient_hdrplus_grid(&r)) return false;
    }
    for (unsigned w = 0; w < m.window_count; ++w) {
        // Window zero covers the complete picture. Other windows are fully
        // parsed and checked, but do not replace the global statistics.
        struct ambient_hdrplus local = {0};
        struct ambient_hdrplus *p = w ? &local : &m;
        for (unsigned i = 0; i < 3; ++i) {
            READ(17); if (v > 100000) return false; p->maxscl[i] = v / 100000.0f;
        }
        READ(17); if (v > 100000) return false; p->average = v / 100000.0f;
        READ(4); p->histogram_count = v;
        unsigned previous = 0;
        for (unsigned i = 0; i < p->histogram_count; ++i) {
            READ(7); if (v <= previous || v > 100) return false;
            previous = v; p->histogram[i].percentage = v;
            READ(17); if (v > 100000) return false; p->histogram[i].percentile = v / 100000.0f;
        }
        READ(10); if (v > 1000) return false; p->fraction = v / 1000.0f;
    }
    READ(1); if (v) {
        m.global_projection = true;
        if (!ambient_hdrplus_grid(&r)) return false;
    }
    for (unsigned w = 0; w < m.window_count; ++w) {
        struct ambient_hdrplus local = {0};
        struct ambient_hdrplus *p = w ? &local : &m;
        READ(1); p->tone_mapping = v != 0;
        if (p->tone_mapping) {
            if (m.target_nits <= 0) return false;
            READ(12); p->knee_x = v / 4095.0f;
            READ(12); p->knee_y = v / 4095.0f;
            READ(4); p->anchor_count = v;
            if (!p->anchor_count) return false;
            for (unsigned i = 0; i < p->anchor_count; ++i) {
                READ(10); p->anchors[i] = v / 1023.0f;
            }
        }
        READ(1); if (v) {
            m.global_projection = true;
            READ(6); // color saturation weight: no corresponding libplacebo field
        }
    }
    if (r.bits - r.pos > 7) return false;
    while (r.pos < r.bits) { READ(1); if (v) return false; }
#undef READ
    *out = m;
    return true;
}
