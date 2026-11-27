#pragma once
/* Private Ambient TV static-link interface, built against the pinned FFmpeg.
 * No FFmpeg-private types cross the library boundary. */
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
struct ambient_dovi_curve {
    uint8_t num_pivots;
    uint16_t pivots[9];
    uint8_t mapping[8], poly_order[8], mmr_order[8];
    int64_t poly_coef[8][3], mmr_constant[8], mmr_coef[8][3][7];
};
struct ambient_dovi_metadata {
    uint8_t coef_log2_denom, bl_bit_depth, el_bit_depth, full_range;
    float nonlinear_offset[3], nonlinear_matrix[9], linear_matrix[9];
    uint16_t source_min_pq, source_max_pq;
    struct ambient_dovi_curve curves[3];
};
struct ambient_dovi_parser;
struct ambient_dovi_parser *av_ambient_dovi_alloc(void);
void av_ambient_dovi_flush(struct ambient_dovi_parser *);
void av_ambient_dovi_free(struct ambient_dovi_parser *);
/* Unescaped RPU beginning with byte 25, single-layer profile 8 only.
 * Returns zero on success, a negative AVERROR on any unsupported/invalid RPU.
 * The output is a value snapshot, not borrowed parser state. */
int av_ambient_dovi_parse(struct ambient_dovi_parser *, const uint8_t *, size_t,
                         struct ambient_dovi_metadata *);
#ifdef __cplusplus
}
#endif
