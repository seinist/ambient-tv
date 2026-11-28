/* SPDX-License-Identifier: GPL-2.0-or-later
 * Native terminal operations; included after the pinned interpreter types
 * and SH2delay. Guest delay-slot fetch/exception behavior stays upstream.
 */
#ifndef AMBIENT_SH2_CONTROL_H
#define AMBIENT_SH2_CONTROL_H
#ifndef AMBIENT_SH2_DELAY
#define AMBIENT_SH2_DELAY SH2delay
#endif
static inline int ambient_sh2_control_supported(uint16_t op)
{
    const unsigned single = op & 0xf0ff, top = op & 0xf000;
    const unsigned high = op & 0xff00, key = op & 0xf00f;
    return top == 0xa000 || top == 0xb000 ||
        single == 0x0023 || single == 0x0003 || single == 0x402b ||
        single == 0x400b || single == 0x000b || single == 0x001b ||
        high == 0x8d00 || high == 0x8f00 || high == 0xc700 ||
        key == 0x2007 || key == 0x3004;
}

static inline int ambient_sh2_control(SH2_struct* sh, uint16_t op)
{
    const unsigned n = (op >> 8) & 15, m = (op >> 4) & 15;
    const unsigned single = op & 0xf0ff, top = op & 0xf000;
    const u32 pc = sh->regs.PC;
    u32 target;
    if (top == 0xa000 || top == 0xb000) {
        const int disp = (op & 0x800u) ? (int)(op & 0xfffu)-4096 : (int)(op & 0xfffu);
        if (top == 0xb000) sh->regs.PR = pc+4;
        target = pc+4+(u32)(disp*2);
    } else if (single == 0x0023 || single == 0x0003) {
        if (single == 0x0003) sh->regs.PR = pc+4;
        target = pc+4+sh->regs.R[n];
    } else if (single == 0x402b || single == 0x400b) {
        if (single == 0x400b) sh->regs.PR = pc+4;
        target = sh->regs.R[n];
    } else if (single == 0x000b) {
        target = sh->regs.PR;
    } else if ((op & 0xff00u) == 0x8d00 || (op & 0xff00u) == 0x8f00) {
        const unsigned taken = (sh->regs.SR.all & 1u) == ((op & 0xff00u) == 0x8d00);
        if (!taken) { sh->regs.PC += 2; ++sh->cycles; return 1; }
        target = pc+4+(u32)(2*(int32_t)(int8_t)op);
    } else {
        if ((op & 0xff00u) == 0xc700) {
            sh->regs.R[0] = ((pc+4)&~3u)+((op&255u)<<2);
        } else if ((op & 0xf00fu) == 0x2007) {
            const unsigned q = sh->regs.R[n] >> 31, mm = sh->regs.R[m] >> 31;
            sh->regs.SR.all = (sh->regs.SR.all & ~0x301u) | (q<<8) | (mm<<9) | (q^mm);
        } else if ((op & 0xf00fu) == 0x3004) {
            const u32 sr = sh->regs.SR.all;
            const unsigned oldq = (sr>>8)&1u, mm = (sr>>9)&1u;
            const unsigned sign = sh->regs.R[n]>>31;
            const u32 a = (sh->regs.R[n]<<1)|(sr&1u);
            /* Upstream reads Rm after shifting Rn (including n==m). */
            const u32 b = n==m ? a : sh->regs.R[m];
            const unsigned subtract = oldq==mm;
            const u32 result = subtract ? a-b : a+b;
            const unsigned carry = subtract ? result>a : result<a;
            const unsigned q = carry ^ sign ^ mm;
            sh->regs.R[n] = result;
            sh->regs.SR.all = (sr&~0x101u)|(q<<8)|(q==mm);
        } else if (single == 0x001b) {
            sh->cycles += 3; return 1;
        } else return 0;
        sh->regs.PC += 2; ++sh->cycles; return 1;
    }
    sh->regs.PC = target;
    sh->cycles += 2;
    AMBIENT_SH2_DELAY(sh, pc+2);
    return 1;
}
#endif
