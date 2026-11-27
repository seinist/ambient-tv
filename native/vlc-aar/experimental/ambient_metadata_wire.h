#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
enum { AMBIENT_METADATA_HEADER_SIZE = 48 };
static inline void ambient_metadata_be(uint8_t *p,uint64_t v,unsigned n)
{
    while(n){p[--n]=(uint8_t)v;v>>=8;}
}
static inline void ambient_metadata_header(uint8_t p[AMBIENT_METADATA_HEADER_SIZE],
    uint32_t kind,uint64_t pts,uint64_t duration,uint32_t size,uint32_t epoch,
    int64_t target,uint64_t request)
{
    memcpy(p,"AFM3",4);
    ambient_metadata_be(p+4,kind,4);ambient_metadata_be(p+8,pts,8);
    ambient_metadata_be(p+16,duration,8);ambient_metadata_be(p+24,size,4);
    ambient_metadata_be(p+28,epoch,4);ambient_metadata_be(p+32,(uint64_t)target,8);
    ambient_metadata_be(p+40,request,8);
}
static inline bool ambient_metadata_ack_valid(const uint8_t p[8],uint32_t epoch)
{
    uint8_t expected[8];
    memcpy(expected,"AFA1",4);ambient_metadata_be(expected+4,epoch,4);
    return memcmp(p,expected,8)==0;
}
