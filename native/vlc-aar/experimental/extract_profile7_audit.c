/* Read a real unmodified Profile 7 access unit and decode the two layers
 * independently, using the pinned FFmpeg dovi_split filter and original
 * hvcC/hvcE configuration. Never manufacture or overwrite an RPU flag. */
#include "profile7_audit_frame.h"
#include "dovi_rpu_au.h"
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/opt.h>
#include <libavutil/pixfmt.h>
#include <assert.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>

enum { MAX_PENDING=64, MAX_PACKETS=512 };
struct layer {
    AVBSFContext *bsf;
    AVCodecContext *dec;
    AVFrame *frames[MAX_PENDING];
    unsigned count;
};
struct snapshot { int64_t pts; struct ambient_dovi_metadata m; };
static void init_layer(struct layer *l,const AVStream *st,const char *mode)
{
    assert(av_bsf_alloc(av_bsf_get_by_name("dovi_split"),&l->bsf)>=0);
    assert(avcodec_parameters_copy(l->bsf->par_in,st->codecpar)>=0);
    l->bsf->time_base_in=st->time_base;
    assert(av_opt_set(l->bsf->priv_data,"mode",mode,0)>=0&&av_bsf_init(l->bsf)>=0);
    const AVCodec *codec=avcodec_find_decoder(AV_CODEC_ID_HEVC);assert(codec);
    l->dec=avcodec_alloc_context3(codec);assert(l->dec);
    assert(avcodec_parameters_to_context(l->dec,l->bsf->par_out)>=0);
    l->dec->pkt_timebase=st->time_base;l->dec->thread_count=2;
    l->dec->err_recognition=AV_EF_EXPLODE;
    assert(avcodec_open2(l->dec,codec,NULL)>=0);
}
static void receive(struct layer *l)
{
    for(;;){
        AVFrame *f=av_frame_alloc();assert(f);
        int ret=avcodec_receive_frame(l->dec,f);
        if(ret==AVERROR(EAGAIN)||ret==AVERROR_EOF){av_frame_free(&f);return;}
        assert(ret>=0&&f->pts!=AV_NOPTS_VALUE&&!(f->flags&AV_FRAME_FLAG_CORRUPT));
        assert(f->format==AV_PIX_FMT_YUV420P10LE&&f->width>0&&f->height>0);
        assert(!f->crop_top&&!f->crop_left&&!f->crop_bottom&&!f->crop_right);
        assert(l->count<MAX_PENDING);
        for(unsigned j=0;j<l->count;j++)assert(l->frames[j]->pts!=f->pts);
        l->frames[l->count++]=f;
    }
}
static void feed(struct layer *l,const AVPacket *packet)
{
    AVPacket *p=packet?av_packet_clone(packet):NULL;
    assert(!packet||p);
    int ret=av_bsf_send_packet(l->bsf,p);
    av_packet_free(&p);assert(ret>=0);
    p=av_packet_alloc();assert(p);
    for(;;){
        ret=av_bsf_receive_packet(l->bsf,p);
        if(ret==AVERROR(EAGAIN)||ret==AVERROR_EOF)break;
        assert(ret>=0&&avcodec_send_packet(l->dec,p)>=0);
        av_packet_unref(p);receive(l);
    }
    av_packet_free(&p);
    if(!packet){assert(avcodec_send_packet(l->dec,NULL)>=0);receive(l);}
}
static void release(struct layer *l)
{
    for(unsigned i=0;i<l->count;i++)av_frame_free(&l->frames[i]);
    avcodec_free_context(&l->dec);av_bsf_free(&l->bsf);
}
static void write_frame(FILE *f,const AVFrame *v)
{
    assert(v->width<=4096&&v->height<=2304);
    for(unsigned c=0;c<3;c++){
        unsigned w=c?(v->width+1)/2:v->width,h=c?(v->height+1)/2:v->height;
        assert(v->data[c]&&v->linesize[c]>=(int)(2*w));
        for(unsigned y=0;y<h;y++)assert(fwrite(v->data[c]+(size_t)y*v->linesize[c],2,w,f)==w);
    }
}
static int match(FILE *out,const struct layer *bl,const struct layer *el,
    const struct snapshot *snap,unsigned count,AVRational tb,bool require_bl,bool require_fel)
{
    for(unsigned i=0;i<bl->count;i++)for(unsigned j=0;j<el->count;j++){
        const AVFrame *b=bl->frames[i],*e=el->frames[j];if(b->pts!=e->pts)continue;
        for(unsigned k=0;k<count;k++){
            if(snap[k].pts!=b->pts)continue;
            const struct ambient_dovi_metadata *m=&snap[k].m;
            bool fel=false;
            for(unsigned c=0;c<3;c++)fel|=m->nlq_maximum[c]&&(m->nlq_slope[c]||m->nlq_threshold[c]);
            if((require_bl&&!m->bl_spatial_resampling)||(require_fel&&!fel))continue;
            struct ambient_profile7_audit_frame h={.rpu=*m,.bw=b->width,.bh=b->height,.ew=e->width,.eh=e->height,
                .pts=b->pts,.tb_num=tb.num,.tb_den=tb.den};
            assert(fwrite(&h,sizeof(h),1,out)==1);
            write_frame(out,b);write_frame(out,e);
            printf("Profile7 original matched PTS %" PRId64 " timebase %d/%d BL %ux%u EL %ux%u BL_flag %u EL_flag %u FEL %u\n",
                h.pts,tb.num,tb.den,h.bw,h.bh,h.ew,h.eh,m->bl_spatial_resampling,m->el_spatial_resampling,(unsigned)fel);
            return 1;
        }
    }
    return 0;
}
int main(int argc,char **argv)
{
    assert(argc>=3&&argc<=5);
    bool require_bl=false,require_fel=false;
    for(int i=3;i<argc;i++){
        if(!strcmp(argv[i],"--require-bl-upscale"))require_bl=true;
        else if(!strcmp(argv[i],"--require-fel"))require_fel=true;
        else abort();
    }
    AVFormatContext *fmt=NULL;
    // Local file only; no URLs, credentials or decryption.
    assert(avformat_open_input(&fmt,argv[1],NULL,NULL)>=0);
    assert(avformat_find_stream_info(fmt,NULL)>=0);
    int vi=av_find_best_stream(fmt,AVMEDIA_TYPE_VIDEO,-1,-1,NULL,0);assert(vi>=0);
    AVStream *st=fmt->streams[vi];
    assert(st->codecpar->codec_id==AV_CODEC_ID_HEVC);
    const AVPacketSideData *side=av_packet_side_data_get(st->codecpar->coded_side_data,
        st->codecpar->nb_coded_side_data,AV_PKT_DATA_DOVI_CONF);
    assert(side&&side->size>=sizeof(AVDOVIDecoderConfigurationRecord));
    const AVDOVIDecoderConfigurationRecord *cfg=(const void *)side->data;
    assert(cfg->dv_profile==7&&cfg->bl_present_flag&&cfg->el_present_flag&&cfg->rpu_present_flag);
    const uint8_t *extra=st->codecpar->extradata;
    unsigned length_size=st->codecpar->extradata_size>=23&&extra[0]==1?(extra[21]&3)+1:0;
    struct layer bl={0},el={0};
    init_layer(&bl,st,"bl");init_layer(&el,st,"el");
    struct ambient_dovi_parser *parser=av_ambient_dovi_alloc(7);assert(parser);
    struct snapshot *snaps=calloc(MAX_PENDING,sizeof(*snaps));assert(snaps);
    AVPacket *pkt=av_packet_alloc();assert(pkt);
    FILE *out=fopen(argv[2],"wb");assert(out);
    unsigned count=0,packets=0;bool found=false;
    while(packets<MAX_PACKETS&&av_read_frame(fmt,pkt)>=0){
        if(pkt->stream_index!=vi){av_packet_unref(pkt);continue;}
        packets++;
        struct ambient_rpu_au au;
        enum ambient_rpu_status status=ambient_rpu_find(pkt->data,pkt->size,length_size,&au);
        assert(status!=AMBIENT_RPU_INVALID);
        if(status==AMBIENT_RPU_FOUND){
            assert(pkt->pts!=AV_NOPTS_VALUE&&count<MAX_PENDING);
            uint8_t rbsp[65536+64]={0};size_t n=0;
            assert(ambient_rpu_unescape(pkt->data+au.payload_start,au.payload_size,rbsp,sizeof(rbsp),&n));
            for(unsigned j=0;j<count;j++)assert(snaps[j].pts!=pkt->pts);
            snaps[count].pts=pkt->pts;
            assert(av_ambient_dovi_parse(parser,rbsp,n,&snaps[count].m)==0);count++;
        }
        feed(&bl,pkt);feed(&el,pkt);av_packet_unref(pkt);
        if(match(out,&bl,&el,snaps,count,st->time_base,require_bl,require_fel)){found=true;break;}
        // This is a bounded first-match audit, not a whole-film decoder.
        if(count>=MAX_PENDING-8||bl.count>=MAX_PENDING-8||el.count>=MAX_PENDING-8)break;
    }
    if(!found){
        feed(&bl,NULL);feed(&el,NULL);
        found=match(out,&bl,&el,snaps,count,st->time_base,require_bl,require_fel);
    }
    assert(fclose(out)==0);
    release(&bl);release(&el);av_ambient_dovi_free(parser);free(snaps);av_packet_free(&pkt);avformat_close_input(&fmt);
    if(!found){remove(argv[2]);fprintf(stderr,"No exact matched frame satisfying original RPU flags in bounded input\n");return 1;}
    return 0;
}
