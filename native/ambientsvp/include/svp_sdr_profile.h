/* SPDX-License-Identifier: Apache-2.0
 * Fail-closed allowlist for the 8-bit 4:2:0 opaque decoder path.
 * Profiles are elementary-stream profile IDs, not Android OMX flags.
 */
#ifndef AMBIENTSVP_SDR_PROFILE_H
#define AMBIENTSVP_SDR_PROFILE_H
#include <stdbool.h>
typedef enum {
    SVP_CODEC_AVC, SVP_CODEC_HEVC, SVP_CODEC_VP9, SVP_CODEC_UNSUPPORTED
} SvpSdrCodec;
static inline bool svp_sdr8_profile(SvpSdrCodec codec,int profile)
{
    switch (codec) {
    case SVP_CODEC_AVC: return profile==66 || profile==77 || profile==100;
    case SVP_CODEC_HEVC: return profile==1; /* Main, not Main 10/RExt */
    case SVP_CODEC_VP9: return profile==0; /* 8-bit 4:2:0, not profiles 1..3 */
    default: return false;
    }
}
#endif
