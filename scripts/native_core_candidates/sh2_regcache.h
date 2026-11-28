/* SPDX-License-Identifier: GPL-2.0-or-later
 * Local register allocation across an entire pure-native trace. The input
 * uses x0 for the guest register file, x7 for SR, w1..w5 for scratch.
 * No generated helper calls are permitted while cached registers are live.
 */
#ifndef AMBIENT_SH2_REGCACHE_H
#define AMBIENT_SH2_REGCACHE_H
#include <stdint.h>
#include <stddef.h>
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
/* The emitter's only x0-relative memory operands are guest registers.
 * x7 refers to the distinct SR object. Reserve key 32 for SR; support
 * non-contiguous system-register layouts without baking in struct offsets.
 * Key 16 (the real layout's SR offset) is deliberately not cached via x0.
 */
#define AMBIENT_SH2_STATE_KEYS 33
static inline int ambient_sh2_state_access(uint32_t word,unsigned* guest,unsigned* load)
{
    const uint32_t form=word&0xffc003e0u;
    if(form==0xb94000e0u || form==0xb90000e0u) {
        if((word>>10)&4095u)return 0;
        *guest=32;*load=form==0xb94000e0u;return 1;
    }
    if(form!=0xb9400000u && form!=0xb9000000u)return 0;
    *guest=(word>>10)&4095u;
    if(*guest>=32 || *guest==16)return 0;
    *load=form==0xb9400000u;return 1;
}
static inline uint32_t ambient_sh2_state_word(unsigned guest,unsigned load,unsigned host)
{
    return (load?0xb9400000u:0xb9000000u) |
        (guest==32?0xe0u:(guest<<10)) | host;
}
/* Returns the number of output words. Input excludes entry/return instructions.
 * Allocation is based on actual generated accesses, so aliases and opcodes
 * with implicit R0 need no special decoder. No flags are modified by MOV.
 */
static inline unsigned ambient_sh2_regcache_split(const uint32_t* input, unsigned words,
                                             uint32_t* output,unsigned* loop_start,unsigned* loop_end)
{
    unsigned count[AMBIENT_SH2_STATE_KEYS]={0}, mapping[AMBIENT_SH2_STATE_KEYS]={0}, cursor=0;
    uint64_t loaded=0,dirty=0;
    for(unsigned i=0;i<words;++i) {
        unsigned guest, load;
        if(ambient_sh2_state_access(input[i],&guest,&load)) ++count[guest];
    }
    for(unsigned host=8;host<8+AMBIENT_SH2_REGCACHE_HOSTS;++host) {
        unsigned best=AMBIENT_SH2_STATE_KEYS;
        for(unsigned guest=0;guest<AMBIENT_SH2_STATE_KEYS;++guest)
            if(!mapping[guest] && count[guest]>=3 &&
               (best==AMBIENT_SH2_STATE_KEYS || count[guest]>count[best])) best=guest;
        if(best==AMBIENT_SH2_STATE_KEYS)break;
        mapping[best]=host;
    }
    if(loop_start) {
        /* The first access to each allocated register determines whether
         * its initial guest value is needed. A first store overwrites it
         * before any use on every iteration. Other loads can be hoisted
         * because this region has no calls or aliased guest writes. */
        uint64_t seen=0;
        for(unsigned i=0;i<words;++i) {
            unsigned guest,load;
            if(!ambient_sh2_state_access(input[i],&guest,&load)||!mapping[guest])continue;
            const uint64_t bit=UINT64_C(1)<<guest;
            if(seen&bit)continue;
            seen|=bit;
            if(load) {
                output[cursor++]=ambient_sh2_state_word(guest,1,mapping[guest]);
                loaded|=bit;
            }
        }
        *loop_start=cursor;
    }
    for(unsigned i=0;i<words;++i) {
        unsigned guest,load;
        const uint32_t word=input[i];
        if(!ambient_sh2_state_access(word,&guest,&load)||!mapping[guest]) {
            output[cursor++]=word;continue;
        }
        const unsigned host=mapping[guest], rt=word&31u;
        const uint64_t bit=UINT64_C(1)<<guest;
        if(load) {
            if(!(loaded&bit)) {
                output[cursor++]=ambient_sh2_state_word(guest,1,host);
                loaded|=bit;
            }
            output[cursor++]=0x2a0003e0u|(host<<16)|rt;
        } else {
            output[cursor++]=0x2a0003e0u|(rt<<16)|host;
            loaded|=bit;dirty|=bit;
        }
    }
    if(loop_end)*loop_end=cursor;
    for(unsigned guest=0;guest<AMBIENT_SH2_STATE_KEYS;++guest)
        if(dirty&(UINT64_C(1)<<guest))output[cursor++]=ambient_sh2_state_word(guest,0,mapping[guest]);
    return cursor;
}
static inline unsigned ambient_sh2_regcache(const uint32_t* input,unsigned words,uint32_t* output)
{
    return ambient_sh2_regcache_split(input,words,output,NULL,NULL);
}
#endif
