#include "dovi_fel_decoder.h"
#include "dovi_rpu_au.h"
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

int main(int argc,char **argv)
{
    assert(argc==2);
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
    free(input);
    puts("Decoded real HEVC EL codewords; exact PTS, drain and flush verified");
}
