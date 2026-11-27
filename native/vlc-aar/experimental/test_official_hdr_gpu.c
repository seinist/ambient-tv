/* External, rights-cleared reference frames; do not generate expected pixels
 * from the implementation under test. Native prepasses produce PQ BT.2020 RGB.
 * Input/expected frame layout: little-endian float32 triples, row-major. */
#include "hdr10plus_spatial.h"
#include "dovi_fel_render.h"
#include <libplacebo/vulkan.h>
#include <libplacebo/shaders/sampling.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <errno.h>

static void *read_exact(const char *path,size_t size)
{
    FILE *f=fopen(path,"rb");assert(f);
    void *p=malloc(size);assert(p);
    assert(fread(p,1,size,f)==size && fgetc(f)==EOF && !ferror(f));
    fclose(f);return p;
}
static void *read_metadata(const char *path,size_t *size,size_t cap)
{
    FILE *f=fopen(path,"rb");assert(f);
    assert(fseek(f,0,SEEK_END)==0);
    long n=ftell(f);assert(n>0 && (unsigned long)n<=cap);
    fclose(f);*size=(size_t)n;return read_exact(path,*size);
}
static unsigned dim(const char *s)
{
    char *end;errno=0;unsigned long v=strtoul(s,&end,10);
    assert(!errno && end!=s && !*end && v>0 && v<=8192);
    return (unsigned)v;
}
static double number(const char *s)
{
    char *end;errno=0;double v=strtod(s,&end);
    assert(!errno && end!=s && !*end && isfinite(v));return v;
}
int main(int argc,char **argv)
{
    /* hdr: w h input metadata expected max-error min-target max-target mastering
     * fel: w h packed-input RPU expected max-error el-w el-h EL-planar
     * These tools validate a characterized target and zero-origin visible frames.
     * More complex crops/chroma siting must be supplied by a future fixture schema. */
    assert(argc==11);
    bool hdr=!strcmp(argv[1],"hdr10plus"),fel=!strcmp(argv[1],"fel");
    assert(hdr||fel);
    unsigned w=dim(argv[2]),h=dim(argv[3]);
    size_t count=(size_t)w*h;
    assert(count<=UINT32_C(16777216));
    double tolerance=number(argv[7]);assert(tolerance>0 && tolerance<=0.01);
    float *rgb=read_exact(argv[4],count*3*sizeof(float));
    float *expected=read_exact(argv[6],count*3*sizeof(float));
    float *rgba=malloc(count*4*sizeof(float));assert(rgba);
    for(size_t i=0;i<count;i++){
        for(unsigned j=0;j<3;j++){
            float v=rgb[3*i+j],e=expected[3*i+j];
            assert(isfinite(v)&&v>=0&&v<=1&&isfinite(e)&&e>=0&&e<=1);
            rgba[4*i+j]=v;
        }
        rgba[4*i+3]=1;
    }
    free(rgb);
    pl_log log=pl_log_create(PL_API_VER,&(struct pl_log_params){.log_cb=pl_log_color,.log_level=PL_LOG_WARN});
    pl_vulkan vk=pl_vulkan_create(log,&(struct pl_vulkan_params){.allow_software=true,.queue_count=1});
    assert(vk);pl_gpu gpu=vk->gpu;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|PL_FMT_CAP_HOST_READABLE);
    assert(fmt);
    pl_tex input=pl_tex_create(gpu,pl_tex_params(.w=w,.h=h,.format=fmt,.sampleable=true,.initial_data=rgba));
    pl_tex output=pl_tex_create(gpu,pl_tex_params(.w=w,.h=h,.format=fmt,.renderable=true,.host_readable=true));
    assert(input&&output);
    struct pl_frame img={.num_planes=1,.repr=pl_color_repr_rgb,.color=pl_color_space_hdr10,.crop={0,0,w,h}};
    img.planes[0]=(struct pl_plane){.texture=input,.components=3,.component_mapping={0,1,2,-1}};
    size_t meta_size;
    void *meta=read_metadata(argv[5],&meta_size,hdr?1024:65536);
    struct ambient_hdrplus_spatial spatial={0};
    struct ambient_fel_render render={0};
    if(hdr){
        struct ambient_hdrplus metadata;
        assert(ambient_hdrplus_parse(meta,meta_size,&metadata));
        assert(metadata.metadata_constraints_ok);
        struct pl_frame target={.color=pl_color_space_hdr10};
        target.color.hdr.min_luma=number(argv[8]);target.color.hdr.max_luma=number(argv[9]);
        img.color.hdr.max_luma=number(argv[10]);
        assert(img.color.hdr.max_luma>0&&img.color.hdr.max_luma<=10000);
        assert(ambient_hdrplus_spatial_render(gpu,log,&spatial,&img,&target,&metadata));
    }else{
#ifdef AMBIENT_OFFICIAL_FEL
        unsigned ew=dim(argv[8]),eh=dim(argv[9]);
        assert(ew<=1920&&eh<=1088);
        size_t samples=(size_t)ew*eh+2*(size_t)((ew+1)/2)*((eh+1)/2);
        struct ambient_fel_picture *pic=calloc(1,sizeof(*pic)+samples*sizeof(uint16_t));assert(pic);
        struct ambient_dovi_parser *parser=av_ambient_dovi_alloc(7);assert(parser);
        assert(av_ambient_dovi_parse(parser,meta,meta_size,&pic->rpu)==0);
        av_ambient_dovi_free(parser);
        assert(pic->rpu.residual_present);
        uint16_t *el=read_exact(argv[10],samples*sizeof(uint16_t));
        for(size_t i=0;i<samples;i++)assert(el[i]<=1023);
        memcpy(pic->samples,el,samples*sizeof(uint16_t));free(el);
        pic->width=ew;pic->height=eh;pic->sample_count=samples;pic->pts=1;
        assert(ambient_fel_render_picture(gpu,log,&render,&img,pic,1,0,0));
        free(pic);
#else
        fprintf(stderr,"FEL mode requires the pinned patched FFmpeg parser\n");return 2;
#endif
    }
    free(meta);
    pl_dispatch dp=pl_dispatch_create(log,gpu);assert(dp);
    pl_shader sh=pl_dispatch_begin(dp);
    pl_shader_sample_direct(sh,pl_sample_src(.tex=img.planes[0].texture));
    assert(pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=output)));
    assert(pl_tex_download(gpu,pl_tex_transfer_params(.tex=output,.ptr=rgba)));
    double max=0,sum=0;
    for(size_t i=0;i<count;i++)for(unsigned j=0;j<3;j++){
        double d=fabs((double)rgba[4*i+j]-expected[3*i+j]);
        assert(isfinite(d));max=fmax(max,d);sum+=d*d;
    }
    printf("{\"pixels\":%zu,\"max_pq_error\":%.12g,\"rms_pq_error\":%.12g,\"limit\":%.12g}\n",
        count,max,sqrt(sum/(3*count)),tolerance);
    pl_gpu_finish(gpu);pl_dispatch_destroy(&dp);
    ambient_hdrplus_spatial_destroy(gpu,&spatial);ambient_fel_render_destroy(gpu,&render);
    pl_tex_destroy(gpu,&input);pl_tex_destroy(gpu,&output);
    pl_vulkan_destroy(&vk);pl_log_destroy(&log);free(rgba);free(expected);
    return max<=tolerance?0:1;
}
