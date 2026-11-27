#pragma once
#include <vlc_common.h>
#include <vlc_es.h>
#include <vlc_ancillary.h>
#include "ambient_dovi_parser.h"

static inline bool ambient_dovi_format(const video_format_t *f)
{
    // Only a verifiable single-layer PQ/BT.2020 limited-range base layer.
    return f && f->dovi.profile == 8 && f->dovi.bl_present &&
        f->dovi.rpu_present && !f->dovi.el_present &&
        f->primaries == COLOR_PRIMARIES_BT2020 && f->space == COLOR_SPACE_BT2020 &&
        f->transfer == TRANSFER_FUNC_SMPTE_ST2084 &&
        f->color_range == COLOR_RANGE_LIMITED;
}
static inline void ambient_dovi_map(vlc_video_dovi_metadata_t *dst,
                                     const struct ambient_dovi_metadata *src)
{
    *dst = (vlc_video_dovi_metadata_t){0};
    dst->bl_bit_depth = src->bl_bit_depth;
    dst->el_bit_depth = src->el_bit_depth;
    dst->coef_log2_denom = src->coef_log2_denom;
    dst->nlq_method_idc = VLC_DOVI_NLQ_NONE;
    dst->source_min_pq = src->source_min_pq;
    dst->source_max_pq = src->source_max_pq;
    memcpy(dst->nonlinear_offset, src->nonlinear_offset, sizeof(dst->nonlinear_offset));
    memcpy(dst->nonlinear_matrix, src->nonlinear_matrix, sizeof(dst->nonlinear_matrix));
    memcpy(dst->linear_matrix, src->linear_matrix, sizeof(dst->linear_matrix));
    for (unsigned i = 0; i < 3; ++i) {
        struct vlc_dovi_reshape_t *d = &dst->curves[i];
        const struct ambient_dovi_curve *s = &src->curves[i];
        d->num_pivots = s->num_pivots;
        memcpy(d->pivots, s->pivots, sizeof(d->pivots));
        for (unsigned j = 0; j + 1 < s->num_pivots; ++j) {
            d->mapping_idc[j] = s->mapping[j];
            d->poly_order[j] = s->poly_order[j];
            d->mmr_order[j] = s->mmr_order[j];
            memcpy(d->poly_coef[j], s->poly_coef[j], sizeof(d->poly_coef[j]));
            d->mmr_constant[j] = s->mmr_constant[j];
            memcpy(d->mmr_coef[j], s->mmr_coef[j], sizeof(d->mmr_coef[j]));
        }
    }
}
