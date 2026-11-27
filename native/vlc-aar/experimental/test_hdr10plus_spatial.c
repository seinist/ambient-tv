#include "hdr10plus_spatial_geometry.h"
#include <assert.h>
int main(void) {
    struct ambient_hdrplus m={.window_count=3,.target_nits=1000};
    for (unsigned i=0;i<3;++i) {
        m.windows[i].maxscl[0]=0.1f;
        m.windows[i].tone_mapping=true;
        m.windows[i].knee_x=0.5f;m.windows[i].knee_y=0.25f;
        m.windows[i].anchor_count=2;m.windows[i].anchors[0]=0.25f;m.windows[i].anchors[1]=0.75f;
        m.windows[i].geometry=(struct ambient_hdrplus_geometry){
            .left=0,.top=0,.right=199,.bottom=199,.center_x=100,.center_y=100,
            .semimajor_inner=20,.semimajor_outer=40,.semiminor_outer=20,
        };
    }
    struct ambient_hdrplus_mask masks[2];bool layering;
    assert(ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering)&&!layering);
    assert(ambient_hdrplus_weight(&masks[0],100,100)==1.f);
    assert(ambient_hdrplus_weight(&masks[0],120,100)==1.f);
    assert(fabsf(ambient_hdrplus_weight(&masks[0],130,100)-0.5f)<1e-5f);
    assert(ambient_hdrplus_weight(&masks[0],140,100)==0.f);
    assert(ambient_hdrplus_weight(&masks[0],100,130)==0.f);
    m.windows[1].geometry.angle=90;
    assert(ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    assert(fabsf(ambient_hdrplus_weight(&masks[0],100,130)-0.5f)<1e-5f);
    m.windows[1].geometry.overlap=1;
    assert(!ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    m.windows[2].geometry.overlap=1;
    assert(ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering)&&layering);
    m.targeted_grid.rows=2;
    assert(!ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    m.targeted_grid.rows=0;m.windows[0].saturation_mapping=true;
    assert(!ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    m.windows[0].saturation_mapping=false;m.windows[2].geometry.right=200;
    assert(!ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    m.window_count=2;
    assert(ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    assert(ambient_hdrplus_weight(&masks[1],100,100)==0.f);
}
