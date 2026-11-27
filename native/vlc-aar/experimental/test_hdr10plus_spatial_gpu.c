#include "hdr10plus_spatial.h"
#include <libplacebo/vulkan.h>
#include <libplacebo/shaders/sampling.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

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
    m.windows[1].saturation_mapping=true;
    assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(img.planes[0].texture==input);
    pl_gpu_finish(gpu);
    pl_tex_destroy(gpu,&readback);
    ambient_hdrplus_spatial_destroy(gpu,&state);
    pl_tex_destroy(gpu,&input);
    free(pixels);
    pl_vulkan_destroy(&vk);
    pl_log_destroy(&log);
}
