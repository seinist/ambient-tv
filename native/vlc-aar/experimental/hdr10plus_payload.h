#pragma once
/* Restricted ST 2094-40 application 4 parser for the global-window subset
 * representable by libplacebo 5. Unsupported features fail closed.
 * All reads are bounded and the caller receives a result only on success. */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

enum ambient_hdrplus_status { AMBIENT_HDRPLUS_NONE, AMBIENT_HDRPLUS_VALID, AMBIENT_HDRPLUS_UNSUPPORTED };
struct ambient_hdrplus {
    uint8_t version;
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
static inline bool ambient_hdrplus_parse(const uint8_t *data, size_t size, struct ambient_hdrplus *out)
{
    /* Complete registered ITU-T T.35 header: country, provider, oriented
     * provider, application identifier. Do not guess payload offsets. */
    static const uint8_t header[] = {0xb5, 0x00, 0x3c, 0x00, 0x01, 0x04};
    if (!data || !out || size <= sizeof(header) || size > 1024 ||
        memcmp(data, header, sizeof(header))) return false;
    struct ambient_hdrplus_bits r = { data + sizeof(header), (size - sizeof(header)) * 8, 0 };
    struct ambient_hdrplus m = {0};
    uint32_t v;
#define READ(n) do { if (!ambient_hdrplus_read(&r, (n), &v)) return false; } while (0)
    READ(8); if (v != 1) return false; m.version = v;
    READ(2); if (v != 1) return false; /* one full-frame window */
    READ(27); if (v > 10000) return false; m.target_nits = (float)v;
    READ(1); if (v) return false; /* targeted-display luminance grid */
    for (unsigned i = 0; i < 3; ++i) {
        READ(17); if (v > 100000) return false; m.maxscl[i] = v / 100000.0f;
    }
    READ(17); if (v > 100000) return false; m.average = v / 100000.0f;
    READ(4); m.histogram_count = v;
    unsigned previous = 0;
    for (unsigned i = 0; i < m.histogram_count; ++i) {
        READ(7); if (v <= previous || v > 100) return false;
        previous = v; m.histogram[i].percentage = v;
        READ(17); if (v > 100000) return false; m.histogram[i].percentile = v / 100000.0f;
    }
    READ(10); if (v > 1000) return false; m.fraction = v / 1000.0f;
    READ(1); if (v) return false; /* mastering-display luminance grid */
    READ(1); m.tone_mapping = v != 0;
    if (m.tone_mapping) {
        if (m.target_nits <= 0) return false;
        READ(12); m.knee_x = v / 4095.0f;
        READ(12); m.knee_y = v / 4095.0f;
        READ(4); m.anchor_count = v;
        if (!m.anchor_count) return false;
        for (unsigned i = 0; i < m.anchor_count; ++i) {
            READ(10); m.anchors[i] = v / 1023.0f;
        }
    }
    READ(1); if (v) return false; /* saturation mapping unsupported */
    if (r.bits - r.pos > 7) return false;
    while (r.pos < r.bits) { READ(1); if (v) return false; }
#undef READ
    *out = m;
    return true;
}
