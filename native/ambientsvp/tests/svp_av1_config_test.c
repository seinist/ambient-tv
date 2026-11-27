/* SPDX-License-Identifier: Apache-2.0 */
#include "svp_av1_config.h"
#include <assert.h>

int main(void)
{
    /* Framing fixture only: VLC validates the sequence syntax separately. */
    const uint8_t config[]={0x81,0x04,0x0c,0x00,0x0a,0x02,0x00,0x01,0x2a,0x01,0x03};
    SvpAv1Config c;
    assert(svp_av1_config(config,sizeof(config),&c));
    assert(c.csd==config+4 && c.csd_size==7);
    assert(c.sequence.data==config+4 && c.sequence.size==4);
    for (size_t n=0;n<8;n++) assert(!svp_av1_config(config,n,&c));
    uint8_t bad[sizeof(config)];
    memcpy(bad,config,sizeof(bad));bad[2]|=0x40; /* Main10 */
    assert(!svp_av1_config(bad,sizeof(bad),&c));
    memcpy(bad,config,sizeof(bad));bad[2]|=0x10; /* monochrome */
    assert(!svp_av1_config(bad,sizeof(bad),&c));
    memcpy(bad,config,sizeof(bad));bad[1]|=0x20; /* High */
    assert(!svp_av1_config(bad,sizeof(bad),&c));
    memcpy(bad,config,sizeof(bad));bad[5]=0x7f; /* truncated OBU */
    assert(!svp_av1_config(bad,sizeof(bad),&c));
    memcpy(bad,config,sizeof(bad));bad[8]=0x0a; /* duplicate sequence */
    assert(!svp_av1_config(bad,sizeof(bad),&c));
    assert(svp_av1_config(config,sizeof(config),&c));
    const uint8_t packet[]={0x12,0x00,0x0a,0x02,0x00,0x01,0x32,0x01,0xff};
    assert(svp_av1_same_sequence(&c,packet,sizeof(packet)));
    uint8_t changed[sizeof(packet)];memcpy(changed,packet,sizeof(packet));changed[5]^=1;
    assert(!svp_av1_same_sequence(&c,changed,sizeof(changed)));
    assert(!svp_av1_same_sequence(&c,packet,sizeof(packet)-1));
    const uint8_t unsized[]={0x30,0x55};
    assert(svp_av1_same_sequence(&c,unsized,sizeof(unsized)));
    const uint8_t overflow[]={0x0a,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0x7f};
    assert(!svp_av1_same_sequence(&c,overflow,sizeof(overflow)));
    return 0;
}
