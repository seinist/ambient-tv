/* SPDX-License-Identifier: Apache-2.0
 * Fail-closed dynamic HDR detection for the static-HDR ASC path.
 * Input is the HEVC Annex-B access unit after hxxx_helper conversion.
 */
#ifndef AMBIENTSVP_HEVC_HDR_GUARD_H
#define AMBIENTSVP_HEVC_HDR_GUARD_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
typedef struct { const uint8_t *p; size_t n; unsigned zeros; } SvpSeiReader;
static inline int svp_sei_byte(SvpSeiReader *r)
{
    while (r->n) {
        unsigned b=*r->p++;r->n--;
        if (r->zeros>=2 && b==3) {
            if (!r->n || *r->p>3) return -1;
            r->zeros=0;continue;
        }
        r->zeros=b==0?r->zeros+1:0;
        return (int)b;
    }
    return -1;
}
static inline bool svp_hevc_sei_dynamic(const uint8_t *p,size_t n)
{
    SvpSeiReader r={p,n,0};
    while (r.n) {
        int b=svp_sei_byte(&r);
        if (b<0) return true;
        if (b==0x80 && !r.n) return false; /* RBSP trailing bits */
        size_t type=0,size=0;
        while (b==255) {
            if (type>65535) return true;
            type+=255;b=svp_sei_byte(&r);
            if (b<0) return true;
        }
        type+=(unsigned)b;
        do {
            b=svp_sei_byte(&r);
            if (b<0 || size>n || (size_t)b>n-size) return true;
            size+=(unsigned)b;
        } while (b==255);
        uint8_t prefix[6]={0};
        for (size_t i=0;i<size;i++) {
            b=svp_sei_byte(&r);
            if (b<0) return true;
            if (i<sizeof(prefix)) prefix[i]=(uint8_t)b;
        }
        /* ITU-T T.35 HDR10+ provider 003c, oriented code 0001, app 4.
         * Unknown payloads with this provider are also excluded. */
        if (type==4 && size>=3 && prefix[0]==0xb5 && prefix[1]==0 && prefix[2]==0x3c) return true;
    }
    return true; /* missing trailing bits */
}
static inline size_t svp_hevc_start(const uint8_t *p,size_t n,size_t from)
{
    for (size_t i=from;i<n && n-i>=3;i++)
        if (!p[i]&&!p[i+1]&&p[i+2]==1) return i;
    return n;
}
static inline bool svp_hevc_dynamic_hdr(const uint8_t *p,size_t n)
{
    if (!p || n<5) return true;
    size_t i=svp_hevc_start(p,n,0);
    if (i==n) return true;
    for (size_t j=0;j<i;j++) if (p[j]) return true;
    while (i<n) {
        size_t start=i+3,next=svp_hevc_start(p,n,start),end=next;
        while (end>start && p[end-1]==0) end--;
        if (end-start<2 || (p[start]&0x80) || !(p[start+1]&7)) return true;
        unsigned type=(p[start]>>1)&63;
        unsigned layer=((p[start]&1)<<5)|(p[start+1]>>3);
        if (layer || type==62 || type==63) return true; /* EL / RPU */
        if ((type==39 || type==40) && svp_hevc_sei_dynamic(p+start+2,end-start-2)) return true;
        i=next;
    }
    return false;
}
#endif
