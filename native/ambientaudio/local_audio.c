#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <libavcodec/avcodec.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>

/* One context per decoder worker. JNI callers serialize access and recreate on seek. */
typedef struct Output {
    struct Output *next;
    uint8_t *data;
    int size;
    int64_t pts;
} Output;
typedef struct {
    AVCodecContext *decoder, *encoder;
    SwrContext *swr;
    AVAudioFifo *fifo;
    AVChannelLayout layout, input_layout;
    enum AVSampleFormat input_format;
    int input_rate, channels, ac3, queued_bytes;
    int64_t base_us, samples, read_pts;
    Output *head, *tail;
} Audio;
#define JNI(name) Java_com_ambient_tv_ui_player_audio_LocalAudioBridge_##name
static Audio *ctx(jlong h) { return (Audio *)(intptr_t)h; }
static void close_audio(Audio *a) {
    if (!a) return;
    while (a->head) { Output *o=a->head; a->head=o->next; av_free(o->data); av_free(o); }
    avcodec_free_context(&a->decoder); avcodec_free_context(&a->encoder);
    swr_free(&a->swr); av_audio_fifo_free(a->fifo);
    av_channel_layout_uninit(&a->layout); av_channel_layout_uninit(&a->input_layout);
    av_free(a);
}
static int enqueue(Audio *a, const uint8_t *data, int size, int64_t pts) {
    if (size < 0 || size > 4*1024*1024 - a->queued_bytes) return AVERROR(ENOMEM);
    Output *o=av_mallocz(sizeof(*o));
    if (!o) return AVERROR(ENOMEM);
    o->data=av_memdup(data,size);
    if (!o->data) { av_free(o); return AVERROR(ENOMEM); }
    o->size=size; o->pts=pts;
    if (a->tail) a->tail->next=o; else a->head=o;
    a->tail=o; a->queued_bytes+=size;
    return 0;
}
static int drain_encoder(Audio *a) {
    AVPacket *p=av_packet_alloc();
    if (!p) return AVERROR(ENOMEM);
    int r;
    while ((r=avcodec_receive_packet(a->encoder,p))>=0) {
        int64_t time=a->base_us+av_rescale_q(p->pts+a->encoder->initial_padding,
            a->encoder->time_base,(AVRational){1,1000000});
        r=enqueue(a,p->data,p->size,time); av_packet_unref(p);
        if (r<0) break;
    }
    av_packet_free(&p);
    return r==AVERROR(EAGAIN)||r==AVERROR_EOF ? 0:r;
}
static int encode_fifo(Audio *a, int final) {
    int r=0, count=a->encoder->frame_size;
    while (av_audio_fifo_size(a->fifo)>=count || (final && av_audio_fifo_size(a->fifo)>0)) {
        AVFrame *f=av_frame_alloc(); if (!f) return AVERROR(ENOMEM);
        f->format=a->encoder->sample_fmt; f->sample_rate=48000; f->nb_samples=count;
        av_channel_layout_copy(&f->ch_layout,&a->layout);
        r=av_frame_get_buffer(f,0);
        if (r>=0) {
            av_samples_set_silence(f->data,0,count,a->channels,f->format);
            int n=FFMIN(count,av_audio_fifo_size(a->fifo));
            r=av_audio_fifo_read(a->fifo,(void **)f->data,n);
            f->pts=a->samples; a->samples+=count;
            if (r>=0) r=avcodec_send_frame(a->encoder,f);
        }
        av_frame_free(&f);
        if (r<0) return r;
        if ((r=drain_encoder(a))<0) return r;
    }
    return r;
}
static int convert(Audio *a, AVFrame *input) {
    int r;
    if (input && a->swr && (a->input_rate!=input->sample_rate ||
        a->input_format!=input->format || av_channel_layout_compare(&a->input_layout,&input->ch_layout))) {
        /* A format change requires a new resampler; never reuse a stale channel matrix. */
        swr_free(&a->swr); av_channel_layout_uninit(&a->input_layout);
    }
    if (!a->swr) {
        if (!input) return 0;
        a->input_rate=input->sample_rate; a->input_format=input->format;
        av_channel_layout_copy(&a->input_layout,&input->ch_layout);
        r=swr_alloc_set_opts2(&a->swr,&a->layout,a->ac3?AV_SAMPLE_FMT_FLTP:AV_SAMPLE_FMT_S16,
            48000,&input->ch_layout,input->format,input->sample_rate,0,NULL);
        if (r<0 || (r=swr_init(a->swr))<0) return r;
    }
    int capacity=swr_get_out_samples(a->swr,input?input->nb_samples:0);
    if (capacity<=0) return 0;
    uint8_t **data=NULL; int linesize;
    r=av_samples_alloc_array_and_samples(&data,&linesize,a->channels,capacity,
        a->ac3?AV_SAMPLE_FMT_FLTP:AV_SAMPLE_FMT_S16,0);
    if (r<0) return r;
    int n=swr_convert(a->swr,data,capacity,input?(const uint8_t **)input->extended_data:NULL,input?input->nb_samples:0);
    r=n;
    if (n>0) {
        if (a->ac3) {
            r=av_audio_fifo_write(a->fifo,(void **)data,n);
            if (r>=0) r=encode_fifo(a,0);
        } else {
            r=enqueue(a,data[0],n*a->channels*2,a->base_us+av_rescale(a->samples,1000000,48000));
            a->samples+=n;
        }
    }
    av_freep(&data[0]); av_freep(&data);
    return r<0?r:0;
}
JNIEXPORT jboolean JNICALL JNI(supports)(JNIEnv *env,jclass cls,jstring name) {
    const char *s=(*env)->GetStringUTFChars(env,name,NULL);
    if (!s) return JNI_FALSE;
    int ok=avcodec_find_decoder_by_name(s)!=NULL && avcodec_find_encoder_by_name("ac3")!=NULL;
    (*env)->ReleaseStringUTFChars(env,name,s); return ok;
}
JNIEXPORT jlong JNICALL JNI(create)(JNIEnv *env,jclass cls,jstring name,jbyteArray extra,jint channels,jboolean ac3) {
    if (channels!=2 && channels!=6 && channels!=8) return 0;
    const char *s=(*env)->GetStringUTFChars(env,name,NULL); if (!s) return 0;
    const AVCodec *codec=avcodec_find_decoder_by_name(s);
    (*env)->ReleaseStringUTFChars(env,name,s); if (!codec) return 0;
    Audio *a=av_mallocz(sizeof(*a)); if (!a) return 0;
    a->channels=channels; a->ac3=ac3; a->base_us=AV_NOPTS_VALUE;
    av_channel_layout_default(&a->layout,channels);
    a->decoder=avcodec_alloc_context3(codec); if (!a->decoder) goto fail;
    a->decoder->pkt_timebase=(AVRational){1,1000000};
    a->decoder->thread_count=1;
    int size=extra?(*env)->GetArrayLength(env,extra):0;
    if (size>0) {
        a->decoder->extradata=av_mallocz(size+AV_INPUT_BUFFER_PADDING_SIZE);
        if (!a->decoder->extradata) goto fail;
        a->decoder->extradata_size=size;
        (*env)->GetByteArrayRegion(env,extra,0,size,(jbyte *)a->decoder->extradata);
    }
    if (avcodec_open2(a->decoder,codec,NULL)<0) goto fail;
    if (ac3) {
        const AVCodec *enc=avcodec_find_encoder_by_name("ac3"); if (!enc) goto fail;
        a->encoder=avcodec_alloc_context3(enc); if (!a->encoder) goto fail;
        a->encoder->sample_fmt=AV_SAMPLE_FMT_FLTP; a->encoder->sample_rate=48000;
        a->encoder->time_base=(AVRational){1,48000}; a->encoder->bit_rate=640000;
        av_channel_layout_copy(&a->encoder->ch_layout,&a->layout);
        if (avcodec_open2(a->encoder,enc,NULL)<0) goto fail;
        a->fifo=av_audio_fifo_alloc(AV_SAMPLE_FMT_FLTP,channels,1536);
        if (!a->fifo) goto fail;
    }
    return (jlong)(intptr_t)a;
fail: close_audio(a); return 0;
}
JNIEXPORT jint JNICALL JNI(send)(JNIEnv *env,jclass cls,jlong handle,jobject buffer,jint size,jlong time) {
    Audio *a=ctx(handle); if (!a) return AVERROR(EINVAL);
    AVPacket *p=NULL; int r;
    if (buffer) {
        void *data=(*env)->GetDirectBufferAddress(env,buffer);
        if (!data || size<0 || size>16*1024*1024 || size>(*env)->GetDirectBufferCapacity(env,buffer)) return AVERROR(EINVAL);
        p=av_packet_alloc(); if (!p) return AVERROR(ENOMEM);
        if ((r=av_new_packet(p,size))<0) { av_packet_free(&p); return r; }
        memcpy(p->data,data,size); p->pts=time;
    }
    r=avcodec_send_packet(a->decoder,p); av_packet_free(&p); if (r<0) return r;
    AVFrame *f=av_frame_alloc(); if (!f) return AVERROR(ENOMEM);
    while ((r=avcodec_receive_frame(a->decoder,f))>=0) {
        if (a->base_us==AV_NOPTS_VALUE) a->base_us=f->pts==AV_NOPTS_VALUE?time:f->pts;
        r=convert(a,f); av_frame_unref(f); if (r<0) break;
    }
    av_frame_free(&f);
    if (r!=AVERROR(EAGAIN) && r!=AVERROR_EOF && r<0) return r;
    if (!buffer) {
        if ((r=convert(a,NULL))<0) return r;
        if (a->ac3) {
            if ((r=encode_fifo(a,1))<0) return r;
            if ((r=avcodec_send_frame(a->encoder,NULL))<0) return r;
            return drain_encoder(a);
        }
    }
    return 0;
}
JNIEXPORT jbyteArray JNICALL JNI(receive)(JNIEnv *env,jclass cls,jlong handle) {
    Audio *a=ctx(handle); if (!a || !a->head) return NULL;
    Output *o=a->head;
    jbyteArray result=(*env)->NewByteArray(env,o->size); if (!result) return NULL;
    (*env)->SetByteArrayRegion(env,result,0,o->size,(jbyte *)o->data);
    a->read_pts=o->pts; a->head=o->next; if (!a->head) a->tail=NULL;
    a->queued_bytes-=o->size; av_free(o->data); av_free(o); return result;
}
JNIEXPORT jlong JNICALL JNI(timestamp)(JNIEnv *env,jclass cls,jlong handle) { Audio *a=ctx(handle); return a ? a->read_pts : 0; }
JNIEXPORT void JNICALL JNI(destroy)(JNIEnv *env,jclass cls,jlong handle) { close_audio(ctx(handle)); }
