/* Reuse the independent scalar ETSI oracle from the host regression,
 * not any of the production GLSL arithmetic. */
#define main ambient_generated_reference_main
#include "test_etsi_external_fel_gpu.c"
#undef main
#include "profile7_audit_frame.h"
#include <inttypes.h>

static size_t samples(unsigned w,unsigned h){return (size_t)w*h+2*(size_t)((w+1)/2)*((h+1)/2);}
static uint16_t *read_codes(FILE *f,unsigned w,unsigned h)
{
    size_t n=samples(w,h);uint16_t *p=malloc(n*2);assert(p);
    assert(fread(p,2,n,f)==n);
    for(size_t i=0;i<n;i++)assert(p[i]<=1023);
    return p;
}
static uint16_t *expanded_codes(const uint16_t *raw,unsigned w,unsigned h,bool scale,bool bl)
{
    size_t n=samples(w,h);
    if(!scale){uint16_t *p=malloc(n*2);assert(p);memcpy(p,raw,n*2);return p;}
    unsigned cw=(w+1)/2,ch=(h+1)/2;size_t l=(size_t)w*h,c=(size_t)cw*ch;
    uint16_t *p=malloc(samples(w*2,h*2)*2);assert(p);
    for(unsigned k=0;k<3;k++){
        size_t inoff=k?l+(k-1)*c:0,outoff=k?4*l+(k-1)*l:0;
        odd_expand(raw+inoff,k?cw:w,k?ch:h,bl,k==0,p+outoff,k?w:2*w,k?h:2*h);
    }
    return p;
}
static void check_actual(pl_gpu gpu,pl_dispatch dp,pl_tex actual,bool chroma,
    const uint16_t *bl,const uint16_t *el,unsigned w,unsigned h,const struct ambient_dovi_metadata *m)
{
    unsigned cw=(w+1)/2,ch=(h+1)/2,tw=chroma?cw:w,th=chroma?ch:h;
    size_t l=(size_t)w*h,c=(size_t)cw*ch;
    assert(actual&&actual->params.w==(int)tw&&actual->params.h==(int)th);
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);assert(fmt);
    pl_tex rb=pl_tex_create(gpu,pl_tex_params(.w=tw,.h=th,.format=fmt,.renderable=true,.host_readable=true));assert(rb);
    const struct pl_shader_desc d={.desc={.name="actual_grid",.type=PL_DESC_SAMPLED_TEX},.binding={.object=actual}};
    pl_shader sh=pl_dispatch_begin(dp);assert(sh);
    assert(pl_shader_custom(sh,&(struct pl_custom_shader){.input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
        .descriptors=&d,.num_descriptors=1,.body="color=texelFetch(actual_grid,ivec2(gl_FragCoord.xy),0);"}));
    assert(pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=rb)));
    float *pixels=malloc((size_t)tw*th*4*sizeof(*pixels));assert(pixels);
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=rb,.ptr=pixels)));
    size_t exact=0,total=(size_t)tw*th*(chroma?2:1);unsigned max=0;
    for(unsigned y=0;y<th;y++)for(unsigned x=0;x<tw;x++){
        size_t j=(size_t)y*tw+x,ci=chroma?j:(size_t)(y/2)*cw+x/2;
        unsigned yy=bl[j];
        if(chroma){
            unsigned row[2];
            for(unsigned k=0;k<2;k++){
                int xx=(int)(2*x),ry=(int)(2*y+k);
                row[k]=(at_plane(bl,w,h,xx-1,ry)+2*at_plane(bl,w,h,xx,ry)+at_plane(bl,w,h,xx+1,ry)+2)/4;
            }
            yy=(row[0]+row[1]+1)/2;
        }
        unsigned b[]={yy,bl[l+ci],bl[l+c+ci]};
        for(unsigned k=0;k<(chroma?2u:1u);k++){
            unsigned cc=chroma?k+1:0;
            unsigned want=oracle(m,b,el[chroma?l+k*c+j:j],cc);
            float p=pixels[4*j+k];assert(isfinite(p)&&p>=0&&p<=1);
            unsigned got=(unsigned)lroundf(p*65535.f),err=(unsigned)abs((int)got-(int)want);
            if(err>max){max=err;fprintf(stderr,"Actual P7 %s pixel %zu component %u got %u expected %u\n",chroma?"UV":"Y",j,cc,got,want);}
            exact+=err==0;
        }
    }
    printf("Actual P7 native %s: %zu/%zu exact, max %u LSB\n",chroma?"UV":"Y",exact,total,max);
    assert(max==0&&exact==total);
    free(pixels);pl_tex_destroy(gpu,&rb);
}
int main(int argc,char **argv)
{
    assert(argc==2);
    FILE *f=fopen(argv[1],"rb");assert(f);
    struct ambient_profile7_audit_frame h;
    assert(fread(&h,sizeof(h),1,f)==1&&h.bw&&h.bh&&h.ew&&h.eh&&h.bw<=4096&&h.bh<=2304&&h.ew<=1920&&h.eh<=1088);
    uint16_t *raw_b=read_codes(f,h.bw,h.bh),*raw_e=read_codes(f,h.ew,h.eh);
    assert(fgetc(f)==EOF&&!ferror(f));fclose(f);
    bool bs=h.rpu.bl_spatial_resampling,es=h.rpu.el_spatial_resampling;
    unsigned w=h.bw*(bs?2:1),ht=h.bh*(bs?2:1);
    assert(w==h.ew*(es?2:1)&&ht==h.eh*(es?2:1)&&w<=4096&&ht<=2304);
    uint16_t *b=expanded_codes(raw_b,h.bw,h.bh,bs,true),*e=expanded_codes(raw_e,h.ew,h.eh,es,false);
    size_t l=(size_t)h.bw*h.bh,c=(size_t)((h.bw+1)/2)*((h.bh+1)/2);
    float *packed=malloc(l*4*sizeof(*packed));assert(packed);
    for(size_t i=0;i<l;i++){
        size_t ci=(i/h.bw/2)*((h.bw+1)/2)+(i%h.bw)/2;
        packed[4*i]=raw_b[l+c+ci]/1023.f;packed[4*i+1]=raw_b[i]/1023.f;
        packed[4*i+2]=raw_b[l+ci]/1023.f;packed[4*i+3]=1.f;
    }
    pl_log log=pl_log_create(PL_API_VER,&(struct pl_log_params){.log_cb=pl_log_color,.log_level=PL_LOG_WARN});
    pl_vulkan vk=pl_vulkan_create(log,&(struct pl_vulkan_params){.allow_software=true,.queue_count=1});
    assert(vk);pl_gpu gpu=vk->gpu;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);assert(fmt);
    pl_tex bl=pl_tex_create(gpu,pl_tex_params(.w=h.bw,.h=h.bh,.format=fmt,.sampleable=true,.initial_data=packed));assert(bl);
    free(packed);
    size_t eln=samples(h.ew,h.eh);
    struct ambient_fel_picture *pic=calloc(1,sizeof(*pic)+eln*2);assert(pic);
    pic->rpu=h.rpu;pic->width=pic->coded_width=h.ew;pic->height=pic->coded_height=h.eh;
    pic->sample_count=eln;pic->pts=1;memcpy(pic->samples,raw_e,eln*2);
    struct pl_frame frame={.num_planes=1,.crop={0,0,h.bw,h.bh}};frame.planes[0].texture=bl;
    struct ambient_fel_render render={0};
    assert(ambient_fel_render_picture_ex(gpu,log,&render,&frame,pic,1,0,0,h.bw,h.bh));
    assert(render.output->params.w==(int)w&&render.output->params.h==(int)ht);
    check_actual(gpu,render.dispatch,render.native420.y,false,b,e,w,ht,&h.rpu);
    check_actual(gpu,render.dispatch,render.native420.uv,true,b,e,w,ht,&h.rpu);
    printf("Actual P7 source PTS %" PRId64 " output %ux%u: full native 4:2:0 reference PASSED\n",h.pts,w,ht);
    pl_gpu_finish(gpu);ambient_fel_render_destroy(gpu,&render);pl_tex_destroy(gpu,&bl);
    pl_vulkan_destroy(&vk);pl_log_destroy(&log);free(pic);free(raw_b);free(raw_e);free(b);free(e);
}
