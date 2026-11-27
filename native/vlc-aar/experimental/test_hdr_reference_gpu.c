#include "hdr10plus_spatial.h"
#include "dovi_fel_render.h"
#include "hdr_reference_vectors.h"
#include <libplacebo/vulkan.h>
#include <libplacebo/shaders/sampling.h>
#include <assert.h>
#include <stdio.h>
#include <math.h>

enum { W=16,H=16,N=W*H };
static void metrics(const char *label,const float *pixels,const float expected[N][6],
    unsigned offset,double tolerance)
{
    double max=0,sum=0;
    for(unsigned i=0;i<N;i++)for(unsigned k=0;k<3;k++){
        double d=fabs(pixels[i*4+k]-expected[i][offset+k]);
        assert(isfinite(d));
        max=fmax(max,d);sum+=d*d;
    }
    printf("%s: %u channels, max PQ error %.8f, RMS %.8f, limit %.8f\n",
        label,3u*N,max,sqrt(sum/(3*N)),tolerance);
    assert(max<tolerance);
}
int main(void)
{
    pl_log log=pl_log_create(PL_API_VER,&(struct pl_log_params){.log_cb=pl_log_color,.log_level=PL_LOG_WARN});
    pl_vulkan vk=pl_vulkan_create(log,&(struct pl_vulkan_params){.allow_software=true,.queue_count=1});
    assert(vk);pl_gpu gpu=vk->gpu;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    float base[N*4],el[N*4],pixels[N*4];
    for(unsigned i=0;i<N;i++){
        for(unsigned k=0;k<3;k++)base[i*4+k]=hdr_vectors[i][k];
        base[i*4+3]=1;
    }
    pl_tex hdr=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=base));
    pl_tex out=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.renderable=true,.host_readable=true));
    assert(hdr&&out);
    struct pl_frame img={.num_planes=1,.repr=pl_color_repr_rgb,.color=pl_color_space_hdr10,.crop={0,0,W,H}};
    img.planes[0]=(struct pl_plane){.texture=hdr,.components=3,.component_mapping={0,1,2,-1}};
    img.color.hdr.max_luma=1000;
    struct pl_frame target={.color=pl_color_space_hdr10};
    target.color.hdr.min_luma=.005f;target.color.hdr.max_luma=600;
    struct ambient_hdrplus meta={.version=0,.window_count=1,.target_nits=600};
    meta.windows[0]=(struct ambient_hdrplus_window){.tone_mapping=true,.knee_x=.25f,.knee_y=.2f,
        .maxscl={.1f,.1f,.1f},.anchor_count=3,.anchors={.1f,.7f,.9f}};
    struct ambient_hdrplus_spatial state={0};
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&meta));
    assert(state.output_max==600);
    pl_shader sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=out)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=out,.ptr=pixels)));
    metrics("HDR10+ authored nonneutral ramp",pixels,hdr_vectors,3,.0025);
    pl_gpu_finish(gpu);ambient_hdrplus_spatial_destroy(gpu,&state);pl_tex_destroy(gpu,&hdr);

    struct pl_dovi_metadata reshape={0};
    const struct ambient_fel_nlq nlq={.el_bits=10,.coef_log2_denom=16,
        .offset={512,512,512},.slope={256,256,256},.threshold={128,128,128},.maximum={4096,4096,4096}};
    for(unsigned k=0;k<3;k++){
        reshape.comp[k].num_pivots=2;reshape.comp[k].pivots[1]=1023.f/1024.f;
        reshape.comp[k].poly_coeffs[0][0]=6554.f/65536.f;
        reshape.comp[k].poly_coeffs[0][1]=58982.f/65536.f;
        reshape.nonlinear.m[k][k]=reshape.linear.m[k][k]=1;
    }
    reshape.nonlinear.m[0][1]=.05f;reshape.nonlinear_offset[0]=.02f;reshape.linear.m[1][2]=.04f;
    for(unsigned i=0;i<N;i++){
        base[4*i]=fel_inputs[i][2];base[4*i+1]=fel_inputs[i][0];base[4*i+2]=fel_inputs[i][1];
        for(unsigned k=0;k<3;k++)el[4*i+k]=fel_inputs[i][3+k];
        base[4*i+3]=el[4*i+3]=1;
    }
    pl_tex bl=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=base));
    pl_tex residual=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=el));
    pl_dispatch dp=pl_dispatch_create(log,gpu);assert(bl&&residual&&dp);
    assert(ambient_fel_compose_domain(gpu,dp,bl,residual,out,&reshape,&nlq,true,true,NULL,true));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=out,.ptr=pixels)));
    metrics("Dolby FEL experimental reconstructed PQ RGB",pixels,fel_expected,0,.001);
    pl_gpu_finish(gpu);pl_dispatch_destroy(&dp);
    pl_tex_destroy(gpu,&residual);pl_tex_destroy(gpu,&bl);pl_tex_destroy(gpu,&out);
    pl_vulkan_destroy(&vk);pl_log_destroy(&log);
}
