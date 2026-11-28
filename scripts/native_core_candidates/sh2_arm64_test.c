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
    case 0x300e: {
        uint64_t value = (uint64_t)a + b + (*sr & 1u);
        r[n] = (uint32_t)value; t = value >> 32; goto flag;
    }
    case 0x300a: {
        uint64_t subtract = (uint64_t)b + (*sr & 1u);
        r[n] = a - (uint32_t)subtract; t = (uint64_t)a < subtract; goto flag;
    }
    case 0x600a: {
        uint64_t subtract = (uint64_t)b + (*sr & 1u);
        r[n] = 0u - (uint32_t)subtract; t = subtract != 0; goto flag;
    }
    case 0x300f: {
        int64_t value = (int64_t)(int32_t)a + (int32_t)b;
        r[n] = (uint32_t)value; t = value < INT32_MIN || value > INT32_MAX; goto flag;
    }
    case 0x300b: {
        int64_t value = (int64_t)(int32_t)a - (int32_t)b;
        r[n] = (uint32_t)value; t = value < INT32_MIN || value > INT32_MAX; goto flag;
    }
    case 0x200c: {
        uint32_t difference = a ^ b;
        t = 0;
        for (unsigned byte=0; byte<4; ++byte)
            t |= ((difference >> (byte*8)) & 255u) == 0;
        goto flag;
    }
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
    case 0x4024: r[n] = (a << 1) | (*sr & 1u); t = a >> 31; goto flag;
    case 0x4025: r[n] = (a >> 1) | ((*sr & 1u) << 31); t = a & 1u; goto flag;
    case 0x4000: case 0x4020: r[n] = a << 1; t = a >> 31; goto flag;
    case 0x4001: r[n] = a >> 1; t = a & 1; goto flag;
    case 0x4021: r[n] = (a >> 1) | (a & 0x80000000u); t = a & 1; goto flag;
    case 0x4004: r[n] = (a << 1) | (a >> 31); t = a >> 31; goto flag;
    case 0x4005: r[n] = (a >> 1) | (a << 31); t = a & 1; goto flag;
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
    for (unsigned kind=0; kind<2; ++kind)
        for (int displacement=-128; displacement<128; ++displacement)
            for (unsigned flag=0; flag<2; ++flag) {
                uint16_t op = (uint16_t)((kind ? 0x8b00 : 0x8900) | (displacement & 255));
                uint32_t pc = 0xfffffff0u;
                const int taken = kind ? !flag : flag;
                unsigned cost = ambient_sh2_branch(op, &pc, 0x3f0u|flag);
                assert(cost == (taken ? 3u : 1u));
                assert(pc == 0xfffffff0u + (uint32_t)(taken ? 4+2*displacement : 2));
            }
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
    // Exercise native multiplication and system-register transfers against
    // scalar arithmetic, with deliberately non-contiguous register offsets.
    struct { uint32_t r[16], pad[3], mach, macl, gbr, vbr, pr; } regs;
    const AmbientSh2Layout layout = {76,80,84,88,92};
    for (unsigned raw=0; raw<65536; ++raw) {
        const unsigned key=raw&0xf00f, single=raw&0xf0ff;
        unsigned cost=0, expected_cost=1;
        uint32_t words[AMBIENT_SH2_MAX_WORDS]={0xd503245fu,0xaa0103e7u};
        unsigned n=ambient_sh2_emit_extended(raw,words+2,layout,&cost);
        if (!n || ambient_sh2_emit(raw,words+2)) continue;
        n=ambient_sh2_emit_extended(raw,words+2,layout,&cost);
        words[n+1]=0xd65f03c0u;
        assert(mprotect(code,(size_t)page,PROT_READ|PROT_WRITE)==0);
        memcpy(code,words,(n+2)*sizeof(uint32_t));
        __builtin___clear_cache((char*)code,(char*)code+(n+2)*sizeof(uint32_t));
        assert(mprotect(code,(size_t)page,PROT_READ|PROT_EXEC)==0);
        for(unsigned sample=0;sample<32;++sample) {
            memset(&regs,0,sizeof(regs));
            for(unsigned j=0;j<16;++j) regs.r[j]=sample<16?edge[(sample+j)%8]:next(&seed);
            regs.mach=next(&seed);regs.macl=next(&seed);regs.gbr=next(&seed);
            regs.vbr=next(&seed);regs.pr=next(&seed);
            uint32_t sr=next(&seed),want_sr=sr;
            __typeof__(regs) want=regs;
            const unsigned a=(raw>>8)&15,b=(raw>>4)&15;
            uint64_t product;
            switch(key) {
            case 0x0007: want.macl=want.r[a]*want.r[b];expected_cost=2;break;
            case 0x200e: want.macl=(uint32_t)(uint16_t)want.r[a]*(uint16_t)want.r[b];break;
            case 0x200f: want.macl=(uint32_t)((int32_t)(int16_t)want.r[a]*(int16_t)want.r[b]);break;
            case 0x3005: case 0x300d:
                product=key==0x3005?(uint64_t)want.r[a]*want.r[b]:
                    (uint64_t)((int64_t)(int32_t)want.r[a]*(int32_t)want.r[b]);
                want.mach=product>>32;want.macl=(uint32_t)product;expected_cost=2;break;
            default:
                switch(single) {
                case 0x0009: break;
                case 0x0008: want_sr&=~1u;break;
                case 0x0018: want_sr|=1u;break;
                case 0x0019: want_sr&=~0x301u;break;
                case 0x0028: want.mach=want.macl=0;break;
                case 0x0002: want.r[a]=sr;break;
                case 0x400e: want_sr=want.r[a]&0x3f3u;break;
                case 0x0012: want.r[a]=want.gbr;break;
                case 0x0022: want.r[a]=want.vbr;break;
                case 0x000a: want.r[a]=want.mach;break;
                case 0x001a: want.r[a]=want.macl;break;
                case 0x002a: want.r[a]=want.pr;break;
                case 0x401e: want.gbr=want.r[a];break;
                case 0x402e: want.vbr=want.r[a];break;
                case 0x400a: want.mach=want.r[a];break;
                case 0x401a: want.macl=want.r[a];break;
                case 0x402a: want.pr=want.r[a];break;
                default: assert(0);
                }
            }
            ((void(*)(uint32_t*,uint32_t*))code)(regs.r,&sr);
            assert(cost==expected_cost && sr==want_sr && !memcmp(&regs,&want,sizeof(regs)));
        }
    }
    // A read/write-heavy trace must actually eliminate guest memory traffic,
    // while leaving all native arithmetic and condition flags intact.
    {
        uint32_t input[128],output[160];
        unsigned count=0;
        for(unsigned i=0;i<16;++i)count+=ambient_sh2_emit(0x312c,input+count)-1;
        const unsigned optimized=ambient_sh2_regcache(input,count,output);
        unsigned before=0,after=0,guest,load;
        for(unsigned i=0;i<count;++i)before+=ambient_sh2_gpr_access(input[i],&guest,&load);
        for(unsigned i=0;i<optimized;++i)after+=ambient_sh2_gpr_access(output[i],&guest,&load);
        assert(before==48 && after==3);
    }
    // Every legal guest instruction is covered by either the native emitter
    // or a specialized terminal. The pinned decoder parity is checked separately.
    // Test concatenated blocks, not just independent opcodes: flags and
    // register outputs must survive across each emitted operation.
    for (unsigned block=0; block<4096; ++block) {
        uint32_t expected[16], actual[16];
        uint32_t want = next(&seed), got = want;
        for (unsigned i=0;i<16;++i) expected[i]=actual[i]=next(&seed);
        uint32_t words[AMBIENT_SH2_MAX_WORDS] = {0xd503245fu,0xaa0103e7u};
        unsigned cursor=2;
        for (unsigned i=0;i<AMBIENT_SH2_MAX_OPS;) {
            uint16_t op=(uint16_t)next(&seed);
            unsigned n=ambient_sh2_emit(op,words+cursor);
            if (!n) continue;
            assert(reference(op,expected,&want));
            cursor+=n-1;
            ++i;
        }
        uint32_t optimized[AMBIENT_SH2_MAX_WORDS]={words[0],words[1]};
        cursor=2+ambient_sh2_regcache(words+2,cursor-2,optimized+2);
        optimized[cursor++]=0xd65f03c0u;
        assert(cursor<=AMBIENT_SH2_MAX_WORDS);
        memcpy(words,optimized,cursor*sizeof(uint32_t));
        assert(mprotect(code,(size_t)page,PROT_READ|PROT_WRITE)==0);
        memcpy(code,words,cursor*sizeof(uint32_t));
        __builtin___clear_cache((char*)code,(char*)code+cursor*sizeof(uint32_t));
        assert(mprotect(code,(size_t)page,PROT_READ|PROT_EXEC)==0);
        ((void(*)(uint32_t*,uint32_t*))code)(actual,&got);
        assert(want==got && memcmp(expected,actual,sizeof(actual))==0);
    }
    munmap(code, (size_t)page);
    printf("AArch64 SH2: 4096 multi-instruction blocks passed\n");
    printf("AArch64 SH2: %u opcode encodings, %u differential executions passed\n",tested,tested*32);
    return 0;
}
