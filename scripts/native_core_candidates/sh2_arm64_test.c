/* SPDX-License-Identifier: GPL-2.0-or-later
 * Execute every supported opcode on a real AArch64 CPU and compare with
 * an independent scalar model, including signed limits and register aliasing.
 */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "sh2_arm64.h"

static int reference(uint16_t op, uint32_t* r, uint32_t* sr)
{
    const unsigned n = (op >> 8) & 15, m = (op >> 4) & 15;
    uint32_t a = r[n], b = r[m], t = 0;
    const unsigned imm = op & 255;
    if (op == 0x0009) return 1;
    if ((op & 0xf000) == 0xe000) { r[n] = (uint32_t)(int32_t)(int8_t)op; return 1; }
    if ((op & 0xf000) == 0x7000) { r[n] = a + (uint32_t)(int32_t)(int8_t)op; return 1; }
    switch (op & 0xf00f) {
    case 0x6003: r[n] = b; return 1;
    case 0x6007: r[n] = ~b; return 1;
    case 0x6008: r[n] = (b & 0xffff0000u) | ((b & 255u) << 8) | ((b >> 8) & 255u); return 1;
    case 0x6009: r[n] = b << 16 | b >> 16; return 1;
    case 0x600b: r[n] = 0u - b; return 1;
    case 0x600c: r[n] = b & 255u; return 1;
    case 0x600d: r[n] = b & 65535u; return 1;
    case 0x600e: r[n] = (uint32_t)(int32_t)(int8_t)b; return 1;
    case 0x600f: r[n] = (uint32_t)(int32_t)(int16_t)b; return 1;
    case 0x300c: r[n] = a+b; return 1;
    case 0x3008: r[n] = a-b; return 1;
    case 0x2009: r[n] = a&b; return 1;
    case 0x200a: r[n] = a^b; return 1;
    case 0x200b: r[n] = a|b; return 1;
    case 0x200d: r[n] = b << 16 | a >> 16; return 1;
    case 0x3000: t = a == b; goto flag;
    case 0x3002: t = a >= b; goto flag;
    case 0x3003: t = (int32_t)a >= (int32_t)b; goto flag;
    case 0x3006: t = a > b; goto flag;
    case 0x3007: t = (int32_t)a > (int32_t)b; goto flag;
    case 0x2008: t = (a & b) == 0; goto flag;
    }
    switch (op & 0xff00) {
    case 0xc900: r[0] &= imm; return 1;
    case 0xca00: r[0] ^= imm; return 1;
    case 0xcb00: r[0] |= imm; return 1;
    case 0xc800: t = (r[0] & imm) == 0; goto flag;
    case 0x8800: t = r[0] == (uint32_t)(int32_t)(int8_t)op; goto flag;
    }
    switch (op & 0xf0ff) {
    case 0x4008: r[n] = a << 2; return 1;
    case 0x4018: r[n] = a << 8; return 1;
    case 0x4028: r[n] = a << 16; return 1;
    case 0x4009: r[n] = a >> 2; return 1;
    case 0x4019: r[n] = a >> 8; return 1;
    case 0x4029: r[n] = a >> 16; return 1;
    case 0x0029: r[n] = *sr & 1; return 1;
    case 0x4010: r[n] = a-1; t = r[n] == 0; goto flag;
    case 0x4011: t = (int32_t)a >= 0; goto flag;
    case 0x4015: t = (int32_t)a > 0; goto flag;
    }
    if (op == 0x0008 || op == 0x0018) { t = op == 0x0018; goto flag; }
    return 0;
flag:
    *sr = (*sr & ~1u) | t;
    return 1;
}

static uint32_t next(uint32_t* state) {
    *state ^= *state << 13;
    *state ^= *state >> 17;
    *state ^= *state << 5;
    return *state;
}

int main(void)
{
    const long page = sysconf(_SC_PAGESIZE);
    assert(page > 0);
    uint32_t* code = mmap(NULL, (size_t)page, PROT_READ|PROT_WRITE,
                          MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
    assert(code != MAP_FAILED);
    uint32_t seed = 0x13572468u;
    unsigned tested = 0;
    const uint32_t edge[] = {0, 1, 0xffffffffu, 0x80000000u, 0x7fffffffu, 0x80, 0x8000, 0x1234abcd};
    for (unsigned raw=0; raw<65536; ++raw) {
        uint32_t words[AMBIENT_SH2_MAX_WORDS] = {0xd503245fu, 0xaa0103e7u};
        unsigned n = ambient_sh2_emit((uint16_t)raw, words+2);
        uint32_t scratch[16] = {0}, sr = 0;
        assert((n != 0) == reference((uint16_t)raw, scratch, &sr));
        if (!n) continue;
        ++tested;
        words[n+1] = 0xd65f03c0u;
        assert(mprotect(code, (size_t)page, PROT_READ|PROT_WRITE) == 0);
        memcpy(code, words, (n+2)*sizeof(uint32_t));
        __builtin___clear_cache((char*)code, (char*)code+(n+2)*sizeof(uint32_t));
        assert(mprotect(code, (size_t)page, PROT_READ|PROT_EXEC) == 0);
        for (unsigned sample=0; sample<32; ++sample) {
            uint32_t expected[16], actual[16];
            for (unsigned i=0; i<16; ++i)
                expected[i] = actual[i] = sample < 16 ? edge[(sample+i)%8] : next(&seed);
            // Equal operands, aliasing and zero flags are deliberately exercised.
            if (sample < 8) expected[(raw>>8)&15] = actual[(raw>>8)&15] =
                actual[(raw>>4)&15];
            uint32_t want = next(&seed), got = want;
            reference((uint16_t)raw, expected, &want);
            ((void(*)(uint32_t*,uint32_t*))code)(actual,&got);
            if (want != got || memcmp(expected,actual,sizeof(actual)) != 0) {
                fprintf(stderr,"SH2 opcode %04x sample %u: expected SR %08x got %08x\n",raw,sample,want,got);
                return 1;
            }
        }
    }
    munmap(code, (size_t)page);
    printf("AArch64 SH2: %u opcode encodings, %u differential executions passed\n",tested,tested*32);
    return 0;
}
