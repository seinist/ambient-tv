#include "hdr10plus_payload.h"
#include <assert.h>
#include <math.h>

struct writer { uint8_t data[1024]; size_t pos; };
static void put(struct writer *w, unsigned n, uint32_t v) {
    assert(w->pos + n <= sizeof(w->data) * 8);
    for (unsigned i = n; i > 0; --i, ++w->pos)
        w->data[w->pos / 8] |= ((v >> (i - 1)) & 1u) << (7 - w->pos % 8);
}
static void geometry(struct writer *w) {
    put(w,16,10); put(w,16,20); put(w,16,100); put(w,16,200);
    put(w,16,50); put(w,16,100); put(w,8,90);
    put(w,16,20); put(w,16,40); put(w,16,30); put(w,1,0);
}
static void grid(struct writer *w, unsigned n) {
    put(w,5,n); put(w,5,n);
    for (unsigned i=0;i<n*n;++i) put(w,4,i%16);
}
static struct writer fixture(unsigned windows, unsigned grid_size, unsigned saturation) {
    struct writer w = {{0xb5,0,0x3c,0,1,4},48};
    put(&w,8,1); put(&w,2,windows);
    for (unsigned i=1;i<windows;++i) geometry(&w);
    put(&w,27,1000); put(&w,1,grid_size!=0);
    if(grid_size) grid(&w,grid_size);
    for (unsigned i=0;i<windows;++i) {
        put(&w,17,10000+i); put(&w,17,9000); put(&w,17,8000); put(&w,17,2500+i);
        put(&w,4,2); put(&w,7,50); put(&w,17,2000); put(&w,7,99); put(&w,17,9000);
        put(&w,10,100);
    }
    put(&w,1,grid_size!=0);
    if(grid_size) grid(&w,grid_size);
    for (unsigned i=0;i<windows;++i) {
        put(&w,1,1);
        put(&w,12,2048+i); put(&w,12,1024); put(&w,4,2); put(&w,10,300); put(&w,10,700);
        put(&w,1,saturation);
        if(saturation) put(&w,6,8);
    }
    return w;
}
int main(void) {
    struct ambient_hdrplus m;
    for(unsigned windows=1;windows<=3;++windows) {
        for(unsigned grids=0;grids<=2;++grids) {
            for(unsigned saturation=0;saturation<=1;++saturation) {
                struct writer w = fixture(windows,grids==2?25:grids==1?2:0,saturation);
                size_t size = (w.pos + 7) / 8;
                assert(ambient_hdrplus_parse(w.data,size,&m));
                const struct ambient_hdrplus_window *global = &m.windows[0];
                assert(m.version == 1 && m.target_nits == 1000 && global->anchor_count == 2);
                assert(m.window_count == windows);
                assert(m.global_projection == (windows>1 || grids || saturation));
                assert(fabsf(global->maxscl[0] - 0.1f) < 0.00001f);
                assert(global->histogram_count == 2 && global->histogram[1].percentage == 99);
                assert(global->tone_mapping && fabsf(global->average - 0.025f) < 0.00001f);
                for (unsigned i=0;i<windows;++i) {
                    const struct ambient_hdrplus_window *local=&m.windows[i];
                    assert(fabsf(local->maxscl[0]-(10000+i)/100000.0f)<0.000001f);
                    assert(fabsf(local->knee_x-(2048+i)/4095.0f)<0.000001f);
                    assert(local->saturation_mapping==!!saturation);
                    assert(local->saturation_weight==(saturation?8:0));
                    if(i) {
                        assert(local->geometry.left==10 && local->geometry.center_x==50);
                        assert(local->geometry.semimajor_outer==40 && local->geometry.angle==90);
                    }
                }
                unsigned dim=grids==2?25:grids==1?2:0;
                assert(m.targeted_grid.rows==dim && m.mastering_grid.cols==dim);
                if(dim) {
                    assert(m.targeted_grid.values[dim-1][dim-1]==(dim*dim-1)%16);
                    assert(m.mastering_grid.values[dim-1][dim-1]==(dim*dim-1)%16);
                }
                w.data[6]=0;
                assert(ambient_hdrplus_parse(w.data,size,&m) && m.version==0);
                w.data[6]=2;
                assert(!ambient_hdrplus_parse(w.data,size,&m));
                w.data[6]=1;
                // Reject every byte-truncated fixture, including the last local window.
                for(size_t n=0;n<size;++n) assert(!ambient_hdrplus_parse(w.data,n,&m));
                assert(!ambient_hdrplus_parse(w.data,size+1,&m));
                w.data[0] ^= 1; assert(!ambient_hdrplus_parse(w.data,size,&m));
            }
        }
    }
    struct writer w = fixture(1,1,0);
    assert(!ambient_hdrplus_parse(w.data,(w.pos+7)/8,&m)); // invalid grid dimensions
    w = fixture(0,0,0);
    assert(!ambient_hdrplus_parse(w.data,(w.pos+7)/8,&m));
}
