/* Independent full-frame HDR10+ oracle, including wire parser and GPU readback.
 * Generated values do not use libplacebo or production LUTs. */
#include "hdr10plus_spatial.h"
#include <libplacebo/vulkan.h>
#include <libplacebo/shaders/sampling.h>
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include "hdr10plus_frame_reference.h"

static void check(const char *name,const float *actual,const float expected[REF_N][3],
                  double max_limit,double rms_limit)
{
    double worst=0,sum=0;
    unsigned wi=0,wk=0;
    for(unsigned i=0;i<REF_N;i++){
        for(unsigned k=0;k<3;k++){
            double d=fabs((double)actual[4*i+k]-expected[i][k]);
            assert(isfinite(d));
            if(d>worst){worst=d;wi=i;wk=k;}
            sum+=d*d;
        }
    }
    double rms=sqrt(sum/(3*REF_N));
    fprintf(stderr,"%s: %u channels, max %.8f (limit %.8f), RMS %.8f (limit %.8f), worst (%u,%u) channel %u got %.8f expected %.8f\n",
        name,3u*REF_N,worst,max_limit,rms,rms_limit,wi%REF_W,wi/REF_W,wk,
        actual[4*wi+wk],expected[wi][wk]);
    assert(worst<=max_limit && rms<=rms_limit);
}

static void download(pl_gpu gpu,pl_dispatch dp,pl_tex input,pl_tex readback,float *pixels)
{
    pl_shader sh=pl_dispatch_begin(dp);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=input));
    assert(pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
}

static void run_frame(pl_gpu gpu,pl_log log,pl_fmt fmt,pl_tex readback,
                      pl_dispatch dp,pl_renderer renderer,struct ambient_hdrplus_spatial *state,
                      const struct frame_reference *f)
{
    float rgba[REF_N*4];
    for(unsigned i=0;i<REF_N;i++){
        for(unsigned k=0;k<3;k++)rgba[4*i+k]=f->input[i][k];
        rgba[4*i+3]=1;
    }
    pl_tex input=pl_tex_create(gpu,pl_tex_params(.w=REF_W,.h=REF_H,.format=fmt,
        .sampleable=true,.initial_data=rgba));
    assert(input);
    struct ambient_hdrplus m={0};
    assert(ambient_hdrplus_parse(f->wire,f->wire_size,&m));
    assert(m.metadata_constraints_ok && m.version==f->version && m.target_nits==f->peak);
    struct pl_frame src={.num_planes=1,.repr=pl_color_repr_rgb,
        .color=pl_color_space_hdr10,.crop={0,0,REF_W,REF_H}};
    src.color.hdr.max_luma=4000;
    src.planes[0]=(struct pl_plane){.texture=input,.components=3,
        .component_mapping={0,1,2,-1},.flipped=f->flipped};
    if(f->kind){
        src.repr=(struct pl_color_repr){.sys=PL_COLOR_SYSTEM_BT_2020_NC,
            .levels=f->kind==1?PL_COLOR_LEVELS_LIMITED:PL_COLOR_LEVELS_FULL,
            .bits={.sample_depth=10,.color_depth=10}};
        src.planes[0].component_mapping[0]=2;
        src.planes[0].component_mapping[1]=0;
        src.planes[0].component_mapping[2]=1;
    }
    struct pl_frame target={.color=pl_color_space_hdr10};
    target.color.hdr.min_luma=.005f;
    target.color.hdr.max_luma=f->peak;
    if(f->target==1) target.color=pl_color_space_bt709;
    if(f->target==2) target.color.transfer=PL_COLOR_TRC_HLG;
    struct pl_frame img=src;
    assert(ambient_hdrplus_spatial_render(gpu,log,state,&img,&target,&m));
    assert(fabsf(state->output_max-f->peak)<.01f);
    assert(img.color.transfer==PL_COLOR_TRC_PQ && img.repr.sys==PL_COLOR_SYSTEM_RGB);
    assert(img.color.hdr.max_luma==state->output_max && !img.planes[0].flipped);
    download(gpu,dp,img.planes[0].texture,readback,rgba);
    check(f->name,rgba,f->expected,f->kind?.0035:.0025,f->kind?.001:.0008);
    // Replay the same parsed scene through the live cache. The oracle remains
    // independent and must still match after the previous scene's geometry.
    pl_tex lut=state->lut,output=state->output;
    img=src;
    assert(ambient_hdrplus_spatial_render(gpu,log,state,&img,&target,&m));
    assert(state->lut==lut && state->output==output);
    download(gpu,dp,img.planes[0].texture,readback,rgba);
    check(f->name,rgba,f->expected,f->kind?.0035:.0025,f->kind?.001:.0008);
    if(f->final){
        // Match the production final renderer: no additional dynamic OOTF
        // or peak analysis. Validate PQ, characterized HLG and in-gamut SDR.
        target.num_planes=1;target.repr=pl_color_repr_rgb;
        target.planes[0]=(struct pl_plane){.texture=readback,.components=3,
            .component_mapping={0,1,2,-1}};
        target.crop=(struct pl_rect2df){0,0,REF_W,REF_H};
        struct pl_color_map_params cm=pl_color_map_default_params;
        cm.metadata=PL_HDR_METADATA_HDR10;cm.tone_mapping_function=&pl_tone_map_clip;
        cm.tone_mapping_algo=0;cm.inverse_tone_mapping=false;
        struct pl_render_params rp=pl_render_default_params;
        rp.color_map_params=&cm;rp.peak_detect_params=NULL;
        rp.dither_params=NULL;
        assert(pl_render_image(renderer,&img,&target,&rp));
        assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=rgba)));
        check(f->target==2?"HLG final renderer":f->target==1?"SDR final renderer":"PQ final renderer",
              rgba,f->final,f->target==2?.012:f->target==1?.015:.004,
              f->target==2?.004:f->target==1?.005:.0015);
        // Quantize the actual final renderer into a packed 10-bit target.
        // Compare code values after readback (not CPU rounding of GPU floats).
        pl_fmt ten=pl_find_named_fmt(gpu,"rgb10a2");
        assert(ten && (ten->caps&PL_FMT_CAP_RENDERABLE) && (ten->caps&PL_FMT_CAP_SAMPLEABLE));
        pl_tex quantized=pl_tex_create(gpu,pl_tex_params(.w=REF_W,.h=REF_H,
            .format=ten,.renderable=true,.sampleable=true));
        assert(quantized);
        target.planes[0].texture=quantized;
        target.repr.bits.sample_depth=10;target.repr.bits.color_depth=10;
        assert(pl_render_image(renderer,&img,&target,&rp));
        download(gpu,dp,quantized,readback,rgba);
        for(unsigned i=0;i<REF_N;i++)for(unsigned k=0;k<3;k++){
            double code=rgba[4*i+k]*1023.;
            assert(isfinite(code) && fabs(code-round(code))<.002);
        }
        check("10-bit final renderer",rgba,f->final,
              f->target==2?.013:f->target==1?.016:.005,
              f->target==2?.005:f->target==1?.006:.0025);
        pl_gpu_finish(gpu);pl_tex_destroy(gpu,&quantized);
        target.planes[0].texture=readback;
    }
    // A malformed frame must not replace the input or the good cache.
    img=src;
    img.crop.x0=1;
    assert(!ambient_hdrplus_spatial_render(gpu,log,state,&img,&target,&m));
    assert(img.planes[0].texture==input && state->lut==lut);
    pl_gpu_finish(gpu);pl_tex_destroy(gpu,&input);
}

int main(void)
{
    pl_log log=pl_log_create(PL_API_VER,&(struct pl_log_params){
        .log_cb=pl_log_color,.log_level=PL_LOG_WARN});
    pl_vulkan vk=pl_vulkan_create(log,&(struct pl_vulkan_params){
        .allow_software=true,.queue_count=1,
        .instance_params=pl_vk_inst_params(.debug=true,
            .extensions=(const char *const[]){"VK_EXT_debug_utils"},.num_extensions=1)});
    assert(vk);pl_gpu gpu=vk->gpu;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    pl_tex readback=pl_tex_create(gpu,pl_tex_params(.w=REF_W,.h=REF_H,.format=fmt,
        .renderable=true,.host_readable=true));
    pl_dispatch dp=pl_dispatch_create(log,gpu);
    pl_renderer renderer=pl_renderer_create(log,gpu);
    assert(readback&&dp&&renderer);
    struct ambient_hdrplus_spatial state={0};
    for(unsigned i=0;i<sizeof(frames)/sizeof(frames[0]);i++)
        run_frame(gpu,log,fmt,readback,dp,renderer,&state,&frames[i]);
    // Return to the first metadata after all intervening cache invalidations.
    run_frame(gpu,log,fmt,readback,dp,renderer,&state,&frames[0]);
    pl_gpu_finish(gpu);
    ambient_hdrplus_spatial_destroy(gpu,&state);
    pl_renderer_destroy(&renderer);pl_dispatch_destroy(&dp);
    pl_tex_destroy(gpu,&readback);pl_vulkan_destroy(&vk);pl_log_destroy(&log);
    return 0;
}
