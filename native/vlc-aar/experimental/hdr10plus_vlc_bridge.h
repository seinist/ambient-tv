#pragma once
#include <vlc_common.h>
#include <vlc_ancillary.h>
#include "hdr10plus_payload.h"

/* Private frame ancillary carrying all spatial metadata, including fields
 * that VLC_ANCILLARY_ID_HDR10PLUS cannot currently represent. */
#define AMBIENT_HDRPLUS_FULL_ID VLC_FOURCC('A','H','P','+')

static inline void ambient_hdrplus_project_global(vlc_video_hdr_dynamic_metadata_t *dst,
                                                  const struct ambient_hdrplus *m)
{
    const struct ambient_hdrplus_window *w = &m->windows[0];
    *dst = (vlc_video_hdr_dynamic_metadata_t){0};
    dst->country_code = 0xb5;
    dst->application_version = m->version;
    dst->targeted_luminance = m->target_nits;
    for (unsigned i=0;i<3;++i) dst->maxscl[i] = w->maxscl[i];
    dst->average_maxrgb = w->average;
    dst->fraction_bright_pixels = w->fraction;
    dst->num_histogram = w->histogram_count;
    for (unsigned i=0;i<w->histogram_count;++i) {
        dst->histogram[i].percentage = w->histogram[i].percentage;
        dst->histogram[i].percentile = w->histogram[i].percentile;
    }
    dst->tone_mapping_flag = w->tone_mapping;
    dst->knee_point_x = w->knee_x;
    dst->knee_point_y = w->knee_y;
    dst->num_bezier_anchors = w->anchor_count;
    for (unsigned i=0;i<w->anchor_count;++i) dst->bezier_curve_anchors[i] = w->anchors[i];
}
