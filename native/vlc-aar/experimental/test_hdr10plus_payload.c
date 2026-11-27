#include "hdr10plus_payload.h"
#include <assert.h>
#include <math.h>

struct writer { uint8_t data[128]; size_t pos; };
static void put(struct writer *w, unsigned n, uint32_t v) {
    for (unsigned i = n; i > 0; --i, ++w->pos)
        w->data[w->pos / 8] |= ((v >> (i - 1)) & 1u) << (7 - w->pos % 8);
}
static struct writer fixture(unsigned windows, unsigned grid, unsigned saturation) {
    struct writer w = {{0xb5,0,0x3c,0,1,4},48};
    put(&w,8,1); put(&w,2,windows); put(&w,27,1000); put(&w,1,grid);
    put(&w,17,10000); put(&w,17,9000); put(&w,17,8000); put(&w,17,2500);
    put(&w,4,2); put(&w,7,50); put(&w,17,2000); put(&w,7,99); put(&w,17,9000);
    put(&w,10,100); put(&w,1,0); put(&w,1,1);
    put(&w,12,2048); put(&w,12,1024); put(&w,4,2); put(&w,10,300); put(&w,10,700);
    put(&w,1,saturation);
    return w;
}
int main(void) {
    struct writer w = fixture(1,0,0);
    const size_t size = (w.pos + 7) / 8;
    struct ambient_hdrplus m;
    assert(ambient_hdrplus_parse(w.data,size,&m));
    assert(m.version == 1 && m.target_nits == 1000 && m.anchor_count == 2);
    assert(fabsf(m.maxscl[0] - 0.1f) < 0.00001f);
    assert(m.histogram_count == 2 && m.histogram[1].percentage == 99);
    assert(m.tone_mapping && fabsf(m.average - 0.025f) < 0.00001f);
    for(size_t n=0;n<size;++n) assert(!ambient_hdrplus_parse(w.data,n,&m));
    assert(!ambient_hdrplus_parse(w.data,size+1,&m));
    w.data[0] ^= 1; assert(!ambient_hdrplus_parse(w.data,size,&m));
    w = fixture(2,0,0); assert(!ambient_hdrplus_parse(w.data,size,&m));
    w = fixture(1,1,0); assert(!ambient_hdrplus_parse(w.data,size,&m));
    w = fixture(1,0,1); assert(!ambient_hdrplus_parse(w.data,size,&m));
}
