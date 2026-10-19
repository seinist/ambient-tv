/* SPDX-License-Identifier: LGPL-2.1-or-later */
#ifndef AMBIENTSVP_RETRY_H
#define AMBIENTSVP_RETRY_H
#include <stdbool.h>

/*
 * Timing gaps (dropped source frame, PTS jump, broadcast splice) are transient. Instead of
 * passing through for the rest of the session, frames pass through for a cool-down and SVP
 * starts again. Repeated gaps back off exponentially (about 1 s, 2 s, ... capped at 32 s at
 * 25 fps); a long clean run forgets them. Format, unavailable and engine errors stay sticky.
 */
#define SVP_RETRY_BASE_FRAMES 25
#define SVP_RETRY_MAX_SHIFT 5
#define SVP_RETRY_CLEAN_FRAMES 750

typedef struct { int gaps, cooldown, clean; } SvpRetry;

static inline void svp_retry_clear(SvpRetry *r) { r->gaps = r->cooldown = r->clean = 0; }

/* A timing gap was reported: start (or lengthen) the pass-through cool-down. */
static inline void svp_retry_gap(SvpRetry *r) {
    int shift = r->gaps < SVP_RETRY_MAX_SHIFT ? r->gaps : SVP_RETRY_MAX_SHIFT;
    r->cooldown = SVP_RETRY_BASE_FRAMES << shift;
    r->gaps++;
    r->clean = 0;
}

/* Per source frame: true while this frame must still pass through. */
static inline bool svp_retry_waiting(SvpRetry *r) {
    if (r->cooldown <= 0) return false;
    r->cooldown--;
    return true;
}

/* Per interpolated source frame. */
static inline void svp_retry_frame_ok(SvpRetry *r) {
    if (r->gaps && ++r->clean >= SVP_RETRY_CLEAN_FRAMES) { r->gaps = 0; r->clean = 0; }
}
#endif
