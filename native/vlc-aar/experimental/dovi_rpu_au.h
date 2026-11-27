#pragma once
/* HEVC access-unit RPU extraction for the native decoder route. No Dolby
 * metadata parsing is attempted here. Framing is explicit: never guess
 * length-prefix sizes or scan a length-prefixed payload for start codes. */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

enum ambient_rpu_status { AMBIENT_RPU_NONE, AMBIENT_RPU_FOUND, AMBIENT_RPU_INVALID };
struct ambient_rpu_au {
    size_t nal_start, nal_end, payload_start, payload_size;
    bool has_vcl, has_nonbase_layer;
};

static inline size_t ambient_rpu_start_code(const uint8_t *p, size_t n, size_t from, size_t *prefix)
{
    for (size_t i = from; i < n && n-i >= 3; ++i) {
        if (p[i] || p[i+1]) continue;
        if (n-i >= 4 && p[i+2] == 0 && p[i+3] == 1) { *prefix = 4; return i; }
        if (p[i+2] == 1) { *prefix = 3; return i; }
    }
    *prefix = 0;
    return n;
}
static inline enum ambient_rpu_status
ambient_rpu_find(const uint8_t *p, size_t size, unsigned length_size, struct ambient_rpu_au *out)
{
    if (!p || !out || !size || length_size > 4 || length_size == 3)
        return AMBIENT_RPU_INVALID;
    struct ambient_rpu_au result = {0};
    bool found = false;
    size_t cursor = 0;
    while (cursor < size) {
        size_t start, data, end;
        if (length_size) {
            if (size - cursor < length_size) return AMBIENT_RPU_INVALID;
            uint32_t length = 0;
            start = cursor;
            for (unsigned i = 0; i < length_size; ++i)
                length = (length << 8) | p[cursor++];
            data = cursor;
            if (length < 2 || length > size-cursor) return AMBIENT_RPU_INVALID;
            end = data + length;
        } else {
            size_t prefix;
            start = ambient_rpu_start_code(p, size, cursor, &prefix);
            if (!prefix) return AMBIENT_RPU_INVALID;
            for (size_t i = cursor; i < start; ++i)
                if (p[i]) return AMBIENT_RPU_INVALID;
            data = start + prefix;
            size_t next_prefix;
            end = ambient_rpu_start_code(p, size, data, &next_prefix);
            if (end-data < 2) return AMBIENT_RPU_INVALID;
        }
        const unsigned type = (p[data] >> 1) & 63;
        if ((p[data] & 0x80) || !(p[data+1] & 7)) return AMBIENT_RPU_INVALID;
        const unsigned layer = ((p[data] & 1u) << 5) | (p[data+1] >> 3);
        if (layer || type == 63) result.has_nonbase_layer = true;
        if (type <= 31 && !layer) result.has_vcl = true;
        // HEVC type 62, layer ID zero, temporal ID zero, Dolby RPU prefix.
        // Match FFmpeg's HEVC RPU framing and validate its NAL prefix.
        if (type == 62 && (p[data] & 1) == 0 && p[data+1] == 1 &&
            end-data > 2 && p[data+2] == 25) {
            if (found || end-data < 7 || end-data > 65536) return AMBIENT_RPU_INVALID;
            found = true;
            result.nal_start = start;
            result.nal_end = end;
            result.payload_start = data+2;
            result.payload_size = end-data-2;
        }
        cursor = end;
    }
    *out = result;
    return found ? AMBIENT_RPU_FOUND : AMBIENT_RPU_NONE;
}

// Remove HEVC emulation-prevention bytes and validate the escaped sequence.
// The caller supplies size + FFmpeg's required input padding if used there.
static inline bool ambient_rpu_unescape(const uint8_t *src, size_t size,
                                        uint8_t *dst, size_t capacity, size_t *written)
{
    if (!src || !dst || !written || size < 5 || size > 65536 || src[0] != 25)
        return false;
    unsigned zeroes = 0;
    size_t n = 0;
    for (size_t i = 0; i < size; ++i) {
        uint8_t b = src[i];
        if (zeroes >= 2 && b == 3) {
            if (i+1 == size || src[i+1] > 3) return false;
            zeroes = 0;
            continue;
        }
        if (n == capacity) return false;
        dst[n++] = b;
        zeroes = b ? 0 : zeroes+1;
    }
    *written = n;
    return n >= 5;
}

// Bounded association of independently owned decoded metadata with exact
// presentation timestamps. No FIFO matching: codec output can reorder B frames.
// Caller serializes access under decoder lock; duplicates/full queue fail closed.
#define AMBIENT_RPU_PTS_CAPACITY 64
struct ambient_rpu_pts_entry { int64_t pts; void *metadata; };
struct ambient_rpu_pts_queue { struct ambient_rpu_pts_entry entries[AMBIENT_RPU_PTS_CAPACITY]; };
static inline bool ambient_rpu_pts_insert(struct ambient_rpu_pts_queue *q, int64_t pts, void *metadata)
{
    if (!q || !metadata || pts <= 0) return false;
    size_t free_slot = AMBIENT_RPU_PTS_CAPACITY;
    for (size_t i = 0; i < AMBIENT_RPU_PTS_CAPACITY; ++i) {
        if (q->entries[i].metadata && q->entries[i].pts == pts) return false;
        if (!q->entries[i].metadata && free_slot == AMBIENT_RPU_PTS_CAPACITY) free_slot = i;
    }
    if (free_slot == AMBIENT_RPU_PTS_CAPACITY) return false;
    q->entries[free_slot] = (struct ambient_rpu_pts_entry){pts, metadata};
    return true;
}
static inline void *ambient_rpu_pts_take(struct ambient_rpu_pts_queue *q, int64_t pts)
{
    if (!q || pts <= 0) return NULL;
    for (size_t i = 0; i < AMBIENT_RPU_PTS_CAPACITY; ++i) {
        if (q->entries[i].metadata && q->entries[i].pts == pts) {
            void *m = q->entries[i].metadata;
            q->entries[i] = (struct ambient_rpu_pts_entry){0};
            return m;
        }
    }
    return NULL;
}
static inline void ambient_rpu_pts_clear(struct ambient_rpu_pts_queue *q, void (*release)(void *))
{
    if (!q || !release) return;
    for (size_t i = 0; i < AMBIENT_RPU_PTS_CAPACITY; ++i) {
        if (q->entries[i].metadata) release(q->entries[i].metadata);
        q->entries[i] = (struct ambient_rpu_pts_entry){0};
    }
}


/* Validate explicit framing and both outer/inner HEVC headers. Never
 * scan an hvcC sample for Annex B start codes. */
#define AMBIENT_FEL_AU_LIMIT (32u*1024u*1024u)
static inline bool ambient_fel_nal_bounds(const uint8_t *p,size_t n,size_t cursor,
    unsigned length_size,size_t *data,size_t *end)
{
    if(cursor>=n)return false;
    if(length_size){
        if((length_size!=1&&length_size!=2&&length_size!=4)||n-cursor<length_size)
            return false;
        uint32_t len=0;
        for(unsigned j=0;j<length_size;j++)len=(len<<8)|p[cursor+j];
        *data=cursor+length_size;
        if(len<2||len>n-*data)return false;
        *end=*data+len;
    }else{
        size_t prefix,next;
        size_t start=ambient_rpu_start_code(p,n,cursor,&prefix);
        if(!prefix)return false;
        for(size_t j=cursor;j<start;j++)if(p[j])return false;
        *data=start+prefix;
        *end=ambient_rpu_start_code(p,n,*data,&next);
    }
    if(*end-*data<2||(p[*data]&0x81)||!(p[*data+1]&7)||(p[*data+1]>>3))
        return false;
    if(((p[*data]>>1)&63)==63 &&
        (*end-*data<5||(p[*data+2]&0x81)||!(p[*data+3]&7)||
         (p[*data+3]>>3)||((p[*data+2]>>1)&63)>=62))
        return false;
    return true;
}

/* Convert explicitly length-prefixed combined input to Annex B for the
 * pinned FFmpeg dovi_split BSF. Two-pass sizing validates the whole
 * access unit before writing. Original BL framing is kept separately. */
static inline bool ambient_fel_to_annexb(const uint8_t *p,size_t n,unsigned length_size,
    uint8_t *dst,size_t cap,size_t *written)
{
    if(!p||!n||n>AMBIENT_FEL_AU_LIMIT||!written||
        (length_size!=1&&length_size!=2&&length_size!=4))return false;
    size_t total=0;
    for(size_t cur=0;cur<n;){
        size_t data,end;
        if(!ambient_fel_nal_bounds(p,n,cur,length_size,&data,&end)||
            end-data>AMBIENT_FEL_AU_LIMIT-4||
            end-data+4>AMBIENT_FEL_AU_LIMIT-total)return false;
        total+=end-data+4;cur=end;
    }
    if(dst){
        if(cap<total)return false;
        size_t out=0;
        for(size_t cur=0;cur<n;){
            size_t data,end;
            if(!ambient_fel_nal_bounds(p,n,cur,length_size,&data,&end))return false;
            dst[out]=dst[out+1]=dst[out+2]=0;dst[out+3]=1;
            memcpy(dst+out+4,p+data,end-data);
            out+=end-data+4;cur=end;
        }
    }
    *written=total;return true;
}

/* Remove wrapped EL NALs before handing the BL to the vendor decoder.
 * Validate the entire input first; never partially mutate malformed data.
 * Both Annex B and explicit 1/2/4-byte length framing are supported. */
static inline bool ambient_fel_strip_el_framed(uint8_t *p,size_t *size,unsigned length_size)
{
    if(!p||!size||!*size||*size>AMBIENT_FEL_AU_LIMIT)return false;
    size_t n=*size;
    for(unsigned pass=0;pass<2;pass++){
        size_t cursor=0,out=0;
        while(cursor<n){
            size_t data,end;
            if(!ambient_fel_nal_bounds(p,n,cursor,length_size,&data,&end))return false;
            if(((p[data]>>1)&63)!=63){
                if(pass)memmove(p+out,p+cursor,end-cursor);
                out+=end-cursor;
            }
            cursor=end;
        }
        if(!out)return false;
        if(pass)*size=out;
    }
    return true;
}
static inline bool ambient_fel_strip_el(uint8_t *p,size_t *size)
{
    return ambient_fel_strip_el_framed(p,size,0);
}
