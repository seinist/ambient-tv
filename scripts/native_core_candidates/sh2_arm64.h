/* SPDX-License-Identifier: GPL-2.0-or-later
 * Conservative AArch64 straight-line SH2 block compiler.
 * This header is included only by the explicit candidate interpreter TU.
 */
#ifndef AMBIENT_SH2_ARM64_H
#define AMBIENT_SH2_ARM64_H
#include <stdint.h>
#include <stddef.h>

#include "sh2_regcache.h"
#define AMBIENT_SH2_MAX_OPS 32
#define AMBIENT_SH2_MAX_WORDS (16 * AMBIENT_SH2_MAX_OPS + 30 + AMBIENT_SH2_REGCACHE_EXTRA)

/* Emit a single side-effect-free, one-cycle SH2 instruction into AArch64.
 * x0 points at R[16], x7 at SR; w1..w5 are caller-saved scratch.
 * Only the T status bit is changed by flag-setting instructions.
 * Branches, delay slots and MMIO remain interpreter operations.
 */
static unsigned ambient_sh2_emit(uint16_t op, uint32_t* code)
{
    const unsigned n = (op >> 8) & 15, m = (op >> 4) & 15;
    const unsigned key = op & 0xf00f;
    const uint32_t load_n = 0xb9400001u | (n << 10);
    const uint32_t load_m = 0xb9400002u | (m << 10);
    const uint32_t store_n = 0xb9000001u | (n << 10);
    uint32_t alu = 0;
    unsigned count = 0;
    if (op == 0x0009) return 1; /* NOP */
    if ((op & 0xf000) == 0xe000) {
        const int imm = (int8_t)op;
        code[count++] = imm < 0 ? 0x12800001u | ((uint32_t)(-imm-1) << 5)
                                : 0x52800001u | ((uint32_t)imm << 5);
    } else if ((op & 0xf000) == 0x7000) {
        const int imm = (int8_t)op;
        code[count++] = load_n;
        code[count++] = (imm < 0 ? 0x51000021u : 0x11000021u) |
                        ((uint32_t)(imm < 0 ? -imm : imm) << 10);
    } else if (key == 0x6003 || key == 0x6007 || key == 0x600b ||
               key == 0x600c || key == 0x600d || key == 0x600e || key == 0x600f) {
        code[count++] = load_m;
        switch (key) {
        case 0x6003: code[count++] = 0x2a0203e1u; break; /* MOV w1,w2 */
        case 0x6007: code[count++] = 0x2a2203e1u; break; /* MVN */
        case 0x600b: code[count++] = 0x4b0203e1u; break; /* NEG */
        case 0x600c: code[count++] = 0x53001c41u; break; /* UXTB */
        case 0x600d: code[count++] = 0x53003c41u; break; /* UXTH */
        case 0x600e: code[count++] = 0x13001c41u; break; /* SXTB */
        case 0x600f: code[count++] = 0x13003c41u; break; /* SXTH */
        }
    } else if (key == 0x300e || key == 0x300a || key == 0x600a ||
               key == 0x300f || key == 0x300b) {
        // ADDC/SUBC/NEGC use ARM carry/no-borrow, ADDV/SUBV signed overflow.
        if (key != 0x600a) code[count++] = load_n;
        code[count++] = load_m;
        code[count++] = 0xb94000e4u; /* LDR w4,[x7] */
        if (key == 0x300f || key == 0x300b) {
            code[count++] = key == 0x300f ? 0x2b020021u : 0x6b020021u;
            code[count++] = 0x1a9f77e3u; /* CSET w3,VS */
        } else {
            code[count++] = 0x12000083u; /* AND w3,w4,#1 */
            if (key == 0x300e) {
                code[count++] = 0x7100047fu; /* CMP w3,#1 */
                code[count++] = 0x3a020021u; /* ADCS */
                code[count++] = 0x1a9f37e3u; /* carry */
            } else {
                code[count++] = 0x6b0303ffu; /* CMP wzr,w3: C=!T */
                code[count++] = key == 0x600a ? 0x7a0203e1u : 0x7a020021u;
                code[count++] = 0x1a9f27e3u; /* borrow */
            }
        }
        code[count++] = store_n;
        code[count++] = 0x121f7884u;
        code[count++] = 0x2a030084u;
        code[count++] = 0xb90000e4u;
        return count+1;
    } else if (key == 0x200c) {
        // CMP/STR: any equal byte. Standard zero-byte detection on XOR.
        code[count++] = load_n;
        code[count++] = load_m;
        code[count++] = 0x4a020021u;
        code[count++] = 0x3200c3e2u;
        code[count++] = 0x4b020022u;
        code[count++] = 0x0a210042u;
        code[count++] = 0x7201c05fu;
        code[count++] = 0x1a9f07e3u;
        code[count++] = 0xb94000e4u;
        code[count++] = 0x121f7884u;
        code[count++] = 0x2a030084u;
        code[count++] = 0xb90000e4u;
        return count+1;
    } else if ((op & 0xf0ff) == 0x4024 || (op & 0xf0ff) == 0x4025) {
        code[count++] = load_n;
        code[count++] = 0xb94000e4u;
        code[count++] = 0x12000083u; /* old T */
        if (op & 1) {
            code[count++] = 0x12000025u;
            code[count++] = 0x53017c21u;
            code[count++] = 0x2a037c21u;
        } else {
            code[count++] = 0x531f7c25u;
            code[count++] = 0x531f7821u;
            code[count++] = 0x2a030021u;
        }
        code[count++] = 0x2a0503e3u; /* new T */
        code[count++] = store_n;
        code[count++] = 0x121f7884u;
        code[count++] = 0x2a030084u;
        code[count++] = 0xb90000e4u;
        return count+1;
    } else if ((op & 0xf0ff) == 0x4000 || (op & 0xf0ff) == 0x4020 ||
               (op & 0xf0ff) == 0x4001 || (op & 0xf0ff) == 0x4021 ||
               (op & 0xf0ff) == 0x4004 || (op & 0xf0ff) == 0x4005) {
        const unsigned kind = op & 0xf0ff;
        code[count++] = load_n;
        code[count++] = (op & 1) ? 0x12000023u : 0x531f7c23u; /* old low/high bit */
        switch (kind) {
        case 0x4000: case 0x4020: code[count++] = 0x531f7821u; break; /* LSL #1 */
        case 0x4001: code[count++] = 0x53017c21u; break; /* LSR #1 */
        case 0x4021: code[count++] = 0x13017c21u; break; /* ASR #1 */
        case 0x4004: code[count++] = 0x13817c21u; break; /* ROR #31 */
        case 0x4005: code[count++] = 0x13810421u; break; /* ROR #1 */
        }
        code[count++] = store_n;
        code[count++] = 0xb94000e4u;
        code[count++] = 0x121f7884u;
        code[count++] = 0x2a030084u;
        code[count++] = 0xb90000e4u;
        return count+1;
    } else if ((op & 0xf0ff) == 0x4008 || (op & 0xf0ff) == 0x4018 ||
               (op & 0xf0ff) == 0x4028 || (op & 0xf0ff) == 0x4009 ||
               (op & 0xf0ff) == 0x4019 || (op & 0xf0ff) == 0x4029) {
        const unsigned shift = ((op & 0x30) == 0 ? 2 : (op & 0x30) == 0x10 ? 8 : 16);
        code[count++] = load_n;
        code[count++] = (op & 1) ?
            0x53000021u | (shift << 16) | (31u << 10) :
            0x53000021u | ((32u-shift) << 16) | ((31u-shift) << 10);
    } else if (key == 0x6008 || key == 0x6009 || key == 0x200d) {
        code[count++] = load_m;
        if (key == 0x6008) {
            code[count++] = 0x5ac00441u; /* REV16 w1,w2 */
            code[count++] = 0x33003c22u; /* BFI w2,w1,#0,#16 */
            code[count++] = 0x2a0203e1u;
        } else if (key == 0x6009) {
            code[count++] = 0x13824041u; /* ROR w1,w2,#16 */
        } else {
            code[count++] = load_n;
            code[count++] = 0x13814041u; /* EXTR w1,w2,w1,#16 */
        }
    } else if ((op & 0xff00) == 0xc900 || (op & 0xff00) == 0xca00 ||
               (op & 0xff00) == 0xcb00) {
        code[count++] = 0xb9400001u;
        code[count++] = 0x52800002u | ((op & 255u) << 5);
        code[count++] = (op & 0xff00) == 0xc900 ? 0x0a020021u :
                        (op & 0xff00) == 0xca00 ? 0x4a020021u : 0x2a020021u;
        code[count++] = 0xb9000001u;
        return count+1;
    } else if ((op & 0xf0ff) == 0x0029) {
        code[count++] = 0xb94000e4u; /* LDR w4,[x7] */
        code[count++] = 0x53000081u; /* UBFX w1,w4,#0,#1 */
    } else if (key == 0x3000 || key == 0x3002 || key == 0x3003 ||
               key == 0x3006 || key == 0x3007 || key == 0x2008 ||
               (op & 0xff00) == 0xc800 || (op & 0xff00) == 0x8800 ||
               (op & 0xf0ff) == 0x4011 || (op & 0xf0ff) == 0x4015 ||
               (op & 0xf0ff) == 0x4010 || op == 0x0008 || op == 0x0018) {
        uint32_t condition = 0x1a9f17e3u; /* CSET w3,EQ */
        if (op == 0x0008 || op == 0x0018) {
            code[count++] = op == 0x0018 ? 0x52800023u : 0x52800003u;
        } else if ((op & 0xf0ff) == 0x4010) {
            code[count++] = load_n;
            code[count++] = 0x51000421u; /* SUB w1,w1,#1 */
            code[count++] = store_n;
            code[count++] = 0x7100003fu; /* CMP w1,#0 */
            code[count++] = condition;
        } else if ((op & 0xf0ff) == 0x4011 || (op & 0xf0ff) == 0x4015) {
            code[count++] = load_n;
            code[count++] = 0x7100003fu;
            code[count++] = (op & 0xf0ff) == 0x4011 ? 0x1a9fb7e3u : 0x1a9fd7e3u;
        } else if ((op & 0xff00) == 0xc800 || (op & 0xff00) == 0x8800) {
            code[count++] = 0xb9400001u;
            const int immediate = (op & 0xff00) == 0x8800 ? (int8_t)op : (op & 255);
            code[count++] = immediate < 0 ? 0x12800002u | ((uint32_t)(-immediate-1) << 5) :
                                            0x52800002u | ((uint32_t)immediate << 5);
            code[count++] = (op & 0xff00) == 0xc800 ? 0x6a02003fu : 0x6b02003fu;
            code[count++] = condition;
        } else {
            code[count++] = load_n;
            code[count++] = load_m;
            code[count++] = key == 0x2008 ? 0x6a02003fu : 0x6b02003fu;
            switch (key) {
            case 0x3002: condition = 0x1a9f37e3u; break; /* HS */
            case 0x3003: condition = 0x1a9fb7e3u; break; /* GE */
            case 0x3006: condition = 0x1a9f97e3u; break; /* HI */
            case 0x3007: condition = 0x1a9fd7e3u; break; /* GT */
            }
            code[count++] = condition;
        }
        code[count++] = 0xb94000e4u;
        code[count++] = 0x121f7884u; /* AND w4,w4,#~1 */
        code[count++] = 0x2a030084u; /* ORR w4,w4,w3 */
        code[count++] = 0xb90000e4u;
        return count+1;
    } else {
        switch (key) {
        case 0x300c: alu = 0x0b020021u; break; /* ADD */
        case 0x3008: alu = 0x4b020021u; break; /* SUB */
        case 0x2009: alu = 0x0a020021u; break; /* AND */
        case 0x200a: alu = 0x4a020021u; break; /* XOR */
        case 0x200b: alu = 0x2a020021u; break; /* OR */
        default: return 0;
        }
        code[count++] = load_n;
        code[count++] = load_m;
        code[count++] = alu;
    }
    code[count++] = store_n;
    return count + 1; /* 0 is unsupported; 1 is a NOP with no native words */
}

/* Byte offsets relative to the guest register array, derived from the
 * actual pinned core layout, never hard-coded in production. */
typedef struct { unsigned mach, macl, gbr, vbr, pr; } AmbientSh2Layout;

static inline unsigned ambient_sh2_emit_extended(uint16_t op, uint32_t* code,
                                                AmbientSh2Layout layout, unsigned* cycles)
{
    const unsigned n = (op >> 8) & 15, m = (op >> 4) & 15;
    const unsigned key = op & 0xf00f, single = op & 0xf0ff;
    const uint32_t ln = 0xb9400001u | (n << 10);
    const uint32_t lm = 0xb9400002u | (m << 10);
    const uint32_t sn = 0xb9000001u | (n << 10);
    unsigned count = 0, offset = 0;
    *cycles = 1;
    /* Upstream ignores the unused B nibble on these opcodes. */
    if (single == 0x0009 || single == 0x0008 || single == 0x0018)
        return ambient_sh2_emit(single, code);
    if (single == 0x0028) {
        code[count++] = 0xb900001fu | (layout.mach << 8);
        code[count++] = 0xb900001fu | (layout.macl << 8);
        return count+1;
    }
    if (single == 0x0019) { /* DIV0U: clear M, Q, T */
        code[count++] = 0xb94000e1u;
        code[count++] = 0x52806022u; /* MOV w2,#0x301 */
        code[count++] = 0x0a220021u; /* BIC w1,w1,w2 */
        code[count++] = 0xb90000e1u;
        return count+1;
    }
    switch (key) {
    case 0x0007: case 0x200e: case 0x200f: case 0x3005: case 0x300d:
        code[count++] = ln;
        code[count++] = lm;
        if (key == 0x200e || key == 0x200f) {
            code[count++] = key == 0x200e ? 0x53003c21u : 0x13003c21u;
            code[count++] = key == 0x200e ? 0x53003c42u : 0x13003c42u;
        }
        if (key == 0x3005 || key == 0x300d) {
            code[count++] = key == 0x3005 ? 0x9ba27c23u : 0x9b227c23u;
            code[count++] = 0xb9000003u | (layout.macl << 8);
            code[count++] = 0xd360fc63u;
            code[count++] = 0xb9000003u | (layout.mach << 8);
            *cycles = 2;
        } else {
            code[count++] = 0x1b027c21u;
            code[count++] = 0xb9000001u | (layout.macl << 8);
            *cycles = key == 0x0007 ? 2 : 1;
        }
        return count+1;
    default: break;
    }
    switch (single) {
    case 0x0002: /* STC SR */
        code[count++] = 0xb94000e1u; code[count++] = sn; return count+1;
    case 0x400e: /* LDC SR: preserve the exact architectural mask */
        code[count++] = ln; code[count++] = 0x52807e63u;
        code[count++] = 0x0a030021u; code[count++] = 0xb90000e1u; return count+1;
    case 0x0012: case 0x401e: offset = layout.gbr; break;
    case 0x0022: case 0x402e: offset = layout.vbr; break;
    case 0x000a: case 0x400a: offset = layout.mach; break;
    case 0x001a: case 0x401a: offset = layout.macl; break;
    case 0x002a: case 0x402a: offset = layout.pr; break;
    default: return ambient_sh2_emit(op, code);
    }
    if (op & 0x4000u) {
        code[count++] = ln;
        code[count++] = 0xb9000001u | (offset << 8);
    } else {
        code[count++] = 0xb9400001u | (offset << 8);
        code[count++] = sn;
    }
    return count+1;
}

/* Terminal, non-delay conditional branch. Return exact guest cycle charge. */
static inline unsigned ambient_sh2_branch(uint16_t op, uint32_t* pc, uint32_t sr)
{
    const unsigned kind = op & 0xff00u;
    if (kind != 0x8900u && kind != 0x8b00u) return 0;
    const unsigned taken = (sr & 1u) == (kind == 0x8900u);
    *pc += taken ? (uint32_t)(4 + 2 * (int32_t)(int8_t)op) : 2u;
    return taken ? 3u : 1u;
}

#if defined(AMBIENT_SH2_ARM64_JIT)
#if !defined(__aarch64__)
#error ARM64 SH2 candidate must only be built on AArch64
#endif
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
static void ambient_sh2_delay_native(SH2_struct* context, u32 addr);
#define AMBIENT_SH2_DELAY ambient_sh2_delay_native
#include "ambient_sh2_memory.h"
#include "ambient_sh2_control.h"
#undef AMBIENT_SH2_DELAY
#define AMBIENT_REG_OFFSET(field) (offsetof(SH2_struct, regs.field)-offsetof(SH2_struct, regs.R))
static const AmbientSh2Layout ambient_sh2_layout = {
    AMBIENT_REG_OFFSET(MACH), AMBIENT_REG_OFFSET(MACL), AMBIENT_REG_OFFSET(GBR),
    AMBIENT_REG_OFFSET(VBR), AMBIENT_REG_OFFSET(PR)
};
#undef AMBIENT_REG_OFFSET
#define AMBIENT_SH2_SINGLE_STRIDE 19
#define AMBIENT_SH2_SINGLE_COUNT 256
typedef struct {
    uint32_t* code;
    size_t bytes;
    uint8_t cycles[AMBIENT_SH2_SINGLE_COUNT];
    unsigned failed;
} AmbientSh2SinglePage;
/* A single immutable W^X slab serves 256 encodings; no executable mapping
 * per delay slot or cold PC, and no code-cache dependence on guest addresses. */
static AmbientSh2SinglePage ambient_sh2_single[2][256];

static int ambient_sh2_single_emit(SH2_struct* sh, uint16_t op)
{
    AmbientSh2SinglePage* page=&ambient_sh2_single[sh->isslave?1:0][op>>8];
    const unsigned index=op&255u;
    if(page->failed)return 0;
    if(!page->code) {
        uint32_t scratch[16];
        unsigned cost;
        if(!ambient_sh2_emit_extended(op,scratch,ambient_sh2_layout,&cost))return 0;
        const long granule=sysconf(_SC_PAGESIZE);
        if(granule<=0){page->failed=1;return 0;}
        const size_t raw=AMBIENT_SH2_SINGLE_COUNT*AMBIENT_SH2_SINGLE_STRIDE*sizeof(uint32_t);
        const size_t bytes=(raw+(size_t)granule-1)/(size_t)granule*(size_t)granule;
        uint32_t* mapped=mmap(NULL,bytes,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        if(mapped==MAP_FAILED){page->failed=1;return 0;}
        for(unsigned i=0;i<AMBIENT_SH2_SINGLE_COUNT;++i) {
            uint32_t* code=mapped+i*AMBIENT_SH2_SINGLE_STRIDE;
            const unsigned n=ambient_sh2_emit_extended((op&0xff00u)|i,code+2,
                                                        ambient_sh2_layout,&cost);
            if(!n)continue;
            code[0]=0xd503245fu;code[1]=0xaa0103e7u;
            code[n+1]=0xd65f03c0u;
            page->cycles[i]=(uint8_t)cost;
        }
        __builtin___clear_cache((char*)mapped,(char*)mapped+raw);
        if(mprotect(mapped,bytes,PROT_READ|PROT_EXEC)!=0) {
            munmap(mapped,bytes);page->failed=1;return 0;
        }
        page->code=mapped;page->bytes=bytes;
    }
    const unsigned cost=page->cycles[index];
    if(!cost)return 0;
    ((void(*)(u32*,u32*))(page->code+index*AMBIENT_SH2_SINGLE_STRIDE))(sh->regs.R,&sh->regs.SR.all);
    sh->regs.PC+=2;
    sh->cycles+=cost;
    return 1;
}

static int ambient_sh2_native_one(SH2_struct* sh, uint16_t op)
{
    unsigned cost=ambient_sh2_branch(op,&sh->regs.PC,sh->regs.SR.all);
    if(cost){sh->cycles+=cost;return 1;}
    const AmbientMemOp memory=ambient_mem_decode(op);
    if(memory.mode){ambient_mem_execute(sh,memory);return 1;}
    const AmbientComplexMem complex=ambient_complex_decode(op);
    if(complex.mode){ambient_complex_execute(sh,complex);return 1;}
    if(ambient_sh2_control(sh,op))return 1;
    return ambient_sh2_single_emit(sh,op);
}

static void ambient_sh2_delay_native(SH2_struct* sh, u32 addr)
{
    /* Match upstream's target-PC convention exactly. In particular PC-relative
     * loads see the already selected target, not the delay instruction address. */
#ifdef SH2_TRACE
    SH2delay(sh,addr);
    return;
#endif
    /* A cache-enabled SH2 must still fetch exactly once through the
     * emulated cache or data array. */
    if(yabsys.sh2_cache_enabled && (addr&0xc0000000u)==0xc0000000u)
        sh->instruction=DataArrayReadWord(sh,addr);
    else
        sh->instruction=((fetchfunc*)sh->fetchlist)[(addr>>20)&255u](sh,addr);
    if(!ambient_sh2_native_one(sh,sh->instruction))
        ((opcodefunc*)sh->opcodes)[sh->instruction](sh); /* illegal opcode or W^X failure */
    sh->regs.PC-=2;
}
#define AMBIENT_SH2_SETS 128
#define AMBIENT_SH2_WAYS 2
#define AMBIENT_SH2_SLOTS (AMBIENT_SH2_SETS*AMBIENT_SH2_WAYS)
#define AMBIENT_SH2_EDGES 4
#define AMBIENT_SH2_HOT 4
#define AMBIENT_SH2_PAGE_SHIFT 12
#define AMBIENT_SH2_BANK_PAGES 256
#define AMBIENT_SH2_PAGES (3*AMBIENT_SH2_BANK_PAGES)
static uint32_t ambient_sh2_page_generation[AMBIENT_SH2_PAGES];
/* Both CPUs share physical WRAM. Aliases use the same bank and page.
 * Hooks mark metadata only: no executing RX block is ever unmapped here.
 * Untracked bulk writes are covered by exact validation at scope entry. */
void ambient_sh2_note_t2_write(unsigned bank,u32 offset,unsigned size)
{
    if(bank>=3 || !size)return;
    const unsigned mask=bank==0?0x7ffffu:0xfffffu;
    const unsigned first=(offset&mask)>>AMBIENT_SH2_PAGE_SHIFT;
    const unsigned last=((offset+size-1)&mask)>>AMBIENT_SH2_PAGE_SHIFT;
    __atomic_add_fetch(&ambient_sh2_page_generation[bank*AMBIENT_SH2_BANK_PAGES+first],1u,__ATOMIC_RELAXED);
    if(last!=first)
        __atomic_add_fetch(&ambient_sh2_page_generation[bank*AMBIENT_SH2_BANK_PAGES+last],1u,__ATOMIC_RELAXED);
}
static uint64_t ambient_sh2_scope_counter[2];
static uint64_t ambient_sh2_bulk_epoch;
void ambient_sh2_note_bulk_write(void)
{
    __atomic_add_fetch(&ambient_sh2_bulk_epoch,1u,__ATOMIC_RELAXED);
}

typedef struct AmbientSh2Block AmbientSh2Block;
struct AmbientSh2Block {
    uint32_t pc, next_pc;
    uint32_t pcs[AMBIENT_SH2_MAX_OPS];
    uint16_t ops[AMBIENT_SH2_MAX_OPS];
    unsigned count, cycles, hits;
    unsigned span_count;
    uint64_t validated_scope, validated_epoch;
    fetchfunc source_fetch;
    struct { u32 pc,version; uint16_t first,count,page; } spans[AMBIENT_SH2_MAX_OPS+1];
    uint16_t terminal;
    unsigned has_terminal, dynamic_exit, native_loop, native_link, conditional_loop;
    u32 fallthrough_pc, last_dynamic_pc;
    unsigned last_dynamic_valid;
    AmbientMemOp memory;
    AmbientComplexMem complex_memory;
    opcodefunc terminal_handler;
    void* code;
    size_t bytes;
    struct { uint32_t pc; AmbientSh2Block* target; } edges[AMBIENT_SH2_EDGES];
    unsigned next_edge, last_edge;
};
static AmbientSh2Block ambient_sh2_cache[2][AMBIENT_SH2_SLOTS];
static int ambient_sh2_disabled[2];
static uint8_t ambient_sh2_victim[2][AMBIENT_SH2_SETS];
/* Permanent per-CPU slot addresses in page-sized W^X slabs. Never change
 * permissions while executing generated code; SH2 dispatch is serialized
 * on the emulation thread. On 16 KiB hosts several blocks share a page. */
#define AMBIENT_SH2_CODE_STRIDE ((AMBIENT_SH2_MAX_WORDS*4u+63u)&~63u)
static void* ambient_sh2_code_pages[2][AMBIENT_SH2_SLOTS];
static size_t ambient_sh2_code_page_size;
static unsigned ambient_sh2_code_per_page;
static void ambient_sh2_drop_code(AmbientSh2Block* block)
{
    block->code=NULL;block->bytes=0;
}
static void* ambient_sh2_install_code(unsigned cpu,AmbientSh2Block* block,const uint32_t* words,unsigned count)
{
    if(!ambient_sh2_code_page_size) {
        const long page=sysconf(_SC_PAGESIZE);
        if(page<=0 || (size_t)page<AMBIENT_SH2_CODE_STRIDE)return NULL;
        ambient_sh2_code_page_size=(size_t)page;
        ambient_sh2_code_per_page=(unsigned)((size_t)page/AMBIENT_SH2_CODE_STRIDE);
    }
    if(count>AMBIENT_SH2_MAX_WORDS)return NULL;
    const unsigned slot=(unsigned)(block-ambient_sh2_cache[cpu]);
    const unsigned group=slot/ambient_sh2_code_per_page;
    void* page=ambient_sh2_code_pages[cpu][group];
    if(page) {
        if(mprotect(page,ambient_sh2_code_page_size,PROT_READ|PROT_WRITE))return NULL;
    } else {
        page=mmap(NULL,ambient_sh2_code_page_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        if(page==MAP_FAILED)return NULL;
        ambient_sh2_code_pages[cpu][group]=page;
    }
    char* dest=(char*)page+(slot%ambient_sh2_code_per_page)*AMBIENT_SH2_CODE_STRIDE;
    memcpy(dest,words,count*sizeof(uint32_t));
    __builtin___clear_cache(dest,dest+count*sizeof(uint32_t));
    if(mprotect(page,ambient_sh2_code_page_size,PROT_READ|PROT_EXEC)) {
        munmap(page,ambient_sh2_code_page_size);
        ambient_sh2_code_pages[cpu][group]=NULL;
        const unsigned end=(group+1)*ambient_sh2_code_per_page;
        for(unsigned i=group*ambient_sh2_code_per_page;i<end&&i<AMBIENT_SH2_SLOTS;++i)
            memset(&ambient_sh2_cache[cpu][i],0,sizeof(AmbientSh2Block));
        return NULL;
    }
    block->bytes=count*sizeof(uint32_t);return dest;
}

static void ambient_sh2_clear(void)
{
    for (unsigned cpu = 0; cpu < 2; ++cpu) {
        for (unsigned slot = 0; slot < AMBIENT_SH2_SLOTS; ++slot) {
            if(ambient_sh2_code_pages[cpu][slot])
                munmap(ambient_sh2_code_pages[cpu][slot],ambient_sh2_code_page_size);
        }
    }
    for(unsigned cpu=0;cpu<2;++cpu)
        for(unsigned i=0;i<256;++i)
            if(ambient_sh2_single[cpu][i].code)
                munmap(ambient_sh2_single[cpu][i].code,ambient_sh2_single[cpu][i].bytes);
    memset(ambient_sh2_single,0,sizeof(ambient_sh2_single));
    memset(ambient_sh2_cache, 0, sizeof(ambient_sh2_cache));
    memset(ambient_sh2_code_pages,0,sizeof(ambient_sh2_code_pages));
    ambient_sh2_code_page_size=0;ambient_sh2_code_per_page=0;
    ambient_sh2_note_bulk_write();
    memset(ambient_sh2_disabled, 0, sizeof(ambient_sh2_disabled));
    memset(ambient_sh2_victim,0,sizeof(ambient_sh2_victim));
    memset(ambient_sh2_page_generation,0,sizeof(ambient_sh2_page_generation));
    memset(ambient_sh2_scope_counter,0,sizeof(ambient_sh2_scope_counter));
}

static int ambient_sh2_eligible(const SH2_struct* context)
{
#ifdef SH2_TRACE
    (void)context;return 0;
#else
    return context->model == SHMT_SH2 &&
        !context->isIdle && !context->isSleeping && !context->delay &&
        !context->breakpointEnabled && !context->backtraceEnabled &&
        !context->stepOverOut.enabled && !context->trackInfLoop.enabled;
#endif
}

static int ambient_sh2_safe_fetch(SH2_struct* context,fetchfunc fetch,u32 addr)
{
    return !(addr&1u) && (addr&0xc0000000u)!=0xc0000000u &&
        ((fetchfunc*)context->fetchlist)[(addr>>20)&255u]==fetch;
}

/* Peek only already resident cache lines. No refill, LRU mutation,
 * cache-through timing, MMIO or data-array read during compilation/validation.
 * Mirrors the pinned cache_memory_read_w tag/entry/way precedence.
 */
static int ambient_sh2_peek(SH2_struct* context,fetchfunc fetch,u32 addr,u16* op)
{
    if(!ambient_sh2_safe_fetch(context,fetch,addr))return 0;
    if(!yabsys.sh2_cache_enabled) {
        *op=(u16)fetch(context,addr);return 1;
    }
    const cache_enty* ca=&context->onchip.cache;
    if(!ca->enable || (addr&0xe0000000u))return 0;
    const unsigned entry=(addr&0x3f0u)>>4, offset=addr&15u;
    const u32 tag=addr&0x1ffffc00u;
    for(unsigned way=0;way<4;++way) {
        const cache_line* line=&ca->way[way][entry];
        if(line->v && line->tag==tag) {
            *op=(u16)(((u16)line->data[offset]<<8)|line->data[offset+1]);
            return 1;
        }
    }
    return 0;
}

/* The pinned FetchBios/FetchLWram/FetchHWram use host-endian T2 words.
 * Coalesce sequential source addresses, never cross a physical page.
 * A caller may provide a contiguous backing view for a single memcmp;
 * otherwise retain per-word validation (including synthetic bus tests).
 */
static unsigned ambient_sh2_page(fetchfunc fetch,u32 addr)
{
    const unsigned bank=fetch==FetchBios?0:fetch==FetchLWram?1:2;
    const unsigned mask=bank==0?0x7ffffu:0xfffffu;
    return bank*AMBIENT_SH2_BANK_PAGES+((addr&mask)>>AMBIENT_SH2_PAGE_SHIFT);
}
static void ambient_sh2_build_spans(AmbientSh2Block* block,fetchfunc fetch)
{
    block->source_fetch=fetch;block->span_count=0;block->validated_scope=0;
    const unsigned total=block->count+block->has_terminal;
    if(block->has_terminal) {
        block->pcs[block->count]=block->next_pc;
        block->ops[block->count]=block->terminal;
    }
    for(unsigned i=0;i<total;++i) {
        const u32 pc=block->pcs[i];
        const unsigned page=ambient_sh2_page(fetch,pc);
        if(block->span_count) {
            unsigned j=block->span_count-1;
            if(block->spans[j].page==page &&
               pc==block->spans[j].pc+2u*block->spans[j].count) {
                ++block->spans[j].count;continue;
            }
        }
        const unsigned j=block->span_count++;
        block->spans[j].pc=pc;block->spans[j].first=(uint16_t)i;
        block->spans[j].count=1;block->spans[j].page=(uint16_t)page;
        block->spans[j].version=0;
    }
}
static int ambient_sh2_validate(AmbientSh2Block* block,SH2_struct* context,
                               fetchfunc fetch,uint64_t scope)
{
    if(block->source_fetch!=fetch)return 0;
    if(yabsys.sh2_cache_enabled) {
        /* Cache contents and LRU are independent of physical RAM generations. */
        const unsigned total=block->count+block->has_terminal;
        for(unsigned i=0;i<total;++i) {
            u16 current;
            if(!ambient_sh2_peek(context,fetch,block->pcs[i],&current) ||
               current!=block->ops[i])return 0;
        }
        block->validated_scope=0;return 1;
    }
    const uint64_t epoch=__atomic_load_n(&ambient_sh2_bulk_epoch,__ATOMIC_RELAXED);
    int reuse=scope && block->validated_scope==scope && block->validated_epoch==epoch;
#ifdef AMBIENT_SH2_IMMUTABLE_BIOS
    /* The production BIOS is read-only to emulated bus accesses; the T2
     * loader and reset/deinit hooks track its actual changes. Retain its
     * page generations across scheduler slices. Writable RAM remains
     * exact-checked at every new execution scope. */
    if(scope && fetch==FetchBios && block->validated_scope && block->validated_epoch==epoch)
        reuse=1;
#endif
    for(unsigned j=0;j<block->span_count;++j) {
        const u32 pc=block->spans[j].pc;
        const unsigned count=block->spans[j].count,first=block->spans[j].first;
        const unsigned page=block->spans[j].page;
        const uint32_t generation=__atomic_load_n(&ambient_sh2_page_generation[page],__ATOMIC_RELAXED);
        if(!ambient_sh2_safe_fetch(context,fetch,pc) ||
           !ambient_sh2_safe_fetch(context,fetch,pc+2u*(count-1)))return 0;
        if(!reuse || generation!=block->spans[j].version) {
#ifdef AMBIENT_SH2_SOURCE_VIEW
            const void* view=AMBIENT_SH2_SOURCE_VIEW(context,fetch,pc,count);
            if(view) {
                if(memcmp(view,block->ops+first,count*sizeof(u16)))return 0;
            } else
#endif
            for(unsigned i=0;i<count;++i) {
                u16 current;
                if(!ambient_sh2_peek(context,fetch,pc+2*i,&current) ||
                   current!=block->ops[first+i])return 0;
            }
            block->spans[j].version=generation;
        }
    }
    block->validated_scope=scope;block->validated_epoch=epoch;return 1;
}
static uint64_t ambient_sh2_new_scope(unsigned cpu)
{
    if(++ambient_sh2_scope_counter[cpu]==0) {
        for(unsigned i=0;i<AMBIENT_SH2_SLOTS;++i)
            ambient_sh2_cache[cpu][i].validated_scope=0;
        ++ambient_sh2_scope_counter[cpu];
    }
    return ambient_sh2_scope_counter[cpu];
}

static unsigned ambient_sh2_slot(uint32_t pc)
{
    return (pc>>1 ^ pc>>9 ^ pc>>17 ^ pc>>25)&(AMBIENT_SH2_SETS-1);
}

/* Set-associative translated-block cache. Linked pointers always refer to
 * permanent slots, never to freed code pages. The tag is checked again
 * before entry, including after a replacement of the pointed-to slot. */
static AmbientSh2Block* ambient_sh2_lookup(unsigned cpu,u32 pc,int allocate)
{
    const unsigned set=ambient_sh2_slot(pc);
    unsigned empty=AMBIENT_SH2_WAYS;
    for(unsigned way=0;way<AMBIENT_SH2_WAYS;++way) {
        AmbientSh2Block* b=&ambient_sh2_cache[cpu][set+way*AMBIENT_SH2_SETS];
        if(b->pc==pc && (b->hits||b->code||b->has_terminal)) {
            ambient_sh2_victim[cpu][set]=(uint8_t)(way^1u);
            return b;
        }
        if(!b->hits&&!b->code&&!b->has_terminal&&empty==AMBIENT_SH2_WAYS)empty=way;
    }
    if(!allocate)return NULL;
    const unsigned way=empty<AMBIENT_SH2_WAYS?empty:ambient_sh2_victim[cpu][set];
    AmbientSh2Block* b=&ambient_sh2_cache[cpu][set+way*AMBIENT_SH2_SETS];
    ambient_sh2_drop_code(b);
    memset(b,0,sizeof(*b));b->pc=pc;
    ambient_sh2_victim[cpu][set]=(uint8_t)(way^1u);
    return b;
}

static int ambient_sh2_try_block_scoped(SH2_struct* context, u32 budget,
                                 AmbientSh2Block* linked, AmbientSh2Block** executed,uint64_t scope)
{
    if (!ambient_sh2_eligible(context) || context->cycles >= budget ||
        budget - context->cycles < 4)
        return 0;
    const uint32_t pc = context->regs.PC;
    if ((pc & 1u) || pc > UINT32_MAX - 2*AMBIENT_SH2_MAX_OPS ||
        (pc & 0xc0000000u) == 0xc0000000u) return 0;
    const fetchfunc fetch = ((fetchfunc*)context->fetchlist)[(pc >> 20) & 255];
    /* Only side-effect-free fetch regions. Never prefetch cartridges or MMIO. */
    if (fetch != FetchBios && fetch != FetchLWram && fetch != FetchHWram) return 0;
    u16 first;
    if(yabsys.sh2_cache_enabled && !ambient_sh2_peek(context,fetch,pc,&first))return 0;
    const unsigned cpu = context->isslave ? 1 : 0;
    if (ambient_sh2_disabled[cpu]) return 0;
    AmbientSh2Block* block = linked ? linked : ambient_sh2_lookup(cpu,pc,1);
    /* A cache slot is stable, but its translation may have been replaced.
     * Linked targets never bypass guest-code, fetch or cycle-budget checks. */
    if(linked && (block->pc!=pc || (!block->code && !block->has_terminal)))return 0;
    if(linked)ambient_sh2_victim[cpu][ambient_sh2_slot(pc)]=(uint8_t)(((block-ambient_sh2_cache[cpu])/AMBIENT_SH2_SETS)^1u);
    if (block->pc == pc && (block->code || block->has_terminal) &&
        block->cycles <= budget - context->cycles &&
        (!block->has_terminal || block->cycles < budget - context->cycles)) {
        const uint32_t terminal_pc=block->next_pc;
        if(ambient_sh2_validate(block,context,fetch,scope)) {
            if (block->code) {
                /* All sources were resident, pure ALU instructions cannot
                 * evict them. Replay the real fetches in guest order to
                 * preserve LRU exactly; hits add no bus wait cycles. */
                if(yabsys.sh2_cache_enabled)
                    for(unsigned j=0;j<block->count;++j)
                        (void)fetch(context,block->pcs[j]);
                u32 next_pc=block->next_pc;
                unsigned repeat=1,cycle_discount=0;
                if(block->native_loop) {
                    if(scope && !yabsys.sh2_cache_enabled) {
                        repeat=(budget-context->cycles)/block->cycles;
                        if(repeat>64)repeat=64;
                    }
                    if(block->conditional_loop) {
                        const u32 result=((u32 (*)(u32*,u32*,u32))block->code)(context->regs.R,&context->regs.SR.all,repeat);
                        repeat-=result&0x7fffffffu;
                        if(result>>31){next_pc=block->fallthrough_pc;cycle_discount=2;}
                    } else
                        ((void (*)(u32*,u32*,u32))block->code)(context->regs.R,&context->regs.SR.all,repeat);
                } else if(block->dynamic_exit) {
                    AmbientSh2Block* successor=NULL;
                    const u32 predicted=block->last_dynamic_pc;
                    if(scope && !yabsys.sh2_cache_enabled && block->last_dynamic_valid) {
                        AmbientSh2Block* candidate=ambient_sh2_lookup(cpu,predicted,0);
                        if(candidate && candidate!=block && candidate->native_link && candidate->code &&
                           candidate->cycles<=budget-context->cycles-block->cycles) {
                            const fetchfunc next_fetch=((fetchfunc*)context->fetchlist)[(predicted>>20)&255u];
                            if(ambient_sh2_validate(candidate,context,next_fetch,scope))successor=candidate;
                        }
                    }
                    /* Native code computes the real target, compares against the
                     * validated prediction, and invokes the successor directly
                     * only on an exact match. Return high bit-word = followed. */
                    const uint64_t result=((uint64_t (*)(u32*,u32*,u32,void*))block->code)(
                        context->regs.R,&context->regs.SR.all,predicted,successor?successor->code:NULL);
                    if(result>>32) {
                        context->cycles+=block->cycles;
                        block=successor;next_pc=block->next_pc;
                    } else {
                        next_pc=(u32)result;
                        block->last_dynamic_pc=next_pc;block->last_dynamic_valid=1;
                    }
                } else if(block->native_link) {
                    AmbientSh2Block* successor=NULL;
                    /* Validate BOTH pure blocks before the host tail branch.
                     * Their generated code cannot touch the bus, refill cache
                     * lines or change the source mapping between them. */
                    if(scope && !yabsys.sh2_cache_enabled) {
                        AmbientSh2Block* candidate=ambient_sh2_lookup(cpu,next_pc,0);
                        if(candidate && candidate!=block && candidate->native_link &&
                           candidate->code && candidate->cycles<=budget-context->cycles-block->cycles) {
                            const fetchfunc next_fetch=((fetchfunc*)context->fetchlist)[(next_pc>>20)&255u];
                            if(ambient_sh2_validate(candidate,context,next_fetch,scope))
                                successor=candidate;
                        }
                    }
                    ((void (*)(u32*,u32*,void*))block->code)(context->regs.R,&context->regs.SR.all,
                                                            successor?successor->code:NULL);
                    if(successor) {
                        context->cycles+=block->cycles;
                        block=successor;next_pc=block->next_pc;
                    }
                } else
                    ((void (*)(u32*,u32*))block->code)(context->regs.R,&context->regs.SR.all);
                context->instruction = block->ops[block->count-1];
                context->regs.PC = next_pc;
                context->cycles += block->cycles*repeat-cycle_discount;
            }
            if (block->has_terminal) {
                if(yabsys.sh2_cache_enabled)(void)fetch(context,terminal_pc);
                context->instruction = block->terminal;
                unsigned cost = ambient_sh2_branch(block->terminal, &context->regs.PC,
                                                   context->regs.SR.all);
                if (cost) context->cycles += cost;
                else if (block->memory.mode) ambient_mem_execute(context, block->memory);
                else if (block->complex_memory.mode) ambient_complex_execute(context, block->complex_memory);
                else if (!ambient_sh2_native_one(context,block->terminal))
                    block->terminal_handler(context); /* illegal opcode or W^X failure */
            }
            *executed=block;
            return 1;
        }
        ambient_sh2_drop_code(block);
        memset(block, 0, sizeof(*block));
        block->pc = pc;
    }
    if (linked || ((block->code || block->has_terminal) && block->pc == pc)) return 0; /* insufficient cycle budget or invalidated link */
    if (block->pc != pc) {
        ambient_sh2_drop_code(block);
        memset(block, 0, sizeof(*block));
        block->pc = pc;
    }
    if (block->hits < AMBIENT_SH2_HOT && ++block->hits < AMBIENT_SH2_HOT) return 0;
    if (block->count == 1) {
        u16 current;
        if (!ambient_sh2_peek(context,fetch,pc,&current))return 0;
        if(!yabsys.sh2_cache_enabled && current==block->ops[0])return 0;
        block->count = 0;
    }
    uint32_t code[AMBIENT_SH2_MAX_WORDS];
    unsigned words = 0, count = 0, cycles = 0;
    u32 addr=pc;
    block->has_terminal = block->dynamic_exit = block->native_loop = block->native_link = block->conditional_loop = 0;
    code[words++] = 0xd503245fu; /* BTI c, harmless hint on older ARM64 */
    code[words++] = 0xaa0103e7u; /* MOV x7,x1: SR pointer survives scratch register writes */
    while (count < AMBIENT_SH2_MAX_OPS) {
        u16 op;
        if (!ambient_sh2_peek(context,fetch,addr,&op)) break;
        unsigned cost;
        /* Statically known BRA/BSR targets can be fused into a native trace
         * when the delay instruction is pure emitted code. Host execution
         * falls straight through into the target: no lookup, call, spill or
         * reload at this internal guest block boundary. All non-contiguous
         * source addresses are revalidated before the entire trace runs.
         * Never fuse a branch whose slot could mutate code, bus or control. */
        const unsigned top=op&0xf000u;
        if((top==0xa000u || top==0xb000u) && count+2<=AMBIENT_SH2_MAX_OPS &&
           addr<=UINT32_MAX-2 && ambient_sh2_safe_fetch(context,fetch,addr+2)) {
            const int disp=(op&0x800u)?(int)(op&0xfffu)-4096:(int)(op&0xfffu);
            const u32 target=addr+4+(u32)(disp*2);
            u16 slot,target_op;
            if(ambient_sh2_peek(context,fetch,target,&target_op) &&
               ambient_sh2_peek(context,fetch,addr+2,&slot)) {
                uint32_t slot_code[16];
                unsigned slot_cycles;
                const unsigned n=ambient_sh2_emit_extended(slot,slot_code,ambient_sh2_layout,&slot_cycles);
                if(n) {
                    if(top==0xb000u) {
                        const u32 pr=addr+4;
                        code[words++]=0x52800001u|((pr&0xffffu)<<5);
                        code[words++]=0x72a00001u|((pr>>16)<<5);
                        code[words++]=0xb9000001u|(ambient_sh2_layout.pr<<8);
                    }
                    memcpy(code+words,slot_code,(n-1)*sizeof(uint32_t));
                    words+=n-1;cycles+=2+slot_cycles;
                    block->pcs[count]=addr;block->ops[count++]=op;
                    block->pcs[count]=addr+2;block->ops[count++]=slot;
                    addr=target;
                    continue;
                }
            }
        }
        /* For indirect control transfers, capture the target in w6 before
         * executing the slot. w6 is reserved across emitted ALU instructions
         * and the register allocator. In particular RTS must read the old
         * PR even when the slot overwrites it, and JSR/BRAF may modify Rn. */
        const unsigned single=op&0xf0ffu;
        if((single==0x0023 || single==0x0003 || single==0x402b ||
            single==0x400b || single==0x000b) &&
           count+2<=AMBIENT_SH2_MAX_OPS && addr<=UINT32_MAX-2) {
            u16 slot;
            if(ambient_sh2_peek(context,fetch,addr+2,&slot)) {
                uint32_t slot_code[16];unsigned slot_cycles;
                const unsigned n=ambient_sh2_emit_extended(slot,slot_code,ambient_sh2_layout,&slot_cycles);
                if(n) {
                    const unsigned offset=single==0x000b?ambient_sh2_layout.pr:
                        (((op>>8)&15u)*4u);
                    code[words++]=0xb9400006u|(offset<<8); /* LDR w6,[x0,#offset] */
                    if(single==0x0023 || single==0x0003 || single==0x400b) {
                        const u32 pc4=addr+4;
                        code[words++]=0x52800001u|((pc4&0xffffu)<<5);
                        code[words++]=0x72a00001u|((pc4>>16)<<5);
                        if(single==0x0003 || single==0x400b)
                            code[words++]=0xb9000001u|(ambient_sh2_layout.pr<<8);
                        if(single==0x0023 || single==0x0003)
                            code[words++]=0x0b0100c6u; /* ADD w6,w6,w1 */
                    }
                    memcpy(code+words,slot_code,(n-1)*sizeof(uint32_t));
                    words+=n-1;cycles+=2+slot_cycles;
                    block->pcs[count]=addr;block->ops[count++]=op;
                    block->pcs[count]=addr+2;block->ops[count++]=slot;
                    block->dynamic_exit=1;addr+=4;
                    break;
                }
            }
        }
        /* BT/BF back to this entry: carry T out of the register-resident
         * body, execute a bounded native loop, and account for the shorter
         * final not-taken branch. No speculative execution after exit. */
        const unsigned high=op&0xff00u;
        if(count && (high==0x8900u || high==0x8b00u) &&
           addr+4+(u32)(2*(int32_t)(int8_t)op)==pc) {
            code[words++]=0xb94000e5u; /* LDR w5,[x7] */
            block->pcs[count]=addr;block->ops[count++]=op;
            block->conditional_loop=high==0x8900u?1u:2u;
            block->fallthrough_pc=addr+2;
            cycles+=3;addr=pc;break;
        }
        unsigned emitted = ambient_sh2_emit_extended(op, code + words, ambient_sh2_layout, &cost);
        if (!emitted) {
            // One terminal instruction may reuse the verified fetch. Never
            // precompile beyond a potentially mutating memory/control operation.
            const AmbientMemOp memory = ambient_mem_decode(op);
            const AmbientComplexMem complex_memory = ambient_complex_decode(op);
            const unsigned branch = (op & 0xff00u) == 0x8900 || (op & 0xff00u) == 0x8b00;
            if (count >= 2 || memory.mode || complex_memory.mode || branch || ambient_sh2_control_supported(op)) {
                block->terminal = op;
                block->memory = memory;
                block->complex_memory = complex_memory;
                block->terminal_handler = ((opcodefunc*)context->opcodes)[op];
                block->has_terminal = 1;
            }
            break;
        }
        words += emitted-1;
        cycles += cost;
        block->pcs[count]=addr;
        block->ops[count++] = op;
        addr+=2;
    }
    block->next_pc=addr;
    // Memory/control-heavy loops need not contain an ALU prefix. Cache
    // decoded terminal-only operations without allocating executable pages.
    if (!count && block->has_terminal) {
        block->count = block->cycles = 0;
        ambient_sh2_build_spans(block,fetch);
        return 0;
    }
    if (!block->has_terminal && !block->dynamic_exit && !block->conditional_loop && count < 4u) {
        u16 current;
        if(ambient_sh2_peek(context,fetch,pc,&current)) {
            block->ops[0]=current;
            /* A cache miss at a later address is transient, not a negative
             * compilation result. Revisit after normal execution refills it. */
            block->count=yabsys.sh2_cache_enabled?0:1;
        } else block->count=0;
        block->has_terminal = 0;
        return 0;
    }
    uint32_t optimized[AMBIENT_SH2_MAX_WORDS];
    optimized[0]=code[0];optimized[1]=code[1];
    block->native_loop=!block->has_terminal&&!block->dynamic_exit&&addr==pc&&cycles;
    if(block->native_loop) {
        /* w6 is reserved for the bounded repetition count. Hoist initial
         * guest loads, loop over the register-resident body, and commit
         * dirty state once after the final iteration. No RX patching. */
        optimized[2]=0x2a0203e6u; /* MOV w6,w2 */
        unsigned loop_start,loop_end;
        words=3+ambient_sh2_regcache_split(code+2,words-2,optimized+3,&loop_start,&loop_end);
        loop_start+=3;loop_end+=3;
        const unsigned tail=block->conditional_loop?5u:2u;
        memmove(optimized+loop_end+tail,optimized+loop_end,(words-loop_end)*sizeof(uint32_t));
        optimized[loop_end]=0x710004c6u; /* SUBS w6,w6,#1 */
        const unsigned branch=loop_end+(block->conditional_loop?2:1);
        const int displacement=(int)loop_start-(int)branch;
        optimized[branch]=0x54000001u|(((uint32_t)displacement&0x7ffffu)<<5); /* B.NE */
        if(block->conditional_loop) {
            /* Exit on opposite T value, mark bit31 of remaining iterations. */
            optimized[loop_end+1]=(block->conditional_loop==1?0x36000000u:0x37000000u)|(3u<<5)|5u;
            optimized[loop_end+3]=0x14000002u; /* B writeback */
            optimized[loop_end+4]=0x320100c6u; /* ORR w6,w6,#0x80000000 */
        }
        words+=tail;
        if(block->conditional_loop)optimized[words++]=0x2a0603e0u; /* MOV w0,w6 */
    } else if(!block->has_terminal&&!block->dynamic_exit) {
        /* x6 holds an optional prevalidated successor entry, untouched by
         * pure ALU code. At exit all dirty registers are committed; x16
         * is used for the ABI/BTI-compatible direct tail transfer. x2=NULL
         * prevents the successor from following an unvalidated third block. */
        block->native_link=1;
        optimized[2]=0xaa0203e6u; /* MOV x6,x2 */
        words=3+ambient_sh2_regcache(code+2,words-2,optimized+3);
        optimized[words++]=0xb40000a6u; /* CBZ x6,return */
        optimized[words++]=0xaa0603f0u; /* MOV x16,x6 */
        optimized[words++]=0xaa0703e1u; /* MOV x1,x7 */
        optimized[words++]=0xaa1f03e2u; /* MOV x2,xzr */
        optimized[words++]=0xd61f0200u; /* BR x16 */
    } else words=2+ambient_sh2_regcache(code+2,words-2,optimized+2);
    if(block->dynamic_exit) {
        /* Preserve the prediction and link target in callee-saved registers,
         * and the caller LR across BLR. Guest registers are already committed
         * before the compare; the successor receives the normal pure ABI. */
        memmove(optimized+6,optimized+2,(words-2)*sizeof(uint32_t));
        optimized[2]=0xa9be53f3u; /* STP x19,x20,[sp,#-32]! */
        optimized[3]=0xf9000bfeu; /* STR x30,[sp,#16] */
        optimized[4]=0x2a0203f3u; /* MOV w19,w2 */
        optimized[5]=0xaa0303f4u; /* MOV x20,x3 */
        words+=4;
        const uint32_t tail[]={
            0x6b1300dfu, /* CMP w6,w19 */
            0x54000101u, /* B.NE mismatch */
            0xb40000f4u, /* CBZ x20,mismatch */
            0xaa1403f0u, /* MOV x16,x20 */
            0xaa0703e1u, /* MOV x1,x7 */
            0xaa1f03e2u, /* MOV x2,xzr */
            0xd63f0200u, /* BLR x16 */
            0xd2c00020u, /* MOVZ x0,#1,LSL #32 */
            0x14000002u, /* B restore */
            0x2a0603e0u, /* mismatch: MOV w0,w6 */
            0xf9400bfeu, /* restore: LDR x30,[sp,#16] */
            0xa8c253f3u  /* LDP x19,x20,[sp],#32 */
        };
        memcpy(optimized+words,tail,sizeof(tail));words+=sizeof(tail)/sizeof(tail[0]);
    }
    optimized[words++]=0xd65f03c0u;
    void* mapped=ambient_sh2_install_code(cpu,block,optimized,words);
    if(!mapped){ambient_sh2_disabled[cpu]=1;return 0;}
    block->code=mapped;
    block->count = count;
    block->cycles = cycles;
    ambient_sh2_build_spans(block,fetch);
    return 0; /* cold execution uses the native single-op path */
}

/* Direct entry (tests/debug callers) never trusts an earlier scope. */
static inline int ambient_sh2_try_block(SH2_struct* context,u32 budget,
                                  AmbientSh2Block* linked,AmbientSh2Block** executed)
{
    return ambient_sh2_try_block_scoped(context,budget,linked,executed,0);
}

/* Four cached outgoing edges per block accommodate polymorphic jumps.
 * Try the most recently used edge first and retain exact target tags.
 * Never patch RX pages. Chained entry uses the exact same validation as an
 * ordinary cache hit and writes back architectural state before helpers.
 */
static int ambient_sh2_try_in_scope(SH2_struct* context,u32 budget,uint64_t* execution_scope)
{
    AmbientSh2Block* current=NULL;
    const unsigned cpu=context->isslave?1:0;
    uint64_t scope=*execution_scope;
    if(!scope)scope=ambient_sh2_new_scope(cpu);
    if(!ambient_sh2_try_block_scoped(context,budget,NULL,&current,scope)) {
        *execution_scope=0;return 0;
    }
    if(current->has_terminal)scope=ambient_sh2_new_scope(cpu);
    if(current->native_loop&&!yabsys.sh2_cache_enabled) {
        *execution_scope=scope;return 1;
    }
    for(unsigned chain=1;chain<16 && ambient_sh2_eligible(context) &&
        context->cycles<budget;++chain) {
        const uint32_t pc=context->regs.PC;
        /* A terminal helper has already opened a fresh validation scope. */
        AmbientSh2Block* target=NULL;
        for(unsigned j=0;j<AMBIENT_SH2_EDGES;++j) {
            const unsigned i=(current->last_edge+j)&(AMBIENT_SH2_EDGES-1);
            AmbientSh2Block* candidate=current->edges[i].target;
            if(candidate && current->edges[i].pc==pc && candidate->pc==pc &&
               (candidate->code||candidate->has_terminal)) {
                target=candidate;current->last_edge=i;break;
            }
        }
        if(!target) {
            target=ambient_sh2_lookup(cpu,pc,0);
            if(!target || (!target->code && !target->has_terminal))break;
            const unsigned i=current->next_edge++&(AMBIENT_SH2_EDGES-1);
            current->edges[i].pc=pc;current->edges[i].target=target;
            current->last_edge=i;
        }
        AmbientSh2Block* completed=NULL;
        if(!ambient_sh2_try_block_scoped(context,budget,target,&completed,scope))break;
        current=completed;
        if(current->has_terminal)scope=ambient_sh2_new_scope(cpu);
        if(current->native_loop&&!yabsys.sh2_cache_enabled)break;
    }
    *execution_scope=scope;return 1;
}
static inline int ambient_sh2_try(SH2_struct* context,u32 budget)
{
    uint64_t scope=0;
    return ambient_sh2_try_in_scope(context,budget,&scope);
}
#endif
#endif
