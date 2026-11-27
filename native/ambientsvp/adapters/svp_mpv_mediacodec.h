/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Included at the end of the pinned MPV Vulkan hwdec implementation.
 * Explicit interop "mediacodec-svp-vulkan" supplies the actual decoder
 * Surface and translates decoded AVMediaCodecBuffer frames to AVVkFrame
 * before the video-filter graph. The existing Vulkan mapper presents
 * those output frames using FFmpeg timeline semaphores.
 */
#ifndef SVP_MPV_MEDIACODEC_H
#define SVP_MPV_MEDIACODEC_H
#include <dlfcn.h>
#include <stdlib.h>
#include <time.h>
#include <math.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/mediacodec.h>
#include <libavutil/hwcontext_mediacodec.h>
#include <libavutil/pixdesc.h>
#include <libavutil/mathematics.h>
#include "misc/jni.h"
#include "ambientsvp_android_vulkan.h"

#define MC_SLOTS 4
struct svp_mc_job {
    AmbientSvpAhbJob *job;
    AVFrame *frame;
    VkImageView view;
};
struct svp_mc_state {
    void *lib;
    AmbientSvpMediaReader *reader;
    AmbientSvpAhbImporter *importer;
    AVBufferRef *vkref,*frames;
    AVHWDeviceContext *avdev;
    AVVulkanDeviceContext *vkdev;
    VkQueue queue;
    uint32_t family;
    unsigned width,height;
    jobject surface;
    JavaVM *vm;
    AmbientSvpMediaReader *(*reader_create)(uint32_t,uint32_t);
    void (*reader_destroy)(AmbientSvpMediaReader *);
    jobject (*reader_surface)(const AmbientSvpMediaReader *,JNIEnv *);
    int (*acquire)(const AmbientSvpMediaReader *,AmbientSvpMediaFrame **);
    int (*frame_info)(const AmbientSvpMediaFrame *,AmbientSvpMediaInfo *);
    void (*discard)(AmbientSvpMediaFrame *);
    AmbientSvpAhbImporter *(*importer_create)(VkInstance,VkPhysicalDevice,VkDevice,
        PFN_vkGetInstanceProcAddr,VkQueue,uint32_t,const char *const *,uint32_t,uint32_t);
    void (*importer_destroy)(AmbientSvpAhbImporter *);
    int (*submit)(const AmbientSvpAhbImporter *,AmbientSvpMediaFrame **,uint32_t,
        const AmbientSvpAhbDestination *,AmbientSvpAhbJob **);
    int (*release)(AmbientSvpAhbJob *,VkFence);
    PFN_vkCreateImageView create_view;
    PFN_vkDestroyImageView destroy_view;
    struct svp_mc_job jobs[MC_SLOTS];
};
struct svp_mc_driver {
    // Must stay first: reuse the existing MPV Vulkan device/mapper.
    struct vulkan_hw_priv vk;
    struct mp_hwdec_ctx mc;
};

static unsigned svp_mc_reap(struct svp_mc_state *s)
{
    unsigned n=0;
    for (unsigned i=0;i<MC_SLOTS;i++) {
        struct svp_mc_job *j=&s->jobs[i];
        if (!j->job) continue;
        if (s->release(j->job,VK_NULL_HANDLE)==1) {
            s->destroy_view(s->vkdev->act_dev,j->view,NULL);
            av_frame_free(&j->frame);
            *j=(struct svp_mc_job){0};
        } else n++;
    }
    return n;
}
static void svp_mc_sleep(void)
{
    struct timespec delay={.tv_nsec=1000000};
    nanosleep(&delay,NULL);
}
static void svp_mc_free_state(struct svp_mc_state *s)
{
    if (!s) return;
    if (s->importer) {
        for (int i=0;i<3000 && svp_mc_reap(s);i++) svp_mc_sleep();
        if (svp_mc_reap(s)) {
            // Keep the entire device/frame/reader/library graph alive on a
            // stuck driver; never free native resources still in flight.
            return;
        }
        s->importer_destroy(s->importer);
    }
    av_buffer_unref(&s->frames);
    if (s->surface && s->vm) {
        JNIEnv *env=NULL;
        bool detach=false;
        if ((*s->vm)->GetEnv(s->vm,(void **)&env,JNI_VERSION_1_6)==JNI_EDETACHED) {
            if ((*s->vm)->AttachCurrentThread(s->vm,&env,NULL)==JNI_OK) detach=true;
        }
        if (env) (*env)->DeleteGlobalRef(env,s->surface);
        if (detach) (*s->vm)->DetachCurrentThread(s->vm);
    }
    if (s->reader) s->reader_destroy(s->reader);
    av_buffer_unref(&s->vkref);
    if (s->lib) dlclose(s->lib);
    free(s);
}
static void svp_mc_device_free(AVHWDeviceContext *ctx)
{
    svp_mc_free_state(ctx->user_opaque);
}

static bool svp_mc_features(const AVVulkanDeviceContext *v)
{
    bool ycbcr=false,timeline=false;
    for (const VkBaseInStructure *p=v->device_features.pNext;p;p=p->pNext) {
        switch (p->sType) {
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES:
            ycbcr|=((const VkPhysicalDeviceVulkan11Features *)p)->samplerYcbcrConversion;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES:
            ycbcr|=((const VkPhysicalDeviceSamplerYcbcrConversionFeatures *)p)->samplerYcbcrConversion;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES:
            timeline|=((const VkPhysicalDeviceVulkan12Features *)p)->timelineSemaphore;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES:
            timeline|=((const VkPhysicalDeviceTimelineSemaphoreFeatures *)p)->timelineSemaphore;break;
        default: break;
        }
    }
    return ycbcr&&timeline;
}

static int svp_mc_init(struct ra_hwdec *hw)
{
    struct svp_mc_driver *p=hw->priv;
    if (!ra_vk_ctx_get(hw->ra_ctx) || vulkan_init(hw)<0 || !p->vk.hwctx.av_device_ref)
        return -1;
    struct svp_mc_state *s=calloc(1,sizeof(*s));
    if (!s) return -1;
    s->lib=dlopen("libambientsvp.so",RTLD_NOW|RTLD_LOCAL);
    if (!s->lib) goto fail;
#define LOAD(member,symbol) s->member=dlsym(s->lib,"ambient_svp_" #symbol); if(!s->member) goto fail
    LOAD(reader_create,mediacodec_reader_create); LOAD(reader_destroy,mediacodec_reader_destroy);
    LOAD(reader_surface,mediacodec_reader_surface); LOAD(acquire,mediacodec_acquire);
    LOAD(frame_info,mediacodec_frame_info); LOAD(discard,mediacodec_frame_discard);
    LOAD(importer_create,ahb_importer_create); LOAD(importer_destroy,ahb_importer_destroy);
    LOAD(submit,ahb_submit_into); LOAD(release,ahb_release);
#undef LOAD
    s->vkref=av_buffer_ref(p->vk.hwctx.av_device_ref);
    if (!s->vkref) goto fail;
    s->avdev=(AVHWDeviceContext *)s->vkref->data;
    s->vkdev=s->avdev->hwctx;
    if (!s->vkdev || !svp_mc_features(s->vkdev) ||
        (s->vkdev->queue_flags&VK_DEVICE_QUEUE_CREATE_PROTECTED_BIT)) goto fail;
    bool found=false;
    for (int i=0;i<s->vkdev->nb_qf && i<64;i++) {
        AVVulkanDeviceQueueFamily q=s->vkdev->qf[i];
        if (q.idx>=0 && q.num>0 && (q.flags&VK_QUEUE_COMPUTE_BIT)) {
            s->family=q.idx;found=true;break;
        }
    }
    if (!found) goto fail;
    PFN_vkGetDeviceProcAddr get=(PFN_vkGetDeviceProcAddr)s->vkdev->get_proc_addr(s->vkdev->inst,"vkGetDeviceProcAddr");
    if (!get) goto fail;
    PFN_vkGetDeviceQueue2 get_queue=(PFN_vkGetDeviceQueue2)get(s->vkdev->act_dev,"vkGetDeviceQueue2");
    s->create_view=(PFN_vkCreateImageView)get(s->vkdev->act_dev,"vkCreateImageView");
    s->destroy_view=(PFN_vkDestroyImageView)get(s->vkdev->act_dev,"vkDestroyImageView");
    if (!get_queue || !s->create_view || !s->destroy_view) goto fail;
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
    if (!s->vkdev->lock_queue || !s->vkdev->unlock_queue) goto fail;
#pragma GCC diagnostic pop
    VkDeviceQueueInfo2 qi={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_INFO_2,.flags=s->vkdev->queue_flags,
        .queueFamilyIndex=s->family,.queueIndex=0};
    get_queue(s->vkdev->act_dev,&qi,&s->queue);
    s->importer=s->importer_create(s->vkdev->inst,s->vkdev->phys_dev,s->vkdev->act_dev,
        s->vkdev->get_proc_addr,s->queue,s->family,s->vkdev->enabled_dev_extensions,
        s->vkdev->nb_enabled_dev_extensions,1);
    if (!s->importer) goto fail;
    // PRIVATE reader dimensions are placeholders until MediaCodec connects.
    s->reader=s->reader_create(64,64);
    if (!s->reader) goto fail;
    JNIEnv *env=MP_JNI_GET_ENV(hw);
    if (!env || (*env)->GetJavaVM(env,&s->vm)!=JNI_OK) goto fail;
    jobject local=s->reader_surface(s->reader,env);
    if (!local) goto fail;
    s->surface=(*env)->NewGlobalRef(env,local);
    (*env)->DeleteLocalRef(env,local);
    if (!s->surface) goto fail;
    AVBufferRef *ref=av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_MEDIACODEC);
    if (!ref) goto fail;
    AVHWDeviceContext *mc=(AVHWDeviceContext *)ref->data;
    ((AVMediaCodecDeviceContext *)mc->hwctx)->surface=s->surface;
    mc->user_opaque=s;mc->free=svp_mc_device_free;
    if (av_hwdevice_ctx_init(ref)<0) {
        av_buffer_unref(&ref);return -1; // free callback owns s
    }
    p->mc=(struct mp_hwdec_ctx){.driver_name=hw->driver->name,.av_device_ref=ref,
        .hw_imgfmt=IMGFMT_MEDIACODEC};
    hwdec_devices_add(hw->devs,&p->mc);
    MP_INFO(hw,"SVP Vulkan MediaCodec Surface selected\n");
    return 0;
fail:
    svp_mc_free_state(s);return -1;
}
static void svp_mc_uninit(struct ra_hwdec *hw)
{
    struct svp_mc_driver *p=hw->priv;
    hwdec_devices_remove(hw->devs,&p->mc);
    av_buffer_unref(&p->mc.av_device_ref);
    vulkan_uninit(hw);
}
static bool svp_mc_pool(struct svp_mc_state *s,unsigned w,unsigned h)
{
    if (s->frames && s->width==w && s->height==h) return true;
    AVBufferRef *ref=av_hwframe_ctx_alloc(s->vkref);
    if (!ref) return false;
    AVHWFramesContext *fc=(AVHWFramesContext *)ref->data;
    fc->format=AV_PIX_FMT_VULKAN;fc->sw_format=AV_PIX_FMT_RGBA;
    fc->width=w;fc->height=h;
    AVVulkanFramesContext *v=fc->hwctx;
    v->usage=VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
    v->flags=AV_VK_FRAME_FLAG_DISABLE_MULTIPLANE;v->nb_layers=1;
    if (av_hwframe_ctx_init(ref)<0 || !v->lock_frame || !v->unlock_frame) {
        av_buffer_unref(&ref);return false;
    }
    av_buffer_unref(&s->frames);
    s->frames=ref;s->width=w;s->height=h;
    return true;
}

/* Called from vd_lavc BEFORE wrapping the decoded frame as mp_image.
 * Return 0 if this decoder device is unrelated, 1 on replacement, -1
 * to let normal MPV hwdec fallback restart from queued packets. Once
 * released to the new Surface, the AVMediaCodecBuffer is never reused. */
int mp_svp_mediacodec_convert(AVBufferRef *device,AVCodecContext *avctx,AVFrame *src,AVRational tb);
int mp_svp_mediacodec_convert(AVBufferRef *device,AVCodecContext *avctx,AVFrame *src,AVRational tb)
{
    if (!device || src->format!=AV_PIX_FMT_MEDIACODEC) return 0;
    AVHWDeviceContext *mc=(AVHWDeviceContext *)device->data;
    if (mc->type!=AV_HWDEVICE_TYPE_MEDIACODEC || mc->free!=svp_mc_device_free) return 0;
    struct svp_mc_state *s=mc->user_opaque;
    // Only explicit 8-bit SDR stream metadata. No HDR reinterpretation.
    const AVPixFmtDescriptor *desc=av_pix_fmt_desc_get(avctx->sw_pix_fmt);
    if (!desc || desc->nb_components<3 || desc->comp[0].depth!=8 ||
        desc->log2_chroma_w!=1 || desc->log2_chroma_h!=1 ||
        src->color_trc==AVCOL_TRC_UNSPECIFIED || src->color_trc==AVCOL_TRC_SMPTE2084 ||
        src->color_trc==AVCOL_TRC_ARIB_STD_B67 || src->pts==AV_NOPTS_VALUE ||
        src->pts<0 || !src->data[3] || src->width<32 || src->height<32 ||
        (src->width|src->height)&1 || (uint64_t)src->width*src->height>1920u*1080u ||
        (src->flags&AV_FRAME_FLAG_INTERLACED) ||
        av_frame_get_side_data(src,AV_FRAME_DATA_DOVI_METADATA)) return -1;
    // Reserve a slot and prepare the output pool before consuming decoder
    // output. A bounded wait is only for backpressure, not a per-frame wait.
    unsigned slot=MC_SLOTS;
    for (int i=0;i<100 && slot==MC_SLOTS;i++) {
        svp_mc_reap(s);
        for (unsigned k=0;k<MC_SLOTS;k++) if (!s->jobs[k].job) {slot=k;break;}
        if (slot==MC_SLOTS) svp_mc_sleep();
    }
    if (slot==MC_SLOTS || !svp_mc_pool(s,src->width,src->height)) return -1;
    AVFrame *out=av_frame_alloc(),*retained=NULL;
    VkImageView view=VK_NULL_HANDLE;
    AmbientSvpMediaFrame *frame=NULL;
    AmbientSvpAhbJob *job=NULL;
    if (!out || av_hwframe_get_buffer(s->frames,out,0)<0 || av_frame_copy_props(out,src)<0) goto fail;
    AVHWFramesContext *fc=(AVHWFramesContext *)out->hw_frames_ctx->data;
    AVVulkanFramesContext *v=fc->hwctx;
    AVVkFrame *vkf=(AVVkFrame *)out->data[0];
    if (!vkf || !vkf->img[0] || vkf->img[1] || v->format[0]!=VK_FORMAT_R8G8B8A8_UNORM) goto fail;
    VkImageViewCreateInfo vi={.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,.image=vkf->img[0],
        .viewType=VK_IMAGE_VIEW_TYPE_2D,.format=VK_FORMAT_R8G8B8A8_UNORM,
        .subresourceRange={.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT,.levelCount=1,.layerCount=1}};
    if (s->create_view(s->vkdev->act_dev,&vi,NULL,&view)!=VK_SUCCESS) goto fail;
    retained=av_frame_clone(out);
    if (!retained) goto fail;
    if (av_mediacodec_release_buffer((AVMediaCodecBuffer *)src->data[3],1)<0) goto fail;
    int64_t expected=av_rescale_q(src->pts,tb,(AVRational){1,1000000000});
    AmbientSvpMediaInfo info;
    for (int i=0;i<200;i++) {
        int got=s->acquire(s->reader,&frame);
        if (got<0) goto fail;
        if (!got) {svp_mc_sleep();continue;}
        if (s->frame_info(frame,&info)<0) goto fail;
        if (info.timestamp_ns<expected-1000) {
            s->discard(frame);frame=NULL;continue;
        }
        if (llabs(info.timestamp_ns-expected)>1000 || info.width!=s->width || info.height!=s->height) goto fail;
        break;
    }
    if (!frame) goto fail;
    v->lock_frame(fc,vkf);
    AmbientSvpAhbDestination dest={vkf->img[0],view,vkf->sem[0],vkf->sem_value[0],vkf->layout[0],
        vkf->queue_family[0],s->width,s->height};
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
    s->vkdev->lock_queue(s->avdev,s->family,0);
    int rc=s->submit(s->importer,&frame,1,&dest,&job);
    s->vkdev->unlock_queue(s->avdev,s->family,0);
#pragma GCC diagnostic pop
    if (!rc) {
        vkf->layout[0]=VK_IMAGE_LAYOUT_GENERAL;
        vkf->access[0]=VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
        vkf->sem_value[0]++;
    }
    v->unlock_frame(fc,vkf);
    if (rc) goto fail;
    s->jobs[slot]=(struct svp_mc_job){job,retained,view};
    out->colorspace=AVCOL_SPC_RGB;out->color_range=AVCOL_RANGE_JPEG;
    out->chroma_location=AVCHROMA_LOC_UNSPECIFIED;
    out->crop_left=out->crop_top=out->crop_right=out->crop_bottom=0;
    av_frame_unref(src);
    av_frame_move_ref(src,out);
    av_frame_free(&out);
    return 1;
fail:
    if (frame) s->discard(frame);
    if (view) s->destroy_view(s->vkdev->act_dev,view,NULL);
    av_frame_free(&retained);av_frame_free(&out);
    return -1;
}

const struct ra_hwdec_driver ra_hwdec_mediacodec_svp_vulkan={
    .name="mediacodec-svp-vulkan",
    .priv_size=sizeof(struct svp_mc_driver),
    .imgfmts={IMGFMT_MEDIACODEC,IMGFMT_VULKAN,0},
    .device_type=AV_HWDEVICE_TYPE_MEDIACODEC,
    .explicit_only=true,
    .init=svp_mc_init,.uninit=svp_mc_uninit,
    .mapper=&(const struct ra_hwdec_mapper_driver){
        .priv_size=sizeof(struct vulkan_mapper_priv),
        .init=mapper_init,.uninit=mapper_uninit,.map=mapper_map,.unmap=mapper_unmap,
        .begin_access=mapper_begin_access,.end_access=mapper_end_access,
    },
};
#endif
