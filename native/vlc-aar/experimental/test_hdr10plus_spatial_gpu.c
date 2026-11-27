#include "hdr10plus_spatial.h"
#include <libplacebo/vulkan.h>
#include <libplacebo/shaders/sampling.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

// CPU reference of the experimental blend, using the exact generated LUTs.
// The result is checked against GPU output, including overlap and feathering.
static float reference_curve(const struct ambient_hdrplus_spatial *s,unsigned i,float v)
{
    float x=fminf(1.f,fmaxf(0.f,sqrtf(v)/s->domains[i]))*(AMBIENT_HDRPLUS_LUT_SIZE-1);
    unsigned n=(unsigned)x, next=n+1<AMBIENT_HDRPLUS_LUT_SIZE?n+1:n;
    return s->curves[i][n]+(s->curves[i][next]-s->curves[i][n])*(x-n);
}
static void check_blend(const struct ambient_hdrplus_spatial *s,const struct ambient_hdrplus *m,
    const float *pixels,unsigned width,unsigned height,float nits)
{
    struct ambient_hdrplus_mask masks[2];
    bool layering;
    assert(ambient_hdrplus_spatial_supported(m,width,height,masks,&layering));
    float v=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,nits);
    float g=reference_curve(s,0,v),l=reference_curve(s,1,v),r=reference_curve(s,2,v);
    unsigned overlaps=0, feathers=0;
    for(unsigned y=0;y<height;++y) for(unsigned x=0;x<width;++x) {
        float a=ambient_hdrplus_weight(&masks[0],x,y),b=ambient_hdrplus_weight(&masks[1],x,y);
        if(a>0.f && b>0.f) ++overlaps;
        if((a>0.f && a<1.f)||(b>0.f && b<1.f)) ++feathers;
        float expected=g;
        if(layering) expected=a>0.f?g*(1.f-a)+l*a:b>0.f?g*(1.f-b)+r*b:g;
        else if(a+b>0.f) expected=g+(a*a*(l-g)+b*b*(r-g))/(a+b);
        float pq=pl_hdr_rescale(PL_HDR_NORM,PL_HDR_PQ,expected);
        for(unsigned k=0;k<3;++k) {
            float actual=pixels[4*(y*width+x)+k];
            // FP16 PQ target and floating shader arithmetic are not bit exact.
            assert(isfinite(actual) && fabsf(actual-pq)<0.0015f);
        }
    }
    assert(overlaps && feathers);
}

int main(void)
{
    pl_log log=pl_log_create(PL_API_VER,&(struct pl_log_params){
        .log_cb=pl_log_color,.log_level=PL_LOG_WARN,
    });
    pl_vulkan vk=pl_vulkan_create(log,&(struct pl_vulkan_params){
        .allow_software=true,.queue_count=1,
    });
    assert(vk);
    pl_gpu gpu=vk->gpu;
    const int width=64,height=64;
    float *pixels=calloc(width*height*4,sizeof(*pixels));
    assert(pixels);
    float pq=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,600.f);
    for(int i=0;i<width*height;++i) {
        pixels[4*i]=pixels[4*i+1]=pixels[4*i+2]=pq;
        pixels[4*i+3]=1.f;
    }
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    pl_tex input=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
        .sampleable=true,.initial_data=pixels));
    assert(input);
    struct pl_frame img={.num_planes=1,.repr=pl_color_repr_rgb,.color=pl_color_space_hdr10};
    img.planes[0]=(struct pl_plane){.texture=input,.components=3,.component_mapping={0,1,2,-1}};
    img.color.hdr.max_luma=1000.f;
    img.crop=(struct pl_rect2df){0,0,width,height};
    struct pl_frame target={.color=pl_color_space_bt709};
    struct ambient_hdrplus m={.window_count=2,.target_nits=203.f,.version=1};
    for(unsigned i=0;i<2;++i){
        m.windows[i].tone_mapping=true;
        m.windows[i].maxscl[0]=0.1f;
        m.windows[i].average=0.025f;
        m.windows[i].knee_x=0.4f;
        m.windows[i].knee_y=i?0.7f:0.2f;
        m.windows[i].anchor_count=2;
        m.windows[i].anchors[0]=i?0.8f:0.2f;
        m.windows[i].anchors[1]=i?0.9f:0.5f;
    }
    m.windows[1].geometry=(struct ambient_hdrplus_geometry){
        .left=0,.top=0,.right=63,.bottom=63,.center_x=32,.center_y=32,
        .semimajor_inner=8,.semimajor_outer=16,.semiminor_outer=16,
    };
    struct ambient_hdrplus_spatial state={0};
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(img.planes[0].texture==state.output && img.color.hdr.max_luma<=203.1f);
    // Convert the selected floating output to a known downloadable RGBA32F
    // format using libplacebo dispatch; verify actual shader pixels, not logs.
    pl_tex readback=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
        .renderable=true,.host_readable=true));
    assert(readback);
    pl_shader sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    float center=pixels[4*(32*width+32)],corner=pixels[0];
    assert(isfinite(center)&&isfinite(corner)&&center>corner+0.001f);
    float center_nits=pl_hdr_rescale(PL_HDR_PQ,PL_HDR_NITS,center);
    float corner_nits=pl_hdr_rescale(PL_HDR_PQ,PL_HDR_NITS,corner);
    printf("Spatial HDR10+ rendered center %.2f nits, corner %.2f nits\n",center_nits,corner_nits);
    assert(center_nits<=203.5f&&corner_nits>=0.f);
    // Same scene reuses the LUT and output; unsupported local features
    // leave the original input untouched.
    pl_tex old_lut=state.lut,old_out=state.output;
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(state.lut==old_lut&&state.output==old_out);
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    // Geometry and histogram changes are shader inputs, not LUT inputs.
    m.windows[1].geometry.center_x=16;
    m.windows[1].histogram_count=1;
    m.windows[1].histogram[0].percentage=50;
    m.windows[1].histogram[0].percentile=0.03f;
    assert(ambient_hdrplus_curve_key_equal(&state.previous,&m));
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(state.previous.windows[1].geometry.center_x==32);
    assert(state.lut==old_lut&&state.output==old_out);
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    assert(pixels[4*(32*width+16)]>pixels[4*(32*width+32)]+0.001f);
    // Actual changes to tone-curve inputs must invalidate the cache.
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    m.windows[1].anchors[0]=0.7f;
    assert(!ambient_hdrplus_curve_key_equal(&state.previous,&m));
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(state.previous.windows[1].anchors[0]==0.7f);
    assert(state.previous.windows[1].geometry.center_x==16);
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    m.windows[1].saturation_mapping=true;
    m.windows[1].saturation_weight=8;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(img.planes[0].texture==state.output);
    // Neutral input is invariant under chroma compensation.
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    for(int i=0;i<width*height;++i) assert(fabsf(pixels[4*i]-pixels[4*i+1])<0.001f);
    // A non-unity target display LUT must change the curve cache and reduce its peak.
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    m.targeted_grid=(struct ambient_hdrplus_grid){.rows=2,.cols=2,.values={{8,8},{8,8}}};
    m.windows[0].fraction=m.windows[1].fraction=0.5f;
    assert(!ambient_hdrplus_curve_key_equal(&state.previous,&m));
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(state.curves[0][255]<=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,state.output_max*8.f/15.f)+0.001f);
    m.targeted_grid=(struct ambient_hdrplus_grid){0};
    // A colored input must react to a nonzero saturation weight.
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    for(int i=0;i<width*height;++i){pixels[4*i]=pq;pixels[4*i+1]=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,250.f);pixels[4*i+2]=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,120.f);pixels[4*i+3]=1.f;}
    pl_tex colored=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,.sampleable=true,.initial_data=pixels));
    assert(colored);
    float spread[2];
    for(unsigned i=0;i<2;++i){
        img.planes[0].texture=colored;img.color=pl_color_space_hdr10;
        m.windows[1].saturation_weight=i?32:0;
        assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
        sh=pl_dispatch_begin(state.dispatch);
        pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
        assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
        assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
        unsigned index=4*(32*width+16);
        spread[i]=pixels[index]-pixels[index+2];
    }
    assert(spread[1]>spread[0]+0.0001f);
    pl_gpu_finish(gpu);pl_tex_destroy(gpu,&colored);
    m.windows[1].saturation_mapping=false;
    // Three windows: both local regions overlap and have different curves.
    m.window_count=3;
    m.windows[2]=m.windows[0];
    m.windows[2].geometry=m.windows[1].geometry;
    m.windows[2].geometry.center_x=24;
    for(unsigned mode=0;mode<2;++mode) {
        m.windows[1].geometry.overlap=mode;
        m.windows[2].geometry.overlap=mode;
        img.planes[0].texture=input;img.color=pl_color_space_hdr10;
        assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
        sh=pl_dispatch_begin(state.dispatch);
        pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
        assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
        assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
        check_blend(&state,&m,pixels,width,height,600.f);
        // Reject accidental target-as-source feedback.
        assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
        assert(img.planes[0].texture==state.output);
    }
    // A crop changes local coordinates; preserve the ordinary renderer path.
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    img.crop.x0=1.f;
    assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(img.planes[0].texture==input && img.crop.x0==1.f);
    pl_gpu_finish(gpu);
    pl_tex_destroy(gpu,&readback);
    ambient_hdrplus_spatial_destroy(gpu,&state);
    pl_tex_destroy(gpu,&input);
    free(pixels);
    pl_vulkan_destroy(&vk);
    pl_log_destroy(&log);
}
