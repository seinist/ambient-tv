/* gcc -std=c11 -Wall -Werror -Iinclude tests/svp_retry_test.c -o /tmp/svp_retry_test && /tmp/svp_retry_test */
#include <assert.h>
#include <stdio.h>
#include "svp_retry.h"

static int passthrough_frames(SvpRetry *r) { int n = 0; while (svp_retry_waiting(r)) n++; return n; }

int main(void) {
    SvpRetry r; svp_retry_clear(&r);
    assert(!svp_retry_waiting(&r));
    /* One gap: about a second of pass-through, then SVP runs again. */
    svp_retry_gap(&r);
    assert(passthrough_frames(&r) == SVP_RETRY_BASE_FRAMES);
    assert(!svp_retry_waiting(&r));
    /* Repeated gaps back off exponentially and cap. */
    svp_retry_gap(&r); assert(passthrough_frames(&r) == SVP_RETRY_BASE_FRAMES * 2);
    for (int i = 0; i < 10; i++) svp_retry_gap(&r);
    assert(passthrough_frames(&r) == SVP_RETRY_BASE_FRAMES << SVP_RETRY_MAX_SHIFT);
    /* A long clean run forgets the earlier gaps. */
    for (int i = 0; i < SVP_RETRY_CLEAN_FRAMES; i++) svp_retry_frame_ok(&r);
    assert(r.gaps == 0);
    svp_retry_gap(&r); assert(passthrough_frames(&r) == SVP_RETRY_BASE_FRAMES);
    /* A gap inside the clean run restarts the count. */
    for (int i = 0; i < SVP_RETRY_CLEAN_FRAMES - 1; i++) svp_retry_frame_ok(&r);
    svp_retry_gap(&r); assert(r.gaps == 2 && r.clean == 0);
    svp_retry_clear(&r); assert(r.gaps == 0 && r.cooldown == 0);
    puts("svp_retry ok");
    return 0;
}
