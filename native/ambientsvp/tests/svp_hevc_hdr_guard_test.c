/* SPDX-License-Identifier: Apache-2.0 */
#include "svp_hevc_hdr_guard.h"
#include <assert.h>
int main(void)
{
    const uint8_t sdr[]={0,0,0,1,0x26,1,0x80};
    const uint8_t static_hdr[]={0,0,1,0x4e,1,137,2,1,2,0x80,0,0,1,0x26,1,0x80};
    const uint8_t hdr10plus[]={0,0,1,0x4e,1,4,7,0xb5,0,0x3c,0,1,4,1,0x80};
    const uint8_t suffix[]={0,0,1,0x50,1,4,7,0xb5,0,0x3c,0,1,4,1,0x80};
    const uint8_t captions[]={0,0,1,0x4e,1,4,4,0xb5,0,0x31,0x47,0x80};
    const uint8_t escaped[]={0,0,1,0x4e,1,137,5,0,0,3,1,2,3,0x80};
    const uint8_t rpu[]={0,0,1,0x7c,1,0x80};
    const uint8_t el[]={0,0,1,0x26,9,0x80};
    const uint8_t truncated[]={0,0,1,0x4e,1,4,10,0xb5,0,0x3c,0x80};
    assert(!svp_hevc_dynamic_hdr(sdr,sizeof(sdr)));
    assert(!svp_hevc_dynamic_hdr(static_hdr,sizeof(static_hdr)));
    assert(!svp_hevc_dynamic_hdr(captions,sizeof(captions)));
    assert(!svp_hevc_dynamic_hdr(escaped,sizeof(escaped)));
    assert(svp_hevc_dynamic_hdr(hdr10plus,sizeof(hdr10plus)));
    assert(svp_hevc_dynamic_hdr(suffix,sizeof(suffix)));
    assert(svp_hevc_dynamic_hdr(rpu,sizeof(rpu)));
    assert(svp_hevc_dynamic_hdr(el,sizeof(el)));
    assert(svp_hevc_dynamic_hdr(truncated,sizeof(truncated)));
    assert(svp_hevc_dynamic_hdr(sdr,3));
    return 0;
}
