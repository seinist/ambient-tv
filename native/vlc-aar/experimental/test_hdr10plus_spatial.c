#include "hdr10plus_spatial_geometry.h"
#include <assert.h>
int main(void) {
    // Independent analytic vectors for equations 3-5 of ST2094-40.
    struct ambient_hdrplus_window curve={.tone_mapping=true,.knee_x=0.f,
        .knee_y=0.25f,.anchor_count=1,.anchors={0.25f}};
    // B2(0.5)=2*0.5*0.5*0.25+0.25=0.375.
    assert(fabsf(ambient_hdrplus_bezier(&curve,0.f)-0.25f)<1e-6f);
    assert(fabsf(ambient_hdrplus_bezier(&curve,0.5f)-0.53125f)<1e-6f);
    assert(ambient_hdrplus_bezier(&curve,1.f)==1.f);
    curve.knee_x=1.f;
    assert(fabsf(ambient_hdrplus_bezier(&curve,0.5f)-0.125f)<1e-6f);
    assert(ambient_hdrplus_bezier(&curve,1.f)==0.25f);
    curve.knee_x=0.5f;curve.knee_y=0.25f;
    assert(fabsf(ambient_hdrplus_bezier(&curve,0.25f)-0.125f)<1e-6f);
    assert(fabsf(ambient_hdrplus_bezier(&curve,0.75f)-0.53125f)<1e-6f);
    curve.anchor_count=0;curve.knee_x=curve.knee_y=0.f;
    assert(fabsf(ambient_hdrplus_bezier(&curve,0.75f)-0.75f)<1e-6f);
    curve.anchor_count=15;
    for(unsigned i=0;i<15;i++)curve.anchors[i]=(i+1)/16.f;
    assert(fabsf(ambient_hdrplus_bezier(&curve,0.625f)-0.625f)<1e-6f);
    curve.histogram_count=9;
    curve.histogram[1].percentage=5;curve.histogram[2].percentage=10;
    curve.histogram[2].percentile=0.00255f;
    curve.histogram[8].percentile=0.001f;
    assert(ambient_hdrplus_source_peak(&curve,1)==0.001f);
    assert(ambient_hdrplus_source_peak(&curve,0)==0.00255f);
    curve.maxscl[0]=0.1f;
    assert(ambient_hdrplus_source_peak(&curve,1)==0.1f);
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
    m.targeted_grid=(struct ambient_hdrplus_grid){.rows=2,.cols=2,.values={{0,3},{12,15}}};
    m.windows[0].fraction=0.75f;m.windows[0].average=0.25f;
    float factor;
    assert(ambient_hdrplus_grid_factor(&m.targeted_grid,&m.windows[0],&factor));
    assert(fabsf(factor-0.65f)<1e-6f);
    m.windows[0].fraction=0.f;
    assert(ambient_hdrplus_grid_factor(&m.targeted_grid,&m.windows[0],&factor)&&factor==1.f);
    m.targeted_grid=(struct ambient_hdrplus_grid){0};m.windows[0].saturation_mapping=true;
    m.windows[0].saturation_weight=63;
    assert(ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    m.windows[0].saturation_mapping=false;m.windows[2].geometry.right=200;
    assert(!ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    m.window_count=2;
    assert(ambient_hdrplus_spatial_supported(&m,200,200,masks,&layering));
    assert(ambient_hdrplus_weight(&masks[1],100,100)==0.f);
}
