/* Exercise the exact pinned FFmpeg dovi_split filter, not an imitation. */
#include <libavcodec/bsf.h>
#include <libavcodec/packet.h>
#include <libavutil/opt.h>
#include <assert.h>
#include <string.h>
#include <stdio.h>
static void split(const char *mode,const uint8_t *expected,size_t expected_size)
{
    const AVBitStreamFilter *filter=av_bsf_get_by_name("dovi_split");
    assert(filter);
    AVBSFContext *ctx=NULL;
    assert(av_bsf_alloc(filter,&ctx)>=0);
    ctx->par_in->codec_id=AV_CODEC_ID_HEVC;
    ctx->par_in->codec_type=AVMEDIA_TYPE_VIDEO;
    ctx->par_in->width=3840;ctx->par_in->height=2160;
    ctx->time_base_in=(AVRational){1,90000};
    assert(av_opt_set(ctx->priv_data,"mode",mode,0)>=0);
    assert(av_bsf_init(ctx)>=0);
    // Synthetic Annex B NALs: ordinary BL, wrapped EL, separate RPU.
    const uint8_t input[]={0,0,0,1,0x02,0x01,0x80,
        0,0,0,1,0x7e,0x01,0x02,0x01,0x90,
        0,0,0,1,0x7c,0x01,0xa0};
    AVPacket *pkt=av_packet_alloc();
    assert(pkt&&av_new_packet(pkt,sizeof(input))>=0);
    memcpy(pkt->data,input,sizeof(input));
    pkt->pts=90000;pkt->dts=87000;pkt->duration=3000;
    assert(av_bsf_send_packet(ctx,pkt)>=0);
    assert(av_bsf_receive_packet(ctx,pkt)>=0);
    assert(pkt->pts==90000&&pkt->dts==87000&&pkt->duration==3000);
    assert((size_t)pkt->size==expected_size);
    assert(memcmp(pkt->data,expected,expected_size)==0);
    av_packet_free(&pkt);av_bsf_free(&ctx);
}
int main(void)
{
    const uint8_t bl[]={0,0,0,1,0x02,0x01,0x80};
    const uint8_t el[]={0,0,0,1,0x02,0x01,0x90};
    const uint8_t bl_rpu[]={0,0,0,1,0x02,0x01,0x80,0,0,0,1,0x7c,0x01,0xa0};
    split("bl",bl,sizeof(bl));
    split("el",el,sizeof(el));
    split("bl_rpu",bl_rpu,sizeof(bl_rpu));
    puts("Pinned FFmpeg BL/EL splitter preserves packet timing");
}
