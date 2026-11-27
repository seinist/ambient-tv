/* SPDX-License-Identifier: Apache-2.0 */
#include "svp_sdr_profile.h"
#include <assert.h>
int main(void)
{
    for (int p=-1;p<256;p++) {
        assert(svp_sdr8_profile(SVP_CODEC_AVC,p)==(p==66||p==77||p==100));
        assert(svp_sdr8_profile(SVP_CODEC_HEVC,p)==(p==1));
        assert(svp_sdr8_profile(SVP_CODEC_VP9,p)==(p==0));
        assert(!svp_sdr8_profile(SVP_CODEC_UNSUPPORTED,p));
    }
    assert(!svp_sdr8_profile(SVP_CODEC_HEVC,0x101));
    assert(!svp_sdr8_profile((SvpSdrCodec)99,0));
    return 0;
}
