#include "dovi_fel_gpu.h"
#include <libplacebo/vulkan.h>
#include <assert.h>
#include <stdio.h>
int main(void)
{
    struct ambient_fel_nlq nlq={.el_bits=10,.coef_log2_denom=16,
        .offset={512,512,512},.slope={256,256,256},
        .threshold={128,128,128},.maximum={4096,4096,4096}};
    assert(ambient_fel_nlq_valid(&nlq));
    assert(ambient_fel_residual(&nlq,0,512.0/1023)==0.0);
    assert(ambient_fel_residual(&nlq,0,513.0/1023)==1.0/256);
    assert(ambient_fel_residual(&nlq,0,511.0/1023)==-1.0/256);
    assert(ambient_fel_residual(&nlq,0,1.0)==1.0/16);
    pl_log log=pl_log_create(PL_API_VER,&(struct pl_log_params){
        .log_cb=pl_log_color,.log_level=PL_LOG_WARN});
    pl_vulkan vk=pl_vulkan_create(log,&(struct pl_vulkan_params){
        .allow_software=true,.queue_count=1,
        .instance_params=pl_vk_inst_params(.debug=true,
            .extensions=(const char *const[]){"VK_EXT_debug_utils"},.num_extensions=1)});
    assert(vk);
    pl_gpu gpu=vk->gpu;
    pl_dispatch dp=pl_dispatch_create(log,gpu);
    assert(dp);
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    float base[16]={0.5,0.5,0.5,1, 0.99,0.01,0.5,1,
                    0.2,0.4,0.6,1, 0.5,0.5,0.5,1};
    float residual[16]={512.f/1023,513.f/1023,511.f/1023,1, 1,0,1,1,
                       0,1,512.f/1023,1, 513.f/1023,511.f/1023,512.f/1023,1};
    float pixels[16];
    pl_tex bl=pl_tex_create(gpu,pl_tex_params(.w=2,.h=2,.format=fmt,
        .sampleable=true,.initial_data=base));
    pl_tex el=pl_tex_create(gpu,pl_tex_params(.w=2,.h=2,.format=fmt,
        .sampleable=true,.initial_data=residual));
    pl_tex out=pl_tex_create(gpu,pl_tex_params(.w=2,.h=2,.format=fmt,
        .renderable=true,.host_readable=true));
    assert(bl&&el&&out);
    // Exercise a nonidentity polynomial on all three BL channels, before NLQ.
    struct pl_dovi_metadata reshape={0};
    for(unsigned i=0;i<3;i++){
        reshape.comp[i].num_pivots=2;
        reshape.comp[i].pivots[0]=0.f;
        reshape.comp[i].pivots[1]=1.f;
        reshape.comp[i].method[0]=0;
        reshape.comp[i].poly_coeffs[0][0]=0.1f;
        reshape.comp[i].poly_coeffs[0][1]=0.9f;
    }
    assert(!ambient_fel_compose(gpu,dp,bl,el,bl,&reshape,&nlq));
    assert(ambient_fel_compose(gpu,dp,bl,el,out,&reshape,&nlq));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=out,.ptr=pixels)));
    for(unsigned i=0;i<4;i++) for(unsigned c=0;c<3;c++){
        unsigned k=i*4+c;
        double expected=fmin(1.0,fmax(0.0,0.1+0.9*base[k]+
            ambient_fel_residual(&nlq,c,residual[k])));
        if(fabs(pixels[k]-expected)>0.0001)
            fprintf(stderr,"FEL pixel %u: actual %f expected %f\n",k,pixels[k],expected);
        assert(isfinite(pixels[k])&&fabs(pixels[k]-expected)<0.0001);
    }
    nlq.el_bits=17;
    assert(!ambient_fel_compose(gpu,dp,bl,el,out,&reshape,&nlq));
    pl_gpu_finish(gpu);
    pl_tex_destroy(gpu,&out);pl_tex_destroy(gpu,&el);pl_tex_destroy(gpu,&bl);
    pl_dispatch_destroy(&dp);
    pl_vulkan_destroy(&vk);pl_log_destroy(&log);
    puts("FEL reconstructed codewords match CPU reference");
}
