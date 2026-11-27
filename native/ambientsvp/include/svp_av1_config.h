/* SPDX-License-Identifier: Apache-2.0
 * Bounded AV1 low-overhead OBU framing for Android MediaCodec CSD.
 * AV1 sequence syntax itself is validated by VLC's pinned AV1 parser.
 */
#ifndef AMBIENTSVP_AV1_CONFIG_H
#define AMBIENTSVP_AV1_CONFIG_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

typedef struct { const uint8_t *data; size_t size; unsigned type; } SvpAv1Obu;
static inline bool svp_av1_next(const uint8_t **data,size_t *size,SvpAv1Obu *obu,bool require_size)
{
    const uint8_t *p=*data;
    size_t n=*size;
    if (!p || !n || (p[0]&0x81)) return false;
    unsigned type=(p[0]>>3)&15;
    size_t h=1;
    if (p[0]&4) {
        if (n<2 || (p[1]&7)) return false;
        h++;
    }
    uint64_t payload;
    if (p[0]&2) {
        payload=0;
        bool end=false;
        for (unsigned i=0;i<8;i++) {
            if (h>=n) return false;
            uint8_t b=p[h++];
            payload|=(uint64_t)(b&127)<<(7*i);
            if (!(b&128)) {end=true;break;}
        }
        if (!end || payload>UINT32_MAX || payload>n-h) return false;
    } else {
        if (require_size) return false;
        payload=n-h;
    }
    if (type==1 && (!payload || (p[0]&4))) return false;
    size_t total=h+(size_t)payload;
    *obu=(SvpAv1Obu){p,total,type};
    *data=p+total;*size=n-total;
    return true;
}
typedef struct { const uint8_t *csd; size_t csd_size; SvpAv1Obu sequence; } SvpAv1Config;
static inline bool svp_av1_config(const uint8_t *data,size_t size,SvpAv1Config *out)
{
    /* av1C version 1, main profile, 8-bit, non-monochrome, 4:2:0.
     * Main profile alone does not distinguish 8-bit and 10-bit AV1. */
    if (!data || size<=4 || size>1024*1024 || data[0]!=0x81 ||
        (data[1]&0xe0) || (data[2]&0x7c)!=0x0c || (data[3]&0xe0))
        return false;
    SvpAv1Config result={data+4,size-4,{0}};
    const uint8_t *p=result.csd;size_t n=result.csd_size;
    while (n) {
        SvpAv1Obu obu;
        if (!svp_av1_next(&p,&n,&obu,true)) return false;
        if (obu.type==1) {
            if (result.sequence.data) return false;
            result.sequence=obu;
        } else if (obu.type!=5 && obu.type!=15) return false;
    }
    if (!result.sequence.data) return false;
    *out=result;
    return true;
}
static inline bool svp_av1_same_sequence(const SvpAv1Config *config,const uint8_t *data,size_t size)
{
    if (!data || !size) return false;
    while (size) {
        SvpAv1Obu obu;
        if (!svp_av1_next(&data,&size,&obu,false)) return false;
        if (obu.type==1 && (obu.size!=config->sequence.size ||
            memcmp(obu.data,config->sequence.data,obu.size))) return false;
    }
    return true;
}
#endif
