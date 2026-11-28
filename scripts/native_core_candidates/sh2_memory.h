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
enum AmbientComplexMode { AC_NONE, AC_TST, AC_AND, AC_XOR, AC_OR, AC_TAS,
                          AC_LOAD_SYS, AC_STORE_SYS, AC_MACW, AC_MACL, AC_TRAP, AC_RTE };
typedef struct {
    uint8_t mode, n, m, control, sys, imm;
} AmbientComplexMem;
static inline AmbientComplexMem ambient_complex_decode(uint16_t op) {
    AmbientComplexMem a={0,(op>>8)&15,(op>>4)&15,0,0,op&255};
    if ((op&0xff00)>=0xcc00 && (op&0xff00)<=0xcf00)
        a.mode=AC_TST+((op>>8)&3);
    else if ((op&0xf0ff)==0x401b) a.mode=AC_TAS;
    else if ((op&0xf000)==0x4000 && a.m<=2 &&
             ((op&15)==2||(op&15)==3||(op&15)==6||(op&15)==7)) {
        a.mode=(op&4)?AC_LOAD_SYS:AC_STORE_SYS;
        a.control=op&1;a.sys=a.m;
    } else if ((op&0xf00f)==0x400f) a.mode=AC_MACW;
    else if ((op&0xf00f)==0x000f) a.mode=AC_MACL;
    else if ((op&0xff00)==0xc300) a.mode=AC_TRAP;
    else if ((op&0xf0ff)==0x002b) a.mode=AC_RTE; // upstream ignores the unused B nibble
    return a;
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

static inline u32* ambient_sysreg(SH2_struct* sh,AmbientComplexMem a) {
    if(a.control) {
        switch(a.sys){case 0:return &sh->regs.SR.all;case 1:return &sh->regs.GBR;default:return &sh->regs.VBR;}
    }
    switch(a.sys){case 0:return &sh->regs.MACH;case 1:return &sh->regs.MACL;default:return &sh->regs.PR;}
}
static inline void ambient_complex_execute(SH2_struct* sh,AmbientComplexMem a) {
    unsigned cycles=1;
    switch(a.mode) {
    case AC_TST:case AC_AND:case AC_XOR:case AC_OR: {
        u32 value=sh->MappedMemoryReadByte(sh,sh->regs.GBR+sh->regs.R[0]);
        if(a.mode==AC_TST)sh->regs.SR.part.T=(value&a.imm)==0;
        else {
            if(a.mode==AC_AND)value&=a.imm;
            else if(a.mode==AC_XOR)value^=a.imm;
            else value|=a.imm;
            sh->MappedMemoryWriteByte(sh,sh->regs.GBR+sh->regs.R[0],value);
        }
        cycles=3;break;
    }
    case AC_TAS: {
        u32 value=sh->MappedMemoryReadByte(sh,sh->regs.R[a.n]);
        sh->regs.SR.part.T=value==0;
        sh->MappedMemoryWriteByte(sh,sh->regs.R[a.n],value|128u);
        cycles=4;break;
    }
    case AC_LOAD_SYS: {
        u32 value=sh->MappedMemoryReadLong(sh,sh->regs.R[a.n]);
        if(a.control && a.sys==0)value&=0x3f3u;
        *ambient_sysreg(sh,a)=value;
        sh->regs.R[a.n]+=4;
        cycles=a.control?3:1;break;
    }
    case AC_STORE_SYS:
        sh->regs.R[a.n]-=4;
        sh->MappedMemoryWriteLong(sh,sh->regs.R[a.n],*ambient_sysreg(sh,a));
        cycles=a.control?2:1;break;
    case AC_MACW: {
        const s16 n=(s16)sh->MappedMemoryReadWord(sh,sh->regs.R[a.n]);sh->regs.R[a.n]+=2;
        const s16 m=(s16)sh->MappedMemoryReadWord(sh,sh->regs.R[a.m]);sh->regs.R[a.m]+=2;
        const int64_t product=(int32_t)n*(int32_t)m;
        if(sh->regs.SR.part.S) {
            const int64_t sum=(int64_t)(s32)sh->regs.MACL+product;
            sh->regs.MACL=sum>INT32_MAX?INT32_MAX:sum<INT32_MIN?(u32)INT32_MIN:(u32)sum;
        } else {
            const uint64_t acc=((uint64_t)sh->regs.MACH<<32)|sh->regs.MACL;
            const uint64_t result=acc+(uint64_t)product;
            sh->regs.MACH=(u32)(result>>32);sh->regs.MACL=(u32)result;
        }
        cycles=3;break;
    }
    case AC_MACL: {
        const s32 n=(s32)sh->MappedMemoryReadLong(sh,sh->regs.R[a.n]);sh->regs.R[a.n]+=4;
        const s32 m=(s32)sh->MappedMemoryReadLong(sh,sh->regs.R[a.m]);sh->regs.R[a.m]+=4;
        const uint64_t product=(uint64_t)((int64_t)n*m);
        const uint64_t low=(uint64_t)sh->regs.MACL+(u32)product;
        u32 high=(u32)(product>>32)+(u32)(low>>32);
        u32 result=(u32)low;
        if(sh->regs.SR.part.S) {
            // Preserve the pinned core's exact MACH saturation arithmetic,
            // including its historical high-half sign-extension convention.
            if(!(sh->regs.MACH&0x8000u))high+=sh->regs.MACH|0xffff0000u;
            high+=sh->regs.MACH&0xffffu;
            if((s32)high<0 && high<0xffff8000u){high=0x8000;result=0;}
            if((s32)high>0 && high>0x7fffu){high=0x7fff;result=0xffffffffu;}
        } else high+=sh->regs.MACH;
        sh->regs.MACH=high;sh->regs.MACL=result;cycles=3;break;
    }
    case AC_TRAP:
        sh->regs.R[15]-=4;sh->MappedMemoryWriteLong(sh,sh->regs.R[15],sh->regs.SR.all);
        sh->regs.R[15]-=4;sh->MappedMemoryWriteLong(sh,sh->regs.R[15],sh->regs.PC+2);
        sh->regs.PC=sh->MappedMemoryReadLong(sh,sh->regs.VBR+(u32)a.imm*4);
        sh->cycles+=8;return;
    case AC_RTE: {
        const u32 pc=sh->regs.PC;
        sh->regs.PC=sh->MappedMemoryReadLong(sh,sh->regs.R[15]);sh->regs.R[15]+=4;
        sh->regs.SR.all=sh->MappedMemoryReadLong(sh,sh->regs.R[15])&0x3f3u;sh->regs.R[15]+=4;
        sh->cycles+=4;SH2delay(sh,pc+2);return;
    }
    default:return;
    }
    sh->regs.PC+=2;sh->cycles+=cycles;
}
#endif
#endif
