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
    float u=fminf(1.f,fmaxf(0.f,sqrtf(v)/s->domains[i]));
    const float *k=s->authored[i];
    if(k[3]>0.f){
        float x=u*u;
        if(k[0]==1.f||x<k[0])return k[2]*k[1]*x/k[0];
        u=(x-k[0])/(1.f-k[0]);
    }
    float x=u*(AMBIENT_HDRPLUS_LUT_SIZE-1);
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
        .instance_params=pl_vk_inst_params(.debug=true,
            .extensions=(const char *const[]){"VK_EXT_debug_utils"},.num_extensions=1),
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
    assert(state.curves[0][AMBIENT_HDRPLUS_LUT_SIZE-1]<=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,state.output_max*8.f/15.f)+0.001f);
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
    // Independent analytical pixel fixture, authored display matches actual
    // target. No reference to the generated LUT in the expected value.
    m=(struct ambient_hdrplus){.window_count=1,.version=1,.target_nits=state.output_max};
    m.windows[0]=(struct ambient_hdrplus_window){.tone_mapping=true,.knee_x=1.f,
        .knee_y=0.5f,.maxscl={0.1f,0.1f,0.1f}};
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    float expected=state.output_max*0.3f;
    assert(fabsf(pl_hdr_rescale(PL_HDR_PQ,PL_HDR_NITS,pixels[0])-expected)<0.6f);
    // Steep authored knee: independent analytic values on either side.
    // In particular a tiny first segment must not be smeared into the LUT.
    const float near_nits[4]={0.f,0.12f,1000.f/4095.f,0.5f};
    m.windows[0].knee_x=1.f/4095.f;m.windows[0].knee_y=0.25f;
    m.windows[0].anchor_count=1;m.windows[0].anchors[0]=0.5f;
    for(int i=0;i<width*height;i++){
        float p=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,near_nits[i%4]);
        pixels[i*4]=pixels[i*4+1]=pixels[i*4+2]=p;pixels[i*4+3]=1.f;
    }
    pl_tex near=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
        .sampleable=true,.initial_data=pixels));
    assert(near);
    img.planes[0].texture=near;img.color=pl_color_space_hdr10;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    for(unsigned i=0;i<4;i++){
        float x=near_nits[i]/1000.f,k=1.f/4095.f;
        float y=x==0.f?0.f:x<k?0.25f*x/k:0.25f+0.75f*(x-k)/(1.f-k);
        float ref=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,y*state.output_max);
        assert(fabsf(pixels[4*i]-ref)<0.0015f);
    }
    pl_gpu_finish(gpu);pl_tex_destroy(gpu,&near);
    // Maximum-degree authored curve: B16(t)=1-(1-t)^16.
    // Check midpoint interpolation against the closed form, independent of
    // the LUT generator, then verify actual GPU pixels for the same curve.
    m.windows[0].knee_x=0.f;m.windows[0].knee_y=0.f;
    m.windows[0].anchor_count=15;
    for(unsigned j=0;j<15;j++)m.windows[0].anchors[j]=1.f;
    const unsigned last=AMBIENT_HDRPLUS_LUT_SIZE-1;
    const float steep_nits[]={1000.f*0.5f/last,1000.f*1.5f/last,100.f,999.f};
    for(int i=0;i<width*height;i++){
        float p=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,steep_nits[i%4]);
        pixels[i*4]=pixels[i*4+1]=pixels[i*4+2]=p;pixels[i*4+3]=1.f;
    }
    pl_tex steep=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
        .sampleable=true,.initial_data=pixels));
    assert(steep);
    img.planes[0].texture=steep;img.color=pl_color_space_hdr10;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    const double scale=state.authored[0][2];
    assert(scale>0.0);
    for(unsigned j=0;j<last;j++){
        double x=(j+0.5)/last;
        double expected=1.0-pow(1.0-x,16.0);
        double actual=(state.curves[0][j]+state.curves[0][j+1])/(2.0*scale);
        assert(fabs(actual-expected)<0.000058);
    }
    assert(!memcmp(state.curves[0],state.curves[1],sizeof(state.curves[0])));
    assert(!memcmp(state.curves[0],state.curves[2],sizeof(state.curves[0])));
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    for(unsigned i=0;i<4;i++){
        double y=1.0-pow(1.0-steep_nits[i]/1000.0,16.0);
        float ref=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,y*state.output_max);
        assert(fabsf(pixels[4*i]-ref)<0.0015f);
    }
    pl_gpu_finish(gpu);pl_tex_destroy(gpu,&steep);
    // Saturation: evaluate B.8-B.12 independently, with ideal-black BT.1886.
    const float channels[3]={600.f,250.f,120.f};
    for(int i=0;i<width*height;i++){
        for(unsigned j=0;j<3;j++)pixels[4*i+j]=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,channels[j]);
        pixels[4*i+3]=1.f;
    }
    pl_tex sat_input=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
        .sampleable=true,.initial_data=pixels));
    assert(sat_input);
    m.windows[0].knee_x=1.f;m.windows[0].knee_y=0.5f;m.windows[0].anchor_count=0;
    m.windows[0].saturation_mapping=true;m.windows[0].saturation_weight=8;
    img.planes[0].texture=sat_input;img.color=pl_color_space_hdr10;img.color.hdr.max_luma=1000.f;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    float e[3];
    for(unsigned j=0;j<3;j++)e[j]=powf(channels[j]/2000.f,1.f/2.4f);
    float lum=0.2627f*e[0]+0.6780f*e[1]+0.0593f*e[2];
    float gain=1.f+fmaxf(0.f,logf(600.f)/logf(state.output_max*0.3f)-1.f);
    for(unsigned j=0;j<3;j++){
        float d=e[j]-lum;
        if(d>0.f)gain=fminf(gain,(1.f-lum)/d);
        if(d<0.f)gain=fminf(gain,-lum/d);
    }
    for(unsigned j=0;j<3;j++){
        float nits=powf(fmaxf(0.f,lum+gain*(e[j]-lum)),2.4f)*state.output_max;
        assert(fabsf(pixels[j]-pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,nits))<0.0015f);
    }
    pl_gpu_finish(gpu);pl_tex_destroy(gpu,&sat_input);
    // A nearly neutral pixel with large authored weight can yield an
    // unconstrained B.11 gain above 4. B.12 explicitly caps eta at 4.
    const float near_neutral[3]={600.f,570.f,540.f};
    for(int i=0;i<width*height;i++){
        for(unsigned j=0;j<3;j++)
            pixels[4*i+j]=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,near_neutral[j]);
        pixels[4*i+3]=1.f;
    }
    sat_input=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
        .sampleable=true,.initial_data=pixels));
    assert(sat_input);
    m.windows[0].knee_y=0.1f;m.windows[0].saturation_weight=63;
    img.planes[0].texture=sat_input;img.color=pl_color_space_hdr10;img.color.hdr.max_luma=1000.f;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    for(unsigned j=0;j<3;j++)e[j]=powf(near_neutral[j]/10000.f,1.f/2.4f);
    lum=0.2627f*e[0]+0.6780f*e[1]+0.0593f*e[2];
    gain=1.f+(63.f/8.f)*fmaxf(0.f,logf(600.f)/logf(state.output_max*0.06f)-1.f);
    for(unsigned j=0;j<3;j++){
        float d=e[j]-lum;
        if(d>0.f)gain=fminf(gain,(1.f-lum)/d);
        if(d<0.f)gain=fminf(gain,-lum/d);
    }
    assert(gain>4.f); // unconstrained by B.12 upper limit
    gain=fminf(gain,4.f);
    for(unsigned j=0;j<3;j++){
        float nits=powf(fmaxf(0.f,lum+gain*(e[j]-lum)),2.4f)*state.output_max;
        assert(fabsf(pixels[j]-pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,nits))<0.003f);
    }
    pl_gpu_finish(gpu);pl_tex_destroy(gpu,&sat_input);
    m.windows[0].saturation_mapping=false;
    // Legal no-OOTF input uses scene statistics, not an uninitialized curve.
    m.windows[0].tone_mapping=false;m.target_nits=0.f;
    m.windows[0].maxscl[0]=m.windows[0].maxscl[1]=m.windows[0].maxscl[2]=0.f;
    m.windows[0].histogram_count=1;m.windows[0].histogram[0].percentage=99;
    m.windows[0].histogram[0].percentile=0.1f;m.windows[0].average=0.025f;
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
    assert(img.planes[0].texture==state.output);
    // Native P010 is packed Cr/Y/Cb. Compare the spatial result against
    // independent CPU BT.2020 NCL decoding of the same quantized codewords,
    // with both full and limited ranges and nonneutral channels.
    m=(struct ambient_hdrplus){.window_count=1,.version=1,.target_nits=state.output_max};
    m.windows[0]=(struct ambient_hdrplus_window){.tone_mapping=true,.knee_x=1.f,
        .knee_y=0.5f,.maxscl={0.1f,0.1f,0.1f}};
    const float yuv[4][3]={{0.45f,0.04f,0.08f},{0.40f,-0.08f,0.04f},
        {0.32f,0.06f,-0.04f},{0.20f,0.f,0.f}};
    float rgb_reference[16];
    for(unsigned range=0;range<2;range++){
        bool limited=range==0;
        float yo=limited?64.f:0.f,ys=limited?876.f:1023.f;
        float cs=limited?896.f:1023.f;
        for(int i=0;i<width*height;i++){
            const float *v=yuv[i%4];
            float yc=roundf(yo+ys*v[0]),uc=roundf(512.f+cs*v[1]),vc=roundf(512.f+cs*v[2]);
            pixels[4*i]=vc/1023.f;pixels[4*i+1]=yc/1023.f;
            pixels[4*i+2]=uc/1023.f;pixels[4*i+3]=1.f;
        }
        pl_tex packed=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
            .sampleable=true,.initial_data=pixels));
        assert(packed);
        for(int i=0;i<width*height;i++){
            float yy=(pixels[4*i+1]*1023.f-yo)/ys;
            float cb=(pixels[4*i+2]*1023.f-512.f)/cs,cr=(pixels[4*i]*1023.f-512.f)/cs;
            float red=yy+1.4746f*cr,blue=yy+1.8814f*cb;
            pixels[4*i]=red;pixels[4*i+1]=(yy-0.2627f*red-0.0593f*blue)/0.6780f;
            pixels[4*i+2]=blue;
        }
        pl_tex rgb=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
            .sampleable=true,.initial_data=pixels));
        assert(rgb);
        img.planes[0].texture=rgb;img.color=pl_color_space_hdr10;img.repr=pl_color_repr_rgb;
        assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
        sh=pl_dispatch_begin(state.dispatch);
        pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
        assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
        assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
        memcpy(rgb_reference,pixels,sizeof(rgb_reference));
        img.planes[0]=(struct pl_plane){.texture=packed,.components=3,.component_mapping={2,0,1,-1}};
        img.color=pl_color_space_hdr10;
        img.repr=(struct pl_color_repr){.sys=PL_COLOR_SYSTEM_BT_2020_NC,
            .levels=limited?PL_COLOR_LEVELS_LIMITED:PL_COLOR_LEVELS_FULL,
            .bits={.sample_depth=10,.color_depth=10}};
        img.repr.bits.color_depth=8;
        assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
        assert(img.planes[0].texture==packed && img.repr.bits.color_depth==8);
        img.repr.bits.color_depth=10;
        assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&target,&m));
        assert(img.repr.sys==PL_COLOR_SYSTEM_RGB && img.planes[0].component_mapping[0]==0 &&
            img.planes[0].component_mapping[1]==1 && img.planes[0].component_mapping[2]==2);
        sh=pl_dispatch_begin(state.dispatch);
        pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
        assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
        assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
        for(unsigned j=0;j<16;j++)if(j%4!=3){
            assert(isfinite(pixels[j]) && fabsf(pixels[j]-rgb_reference[j])<0.003f);
        }
        pl_gpu_finish(gpu);pl_tex_destroy(gpu,&packed);pl_tex_destroy(gpu,&rgb);
    }
    // Swapchain metadata can be source mastering values. Require independent
    // display capability and a matching >=10-bit target surface.
    struct pl_frame characterized={.color=pl_color_space_hdr10};
    characterized.color.hdr.max_luma=4000.f;characterized.repr.bits.color_depth=8;
    assert(!ambient_hdrplus_characterize_target(&characterized,1,0.005f,600.f));
    characterized.repr.bits.color_depth=10;
    assert(!ambient_hdrplus_characterize_target(&characterized,2,0.005f,600.f));
    assert(!ambient_hdrplus_characterize_target(&characterized,1,-1.f,600.f));
    assert(characterized.color.hdr.max_luma==4000.f);
    assert(ambient_hdrplus_characterize_target(&characterized,1,0.005f,600.f));
    assert(characterized.color.hdr.max_luma==600.f&&characterized.color.hdr.min_luma==0.005f);
    // Explicit PQ target: preserve local authored differences and absolute
    // PQ luminance. No SDR range cap, no guessed display capabilities.
    struct pl_frame pq_target={.color=pl_color_space_hdr10};
    pq_target.color.hdr.min_luma=0.005f;
    pq_target.color.hdr.max_luma=600.f;
    m=(struct ambient_hdrplus){.window_count=2,.version=0,.target_nits=600.f};
    m.windows[0]=(struct ambient_hdrplus_window){.tone_mapping=true,.knee_x=1.f,
        .knee_y=0.5f,.maxscl={0.1f,0.1f,0.1f}};
    m.windows[1]=m.windows[0];m.windows[1].knee_y=0.8f;
    m.windows[1].geometry=(struct ambient_hdrplus_geometry){
        .left=0,.top=0,.right=63,.bottom=63,.center_x=32,.center_y=32,
        .semimajor_inner=8,.semimajor_outer=16,.semiminor_outer=16};
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    assert(img.color.transfer==PL_COLOR_TRC_PQ && img.repr.sys==PL_COLOR_SYSTEM_RGB);
    assert(fabsf(state.output_max-600.f)<0.01f && img.color.hdr.max_luma==state.output_max);
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    assert(fabsf(pl_hdr_rescale(PL_HDR_PQ,PL_HDR_NITS,pixels[0])-180.f)<2.f);
    assert(fabsf(pl_hdr_rescale(PL_HDR_PQ,PL_HDR_NITS,pixels[4*(32*width+32)])-288.f)<2.f);
    // Above the former SDR-only peak cap, authored output stays absolute.
    pq_target.color.hdr.max_luma=1500.f;m.target_nits=1500.f;
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    assert(fabsf(state.output_max-1500.f)<0.01f);
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    assert(fabsf(pl_hdr_rescale(PL_HDR_PQ,PL_HDR_NITS,pixels[0])-450.f)<4.f);
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    pq_target.color.hdr.max_luma=0.f;
    assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    pq_target.color.hdr.max_luma=10001.f;
    assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    pq_target.color.hdr.max_luma=600.f;m.target_nits=600.f;
    pq_target.color.hdr.min_luma=0.005f;
    // Annex B.5 is a reversible BT.1886 *working* transform. Verify
    // colored PQ output against a CPU calculation in that domain.
    const float hdr_channels[3]={600.f,400.f,200.f};
    for(int i=0;i<width*height;i++){
        for(unsigned j=0;j<3;j++)
            pixels[4*i+j]=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,hdr_channels[j]);
        pixels[4*i+3]=1.f;
    }
    pl_tex hdr_colored=pl_tex_create(gpu,pl_tex_params(.w=width,.h=height,.format=fmt,
        .sampleable=true,.initial_data=pixels));
    assert(hdr_colored);
    img.planes[0].texture=hdr_colored;img.color=pl_color_space_hdr10;img.color.hdr.max_luma=1000.f;
    m.windows[0].saturation_mapping=true;m.windows[0].saturation_weight=8;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    sh=pl_dispatch_begin(state.dispatch);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=state.output));
    assert(pl_dispatch_finish(state.dispatch,pl_dispatch_params(.shader=&sh,.target=readback)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    float ec[3];
    for(unsigned j=0;j<3;j++)ec[j]=powf(hdr_channels[j]/2000.f,1.f/2.4f);
    float luma=0.2627f*ec[0]+0.6780f*ec[1]+0.0593f*ec[2];
    float g=fminf(4.f,logf(600.f)/logf(180.f));
    for(unsigned j=0;j<3;j++){
        float delta=ec[j]-luma;
        if(delta>0.f)g=fminf(g,(1.f-luma)/delta);
        if(delta<0.f)g=fminf(g,-luma/delta);
    }
    for(unsigned j=0;j<3;j++){
        float value=600.f*powf(fmaxf(0.f,luma+g*(ec[j]-luma)),2.4f);
        assert(fabsf(pixels[j]-pl_hdr_rescale(PL_HDR_NITS,PL_HDR_PQ,value))<0.002f);
    }
    pl_gpu_finish(gpu);pl_tex_destroy(gpu,&hdr_colored);
    // HLG target: keep the prepass in display-referred PQ, then use the
    // actual final renderer's inverse OOTF/OETF and compare neutral pixels
    // against the analytic BT.2100 equation for this characterized display.
    m.windows[0].saturation_mapping=false;
    pq_target.color.transfer=PL_COLOR_TRC_HLG;
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    assert(ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    assert(img.color.transfer==PL_COLOR_TRC_PQ && state.output_max==600.f);
    pl_renderer renderer=pl_renderer_create(log,gpu);
    assert(renderer);
    pq_target.num_planes=1;pq_target.repr=pl_color_repr_rgb;
    pq_target.planes[0]=(struct pl_plane){.texture=readback,.components=3,.component_mapping={0,1,2,-1}};
    pq_target.crop=(struct pl_rect2df){0,0,width,height};
    struct pl_color_map_params cm=pl_color_map_default_params;
    cm.metadata=PL_HDR_METADATA_HDR10;cm.tone_mapping_function=&pl_tone_map_clip;
    cm.tone_mapping_algo=0;cm.inverse_tone_mapping=false;
    struct pl_render_params rp=pl_render_default_params;
    rp.color_map_params=&cm;rp.peak_detect_params=NULL;
    assert(pl_render_image(renderer,&img,&pq_target,&rp));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=pixels)));
    const float gamma=fmaxf(1.f,1.2f+0.42f*log10f(0.6f));
    const float beta=sqrtf(3.f*powf(0.005f/600.f,1.f/gamma));
    for(unsigned j=0;j<2;j++){
        float nits=j?288.f:180.f;
        float scene=12.f*powf(nits/600.f,1.f/gamma);
        float signal=scene<=1.f?0.5f*sqrtf(scene):0.17883277f*logf(scene-0.28466892f)+0.55991073f;
        signal=(signal-beta)/(1.f-beta);
        unsigned idx=j?4*(32*width+32):0;
        assert(isfinite(pixels[idx])&&fabsf(pixels[idx]-signal)<0.012f);
    }
    pl_renderer_destroy(&renderer);
    img.planes[0].texture=input;img.color=pl_color_space_hdr10;
    pq_target.color.hdr.max_luma=0.f;
    assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    pq_target.color.hdr.max_luma=600.f;pq_target.color.hdr.min_luma=500.f;
    assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    pq_target.color.transfer=PL_COLOR_TRC_PQ;pq_target.color.hdr.max_luma=50.f;
    pq_target.color.hdr.min_luma=0.f;
    assert(!ambient_hdrplus_spatial_render(gpu,log,&state,&img,&pq_target,&m));
    assert(img.planes[0].texture==input && img.color.transfer==PL_COLOR_TRC_PQ);
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
