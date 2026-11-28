/* SPDX-License-Identifier: GPL-2.0-or-later
 * Conservative AArch64 straight-line SH2 block compiler.
 * This header is included only by the explicit candidate interpreter TU.
 */
#ifndef AMBIENT_SH2_ARM64_H
#define AMBIENT_SH2_ARM64_H
#include <stdint.h>
#include <stddef.h>

#define AMBIENT_SH2_MAX_OPS 16
#define AMBIENT_SH2_MAX_WORDS (16 * AMBIENT_SH2_MAX_OPS + 3)

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
#include "ambient_sh2_memory.h"
#define AMBIENT_SH2_SLOTS 64
#define AMBIENT_SH2_HOT 4

typedef struct {
    uint32_t pc;
    uint16_t ops[AMBIENT_SH2_MAX_OPS];
    unsigned count, hits;
    uint16_t terminal;
    unsigned has_terminal;
    AmbientMemOp memory;
    AmbientComplexMem complex_memory;
    opcodefunc terminal_handler;
    void* code;
    size_t bytes;
} AmbientSh2Block;
static AmbientSh2Block ambient_sh2_cache[2][AMBIENT_SH2_SLOTS];
static int ambient_sh2_disabled[2];

static void ambient_sh2_clear(void)
{
    for (unsigned cpu = 0; cpu < 2; ++cpu) {
        for (unsigned slot = 0; slot < AMBIENT_SH2_SLOTS; ++slot) {
            AmbientSh2Block* b = &ambient_sh2_cache[cpu][slot];
            if (b->code) munmap(b->code, b->bytes);
        }
    }
    memset(ambient_sh2_cache, 0, sizeof(ambient_sh2_cache));
    memset(ambient_sh2_disabled, 0, sizeof(ambient_sh2_disabled));
}

static int ambient_sh2_try(SH2_struct* context, u32 budget)
{
    if (context->model != SHMT_SH2 || yabsys.sh2_cache_enabled || context->isIdle || context->isSleeping ||
        context->delay || context->breakpointEnabled || context->backtraceEnabled ||
        context->stepOverOut.enabled || context->trackInfLoop.enabled || context->cycles >= budget ||
        budget - context->cycles < 4)
        return 0;
    const uint32_t pc = context->regs.PC;
    if ((pc & 1u) || pc > UINT32_MAX - 2*AMBIENT_SH2_MAX_OPS ||
        (pc & 0xc0000000u) == 0xc0000000u) return 0;
    const fetchfunc fetch = ((fetchfunc*)context->fetchlist)[(pc >> 20) & 255];
    /* Only side-effect-free fetch regions. Never prefetch cartridges or MMIO. */
    if (fetch != FetchBios && fetch != FetchLWram && fetch != FetchHWram) return 0;
    const unsigned cpu = context->isslave ? 1 : 0;
    if (ambient_sh2_disabled[cpu]) return 0;
    AmbientSh2Block* block = &ambient_sh2_cache[cpu][(pc >> 1 ^ pc >> 9) & (AMBIENT_SH2_SLOTS-1)];
    if (block->pc == pc && block->count && block->code &&
        block->count <= budget - context->cycles &&
        (!block->has_terminal || block->count < budget - context->cycles)) {
        unsigned i;
        /* Self-modifying code, savestate loads and RAM aliases cannot reuse
         * a stale translation: verify every source instruction before entry. */
        for (i = 0; i < block->count; ++i) {
            uint32_t addr = pc + i*2;
            if (((fetchfunc*)context->fetchlist)[(addr >> 20) & 255] != fetch ||
                (uint16_t)fetch(context, addr) != block->ops[i]) break;
        }
        const uint32_t terminal_pc = pc + block->count*2;
        if (i == block->count && (!block->has_terminal ||
            (((fetchfunc*)context->fetchlist)[(terminal_pc >> 20) & 255] == fetch &&
             (uint16_t)fetch(context, terminal_pc) == block->terminal))) {
            ((void (*)(u32*, u32*))block->code)(context->regs.R, &context->regs.SR.all);
            context->instruction = block->ops[block->count-1];
            context->regs.PC += block->count * 2;
            context->cycles += block->count;
            if (block->has_terminal) {
                context->instruction = block->terminal;
                unsigned cost = ambient_sh2_branch(block->terminal, &context->regs.PC,
                                                   context->regs.SR.all);
                if (cost) context->cycles += cost;
                else if (block->memory.mode) ambient_mem_execute(context, block->memory);
                else if (block->complex_memory.mode) ambient_complex_execute(context, block->complex_memory);
                else {
                    // Delay-slot, MMIO, bus-wait and self-modifying store
                    // semantics remain in the exact existing opcode handler.
                    // End the block immediately after the potentially mutating access.
                    block->terminal_handler(context);
                }
            }
            return 1;
        }
        munmap(block->code, block->bytes);
        memset(block, 0, sizeof(*block));
    }
    if (block->code && block->pc == pc) return 0; /* insufficient cycle budget */
    if (block->pc != pc) {
        if (block->code) munmap(block->code, block->bytes);
        memset(block, 0, sizeof(*block));
        block->pc = pc;
    }
    if (block->hits < AMBIENT_SH2_HOT && ++block->hits < AMBIENT_SH2_HOT) return 0;
    if (block->count == 1) {
        if ((uint16_t)fetch(context, pc) == block->ops[0]) return 0;
        block->count = 0;
    }
    uint32_t code[AMBIENT_SH2_MAX_WORDS];
    unsigned words = 0, count = 0;
    block->has_terminal = 0;
    code[words++] = 0xd503245fu; /* BTI c, harmless hint on older ARM64 */
    code[words++] = 0xaa0103e7u; /* MOV x7,x1: SR pointer survives scratch register writes */
    while (count < AMBIENT_SH2_MAX_OPS) {
        uint32_t addr = pc + count*2;
        if (((fetchfunc*)context->fetchlist)[(addr >> 20) & 255] != fetch) break;
        uint16_t op = (uint16_t)fetch(context, addr);
        unsigned emitted = ambient_sh2_emit(op, code + words);
        if (!emitted) {
            // One terminal instruction may reuse the verified fetch. Never
            // precompile beyond a potentially mutating memory/control operation.
            if (count >= 2) {
                block->terminal = op;
                block->memory = ambient_mem_decode(op);
                block->complex_memory = ambient_complex_decode(op);
                block->terminal_handler = ((opcodefunc*)context->opcodes)[op];
                block->has_terminal = 1;
            }
            break;
        }
        words += emitted-1;
        block->ops[count++] = op;
    }
    if (count < (block->has_terminal ? 2u : 4u)) {
        block->ops[0] = (uint16_t)fetch(context, pc);
        block->count = 1;
        block->has_terminal = 0;
        return 0;
    }
    code[words++] = 0xd65f03c0u;
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0 || (size_t)page < words*sizeof(uint32_t)) {
        ambient_sh2_disabled[cpu] = 1;
        return 0;
    }
    void* mapped = mmap(NULL, (size_t)page, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
    if (mapped == MAP_FAILED) { ambient_sh2_disabled[cpu] = 1; return 0; }
    memcpy(mapped, code, words*sizeof(uint32_t));
    __builtin___clear_cache((char*)mapped, (char*)mapped + words*sizeof(uint32_t));
    if (mprotect(mapped, (size_t)page, PROT_READ|PROT_EXEC) != 0) {
        munmap(mapped, (size_t)page);
        ambient_sh2_disabled[cpu] = 1;
        return 0;
    }
    block->code = mapped;
    block->bytes = (size_t)page;
    block->count = count;
    return 0; /* interpreter executes this iteration; next hot entry uses translation */
}
#endif
#endif
