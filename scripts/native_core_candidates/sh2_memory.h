/* SPDX-License-Identifier: GPL-2.0-or-later
 * Predecoded SH2 MOV memory operations. Native C implementation preserves the
 * pinned interpreter's mapped bus callbacks; direct host RAM dereferences
 * would bypass write notifications, cache aliases, and device side effects.
 */
#ifndef AMBIENT_SH2_MEMORY_H
#define AMBIENT_SH2_MEMORY_H
#include <stdint.h>
enum AmbientMemMode { AM_NONE, AM_REG, AM_INDEX, AM_DISP, AM_GBR, AM_PC, AM_PRE, AM_POST };
typedef struct {
    uint8_t mode, load, size, base, reg, wait;
    uint16_t offset;
} AmbientMemOp;
static inline AmbientMemOp ambient_mem_decode(uint16_t op) {
    AmbientMemOp a = {0,0,0,0,0,0,0};
    unsigned n=(op>>8)&15, m=(op>>4)&15, low=op&15, top=op>>12;
    if (top==6 && (low<=2 || (low>=4 && low<=6))) {
        a.mode=low>=4?AM_POST:AM_REG; a.load=1; a.size=1u<<(low&3);
        a.base=m;a.reg=n;a.wait=low==1||low==2;
    } else if (top==2 && (low<=2 || (low>=4 && low<=6))) {
        a.mode=low>=4?AM_PRE:AM_REG;a.size=1u<<(low&3);a.base=n;a.reg=m;
    } else if (top==0 && ((low>=4&&low<=6)||(low>=12&&low<=14))) {
        a.mode=AM_INDEX;a.load=low>=12;a.size=1u<<(low&3);
        a.base=a.load?m:n;a.reg=a.load?n:m;
    } else if (top==1 || top==5) {
        a.mode=AM_DISP;a.load=top==5;a.size=4;a.offset=low*4;
        a.base=a.load?m:n;a.reg=a.load?n:m;
    } else if (top==8 && (n==0||n==1||n==4||n==5)) {
        a.mode=AM_DISP;a.load=n>=4;a.size=1u<<(n&1);
        a.base=m;a.reg=0;a.offset=low*a.size;
    } else if (top==12 && (n<=2||(n>=4&&n<=6))) {
        a.mode=AM_GBR;a.load=n>=4;a.size=1u<<(n&3);a.reg=0;a.offset=(op&255)*a.size;
    } else if (top==9 || top==13) {
        a.mode=AM_PC;a.load=1;a.size=top==9?2:4;a.reg=n;a.offset=(op&255)*a.size;
    }
    return a;
}
static inline uint32_t ambient_mem_address(AmbientMemOp a,const uint32_t* r,uint32_t pc,uint32_t gbr) {
    switch(a.mode) {
    case AM_REG:case AM_POST:return r[a.base];
    case AM_PRE:return r[a.base]-a.size;
    case AM_INDEX:return r[a.base]+r[0];
    case AM_DISP:return r[a.base]+a.offset;
    case AM_GBR:return gbr+a.offset;
    case AM_PC:return (a.size==4?((pc+4)&~3u):pc+4)+a.offset;
    default:return 0;
    }
}
#if defined(AMBIENT_SH2_ARM64_JIT)
static inline void ambient_mem_execute(SH2_struct* sh,AmbientMemOp a) {
    const u32 addr=ambient_mem_address(a,sh->regs.R,sh->regs.PC,sh->regs.GBR);
    // Only the two direct word/long loads in the pin use this wait check.
    if(a.wait && sh2_check_wait(sh,addr,a.size==2?1:2)) {
        ++sh->cycles;return; // retry same instruction without modifying registers
    }
    if(a.load) {
        u32 value;
        switch(a.size) {
        case 1:value=(u32)(s32)(s8)sh->MappedMemoryReadByte(sh,addr);break;
        case 2:value=(u32)(s32)(s16)sh->MappedMemoryReadWord(sh,addr);break;
        default:value=sh->MappedMemoryReadLong(sh,addr);break;
        }
        sh->regs.R[a.reg]=value;
        if(a.mode==AM_POST && a.base!=a.reg)sh->regs.R[a.base]+=a.size;
    } else {
        const u32 value=sh->regs.R[a.reg]; // predecrement aliases use old value
        switch(a.size) {
        case 1:sh->MappedMemoryWriteByte(sh,addr,value);break;
        case 2:sh->MappedMemoryWriteWord(sh,addr,value);break;
        default:sh->MappedMemoryWriteLong(sh,addr,value);break;
        }
        if(a.mode==AM_PRE)sh->regs.R[a.base]-=a.size;
    }
    sh->regs.PC+=2;++sh->cycles;
}
#endif
#endif
