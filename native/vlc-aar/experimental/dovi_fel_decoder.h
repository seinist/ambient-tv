#pragma once
/* Experimental software enhancement-layer decoder. The caller owns the BL
 * decoder and must submit each accepted access unit exactly once, in decode
 * order. All operations are serialized by the caller. This is intentionally
 * not yet enabled in VLC: matching and picture ownership must be wired first.
 * Only combined Annex B access units with in-band EL parameter sets. */
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/error.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#define AMBIENT_FEL_QUEUE_CAPACITY 12
struct ambient_fel_decoder {
    AVBSFContext *split;
    AVCodecContext *codec;
    AVFrame *queue[AMBIENT_FEL_QUEUE_CAPACITY];
    bool failed, draining;
};

static inline void ambient_fel_decoder_close(struct ambient_fel_decoder *s)
{
    if (!s) return;
    for (unsigned i=0;i<AMBIENT_FEL_QUEUE_CAPACITY;i++)
        av_frame_free(&s->queue[i]);
    avcodec_free_context(&s->codec);
    av_bsf_free(&s->split);
    memset(s,0,sizeof(*s));
}

static inline int ambient_fel_decoder_open(struct ambient_fel_decoder *s, AVRational time_base)
{
    if (!s || s->codec || s->split || time_base.num<=0 || time_base.den<=0)
        return AVERROR(EINVAL);
    const AVCodec *hevc=avcodec_find_decoder(AV_CODEC_ID_HEVC);
    const AVBitStreamFilter *filter=av_bsf_get_by_name("dovi_split");
    if (!hevc || !filter) return AVERROR(ENOSYS);
    int ret=av_bsf_alloc(filter,&s->split);
    if (ret<0) goto fail;
    s->split->par_in->codec_type=AVMEDIA_TYPE_VIDEO;
    s->split->par_in->codec_id=AV_CODEC_ID_HEVC;
    s->split->time_base_in=time_base;
    ret=av_opt_set(s->split->priv_data,"mode","el",0);
    if (ret<0) goto fail;
    ret=av_bsf_init(s->split);
    if (ret<0) goto fail;
    s->codec=avcodec_alloc_context3(hevc);
    if (!s->codec) { ret=AVERROR(ENOMEM); goto fail; }
    s->codec->pkt_timebase=time_base;
    // Slice threading avoids an extra frame-thread reorder delay.
    s->codec->thread_count=2;
    s->codec->thread_type=FF_THREAD_SLICE;
    s->codec->max_pixels=1920*1088;
    s->codec->err_recognition=AV_EF_EXPLODE;
    ret=avcodec_open2(s->codec,hevc,NULL);
    if (ret>=0) return 0;
fail:
    ambient_fel_decoder_close(s);
    return ret;
}

/* Return owned frames by exact PTS, never by FIFO/decode order. Missing
 * frames remain missing; neither a nearest nor a stale frame is valid. */
static inline AVFrame *ambient_fel_decoder_take(struct ambient_fel_decoder *s,int64_t pts)
{
    if (!s || s->failed || pts==AV_NOPTS_VALUE) return NULL;
    for (unsigned i=0;i<AMBIENT_FEL_QUEUE_CAPACITY;i++) {
        AVFrame *f=s->queue[i];
        if (f && f->pts==pts) { s->queue[i]=NULL; return f; }
    }
    return NULL;
}

static inline int ambient_fel_decoder_receive(struct ambient_fel_decoder *s)
{
    for (;;) {
        AVFrame *f=av_frame_alloc();
        if (!f) return AVERROR(ENOMEM);
        int ret=avcodec_receive_frame(s->codec,f);
        if (ret<0) {
            av_frame_free(&f);
            return ret==AVERROR(EAGAIN)||ret==AVERROR_EOF?0:ret;
        }
        // Only software 10-bit 4:2:0 raw codewords, no range expansion.
        if (f->format!=AV_PIX_FMT_YUV420P10LE || f->pts==AV_NOPTS_VALUE ||
            f->width<=0 || f->height<=0 || f->width>1920 || f->height>1088 ||
            (f->flags&AV_FRAME_FLAG_CORRUPT)) {
            av_frame_free(&f);return AVERROR_INVALIDDATA;
        }
        unsigned slot=AMBIENT_FEL_QUEUE_CAPACITY;
        for (unsigned i=0;i<AMBIENT_FEL_QUEUE_CAPACITY;i++) {
            if (s->queue[i] && s->queue[i]->pts==f->pts) {
                av_frame_free(&f);return AVERROR_INVALIDDATA;
            }
            if (!s->queue[i] && slot==AMBIENT_FEL_QUEUE_CAPACITY) slot=i;
        }
        if (slot==AMBIENT_FEL_QUEUE_CAPACITY) {
            av_frame_free(&f);return AVERROR(ENOBUFS);
        }
        s->queue[slot]=f;
    }
}

static inline int ambient_fel_decoder_submit(struct ambient_fel_decoder *s,const AVPacket *combined)
{
    if (!s || !s->codec || !s->split || s->failed || s->draining ||
        !combined || !combined->data || combined->size<=0 ||
        combined->size>32*1024*1024)
        return AVERROR(EINVAL);
    AVPacket *pkt=av_packet_clone(combined);
    if (!pkt) { s->failed=true;return AVERROR(ENOMEM); }
    int ret=av_bsf_send_packet(s->split,pkt);
    if (ret<0) goto done;
    // dovi_split can legitimately emit no packet for BL-only config.
    while ((ret=av_bsf_receive_packet(s->split,pkt))>=0) {
        ret=avcodec_send_packet(s->codec,pkt);
        if (ret==AVERROR(EAGAIN)) {
            ret=ambient_fel_decoder_receive(s);
            if (ret>=0) ret=avcodec_send_packet(s->codec,pkt);
        }
        av_packet_unref(pkt);
        if (ret<0) goto done;
        ret=ambient_fel_decoder_receive(s);
        if (ret<0) goto done;
    }
    if (ret==AVERROR(EAGAIN)) ret=0;
done:
    av_packet_free(&pkt);
    if (ret<0) s->failed=true;
    return ret;
}

static inline int ambient_fel_decoder_drain(struct ambient_fel_decoder *s)
{
    if (!s || !s->codec || s->failed) return AVERROR(EINVAL);
    if (s->draining) return 0;
    int ret=ambient_fel_decoder_receive(s);
    if (ret>=0) ret=avcodec_send_packet(s->codec,NULL);
    if (ret>=0) ret=ambient_fel_decoder_receive(s);
    if (ret<0) s->failed=true;
    else s->draining=true;
    return ret;
}

// Flush is the only recovery after a failed submit; never reuse old EL frames.
static inline void ambient_fel_decoder_flush(struct ambient_fel_decoder *s)
{
    if (!s || !s->codec || !s->split) return;
    for (unsigned i=0;i<AMBIENT_FEL_QUEUE_CAPACITY;i++)
        av_frame_free(&s->queue[i]);
    av_bsf_flush(s->split);
    avcodec_flush_buffers(s->codec);
    s->failed=false;s->draining=false;
}
