#pragma once
#include "ambient_dovi_parser.h"
#include <stdint.h>
/* Private host audit interchange, not an external format. The extractor and
 * the renderer audit must be built from the same checkout and host ABI. */
struct ambient_profile7_audit_frame {
    struct ambient_dovi_metadata rpu;
    uint32_t bw,bh,ew,eh;
    int64_t pts;
    int32_t tb_num,tb_den;
};
