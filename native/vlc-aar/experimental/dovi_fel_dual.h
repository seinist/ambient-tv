#pragma once
#include "dovi_fel_hvce.h"

#define AMBIENT_FEL_MAX_AU (32u*1024u*1024u)

/* Legacy dual-track carriage: BL sample followed by EL NALs wrapped in
 * type 63, with the original type-62 RPU outside the wrapper. No attempt
 * to infer PTS, track identity, or a missing RPU from NAL contents. */
static inline bool ambient_fel_dual_sample(const uint8_t *bl,size_t bn,unsigned bw,
    const uint8_t *el,size_t en,unsigned ew,const uint8_t *config,size_t cn,
    bool inject,uint8_t *out,size_t cap,size_t *written)
{
    if(!bl||!el||!written||!bn||!en||bn>AMBIENT_FEL_MAX_AU||en>AMBIENT_FEL_MAX_AU||
        (bw!=1&&bw!=2&&bw!=4)||(ew!=1&&ew!=2&&ew!=4))return false;
    bool bv=false,ev=false,rpu=false;
    size_t total=bn;
    for(size_t p=0;p<bn;){
        if(bn-p<bw)return false;
        uint32_t len=0;
        for(unsigned j=0;j<bw;j++)len=(len<<8)|bl[p++];
        if(len<2||len>bn-p||(bl[p]&0x81)||!(bl[p+1]&7)||(bl[p+1]>>3))return false;
        unsigned type=(bl[p]>>1)&63;
        if(type>=62)return false;
        if(type<=31)bv=true;
        p+=len;
    }
    if(!bv)return false;
    size_t prefix=0;
    if(inject){
        if(!ambient_fel_hvce_prefix(config,cn,bw,NULL,0,&prefix)||
            prefix>AMBIENT_FEL_MAX_AU-total)return false;
        total+=prefix;
    }
    for(size_t p=0;p<en;){
        if(en-p<ew)return false;
        uint32_t len=0;
        for(unsigned j=0;j<ew;j++)len=(len<<8)|el[p++];
        if(len<3||len>en-p||(el[p]&0x81)||!(el[p+1]&7)||(el[p+1]>>3))return false;
        unsigned type=(el[p]>>1)&63;
        if(type==63)return false;
        if(type==62){if(rpu)return false;rpu=true;}
        if(type<=31)ev=true;
        uint64_t wrapped=(uint64_t)len+(type==62?0:2);
        if((bw==1&&wrapped>255)||(bw==2&&wrapped>65535)||
            wrapped+bw>AMBIENT_FEL_MAX_AU-total)return false;
        total+=(size_t)wrapped+bw;
        p+=len;
    }
    if(!ev||!rpu)return false;
    if(!out){*written=total;return true;}
    if(cap<total)return false;
    memcpy(out,bl,bn);
    size_t pos=bn;
    if(inject){
        if(!ambient_fel_hvce_prefix(config,cn,bw,out+pos,prefix,&prefix))return false;
        pos+=prefix;
    }
    for(size_t p=0;p<en;){
        uint32_t len=0;
        for(unsigned j=0;j<ew;j++)len=(len<<8)|el[p++];
        bool wrap=((el[p]>>1)&63)!=62;
        uint32_t n=len+(wrap?2:0);
        for(unsigned j=0;j<bw;j++)out[pos++]=(uint8_t)(n>>(8*(bw-1-j)));
        if(wrap){out[pos++]=0x7e;out[pos++]=1;}
        memcpy(out+pos,el+p,len);pos+=len;p+=len;
    }
    *written=pos;
    return pos==total;
}
