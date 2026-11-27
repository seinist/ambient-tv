#pragma once
/* hvcE contains an HEVCDecoderConfigurationRecord for the enhancement
 * layer. Convert its parameter sets to type-63 wrapped NALs using the BL
 * sample's length prefix, without changing the container's framing. */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

static inline unsigned ambient_fel_hvcc_length(const uint8_t *p,size_t n)
{
    if(!p||n<23||p[0]!=1)return 0;
    unsigned len=(p[21]&3)+1;
    return len==3?0:len;
}
static inline bool ambient_fel_hvce_prefix(const uint8_t *p,size_t n,unsigned length,
    uint8_t *dst,size_t capacity,size_t *written)
{
    if(!written||!ambient_fel_hvcc_length(p,n)||n>1024*1024||
        (length!=1&&length!=2&&length!=4))return false;
    size_t off=23,out=0;
    unsigned sets=0;
    for(unsigned a=0;a<p[22];a++){
        if(n-off<3)return false;
        unsigned type=p[off++]&63;
        unsigned count=(unsigned)p[off]<<8|p[off+1];off+=2;
        for(unsigned j=0;j<count;j++){
            if(n-off<2)return false;
            unsigned len=(unsigned)p[off]<<8|p[off+1];off+=2;
            if(len<2||len>n-off||(p[off]&0x81)||!(p[off+1]&7)||
                (p[off+1]>>3)||((p[off]>>1)&63)!=type)return false;
            // Only VPS/SPS/PPS are decoder configuration. Other valid
            // array types are ignored, never injected as picture NALs.
            if(type>=32&&type<=34){
                unsigned wrapped=len+2;
                if((length==1&&wrapped>255)||(length==2&&wrapped>65535))return false;
                size_t add=length+wrapped;
                if(add>1024*1024-out||(dst&&add>capacity-out))return false;
                if(dst){
                    for(unsigned k=0;k<length;k++)
                        dst[out+k]=(uint8_t)(wrapped>>(8*(length-1-k)));
                    dst[out+length]=0x7e;dst[out+length+1]=1;
                    memcpy(dst+out+length+2,p+off,len);
                }
                out+=add;sets|=1u<<(type-32);
            }
            off+=len;
        }
    }
    if(off!=n||sets!=7)return false;
    *written=out;return true;
}

/* Validate all NAL lengths and detect both a wrapped enhancement layer
 * and random access in the BL. No Annex B guessing or timestamp guessing. */
static inline bool ambient_fel_mp4_sample(const uint8_t *p,size_t n,unsigned length,
    bool *has_el,bool *random_access,size_t *el_offset)
{
    if(!p||!n||!has_el||!random_access||!el_offset||(length!=1&&length!=2&&length!=4))return false;
    bool el=false,irap=false,bl_vcl=false;
    size_t first_el=0;
    for(size_t off=0;off<n;){
        size_t start=off;
        if(n-off<length)return false;
        uint32_t len=0;
        for(unsigned i=0;i<length;i++)len=(len<<8)|p[off++];
        if(len<2||len>n-off||(p[off]&0x80)||!(p[off+1]&7))return false;
        unsigned type=(p[off]>>1)&63;
        if(type<=31&&!(p[off]&1)&&!(p[off+1]>>3))bl_vcl=true;
        if(type==63){
            // VLC treats type 63 as AU tail; injecting before BL VCL
            // would cause its packetizer to discard the config as orphan data.
            if(len<5||!bl_vcl)return false;
            if(!el)first_el=start;
            el=true;
        }
        if(type>=16&&type<=23&&!(p[off]&1)&&!(p[off+1]>>3))irap=true;
        off+=len;
    }
    *has_el=el;*random_access=irap;*el_offset=first_el;return true;
}
