/* Independent fixed-point reference for ETSI GS CCM 001 V1.1.1,
 * 5.4.2 and 5.4.3. We compare the actual GPU composition BEFORE color
 * conversion, using authentic external RPU coefficients. BL/EL input
 * samples include deterministic aligned vectors and decoded 4:2:0 planes.
 * Spatial cases validate the experimental bilinear approximation; normative
 * Dolby resampling and display management are not asserted. */
#include "dovi_fel_render.h"
#include "dovi_fel_metadata.h"
#include <libplacebo/vulkan.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>

typedef __int128 wide;
static wide floor_shift(wide v,int s)
{
    if(s<=0)return v*((wide)1<<-s);
    wide d=(wide)1<<s;
    return v>=0?v/d:-((-v+d-1)/d);
}
static unsigned clip(wide v,unsigned max)
{
    return v<0?0:v>max?max:(unsigned)v;
}
static unsigned fixed_prediction(const struct ambient_dovi_metadata *m,const unsigned bl[3],unsigned c)
{
    const struct ambient_dovi_curve *q=&m->curves[c];
    unsigned s[3],seg=0;
    for(unsigned j=0;j<3;j++){
        const struct ambient_dovi_curve *p=&m->curves[j];
        assert(p->num_pivots>=2&&p->num_pivots<=9);
        s[j]=bl[j]<p->pivots[0]?p->pivots[0]:
             bl[j]>p->pivots[p->num_pivots-1]?p->pivots[p->num_pivots-1]:bl[j];
    }
    while(seg+2u<q->num_pivots&&bl[c]>=q->pivots[seg+1])seg++;
    wide acc=0;
    if(q->mapping[seg]==0){
        assert(q->poly_order[seg]>=1&&q->poly_order[seg]<=2);
        // Rational polynomial in code/1024, evaluated in Q20 before Q16 truncation.
        unsigned power=1;
        for(unsigned j=0;j<=q->poly_order[seg];j++){
            acc+=(wide)q->poly_coef[seg][j]*power*((wide)1<<(20-10*j));
            power*=s[c];
        }
    }else{
        assert(q->mapping[seg]==1&&q->mmr_order[seg]>=1&&q->mmr_order[seg]<=3);
        // Seven Q20 monomials per order, quantized at each multiplication.
        uint64_t a[7]={s[0]*1024u,s[1]*1024u,s[2]*1024u,
            s[0]*s[1],s[0]*s[2],s[1]*s[2],(uint64_t)s[0]*s[1]*s[2]/1024u};
        acc=(wide)q->mmr_constant[seg]*1048576;
        for(unsigned j=0;j<7;j++){
            uint64_t term=a[j];
            for(unsigned order=0;order<q->mmr_order[seg];order++){
                acc+=(wide)q->mmr_coef[seg][order][j]*term;
                term=term*a[j]/1048576;
            }
        }
    }
    return clip(floor_shift(acc,4+m->coef_log2_denom),65535);
}
static unsigned oracle(const struct ambient_dovi_metadata *m,const unsigned bl[3],unsigned el,unsigned c)
{
    int delta=(int)el-m->nlq_offset[c];
    wide residual=0;
    if(delta){
        wide mag=(wide)(2*abs(delta)-1)*m->nlq_slope[c]+(wide)2*m->nlq_threshold[c];
        wide cap=(wide)2*m->nlq_maximum[c];
        if(mag>cap)mag=cap;
        residual=floor_shift(delta<0?-mag:mag,(int)m->coef_log2_denom-15);
    }
    // Reconstruct Q16 and quantize to 12-bit output as for a PQ BL.
    return clip(floor_shift((wide)fixed_prediction(m,bl,c)+residual+8,4),4095);
}
enum { W=64,H=64,N=W*H,C=N/4,YUV=N+2*C };
/* Independent rational-coordinate oracle for the implementation's bilinear
 * EL sampler, not the normative Dolby resampling filter. Positions use
 * sixteenth-texel units; products and signed rounding remain integers. */
struct spatial_case {
    const char *name;
    unsigned ew,eh,sx,sy,ox,oy; /* integer BL:EL ratio; local EL offset */
    int cx,cy; /* chroma offsets in half-luma-pixel units */
};
static int floor16(int q)
{
    return q>=0?q/16:-((-q+15)/16);
}
static unsigned at_plane(const uint16_t *p,unsigned w,unsigned h,int x,int y)
{
    x=x<0?0:x>=(int)w?(int)w-1:x;
    y=y<0?0:y>=(int)h?(int)h-1:y;
    return p[(unsigned)y*w+(unsigned)x];
}
static unsigned aligned_el(const uint16_t *el,const struct spatial_case *g,
    unsigned x,unsigned y,unsigned c,unsigned offset,unsigned max_code)
{
    unsigned w=g->ew,h=g->eh;
    const uint16_t *p=el;
    // The local BL pixel center maps to the EL. Horizontally the
    // factor-two layer is left-sited; vertically it is centered.
    int px=(int)((16*x+8+(g->sx==2?8:0))/g->sx+16*g->ox);
    int py=(int)((16*y+8)/g->sy+16*g->oy);
    if(c){
        unsigned luma=w*h;
        w=(w+1)/2;h=(h+1)/2;
        p+=luma+(c-1)*w*h;
        px=(px-8*g->cx)/2;
        py=(py-8*g->cy)/2;
    }
    px-=8;py-=8;
    int ix=floor16(px),iy=floor16(py);
    unsigned fx=(unsigned)(px-16*ix),fy=(unsigned)(py-16*iy);
    assert(fx<16&&fy<16);
    unsigned weighted=(16-fx)*(16-fy)*at_plane(p,w,h,ix,iy)+
        fx*(16-fy)*at_plane(p,w,h,ix+1,iy)+(16-fx)*fy*at_plane(p,w,h,ix,iy+1)+
        fx*fy*at_plane(p,w,h,ix+1,iy+1);
    // NLQ rounds the signed distance from the offset, not the raw sample.
    int delta=(int)weighted-(int)(256*offset);
    int rounded=delta<0?-((-delta+128)/256):(delta+128)/256;
    return clip((int)offset+rounded,max_code);
}
static void crop_el(const uint16_t source[YUV],uint16_t dest[YUV],
    const struct spatial_case *g)
{
    unsigned cw=(g->ew+1)/2,ch=(g->eh+1)/2;
    for(unsigned y=0;y<g->eh;y++)
        memcpy(dest+y*g->ew,source+y*W,g->ew*sizeof(uint16_t));
    for(unsigned c=0;c<2;c++)for(unsigned y=0;y<ch;y++)
        memcpy(dest+g->ew*g->eh+c*cw*ch+y*cw,
            source+N+c*C+y*(W/2),cw*sizeof(uint16_t));
}
/* CPU reference uses a materialized vertical image and independent dot
 * products, not the shader's coordinate or arithmetic code. */
static void reference_expand(const uint16_t *src,unsigned w,unsigned h,bool luma,uint16_t *dst)
{
    const int v0[]={-3,29,111,-9},v1[]={-9,111,29,-3};
    const int vc0[]={64,192},vc1[]={192,64};
    const int hc[]={22,94,-524,2456,2456,-524,94,22};
    uint16_t *v=malloc((size_t)w*h*2*sizeof(*v));assert(v);
    for(unsigned y=0;y<2*h;y++)for(unsigned x=0;x<w;x++){
        int n=(int)y/2,sum=0;
        const int *c=luma?(y%2?v1:v0):(y%2?vc1:vc0);
        int start=luma?(y%2?n-1:n-2):(y%2?n:n-1);
        for(unsigned j=0;j<(luma?4u:2u);j++)sum+=c[j]*(int)at_plane(src,w,h,(int)x,start+(int)j);
        v[y*w+x]=clip(floor_shift((wide)sum+(luma?64:128),luma?7:8),65535);
    }
    for(unsigned y=0;y<2*h;y++)for(unsigned x=0;x<2*w;x++){
        if(!(x%2)){dst[y*2*w+x]=v[y*w+x/2];continue;}
        int sum=0;
        for(int j=0;j<8;j++)sum+=hc[j]*(int)at_plane(v,w,2*h,(int)x/2-3+j,(int)y);
        dst[y*2*w+x]=clip(floor_shift((wide)sum+2048,12),65535);
    }
    free(v);
}
static void assert_filter_pixels(pl_gpu gpu,pl_dispatch dp,pl_tex actual,const uint16_t *expected)
{
    unsigned w=actual->params.w,h=actual->params.h;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    pl_tex readback=pl_tex_create(gpu,pl_tex_params(.w=w,.h=h,.format=fmt,.renderable=true,.host_readable=true));
    assert(readback);
    const struct pl_shader_desc d={.desc={.name="filter_check",.type=PL_DESC_SAMPLED_TEX},.binding={.object=actual}};
    pl_shader sh=pl_dispatch_begin(dp);assert(sh);
    assert(pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,.descriptors=&d,.num_descriptors=1,
        .body="color=texelFetch(filter_check,ivec2(gl_FragCoord.xy),0);",
    }));
    assert(pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=readback)));
    float *out=malloc((size_t)w*h*4*sizeof(*out));assert(out);
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=readback,.ptr=out)));
    for(unsigned i=0;i<w*h;i++){
        unsigned got=(unsigned)lroundf(out[4*i]*65535.f);
        if(got!=expected[i])fprintf(stderr,"Annex B.3 sample %u: actual %u expected %u\n",i,got,expected[i]);
        assert(got==expected[i]);
    }
    free(out);pl_tex_destroy(gpu,&readback);
}
static unsigned native420_ref_luma(const uint16_t *y,unsigned x,unsigned row)
{
    unsigned h[2];
    for(unsigned j=0;j<2;j++){
        unsigned a=at_plane(y,W,H,(int)(2*x)-1,(int)(2*row+j));
        unsigned b=at_plane(y,W,H,(int)(2*x),(int)(2*row+j));
        unsigned c=at_plane(y,W,H,(int)(2*x)+1,(int)(2*row+j));
        h[j]=(a+2*b+c+2)/4;
    }
    return (h[0]+h[1]+1)/2;
}
static void check_native_grid(pl_gpu gpu,pl_dispatch dp,pl_tex actual,bool chroma,
    const uint16_t bl[YUV],const uint16_t el[YUV],const struct ambient_dovi_metadata *m)
{
    unsigned w=chroma?W/2:W,h=chroma?H/2:H;
    assert(actual&&actual->params.w==(int)w&&actual->params.h==(int)h);
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    pl_tex rb=pl_tex_create(gpu,pl_tex_params(.w=w,.h=h,.format=fmt,.renderable=true,.host_readable=true));
    assert(rb);
    const struct pl_shader_desc d={.desc={.name="grid_check",.type=PL_DESC_SAMPLED_TEX},.binding={.object=actual}};
    pl_shader sh=pl_dispatch_begin(dp);assert(sh);
    assert(pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
        .descriptors=&d,.num_descriptors=1,.body="color=texelFetch(grid_check,ivec2(gl_FragCoord.xy),0);",
    }));
    assert(pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=rb)));
    float *out=malloc((size_t)w*h*4*sizeof(*out));assert(out);
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=rb,.ptr=out)));
    unsigned max=0,exact=0;
    for(unsigned i=0;i<w*h;i++){
        unsigned x=i%w,y=i/w,ci=chroma?i:(y/2)*(W/2)+x/2;
        unsigned b[3]={chroma?native420_ref_luma(bl,x,y):bl[i],bl[N+ci],bl[N+C+ci]};
        for(unsigned k=0;k<(chroma?2u:1u);k++){
            unsigned c=chroma?k+1:0;
            unsigned want=oracle(m,b,el[chroma?N+k*C+i:i],c);
            unsigned got=(unsigned)lroundf(out[4*i+k]*65535.f);
            unsigned err=(unsigned)abs((int)got-(int)want);
            if(err>max)max=err;
            exact+=err==0;
            if(err>1)fprintf(stderr,"native420 %s pixel %u component %u got %u expected %u\n",chroma?"UV":"Y",i,c,got,want);
        }
    }
    fprintf(stderr,"native420 %s: %u samples, %u exact, max error %u\n",chroma?"UV":"Y",w*h*(chroma?2u:1u),exact,max);
    assert(max==0);
    free(out);pl_tex_destroy(gpu,&rb);
}
static void check_reference_filter(pl_gpu gpu,pl_log log,pl_dispatch dp,struct ambient_fel_planes *planes,
    const uint16_t source[YUV],const uint16_t bl_frame[YUV],const struct ambient_dovi_metadata *m,
    const struct pl_dovi_metadata *reshape,const struct ambient_fel_nlq *nlq)
{
    const struct spatial_case g={"Annex B.3",32,32,2,2,0,0,0,0};
    uint16_t raw[YUV],expanded[YUV];crop_el(source,raw,&g);
    const uint16_t *data[]={raw,raw+1024,raw+1280};
    const size_t pitches[]={64,32,32};
    assert(ambient_fel_planes_upload(gpu,planes,32,32,data,pitches,0,0));
    planes->mapped=true;planes->x=(struct ambient_fel_axis_map){.5f,0};planes->y=planes->x;
    struct ambient_fel_planes view={0};
    assert(ambient_fel_reference_el(gpu,dp,planes,&view));
    assert(view.x.ratio==1.f&&view.y.ratio==1.f);
    for(unsigned c=0;c<3;c++){
        unsigned w=c?16:32;
        uint16_t *expected=expanded+(c?N+(c-1)*C:0);
        reference_expand(data[c],w,w,c==0,expected);
        assert_filter_pixels(gpu,dp,view.tex[c],expected);
    }
    // A second frame uses impulse, alternating extrema and constant
    // patterns, exercising overshoot clipping and both filter phases.
    for(unsigned i=0;i<1536;i++)raw[i]=(i%7==0)?1023:(i%3==0?0:512);
    assert(ambient_fel_planes_upload(gpu,planes,32,32,data,pitches,0,0));
    planes->mapped=true;planes->x=(struct ambient_fel_axis_map){.5f,0};planes->y=planes->x;
    assert(ambient_fel_reference_el(gpu,dp,planes,&view));
    for(unsigned c=0;c<3;c++){
        unsigned w=c?16:32;
        uint16_t *expected=expanded+(c?N+(c-1)*C:0);
        reference_expand(data[c],w,w,c==0,expected);
        assert_filter_pixels(gpu,dp,view.tex[c],expected);
    }
    fprintf(stderr,"Annex B.3 EL reference: 12288 exact 16-bit output samples, two independent frames\n");

    // Exercise the filtered codes all the way through NLQ and prediction.
    // Keep the 16-bit Annex B overshoot in the oracle (no 10-bit clamp).
    const struct spatial_case identity={"expanded",64,64,1,1,0,0,0,0};
    float input[N*4],pixels[N*4];
    unsigned expected[N][3],over=0;
    for(unsigned i=0;i<YUV;i++)over+=expanded[i]>1023;
    for(unsigned i=0;i<N;i++){
        unsigned x=i%W,y=i/W;
        unsigned bl[3]={bl_frame[i],bl_frame[N+(y/2)*(W/2)+x/2],
            bl_frame[N+C+(y/2)*(W/2)+x/2]};
        for(unsigned c=0;c<3;c++){
            input[4*i+c]=bl[c]/1023.f;
            expected[i][c]=oracle(m,bl,aligned_el(expanded,&identity,x,y,c,m->nlq_offset[c],65535),c);
        }
        input[4*i+3]=1.f;
    }
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    pl_tex bl=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=input));
    pl_tex out=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.renderable=true,.host_readable=true));
    assert(bl&&out);
    assert(ambient_fel_compose_domain(gpu,dp,bl,NULL,out,reshape,nlq,false,false,&view,true));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=out,.ptr=pixels)));
    unsigned max=0;
    for(unsigned i=0;i<N;i++)for(unsigned c=0;c<3;c++){
        unsigned code=clip((wide)floor(pixels[4*i+c]*4096.0+0.5),4095);
        unsigned err=(unsigned)abs((int)code-(int)expected[i][c]);
        if(err>max)max=err;
    }
    fprintf(stderr,"Annex B.3 + NLQ reference: %u components, max error %u, %u overshoot codes\n",3u*N,max,over);
    assert(max<=1);
    pl_tex_destroy(gpu,&out);pl_tex_destroy(gpu,&bl);

    // Check the actual render entry point uses the signalled filter and
    // rejects contradictory geometry. The packed BL order is Cr/Y/Cb.
    for(unsigned i=0;i<N;i++){
        float y=input[4*i],cb=input[4*i+1],cr=input[4*i+2];
        input[4*i]=cr;input[4*i+1]=y;input[4*i+2]=cb;
    }
    bl=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=input));
    assert(bl);
    struct ambient_fel_picture *pic=calloc(1,sizeof(*pic)+1536*sizeof(uint16_t));assert(pic);
    pic->rpu=*m;pic->rpu.bl_spatial_resampling=0;pic->rpu.el_spatial_resampling=1;
    pic->width=pic->height=pic->coded_width=pic->coded_height=32;
    pic->pts=1;pic->sample_count=1536;memcpy(pic->samples,raw,1536*sizeof(uint16_t));
    struct ambient_fel_render render={0};
    struct pl_frame frame={.num_planes=1};
    frame.planes[0].texture=bl;
    pic->rpu.el_spatial_resampling=0;
    assert(!ambient_fel_render_picture_ex(gpu,log,&render,&frame,pic,1,0,0,64,64));
    pic->rpu.el_spatial_resampling=1;
    assert(ambient_fel_render_picture_ex(gpu,log,&render,&frame,pic,1,0,0,64,64));
    assert(render.planes.expanded[0]&&render.planes.expanded[0]->params.w==64);
    assert(frame.planes[0].texture==render.output);
    check_native_grid(gpu,dp,render.native420.y,false,bl_frame,expanded,m);
    check_native_grid(gpu,dp,render.native420.uv,true,bl_frame,expanded,m);
    // Force luma-dependent MMR even when the external RPU uses only
    // polynomial chroma curves. A strong cross-component term catches a
    // regression to point-sampled luma, including both image edges.
    for(unsigned c=1;c<3;c++){
        struct ambient_dovi_curve *q=&pic->rpu.curves[c];
        memset(q,0,sizeof(*q));q->num_pivots=2;q->pivots[1]=1023;
        q->mapping[0]=1;q->mmr_order[0]=1;
        q->mmr_coef[0][0][0]=(INT64_C(1)<<m->coef_log2_denom)/2;
        q->mmr_coef[0][0][c]=(INT64_C(1)<<m->coef_log2_denom)/4;
    }
    frame.planes[0].texture=bl;
    assert(ambient_fel_render_picture_ex(gpu,log,&render,&frame,pic,1,0,0,64,64));
    check_native_grid(gpu,dp,render.native420.uv,true,bl_frame,expanded,&pic->rpu);
    pl_gpu_finish(gpu);ambient_fel_render_destroy(gpu,&render);
    pl_tex_destroy(gpu,&bl);free(pic);
}
static void check_geometry(const struct spatial_case *g,struct ambient_fel_planes *planes)
{
    // Geometry derived independently: choose coded EL=64x64 and coded
    // BL=64 or 128 per axis. The EL has zero visible origin; BL starts
    // at its matched coded offset. The visible bounds are checked in
    // the same production routine used by ambient_fel_render_picture_ex.
    assert(ambient_fel_axis_mapping(W,g->ew,g->ox*g->sx,0,64*g->sx,64,&planes->x));
    assert(ambient_fel_axis_mapping(H,g->eh,g->oy*g->sy,0,64*g->sy,64,&planes->y));
    assert(planes->x.ratio==1.f/g->sx&&planes->x.offset==g->ox);
    assert(planes->y.ratio==1.f/g->sy&&planes->y.offset==g->oy);
    planes->mapped=true;
}
static void read_frame(const char *path,uint16_t p[YUV])
{
    FILE *f=fopen(path,"rb");assert(f);
    assert(fread(p,sizeof(*p),YUV,f)==YUV&&fgetc(f)==EOF&&!ferror(f));
    fclose(f);
    for(unsigned i=0;i<YUV;i++)assert(p[i]<=1023);
}
int main(int argc,char **argv)
{
    assert(argc==2||argc==4);
    bool decoded=argc==4;
    uint16_t bl_frame[YUV],el_frame[YUV];
    if(decoded){read_frame(argv[2],bl_frame);read_frame(argv[3],el_frame);}
    struct ambient_dovi_metadata m={0};
    FILE *f=fopen(argv[1],"rb");assert(f);
    assert(fread(&m,sizeof(m),1,f)==1&&fgetc(f)==EOF&&!ferror(f));fclose(f);
    assert(m.bl_bit_depth==10&&m.el_bit_depth==10&&m.vdr_bit_depth==12&&m.coef_log2_denom<=32);
    struct pl_dovi_metadata reshape;
    struct ambient_fel_nlq nlq;
    assert(ambient_fel_metadata(&m,&reshape,&nlq));
    pl_log log=pl_log_create(PL_API_VER,&(struct pl_log_params){.log_cb=pl_log_color,.log_level=PL_LOG_WARN});
    pl_vulkan vk=pl_vulkan_create(log,&(struct pl_vulkan_params){.allow_software=true,.queue_count=1,
        .instance_params=pl_vk_inst_params(.debug=true,.extensions=(const char *const[]){"VK_EXT_debug_utils"},.num_extensions=1)});
    assert(vk);pl_gpu gpu=vk->gpu;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    const struct spatial_case cases[]={
        {"identity centered",64,64,1,1,0,0,0,0},
        {"identity left/top",64,64,1,1,0,0,-1,-1},
        {"identity right/bottom",64,64,1,1,0,0,1,1},
        {"half XY centered",32,32,2,2,0,0,0,0},
        {"half XY asymmetric siting",32,32,2,2,0,0,-1,1},
        {"half X only",32,64,2,1,0,0,1,-1},
        {"half Y only",64,32,1,2,0,0,-1,1},
        {"half XY odd extents, crop phase",35,33,2,2,2,1,0,0},
        {"half XY odd extents, shifted chroma",35,33,2,2,2,1,1,-1},
    };
    struct ambient_fel_axis_map invalid;
    assert(!ambient_fel_axis_mapping(64,32,2,0,128,64,&invalid));
    assert(!ambient_fel_axis_mapping(64,32,1,0,128,64,&invalid));
    assert(!ambient_fel_axis_mapping(64,32,0,2,128,64,&invalid));
    bool passed=true;
    pl_dispatch dp=pl_dispatch_create(log,gpu);assert(dp);
    struct ambient_fel_planes planes={0};
    for(unsigned ci=0;ci<(decoded?sizeof(cases)/sizeof(cases[0]):1);ci++){
    const struct spatial_case *g=&cases[ci];
    uint16_t spatial_el[YUV];
    if(decoded)crop_el(el_frame,spatial_el,g);
    float bl[N*4],el[N*4],pixels[N*4];
    unsigned expected[N][3],bl_code[N][3],el_code[N][3];
    for(unsigned i=0;i<N;i++){
        unsigned b[3]={(i*73+19)%1024,(i*151+71)%1024,(i*313+37)%1024};
        for(unsigned k=0;k<3;k++){
            if(i%16==0)b[k]=m.curves[k].pivots[(i/16)%m.curves[k].num_pivots];
            if(i%16==1)b[k]=0;
            if(i%16==2)b[k]=1023;
        }
        if(decoded){
            unsigned x=i%W,y=i/W;
            b[0]=bl_frame[i];
            b[1]=bl_frame[N+(y/2)*(W/2)+x/2];
            b[2]=bl_frame[N+C+(y/2)*(W/2)+x/2];
        }
        for(unsigned k=0;k<3;k++){
            int e=(int)m.nlq_offset[k]+(int)((i*(17+2*k))%129)-64;
            if(i%8==0)e=0;
            if(i%8==1)e=1023;
            if(i%8==2)e=m.nlq_offset[k];
            unsigned ec=decoded?aligned_el(spatial_el,g,i%W,i/W,k,m.nlq_offset[k],1023):clip(e,1023);
            bl_code[i][k]=b[k];el_code[i][k]=ec;
            bl[4*i+k]=b[k]/1023.f;el[4*i+k]=ec/1023.f;
            expected[i][k]=oracle(&m,b,ec,k);
        }
        bl[4*i+3]=el[4*i+3]=1;
    }
    pl_tex b=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=bl));
    pl_tex e=NULL;
    if(decoded){
        unsigned luma=g->ew*g->eh,cw=(g->ew+1)/2,ch=(g->eh+1)/2;
        const uint16_t *data[3]={spatial_el,spatial_el+luma,spatial_el+luma+cw*ch};
        const size_t pitch[3]={2*g->ew,2*cw,2*cw};
        assert(ambient_fel_planes_upload(gpu,&planes,g->ew,g->eh,data,pitch,g->cx*.5f,g->cy*.5f));
        check_geometry(g,&planes);
    }else e=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=el));
    pl_tex out=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.renderable=true,.host_readable=true));
    assert(b&&(e||decoded)&&out);
    assert(ambient_fel_compose_domain(gpu,dp,b,e,out,&reshape,&nlq,false,false,decoded?&planes:NULL,true));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=out,.ptr=pixels)));
    unsigned max=0,exact=0,within=0,wi=0,wc=0,actual=0;
    double sum=0;
    for(unsigned i=0;i<N;i++)for(unsigned c=0;c<3;c++){
        float p=pixels[4*i+c];assert(isfinite(p)&&p>=0&&p<=1);
        unsigned got=clip((wide)floor(p*4096.0+0.5),4095);
        unsigned err=(unsigned)abs((int)got-(int)expected[i][c]);
        exact+=err==0;within+=err<=1;sum+=(double)err*err;
        if(err>max){max=err;wi=i;wc=c;actual=got;}
    }
    fprintf(stderr,"ETSI fixed-point / real RPU (%s): %u samples, exact %u, within one %u, max %u, RMS %.6f; worst pixel %u component %u BL %u EL %u actual %u expected %u\n",
        decoded?g->name:"aligned stress vectors",3u*N,exact,within,max,sqrt(sum/(3*N)),wi,wc,bl_code[wi][wc],el_code[wi][wc],actual,expected[wi][wc]);
    passed &= max<=1;
    pl_gpu_finish(gpu);
    pl_tex_destroy(gpu,&out);pl_tex_destroy(gpu,&e);pl_tex_destroy(gpu,&b);
    }
    if(decoded)check_reference_filter(gpu,log,dp,&planes,el_frame,bl_frame,&m,&reshape,&nlq);
    ambient_fel_planes_destroy(gpu,&planes);pl_dispatch_destroy(&dp);
    pl_vulkan_destroy(&vk);pl_log_destroy(&log);
    // Keep the original one-code threshold separately for every case.
    return passed?0:1;
}
