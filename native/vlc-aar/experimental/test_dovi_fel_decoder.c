#include "dovi_fel_decoder.h"
#include "dovi_fel_api.h"
#include "dovi_rpu_au.h"
#include "dovi_fel_dual.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

/* Wrap a real, independently encoded Main10 Annex B picture as EL NALs.
 * This is a decoder/ownership fixture, not a licensed Dolby Vision sample. */
static AVPacket *wrap(const uint8_t *input,size_t size,int64_t pts)
{
    assert(size>0 && size<1024*1024);
    AVPacket *p=av_packet_alloc();
    assert(p&&av_new_packet(p,(int)(size*3+16))>=0);
    size_t cursor=0,out=0;
    while(cursor<size) {
        size_t prefix,next_prefix;
        size_t start=ambient_rpu_start_code(input,size,cursor,&prefix);
        assert(prefix);
        size_t payload=start+prefix;
        size_t end=ambient_rpu_start_code(input,size,payload,&next_prefix);
        assert(end-payload>=2);
        const uint8_t outer[]={0,0,0,1,0x7e,0x01};
        memcpy(p->data+out,outer,sizeof(outer));out+=sizeof(outer);
        memcpy(p->data+out,input+payload,end-payload);out+=end-payload;
        cursor=end;
    }
    av_shrink_packet(p,(int)out);
    p->pts=pts;p->dts=pts;p->duration=1;
    return p;
}

/* Simulate the MP4 sample merger using a real Main10 EL elementary stream
 * and then the packetizer's length-prefix to Annex B conversion. The BL and
 * RPU below are synthetic framing markers, not valid Dolby Vision content;
 * this test exercises actual EL decode and sample ownership. */
static AVPacket *dual(const uint8_t *input,size_t size,int64_t pts)
{
    uint8_t *el=malloc(size*2+32);assert(el);
    size_t off=0,out=0;
    while(off<size){
        size_t prefix,next;
        size_t start=ambient_rpu_start_code(input,size,off,&prefix);
        assert(prefix);
        size_t data=start+prefix,end=ambient_rpu_start_code(input,size,data,&next);
        uint32_t len=(uint32_t)(end-data);assert(len>=3);
        for(unsigned j=0;j<4;j++)el[out++]=(uint8_t)(len>>(8*(3-j)));
        memcpy(el+out,input+data,len);out+=len;off=end;
    }
    const uint8_t rpu[]={0,0,0,3,0x7c,1,0x80};
    memcpy(el+out,rpu,sizeof(rpu));out+=sizeof(rpu);
    const uint8_t bl[]={0,0,0,3,0x26,1,0x80};
    size_t n;
    assert(ambient_fel_dual_sample(bl,sizeof(bl),4,el,out,4,NULL,0,false,NULL,0,&n));
    AVPacket *p=av_packet_alloc();
    assert(p&&av_new_packet(p,(int)n)>=0);
    assert(ambient_fel_dual_sample(bl,sizeof(bl),4,el,out,4,NULL,0,false,p->data,n,&n));
    size_t converted;
    assert(ambient_fel_to_annexb(p->data,n,4,NULL,0,&converted)&&converted==n);
    AVPacket *annex=av_packet_alloc();
    assert(annex&&av_new_packet(annex,(int)converted)>=0);
    assert(ambient_fel_to_annexb(p->data,n,4,annex->data,converted,&converted));
    av_packet_free(&p);p=annex;
    free(el);p->pts=pts;p->dts=pts;p->duration=1;
    return p;
}

/* Real reordered HEVC GOP with exact container packet timestamps.
 * Compare owned EL codewords against an independently decoded reference
 * in presentation order, including both early and late discard. */
static void test_reorder(const char *stream,const char *timestamps,const char *reference)
{
    enum { FRAMES=8, WORDS=64*64+2*32*32 };
    int64_t pts[FRAMES],dts[FRAMES];
    unsigned display[FRAMES];
    FILE *ts=fopen(timestamps,"r");assert(ts);
    bool reordered=false;
    for(unsigned i=0;i<FRAMES;i++){
        long long p,d;
        assert(fscanf(ts,"%lld,%lld",&p,&d)==2);
        pts[i]=p+1000000;dts[i]=d+1000000;
        assert(pts[i]>0&&dts[i]>0);
        if(i){assert(dts[i]>dts[i-1]);if(pts[i]<pts[i-1])reordered=true;}
        display[i]=i;
    }
    fclose(ts);assert(reordered);
    for(unsigned i=1;i<FRAMES;i++){
        unsigned v=display[i],j=i;
        while(j&&pts[display[j-1]]>pts[v]){display[j]=display[j-1];j--;}
        display[j]=v;
    }
    for(unsigned i=1;i<FRAMES;i++)assert(pts[display[i]]>pts[display[i-1]]);
    FILE *f=fopen(stream,"rb");assert(f);
    assert(fseek(f,0,SEEK_END)==0);
    long size=ftell(f);assert(size>0&&size<1024*1024);rewind(f);
    uint8_t *input=malloc((size_t)size);assert(input);
    assert(fread(input,1,(size_t)size,f)==(size_t)size);fclose(f);
    size_t starts[FRAMES+1]={0},cursor=0;
    unsigned auds=0;
    while(cursor<(size_t)size){
        size_t prefix,next;
        size_t start=ambient_rpu_start_code(input,(size_t)size,cursor,&prefix);
        assert(prefix);
        size_t payload=start+prefix,end=ambient_rpu_start_code(input,(size_t)size,payload,&next);
        assert(end-payload>=2);
        if(((input[payload]>>1)&63)==35){
            assert(auds<FRAMES);
            if(auds)starts[auds]=start;
            auds++;
        }
        cursor=end;
    }
    assert(auds==FRAMES);
    starts[FRAMES]=(size_t)size;
    f=fopen(reference,"rb");assert(f);
    uint16_t *expected=malloc(FRAMES*WORDS*sizeof(*expected));assert(expected);
    assert(fread(expected,sizeof(*expected),FRAMES*WORDS,f)==FRAMES*WORDS);
    assert(fgetc(f)==EOF);fclose(f);
    struct ambient_fel_session *session=av_ambient_fel_open();assert(session);
    for(unsigned pass=0;pass<2;pass++){
        int64_t epoch=(int64_t)pass*10000000;
        // A hardware BL drop may happen before the matching EL is decoded.
        av_ambient_fel_discard(session,pts[display[2]]+epoch);
        for(unsigned i=0;i<FRAMES;i++){
            AVPacket *p=wrap(input+starts[i],starts[i+1]-starts[i],pts[i]+epoch);
            assert(av_ambient_fel_submit(session,p->data,p->size,pts[i]+epoch,dts[i]+epoch)==0);
            av_packet_free(&p);
        }
        assert(av_ambient_fel_drain(session)==0);
        // A second drop happens after EL output is already available.
        av_ambient_fel_discard(session,pts[display[5]]+epoch);
        for(unsigned i=0;i<FRAMES;i++){
            int64_t key=pts[display[i]]+epoch;
            struct ambient_fel_picture *p=av_ambient_fel_take(session,key);
            if(i==2||i==5){assert(!p);continue;}
            assert(p&&p->pts==key&&p->width==64&&p->height==64);
            assert(!memcmp(p->samples,expected+(size_t)i*WORDS,WORDS*sizeof(*expected)));
            free(p);
            assert(!av_ambient_fel_take(session,key));
        }
        av_ambient_fel_flush(session);
        for(unsigned i=0;i<FRAMES;i++)assert(!av_ambient_fel_take(session,pts[i]+epoch));
    }
    av_ambient_fel_close(session);
    free(expected);free(input);
    puts("Reordered Main10 GOP matched independent reference, exact PTS, early/late drop and two epochs");
}

int main(int argc,char **argv)
{
    assert(argc==2||argc==5);
    FILE *f=fopen(argv[1],"rb");assert(f);
    assert(fseek(f,0,SEEK_END)==0);
    long n=ftell(f);assert(n>0&&n<1024*1024);
    rewind(f);
    uint8_t *input=malloc((size_t)n);assert(input);
    assert(fread(input,1,(size_t)n,f)==(size_t)n);fclose(f);
    struct ambient_fel_decoder s={0};
    assert(ambient_fel_decoder_open(&s,(AVRational){1,1000})==0);
    for(unsigned iteration=0;iteration<2;iteration++){
        int64_t pts=iteration?170:90;
        AVPacket *p=wrap(input,(size_t)n,pts);
        int ret=ambient_fel_decoder_submit(&s,p);
        if(ret<0)fprintf(stderr,"EL submit: %d\n",ret);
        assert(ret==0);
        // Caller packet remains owned and unchanged.
        assert(p->pts==pts&&p->size>0);
        av_packet_free(&p);
        assert(ambient_fel_decoder_drain(&s)==0);
        assert(!ambient_fel_decoder_take(&s,pts+1));
        assert(!ambient_fel_decoder_take(&s,AV_NOPTS_VALUE));
        AVFrame *el=ambient_fel_decoder_take(&s,pts);
        assert(el&&el->pts==pts&&el->width==64&&el->height==64);
        assert(el->format==AV_PIX_FMT_YUV420P10LE);
        assert(!ambient_fel_decoder_take(&s,pts));
        unsigned y=(unsigned)el->data[0][0]|((unsigned)el->data[0][1]<<8);
        assert(y<=1023);
        // Caller retains the frame even after flushing decoder and queue.
        ambient_fel_decoder_flush(&s);
        assert(el->data[0]&&el->width==64);
        av_frame_free(&el);
        assert(!ambient_fel_decoder_take(&s,pts));
    }
    ambient_fel_decoder_close(&s);
    ambient_fel_decoder_close(&s);
    // Exercise the same opaque static-link API called from VLC.
    struct ambient_fel_session *session=av_ambient_fel_open();
    assert(session);
    assert(!av_ambient_fel_may_receive(NULL));
    assert(av_ambient_fel_may_receive(session));
    AVPacket *wrapped=wrap(input,(size_t)n,90000);
    assert(av_ambient_fel_submit(session,wrapped->data,wrapped->size,90000,87000)==0);
    assert(av_ambient_fel_drain(session)==0);
    assert(!av_ambient_fel_take(session,90001));
    assert(!av_ambient_fel_may_receive(session));
    struct ambient_fel_picture *picture=av_ambient_fel_take(session,90000);
    assert(picture&&picture->pts==90000&&picture->width==64&&picture->height==64);
    assert(!av_ambient_fel_take(session,90000));
    for(unsigned i=0;i<64*64+2*32*32;i++) assert(picture->samples[i]<=1023);
    av_ambient_fel_flush(session);
    assert(av_ambient_fel_may_receive(session));
    AVPacket *paired=dual(input,(size_t)n,190000);
    assert(av_ambient_fel_submit(session,paired->data,paired->size,190000,187000)==0);
    assert(av_ambient_fel_drain(session)==0);
    struct ambient_fel_picture *dual_picture=av_ambient_fel_take(session,190000);
    assert(dual_picture&&dual_picture->width==64&&dual_picture->height==64);
    assert(!memcmp(picture->samples,dual_picture->samples,
        (64*64+2*32*32)*sizeof(uint16_t)));
    free(dual_picture);av_packet_free(&paired);
    av_ambient_fel_flush(session);
    assert(av_ambient_fel_may_receive(session));
    // Overflow of early discard markers is a terminal error, never a wait.
    for(int64_t i=1;i<=65;i++)av_ambient_fel_discard(session,i);
    assert(!av_ambient_fel_may_receive(session));
    av_ambient_fel_flush(session);
    assert(av_ambient_fel_may_receive(session));
    // Owned snapshot survives the decoder session.
    av_ambient_fel_close(session);
    assert(picture->samples[0]<=1023);
    free(picture);
    av_packet_free(&wrapped);
    free(input);
    puts("Decoded real HEVC EL codewords; exact PTS, drain and flush verified");
    if(argc==5)test_reorder(argv[2],argv[3],argv[4]);
}
