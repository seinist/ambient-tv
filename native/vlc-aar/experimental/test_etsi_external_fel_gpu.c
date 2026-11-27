/* Independent fixed-point reference for ETSI GS CCM 001 V1.1.1,
 * 5.4.2 and 5.4.3. We compare the actual GPU composition BEFORE color
 * conversion, using authentic external RPU coefficients. BL/EL input
 * samples are deterministic, already spatially aligned; resampling and
 * the Dolby display-management stage are deliberately not asserted. */
#include "dovi_fel_gpu.h"
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
/* Independent integer bilinear oracle for the implementation's centered
 * equal-resolution EL sampler, NOT the normative Dolby resampling filter.
 * Fractions at luma pixel centers are quarters, so the weights sum to 16. */
static unsigned at_chroma(const uint16_t *p,int x,int y)
{
    x=x<0?0:x>=W/2?W/2-1:x;
    y=y<0?0:y>=H/2?H/2-1:y;
    return p[y*(W/2)+x];
}
static unsigned aligned_el(const uint16_t *el,unsigned x,unsigned y,unsigned c,unsigned offset)
{
    if(!c)return el[y*W+x];
    const uint16_t *p=el+N+(c-1)*C;
    int ix=(int)x/2-(x%2==0),iy=(int)y/2-(y%2==0);
    unsigned fx=x%2?1:3,fy=y%2?1:3;
    int weighted=(int)((4-fx)*(4-fy)*at_chroma(p,ix,iy)+
        fx*(4-fy)*at_chroma(p,ix+1,iy)+(4-fx)*fy*at_chroma(p,ix,iy+1)+
        fx*fy*at_chroma(p,ix+1,iy+1));
    int delta=weighted-(int)(16*offset);
    int rounded=delta<0?-((-delta+8)/16):(delta+8)/16;
    return clip((int)offset+rounded,1023);
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
            unsigned ec=decoded?aligned_el(el_frame,i%W,i/W,k,m.nlq_offset[k]):clip(e,1023);
            bl_code[i][k]=b[k];el_code[i][k]=ec;
            bl[4*i+k]=b[k]/1023.f;el[4*i+k]=ec/1023.f;
            expected[i][k]=oracle(&m,b,ec,k);
        }
        bl[4*i+3]=el[4*i+3]=1;
    }
    pl_tex b=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=bl));
    struct ambient_fel_planes planes={0};
    pl_tex e=NULL;
    if(decoded){
        const uint16_t *data[3]={el_frame,el_frame+N,el_frame+N+C};
        const size_t pitch[3]={W*2,W,W};
        assert(ambient_fel_planes_upload(gpu,&planes,W,H,data,pitch,0,0));
    }else e=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.sampleable=true,.initial_data=el));
    pl_tex out=pl_tex_create(gpu,pl_tex_params(.w=W,.h=H,.format=fmt,.renderable=true,.host_readable=true));
    pl_dispatch dp=pl_dispatch_create(log,gpu);assert(b&&(e||decoded)&&out&&dp);
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
        decoded?"decoded 4:2:0 planes":"aligned stress vectors",3u*N,exact,within,max,sqrt(sum/(3*N)),wi,wc,bl_code[wi][wc],el_code[wi][wc],actual,expected[wi][wc]);
    pl_gpu_finish(gpu);pl_dispatch_destroy(&dp);ambient_fel_planes_destroy(gpu,&planes);
    pl_tex_destroy(gpu,&out);pl_tex_destroy(gpu,&e);pl_tex_destroy(gpu,&b);
    pl_vulkan_destroy(&vk);pl_log_destroy(&log);
    // Allow at most one 12-bit code for the float approximation, never
    // silently broaden the threshold to accommodate a failed comparison.
    return max<=1?0:1;
}
