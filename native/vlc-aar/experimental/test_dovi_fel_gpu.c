#include "dovi_fel_render.h"
#include <stdlib.h>
#include "dovi_fel_metadata.h"
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
    assert(ambient_fel_residual(&nlq,0,511.5/1023)==-1.0/256);
    assert(ambient_fel_residual(&nlq,0,512.5/1023)==1.0/256);
    struct ambient_fel_nlq wide=nlq;
    wide.coef_log2_denom=32;
    wide.slope[0]=UINT64_C(1)<<32;wide.threshold[0]=UINT64_C(1)<<31;
    wide.maximum[0]=UINT64_C(1)<<32;
    assert(ambient_fel_nlq_valid(&wide));
    assert(ambient_fel_residual(&wide,0,513.0/1023)==1.0);
    assert(ambient_fel_residual(&wide,0,511.0/1023)==-1.0);
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
    // The renderer consumes value snapshots from the actual RPU parser, not
    // pointers into decoder state. Exercise the bridge with the same curve.
    struct ambient_dovi_metadata metadata={
        .residual_present=1,.bl_bit_depth=10,.el_bit_depth=10,.vdr_bit_depth=12,
        .coef_log2_denom=16,.nlq_pivots={0,1023}
    };
    for(unsigned i=0;i<3;i++){
        metadata.nlq_offset[i]=nlq.offset[i];
        metadata.nlq_slope[i]=nlq.slope[i];
        metadata.nlq_threshold[i]=nlq.threshold[i];
        metadata.nlq_maximum[i]=nlq.maximum[i];
        metadata.curves[i].num_pivots=2;
        metadata.curves[i].pivots[1]=1023;
        metadata.curves[i].poly_order[0]=1;
        metadata.curves[i].poly_coef[0][0]=6554;
        metadata.curves[i].poly_coef[0][1]=58982;
    }
    assert(ambient_fel_metadata(&metadata,&reshape,&nlq));
    metadata.residual_present=0;
    assert(!ambient_fel_metadata(&metadata,&reshape,&nlq));
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
    // A canonical MEL-like RPU can prove the residual is zero without
    // sampling any EL texture. Also cover zero-maximum components.
    struct ambient_fel_nlq mel=nlq;
    for(unsigned i=0;i<3;i++){mel.offset[i]=0;mel.slope[i]=0;mel.threshold[i]=0;mel.maximum[i]=65536;}
    assert(ambient_fel_nlq_zero(&mel)&&!ambient_fel_nlq_zero(&nlq));
    mel.maximum[0]=0;mel.slope[0]=65536;
    assert(ambient_fel_nlq_zero(&mel));
    mel.threshold[1]=1;
    assert(!ambient_fel_nlq_zero(&mel));
    assert(!ambient_fel_compose(gpu,dp,bl,NULL,out,&reshape,&mel));
    mel.threshold[1]=0;
    assert(ambient_fel_compose(gpu,dp,bl,NULL,out,&reshape,&mel));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=out,.ptr=pixels)));
    for(unsigned i=0;i<16;i++)if(i%4!=3)
        assert(fabsf(pixels[i]-(0.1f+0.9f*base[i]))<0.0001f);
    struct pl_dovi_metadata invalid=reshape;
    invalid.comp[0].method[0]=2;
    assert(!ambient_fel_compose(gpu,dp,bl,NULL,out,&invalid,&mel));
    invalid=reshape;invalid.comp[0].method[0]=1;invalid.comp[0].mmr_order[0]=4;
    assert(!ambient_fel_compose(gpu,dp,bl,NULL,out,&invalid,&mel));
    invalid=reshape;invalid.comp[0].poly_coeffs[0][1]=NAN;
    assert(!ambient_fel_compose(gpu,dp,bl,NULL,out,&invalid,&mel));

    // Half-resolution EL: 2x2 luma, 1x1 chroma; 4x4 BL is
    // packed exactly like the native P010 identity sampler (Cr,Y,Cb).
    const uint16_t y[]={512,516,520,524},u[]={514},v[]={510};
    const uint16_t *planes_data[]={y,u,v};
    const size_t pitches[]={4,2,2};
    struct ambient_fel_planes planes={0};
    assert(ambient_fel_planes_upload(gpu,&planes,2,2,planes_data,pitches,-0.5f,-0.5f));
    float big[64],big_out[64];
    for(unsigned i=0;i<16;i++){
        big[i*4]=0.7f;big[i*4+1]=0.3f;big[i*4+2]=0.5f;big[i*4+3]=1.f;
    }
    pl_tex large_bl=pl_tex_create(gpu,pl_tex_params(.w=4,.h=4,.format=fmt,
        .sampleable=true,.initial_data=big));
    pl_tex large_out=pl_tex_create(gpu,pl_tex_params(.w=4,.h=4,.format=fmt,
        .renderable=true,.host_readable=true));
    assert(large_bl&&large_out);
    assert(ambient_fel_compose_ex(gpu,dp,large_bl,NULL,large_out,&reshape,&nlq,true,false,&planes));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=large_out,.ptr=big_out)));
    for(unsigned row=0;row<4;row++)for(unsigned col=0;col<4;col++){
        // Layer siting: left horizontally, centered vertically.
        double x=fmin(1.0,0.5*col), yy=fmin(1.0,fmax(0.0,0.5*row-0.25));
        double code=512+4*x+8*yy;
        unsigned i=4*(row*4+col);
        double a=0.1+0.9*0.3+ambient_fel_residual(&nlq,0,code/1023.0);
        double b=0.1+0.9*0.5+ambient_fel_residual(&nlq,1,514.0/1023);
        double c=0.1+0.9*0.7+ambient_fel_residual(&nlq,2,510.0/1023);
        assert(fabs(big_out[i]-a)<0.0001);
        assert(fabs(big_out[i+1]-b)<0.0001);
        assert(fabs(big_out[i+2]-c)<0.0001);
    }
    // At exact half codes the signed residual must be symmetric, including
    // when the fraction came from interpolation of integer R16 texels.
    const uint16_t ties[]={511,512,512,513};
    const uint16_t *tie_planes[]={ties,u,v};
    assert(ambient_fel_planes_upload(gpu,&planes,2,2,tie_planes,pitches,0.f,0.f));
    assert(ambient_fel_compose_ex(gpu,dp,large_bl,NULL,large_out,&reshape,&nlq,true,false,&planes));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=large_out,.ptr=big_out)));
    const double center=0.1+0.9*0.3;
    assert(fabs(big_out[4]-(center-1.0/256))<0.0001);
    assert(fabs(big_out[4*(3*4+1)]-(center+1.0/256))<0.0001);
    // 1:1 luma fast path, with non-constant chroma and six sitings.
    const uint16_t direct_y[]={510,511,512,513,514,515,516,517,518,519,520,521,522,523,524,525};
    const uint16_t direct_u[]={508,512,516,520},direct_v[]={520,516,512,508};
    const uint16_t *direct_planes[]={direct_y,direct_u,direct_v};
    const size_t direct_pitches[]={8,4,4};
    const float shifts[][2]={{0,0},{-.5f,0},{-.5f,-.5f},{0,-.5f},{-.5f,.5f},{0,.5f}};
    for(unsigned s=0;s<6;s++){
        assert(ambient_fel_planes_upload(gpu,&planes,4,4,direct_planes,direct_pitches,shifts[s][0],shifts[s][1]));
        assert(ambient_fel_compose_ex(gpu,dp,large_bl,NULL,large_out,&reshape,&nlq,true,false,&planes));
        assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=large_out,.ptr=big_out)));
        for(unsigned row=0;row<4;row++)for(unsigned col=0;col<4;col++){
            double cx=fmin(1.0,fmax(0.0,((col+0.5)-shifts[s][0])*0.5-0.5));
            double cy=fmin(1.0,fmax(0.0,((row+0.5)-shifts[s][1])*0.5-0.5));
            double uc=508+4*cx+8*cy,vc=520-4*cx-8*cy;
            unsigned i=4*(row*4+col);
            assert(fabs(big_out[i]-(center+ambient_fel_residual(&nlq,0,direct_y[row*4+col]/1023.0)))<0.0001);
            assert(fabs(big_out[i+1]-(0.55+ambient_fel_residual(&nlq,1,uc/1023.0)))<0.0001);
            assert(fabs(big_out[i+2]-(0.73+ambient_fel_residual(&nlq,2,vc/1023.0)))<0.0001);
        }
    }
    // A coefficient above UINT32_MAX is still representable and clamps
    // the reconstructed signal to its legal range.
    assert(ambient_fel_compose(gpu,dp,bl,el,out,&reshape,&wide));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=out,.ptr=pixels)));
    assert(pixels[4]==1.f&&pixels[8]==0.f);
    // A neutral signal with identity matrix and no residual remains neutral.
    // The pinned Dolby Vision LMS-to-RGB matrix preserves its neutral axis.
    struct pl_dovi_metadata identity={0};
    for(unsigned i=0;i<3;i++){
        identity.linear.m[i][i]=1.f;identity.nonlinear.m[i][i]=1.f;
    }
    float neutral[16],zero[16];
    for(unsigned i=0;i<16;i++){
        neutral[i]=i%4==3?1.f:0.5f;zero[i]=i%4==3?1.f:512.f/1023;
    }
    pl_tex neutral_bl=pl_tex_create(gpu,pl_tex_params(.w=2,.h=2,.format=fmt,
        .sampleable=true,.initial_data=neutral));
    pl_tex zero_el=pl_tex_create(gpu,pl_tex_params(.w=2,.h=2,.format=fmt,
        .sampleable=true,.initial_data=zero));
    assert(neutral_bl&&zero_el);
    assert(ambient_fel_compose_ex(gpu,dp,neutral_bl,zero_el,out,&identity,&nlq,false,true,NULL));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=out,.ptr=pixels)));
    for(unsigned i=0;i<16;i++)if(i%4!=3)assert(fabsf(pixels[i]-0.5f)<0.0002f);
    // Exercise the exact owned-picture handoff used by display.c.
    struct ambient_fel_picture *pic=calloc(1,sizeof(*pic)+6*sizeof(uint16_t));
    assert(pic);
    pic->rpu=metadata;pic->rpu.residual_present=1;pic->rpu.source_max_pq=3072;
    pic->width=2;pic->height=2;pic->pts=1234;
    for(unsigned i=0;i<3;i++){
        pic->rpu.nonlinear_matrix[3*i+i]=1.f;
        pic->rpu.linear_matrix[3*i+i]=1.f;
        pic->rpu.curves[i].poly_coef[0][0]=0;
        pic->rpu.curves[i].poly_coef[0][1]=65536;
    }
    for(unsigned i=0;i<6;i++)pic->samples[i]=512;
    struct ambient_fel_render render={0};
    struct pl_frame frame={.num_planes=1};
    frame.planes[0].texture=neutral_bl;
    assert(!ambient_fel_render_picture(gpu,log,&render,&frame,pic,1235,0,0));
    assert(frame.planes[0].texture==neutral_bl);
    assert(ambient_fel_render_picture(gpu,log,&render,&frame,pic,1234,0,0));
    assert(frame.planes[0].texture==render.output && frame.repr.sys==PL_COLOR_SYSTEM_RGB);
    assert(frame.color.transfer==PL_COLOR_TRC_PQ);
    // Cropped visible textures are in local coordinates; admit only
    // matched even origins, never silently accept a different EL phase.
    pic->crop_x=2;pic->crop_y=4;
    frame.planes[0].texture=neutral_bl;
    assert(!ambient_fel_render_picture(gpu,log,&render,&frame,pic,1234,0,0));
    assert(!ambient_fel_render_picture(gpu,log,&render,&frame,pic,1234,2,2));
    assert(ambient_fel_render_picture(gpu,log,&render,&frame,pic,1234,2,4));
    pic->crop_x=pic->crop_y=0;
    // Exercise transition from nonzero -> zero -> nonzero metadata.
    // The first MEL frame must not allocate any EL GPU planes.
    struct ambient_fel_render mel_render={0};
    struct ambient_dovi_metadata original_rpu=pic->rpu;
    for(unsigned i=0;i<3;i++){
        pic->rpu.nlq_slope[i]=0;pic->rpu.nlq_threshold[i]=0;
    }
    frame.planes[0].texture=neutral_bl;
    assert(ambient_fel_render_picture(gpu,log,&mel_render,&frame,pic,1234,0,0));
    assert(!mel_render.planes.tex[0]&&!mel_render.planes.tex[1]&&!mel_render.planes.tex[2]);
    pic->rpu=original_rpu;
    frame.planes[0].texture=neutral_bl;
    assert(ambient_fel_render_picture(gpu,log,&mel_render,&frame,pic,1234,0,0));
    assert(mel_render.planes.tex[0]&&mel_render.planes.tex[1]&&mel_render.planes.tex[2]);
    pl_gpu_finish(gpu);ambient_fel_render_destroy(gpu,&mel_render);
    free(pic);
    pl_gpu_finish(gpu);
    ambient_fel_render_destroy(gpu,&render);
    pl_tex_destroy(gpu,&neutral_bl);pl_tex_destroy(gpu,&zero_el);
    pl_tex_destroy(gpu,&large_bl);pl_tex_destroy(gpu,&large_out);
    ambient_fel_planes_destroy(gpu,&planes);
    nlq.el_bits=17;
    assert(!ambient_fel_compose(gpu,dp,bl,el,out,&reshape,&nlq));
    pl_gpu_finish(gpu);
    pl_tex_destroy(gpu,&out);pl_tex_destroy(gpu,&el);pl_tex_destroy(gpu,&bl);
    pl_dispatch_destroy(&dp);
    pl_vulkan_destroy(&vk);pl_log_destroy(&log);
    puts("FEL reconstructed codewords match CPU reference");
}
