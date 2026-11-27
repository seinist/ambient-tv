#include "dovi_rpu_au.h"
#include <assert.h>
#include <stdlib.h>
static unsigned freed;
static void release(void *m) { free(m); ++freed; }
int main(void) {
    // Ordinary slice, RPU, suffix SEI; both 3- and 4-byte start codes.
    const uint8_t annexb[]={0,0,1,0x26,1,0x80,0,0,0,1,0x7c,1,25,8,0,0,3,1,0x80,0,0,1,0x50,1,0x80};
    struct ambient_rpu_au au;
    assert(ambient_rpu_find(annexb,sizeof annexb,0,&au)==AMBIENT_RPU_FOUND);
    assert(au.has_vcl && au.nal_start==6 && au.nal_end==19 && au.payload_start==12);
    uint8_t rbsp[64]; size_t n;
    assert(ambient_rpu_unescape(annexb+au.payload_start,au.payload_size,rbsp,sizeof rbsp,&n));
    assert(n==6 && rbsp[4]==1);
    const uint8_t prefixed[]={0,0,0,3,0x26,1,0x80,0,0,0,8,0x7c,1,25,8,0,0,3,1};
    assert(ambient_rpu_find(prefixed,sizeof prefixed,4,&au)==AMBIENT_RPU_FOUND);
    assert(au.nal_start==7 && au.nal_end==sizeof prefixed);
    assert(ambient_rpu_find(prefixed,sizeof prefixed-1,4,&au)==AMBIENT_RPU_INVALID);
    assert(ambient_rpu_find(annexb,6,0,&au)==AMBIENT_RPU_NONE);
    assert(ambient_rpu_find(annexb,sizeof annexb,3,&au)==AMBIENT_RPU_INVALID);
    const uint8_t duplicate[]={0,0,1,0x7c,1,25,8,1,2,3,0,0,1,0x7c,1,25,8,1,2,3};
    assert(ambient_rpu_find(duplicate,sizeof duplicate,0,&au)==AMBIENT_RPU_INVALID);
    const uint8_t invalid_escape[]={25,8,0,0,3,4};
    assert(!ambient_rpu_unescape(invalid_escape,sizeof invalid_escape,rbsp,sizeof rbsp,&n));
    struct ambient_rpu_pts_queue q={0};
    int a=1,b=2,c=3;
    assert(ambient_rpu_pts_insert(&q,30,&a));
    assert(ambient_rpu_pts_insert(&q,10,&b));
    assert(ambient_rpu_pts_insert(&q,20,&c));
    assert(!ambient_rpu_pts_insert(&q,20,&a));
    assert(!ambient_rpu_pts_take(&q,15));
    assert(ambient_rpu_pts_take(&q,10)==&b);
    assert(ambient_rpu_pts_take(&q,30)==&a);
    assert(ambient_rpu_pts_take(&q,20)==&c);
    assert(!ambient_rpu_pts_take(&q,20));
    for(unsigned i=1;i<=AMBIENT_RPU_PTS_CAPACITY;++i) {
        void *m=malloc(1); assert(m); assert(ambient_rpu_pts_insert(&q,i,m));
    }
    assert(!ambient_rpu_pts_insert(&q,100,&a));
    ambient_rpu_pts_clear(&q,release);
    assert(freed==AMBIENT_RPU_PTS_CAPACITY);
    assert(!ambient_rpu_pts_take(&q,1));
}
