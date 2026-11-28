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
/* AFM4 adds an event-instance ID, stable across retries but never shared by
 * two boxes merely because they have equal payload and timestamps. */
enum { AMBIENT_METADATA_V4_HEADER_SIZE = 56 };
static inline void ambient_metadata_header_v4(uint8_t p[AMBIENT_METADATA_V4_HEADER_SIZE],
    uint32_t kind,uint64_t pts,uint64_t duration,uint32_t size,uint32_t epoch,
    int64_t target,uint64_t request,uint64_t packet)
{
    ambient_metadata_header(p,kind,pts,duration,size,epoch,target,request);
    p[3]='4';
    ambient_metadata_be(p+48,packet,8);
}
enum ambient_metadata_ack { AMBIENT_ACK_RETRY, AMBIENT_ACK_ACCEPTED, AMBIENT_ACK_REJECTED };
static inline enum ambient_metadata_ack ambient_metadata_ack_result(const uint8_t p[8],uint32_t epoch)
{
    uint8_t expected[8];
    memcpy(expected,"AFA1",4);ambient_metadata_be(expected+4,epoch,4);
    if (!memcmp(p,expected,8)) return AMBIENT_ACK_ACCEPTED;
    expected[3]='2';
    if (!memcmp(p,expected,8)) return AMBIENT_ACK_REJECTED;
    return AMBIENT_ACK_RETRY;
}
static inline bool ambient_metadata_ack_valid(const uint8_t p[8],uint32_t epoch)
{
    return ambient_metadata_ack_result(p,epoch)==AMBIENT_ACK_ACCEPTED;
}
