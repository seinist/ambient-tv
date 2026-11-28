/* SPDX-License-Identifier: GPL-2.0-or-later
 * Local register allocation across an entire pure-native trace. The input
 * uses x0 for the guest register file, x7 for SR, w1..w5 for scratch.
 * No generated helper calls are permitted while cached registers are live.
 */
#ifndef AMBIENT_SH2_REGCACHE_H
#define AMBIENT_SH2_REGCACHE_H
#include <stdint.h>
#define AMBIENT_SH2_REGCACHE_HOSTS 10
#define AMBIENT_SH2_REGCACHE_EXTRA (2*AMBIENT_SH2_REGCACHE_HOSTS)
static inline int ambient_sh2_gpr_access(uint32_t word, unsigned* guest, unsigned* load)
{
    const uint32_t form = word & 0xffc003e0u;
    if (form != 0xb9400000u && form != 0xb9000000u) return 0;
    *guest = (word >> 10) & 4095u;
    if (*guest >= 16) return 0; /* MACH/MACL/GBR/VBR/PR are not GPRs */
    *load = form == 0xb9400000u;
    return 1;
}
/* Returns the number of output words. Input excludes entry/return instructions.
 * Allocation is based on actual generated accesses, so aliases and opcodes
 * with implicit R0 need no special decoder. No flags are modified by MOV.
 */
static inline unsigned ambient_sh2_regcache(const uint32_t* input, unsigned words,
                                             uint32_t* output)
{
    unsigned count[16]={0}, mapping[16]={0}, loaded=0, dirty=0, cursor=0;
    for(unsigned i=0;i<words;++i) {
        unsigned guest, load;
        if(ambient_sh2_gpr_access(input[i],&guest,&load)) ++count[guest];
    }
    for(unsigned host=8;host<8+AMBIENT_SH2_REGCACHE_HOSTS;++host) {
        unsigned best=16;
        for(unsigned guest=0;guest<16;++guest)
            if(!mapping[guest] && count[guest]>=3 &&
               (best==16 || count[guest]>count[best])) best=guest;
        if(best==16)break;
        mapping[best]=host;
    }
    for(unsigned i=0;i<words;++i) {
        unsigned guest,load;
        const uint32_t word=input[i];
        if(!ambient_sh2_gpr_access(word,&guest,&load)||!mapping[guest]) {
            output[cursor++]=word;continue;
        }
        const unsigned host=mapping[guest], rt=word&31u, bit=1u<<guest;
        if(load) {
            if(!(loaded&bit)) {
                output[cursor++]=0xb9400000u|(guest<<10)|host;
                loaded|=bit;
            }
            output[cursor++]=0x2a0003e0u|(host<<16)|rt;
        } else {
            output[cursor++]=0x2a0003e0u|(rt<<16)|host;
            loaded|=bit;dirty|=bit;
        }
    }
    for(unsigned guest=0;guest<16;++guest)
        if(dirty&(1u<<guest))output[cursor++]=0xb9000000u|(guest<<10)|mapping[guest];
    return cursor;
}
#endif
