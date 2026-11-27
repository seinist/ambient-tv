#pragma once
#include "dovi_fel_hvce.h"

#define AMBIENT_FEL_MAX_AU (32u*1024u*1024u)

/* A forward-only input needs random access within one bounded mdat.
 * The entire payload is owned by the demuxer until that fragment ends;
 * callers receive a view only while the owner is alive. */
#define AMBIENT_FEL_MAX_MDAT (64u*1024u*1024u)
static inline bool ambient_fel_mdat_slice(const uint8_t *data,size_t bytes,
    uint64_t start,uint64_t offset,size_t size,const uint8_t **slice)
{
    if(!data||!slice||!bytes||bytes>AMBIENT_FEL_MAX_MDAT||!size||
        bytes>UINT64_MAX-start||offset<start||offset-start>bytes||
        size>bytes-(size_t)(offset-start))return false;
    *slice=data+(size_t)(offset-start);
    return true;
}


/* A cursor caches the next byte offset in the current chunk. Sequential
 * playback becomes O(1) even for a chunk containing many variable-sized
 * samples; a backward seek safely restarts from the chunk's first byte.
 * No table allocation and no unchecked addition of file offsets. */
struct ambient_fel_offset_cursor {
    uint32_t chunk, sample;
    uint64_t offset;
    bool valid;
};
static inline bool ambient_fel_sample_offset(struct ambient_fel_offset_cursor *cursor,
    uint32_t chunk,uint32_t first,uint32_t count,uint64_t start,uint32_t sample,
    uint32_t total,uint32_t fixed,const uint32_t *sizes,uint64_t *offset,uint32_t *size)
{
    if(!cursor||!offset||!size||first>total||count>total-first||
        sample<first||sample-first>=count||(!fixed&&!sizes))return false;
    uint64_t pos=start;
    uint32_t n=first;
    if(fixed){
        uint64_t add=(uint64_t)(sample-first)*fixed;
        if(add>UINT64_MAX-pos)return false;
        pos+=add;
    }else{
        if(cursor->valid&&cursor->chunk==chunk&&cursor->sample>=first&&
            cursor->sample<=sample&&cursor->offset>=start){
            n=cursor->sample;pos=cursor->offset;
        }
        for(;n<sample;n++){
            if(sizes[n]>UINT64_MAX-pos)return false;
            pos+=sizes[n];
        }
    }
    uint32_t bytes=fixed?fixed:sizes[sample];
    if(!bytes||bytes>AMBIENT_FEL_MAX_AU||bytes>UINT64_MAX-pos)return false;
    *offset=pos;*size=bytes;
    *cursor=(struct ambient_fel_offset_cursor){.chunk=chunk,.sample=sample+1,
        .offset=pos+bytes,.valid=true};
    return true;
}

/* Convert raw timestamps between independent MP4 track timescales without
 * rounding, overflow, or a compiler-dependent 128-bit integer type.
 * A fractional destination tick means the samples cannot match exactly. */
static inline bool ambient_fel_rescale_exact(int64_t value,uint32_t from,uint32_t to,
    int64_t *result)
{
    if(!from||!to||!result)return false;
    bool neg=value<0;
    uint64_t magnitude=neg?(uint64_t)(-(value+1))+1:(uint64_t)value;
    uint64_t whole=magnitude/from,rem=magnitude%from;
    uint64_t fraction=rem*(uint64_t)to;
    if(fraction%from)return false;
    fraction/=from;
    uint64_t limit=(uint64_t)INT64_MAX+(neg?1u:0u);
    if(fraction>limit||whole>(limit-fraction)/to)return false;
    uint64_t scaled=whole*to+fraction;
    *result=neg?(scaled==limit?INT64_MIN:-(int64_t)scaled):(int64_t)scaled;
    return true;
}

/* A missing tfdt may only inherit the end of a previously validated,
 * contiguous fragment. The other track's explicit start (if any) must
 * agree with that same end. A seek or discontinuity requires both anchors. */
static inline bool ambient_fel_fragment_start(bool bl_explicit,uint64_t bl_time,
    bool el_explicit,uint64_t el_time,bool contiguous,bool prior_valid,
    int64_t prior_bl,int64_t prior_el,uint32_t bl_scale,uint32_t el_scale,
    int64_t *bl_start,int64_t *el_start)
{
    if(!bl_start||!el_start||
        (bl_explicit&&bl_time>INT64_MAX)||(el_explicit&&el_time>INT64_MAX))
        return false;
    bool implicit=!bl_explicit||!el_explicit;
    if(implicit&&(!contiguous||!prior_valid||prior_bl<0||prior_el<0))return false;
    int64_t b=bl_explicit?(int64_t)bl_time:prior_bl;
    int64_t e=el_explicit?(int64_t)el_time:prior_el;
    if(implicit&&(b!=prior_bl||e!=prior_el))return false;
    int64_t mapped;
    if(b<0||e<0||!ambient_fel_rescale_exact(b,bl_scale,el_scale,&mapped)||mapped!=e)
        return false;
    *bl_start=b;*el_start=e;return true;
}

/* Exact decode/presentation timing in the common track timescale. Do
 * not compare rounded microseconds or silently substitute another sample. */
static inline bool ambient_fel_fragment_timing(int64_t el_dts,int64_t el_cts,
    uint32_t el_duration,int64_t bl_dts,int64_t bl_pts,uint32_t bl_duration)
{
    if(el_dts<0||bl_dts<0||!el_duration||el_duration!=bl_duration||
        el_dts!=bl_dts||el_dts>INT64_MAX-el_duration||
        (el_cts>0&&el_dts>INT64_MAX-el_cts)||
        (el_cts<0&&el_dts<INT64_MIN-el_cts))return false;
    return el_dts+el_cts==bl_pts;
}
static inline bool ambient_fel_fragment_range(uint64_t start,uint64_t end,
    uint64_t offset,uint32_t size)
{
    return start<=end&&offset>=start&&offset<=end&&size>0&&
        size<=AMBIENT_FEL_MAX_AU&&size<=end-offset;
}

/* Strict bounded box and signed trun offsets, independent of stream IO. */
static inline bool ambient_fel_box_extent(uint64_t pos,uint64_t size,unsigned header,
    uint64_t *payload,uint64_t *end)
{
    if(!payload||!end||(header!=8&&header!=16)||size<header||
        size>UINT64_MAX-pos)return false;
    *payload=pos+header;*end=pos+size;return true;
}
static inline bool ambient_fel_trun_offset(uint64_t base,int32_t delta,uint64_t *out)
{
    if(!out)return false;
    if(delta<0){
        uint64_t n=(uint64_t)(-(int64_t)delta);
        if(n>base)return false;
        *out=base-n;
    }else{
        if((uint64_t)delta>UINT64_MAX-base)return false;
        *out=base+(uint64_t)delta;
    }
    return true;
}

/* Legacy dual-track carriage: BL sample followed by EL NALs wrapped in
 * type 63, with the original type-62 RPU outside the wrapper. No attempt
 * to infer PTS, track identity, or a missing RPU from NAL contents. */
static inline bool ambient_fel_dual_sample(const uint8_t *bl,size_t bn,unsigned bw,
    const uint8_t *el,size_t en,unsigned ew,const uint8_t *config,size_t cn,
    bool inject,uint8_t *out,size_t cap,size_t *written)
{
    if(!bl||!el||!written||!bn||!en||bn>AMBIENT_FEL_MAX_AU||en>AMBIENT_FEL_MAX_AU||
        (bw!=1&&bw!=2&&bw!=4)||(ew!=1&&ew!=2&&ew!=4))return false;
    bool bv=false,ev=false,rpu=false,br=false,er=false;
    unsigned bfirst=0,efirst=0;
    size_t total=bn;
    for(size_t p=0;p<bn;){
        if(bn-p<bw)return false;
        uint32_t len=0;
        for(unsigned j=0;j<bw;j++)len=(len<<8)|bl[p++];
        if(len<2||len>bn-p||(bl[p]&0x81)||!(bl[p+1]&7)||(bl[p+1]>>3))return false;
        unsigned type=(bl[p]>>1)&63;
        if(type>=62)return false;
        if(type<=31){
            if(len<3)return false;
            if(bl[p+2]&0x80)bfirst++;
            if(!bv && !(bl[p+2]&0x80))return false;
            bv=true;br|=type>=16&&type<=23;
        }
        p+=len;
    }
    if(!bv||bfirst!=1)return false;
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
        if(type<=31){
            if(el[p+2]&0x80)efirst++;
            if(!ev && !(el[p+2]&0x80))return false;
            ev=true;er|=type>=16&&type<=23;
        }
        uint64_t wrapped=(uint64_t)len+(type==62?0:2);
        if((bw==1&&wrapped>255)||(bw==2&&wrapped>65535)||
            wrapped+bw>AMBIENT_FEL_MAX_AU-total)return false;
        total+=(size_t)wrapped+bw;
        p+=len;
    }
    if(!ev||efirst!=1||!rpu||(br&&!er))return false;
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
