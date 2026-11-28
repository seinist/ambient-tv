/* SPDX-License-Identifier: GPL-2.0-or-later
 * Conservative AArch64 straight-line SH2 block compiler.
 * This header is included only by the explicit candidate interpreter TU.
 */
#ifndef AMBIENT_SH2_ARM64_H
#define AMBIENT_SH2_ARM64_H
#include <stdint.h>
#include <stddef.h>

#define AMBIENT_SH2_MAX_OPS 16
#define AMBIENT_SH2_MAX_WORDS (4 * AMBIENT_SH2_MAX_OPS + 2)

/* Emit a single side-effect-free, one-cycle SH2 instruction into AArch64.
 * x0 points at R[16]; w1/w2 are caller-saved scratch. No flags are modified
 * in the guest state, no memory access except the guest register file.
 * Branches, SR changes, delay slots and MMIO remain interpreter operations.
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

#if defined(AMBIENT_SH2_ARM64_JIT)
#if !defined(__aarch64__)
#error ARM64 SH2 candidate must only be built on AArch64
#endif
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#define AMBIENT_SH2_SLOTS 64
#define AMBIENT_SH2_HOT 4

typedef struct {
    uint32_t pc;
    uint16_t ops[AMBIENT_SH2_MAX_OPS];
    unsigned count, hits;
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
    if (yabsys.sh2_cache_enabled || context->isIdle || context->isSleeping ||
        context->delay || context->breakpointEnabled || context->cycles >= budget ||
        budget - context->cycles < 4)
        return 0;
    const uint32_t pc = context->regs.PC;
    if ((pc & 1u) || pc > UINT32_MAX - 2*AMBIENT_SH2_MAX_OPS) return 0;
    const fetchfunc fetch = ((fetchfunc*)context->fetchlist)[(pc >> 20) & 255];
    /* Only side-effect-free fetch regions. Never prefetch cartridges or MMIO. */
    if (fetch != FetchBios && fetch != FetchLWram && fetch != FetchHWram) return 0;
    const unsigned cpu = context->isslave ? 1 : 0;
    if (ambient_sh2_disabled[cpu]) return 0;
    AmbientSh2Block* block = &ambient_sh2_cache[cpu][(pc >> 1 ^ pc >> 9) & (AMBIENT_SH2_SLOTS-1)];
    if (block->pc == pc && block->count && block->code &&
        block->count <= budget - context->cycles) {
        unsigned i;
        /* Self-modifying code, savestate loads and RAM aliases cannot reuse
         * a stale translation: verify every source instruction before entry. */
        for (i = 0; i < block->count; ++i)
            if ((uint16_t)fetch(context, pc + i*2) != block->ops[i]) break;
        if (i == block->count) {
            ((void (*)(u32*))block->code)(context->regs.R);
            context->instruction = block->ops[block->count-1];
            context->regs.PC += block->count * 2;
            context->cycles += block->count;
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
    if (block->count == 1) return 0; /* previously rejected, avoid scanning repeatedly */
    uint32_t code[AMBIENT_SH2_MAX_WORDS];
    unsigned words = 0, count = 0;
    code[words++] = 0xd503245fu; /* BTI c, harmless hint on older ARM64 */
    while (count < AMBIENT_SH2_MAX_OPS) {
        uint32_t addr = pc + count*2;
        if (((fetchfunc*)context->fetchlist)[(addr >> 20) & 255] != fetch) break;
        uint16_t op = (uint16_t)fetch(context, addr);
        unsigned emitted = ambient_sh2_emit(op, code + words);
        if (!emitted) break;
        words += emitted-1;
        block->ops[count++] = op;
    }
    if (count < 4) { block->count = 1; return 0; }
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
