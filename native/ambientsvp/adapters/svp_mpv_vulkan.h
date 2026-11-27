/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Opt-in MPV native Vulkan SVP transport. Uses the decoder/renderer
 * VkDevice, actual AVVkFrame image views and FFmpeg timeline semaphores.
 * No CPU pixel transfer. This is intentionally restricted to compatible
 * 8-bit SDR frames and the independently experimental resident kernels.
 * Included by vf_ambientsvp.c only with HAVE_VULKAN and libavutil 61.
 */
#ifndef SVP_MPV_VULKAN_H
#define SVP_MPV_VULKAN_H
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vulkan.h>
#include <libavutil/frame.h>
#include <libavutil/version.h>
#include "ambientsvp_vulkan.h"
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define SVP_VK_PHASES 8
#define SVP_VK_JOBS 4
#define SVP_VK_FRAMES (2 + SVP_VK_PHASES)
#define SVP_VK_IMAGES (6 + SVP_VK_PHASES)
struct svp_vk_job {
    VkCommandPool pool;
    VkFence fence;
    AmbientSvpVkPair *pair;
    AVFrame *frames[SVP_VK_FRAMES];
    VkImageView views[SVP_VK_IMAGES];
    unsigned nframes, nviews;
    bool submitted, sealed;
};
struct svp_vk_mpv {
    AmbientSvpVk *engine;
    AmbientSvpVk *(*create)(VkInstance,VkPhysicalDevice,VkDevice,PFN_vkGetInstanceProcAddr,uint32_t,uint32_t);
    void (*destroy)(AmbientSvpVk *);
    AmbientSvpVkPair *(*begin)(const AmbientSvpVk *,VkCommandBuffer,const AmbientSvpVkPicture *,const AmbientSvpVkPicture *);
    int (*render)(AmbientSvpVkPair *,float,uint32_t,VkImageView);
    int (*seal)(AmbientSvpVkPair *,VkFence);
    int (*release)(AmbientSvpVkPair *);
    int (*cancel)(AmbientSvpVkPair *);
    AVBufferRef *device_ref, *output_hwframes;
    AVHWDeviceContext *avdev;
    AVVulkanDeviceContext *vkdev;
    VkDevice device;
    VkQueue queue;
    uint32_t family, width, height;
    PFN_vkCreateCommandPool create_pool;
    PFN_vkDestroyCommandPool destroy_pool;
    PFN_vkAllocateCommandBuffers allocate_commands;
    PFN_vkBeginCommandBuffer begin_commands;
    PFN_vkEndCommandBuffer end_commands;
    PFN_vkCreateFence create_fence;
    PFN_vkDestroyFence destroy_fence;
    PFN_vkWaitForFences wait_fences;
    PFN_vkCreateImageView create_view;
    PFN_vkDestroyImageView destroy_view;
    PFN_vkCmdPipelineBarrier2 barrier;
    PFN_vkQueueSubmit submit;
    struct svp_vk_job *jobs[SVP_VK_JOBS];
    struct mp_image *previous;
    struct mp_image *outputs[SVP_VK_PHASES];
    unsigned next_output, output_count;
    double origin, source_rate, target_rate;
    uint64_t tick, input_frames, output_frames, generated_frames;
};
struct svp_vk_ref {
    AVHWFramesContext *fc;
    AVVulkanFramesContext *vfc;
    AVVkFrame *frame;
    bool write;
    unsigned count;
};

static int svp_vk_matrix(struct mp_image *img)
{
    int matrix;
    switch (img->params.repr.sys) {
    case PL_COLOR_SYSTEM_BT_601: matrix=0; break;
    case PL_COLOR_SYSTEM_BT_709: matrix=1; break;
    case PL_COLOR_SYSTEM_RGB: return img->params.repr.levels==PL_COLOR_LEVELS_FULL?3:-1;
    default: return -1;
    }
    switch (img->params.repr.levels) {
    case PL_COLOR_LEVELS_LIMITED: return matrix;
    case PL_COLOR_LEVELS_FULL: return matrix+2;
    default: return -1;
    }
}
static int svp_vk_chroma(struct mp_image *img)
{
    if (img->params.repr.sys==PL_COLOR_SYSTEM_RGB) return 0;
    switch (img->params.chroma_location) {
    case PL_CHROMA_TOP_LEFT: return 0;
    case PL_CHROMA_LEFT: return 1;
    case PL_CHROMA_CENTER: return 2;
    default: return -1;
    }
}
static bool svp_vk_eligible(struct mp_image *img)
{
    if (!img || img->imgfmt!=IMGFMT_VULKAN || !img->hwctx
        || img->w<32 || img->h<32 || (img->w|img->h)&1
        || (uint64_t)img->w*img->h>1920u*1080u
        || img->params.crop.x0 || img->params.crop.y0
        || (img->params.crop.x1 && img->params.crop.x1!=img->w)
        || (img->params.crop.y1 && img->params.crop.y1!=img->h)
        || img->fields&MP_IMGFIELD_INTERLACED || img->pts==MP_NOPTS_VALUE
        || !isfinite(img->pts) || img->enhancement_layer || img->film_grain
        || img->params.color.transfer==PL_COLOR_TRC_UNKNOWN
        || img->params.color.transfer==PL_COLOR_TRC_PQ
        || img->params.color.transfer==PL_COLOR_TRC_HLG
        || svp_vk_matrix(img)<0 || svp_vk_chroma(img)<0)
        return false;
    AVHWFramesContext *fc=(AVHWFramesContext *)img->hwctx->data;
    if (!fc || !fc->device_ctx || !fc->device_ref || !fc->hwctx
        || fc->device_ctx->type!=AV_HWDEVICE_TYPE_VULKAN
        || fc->format!=AV_PIX_FMT_VULKAN
        || (fc->sw_format!=AV_PIX_FMT_NV12 && fc->sw_format!=AV_PIX_FMT_YUV420P && fc->sw_format!=AV_PIX_FMT_RGBA)
        || fc->width<img->w || fc->height<img->h)
        return false;
    if ((fc->sw_format==AV_PIX_FMT_RGBA)!=(img->params.repr.sys==PL_COLOR_SYSTEM_RGB)) return false;
    AVVulkanFramesContext *vfc=fc->hwctx;
    return vfc->nb_layers==1 && vfc->tiling==VK_IMAGE_TILING_OPTIMAL
        && (vfc->usage&VK_IMAGE_USAGE_SAMPLED_BIT)
        && !(vfc->img_flags&VK_IMAGE_CREATE_PROTECTED_BIT)
        && vfc->lock_frame && vfc->unlock_frame;
}

static void svp_vk_job_free(struct svp_vk_mpv *s,struct svp_vk_job *j)
{
    // Only after completion/device loss, or before any successful submit.
    if (j->pool) s->destroy_pool(s->device,j->pool,NULL);
    if (j->pair && !j->submitted) s->cancel(j->pair);
    for (unsigned i=0;i<j->nviews;i++) s->destroy_view(s->device,j->views[i],NULL);
    for (unsigned i=0;i<j->nframes;i++) av_frame_free(&j->frames[i]);
    if (j->fence) s->destroy_fence(s->device,j->fence,NULL);
    free(j);
}
static unsigned svp_vk_reap(struct svp_vk_mpv *s,bool wait)
{
    unsigned outstanding=0;
    if (!s) return 0;
    if (wait) {
        VkFence fences[SVP_VK_JOBS];unsigned count=0;
        for (unsigned i=0;i<SVP_VK_JOBS;i++) if (s->jobs[i]) fences[count++]=s->jobs[i]->fence;
        if (count) s->wait_fences(s->device,count,fences,VK_TRUE,3000000000ULL);
    }
    for (unsigned i=0;i<SVP_VK_JOBS;i++) {
        struct svp_vk_job *j=s->jobs[i];
        if (!j) continue;
        // A submitted pair must be released by its own fence check.
        if (j->sealed && s->release(j->pair)==1) {
            j->pair=NULL;
            svp_vk_job_free(s,j); s->jobs[i]=NULL;
        } else outstanding++;
    }
    return outstanding;
}
static void svp_vk_reset_history(struct svp_vk_mpv *s)
{
    if (!s) return;
    mp_image_unrefp(&s->previous);
    for (unsigned i=0;i<s->output_count;i++) mp_image_unrefp(&s->outputs[i]);
    s->next_output=s->output_count=0;s->tick=0;
    svp_vk_reap(s,false);
}
static bool svp_vk_destroy_state(struct svp_vk_mpv *s)
{
    if (!s) return true;
    svp_vk_reset_history(s);
    // Never destroy an external device or free in-flight descriptors.
    // On a stuck driver the owner must retain this state and library.
    if (svp_vk_reap(s,true)) return false;
    if (s->engine) s->destroy(s->engine);
    av_buffer_unref(&s->output_hwframes);
    av_buffer_unref(&s->device_ref);
    free(s);return true;
}
static bool svp_vk_sync_features(const AVVulkanDeviceContext *ctx)
{
    bool timeline=false,sync2=false;
    for (const VkBaseInStructure *p=ctx->device_features.pNext;p;p=p->pNext) {
        switch (p->sType) {
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES:
            timeline|=((const VkPhysicalDeviceVulkan12Features *)p)->timelineSemaphore;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES:
            sync2|=((const VkPhysicalDeviceVulkan13Features *)p)->synchronization2;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES:
            timeline|=((const VkPhysicalDeviceTimelineSemaphoreFeatures *)p)->timelineSemaphore;break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES:
            sync2|=((const VkPhysicalDeviceSynchronization2Features *)p)->synchronization2;break;
        default: break;
        }
    }
    return timeline && sync2;
}
static struct svp_vk_mpv *svp_vk_new(void *library,struct mp_image *img,double source,double target)
{
    if (!library || !svp_vk_eligible(img) || !(source>0 && target>source && target/source<=SVP_VK_PHASES))
        return NULL;
    struct svp_vk_mpv *s=calloc(1,sizeof(*s));
    if (!s) return NULL;
#define LOAD_API(name) s->name=dlsym(library,"ambient_svp_vk_" #name); if(!s->name) goto fail
    LOAD_API(create); LOAD_API(destroy); LOAD_API(begin); LOAD_API(render);
    LOAD_API(seal); LOAD_API(release); LOAD_API(cancel);
#undef LOAD_API
    AVHWFramesContext *fc=(AVHWFramesContext *)img->hwctx->data;
    s->device_ref=av_buffer_ref(fc->device_ref);
    if (!s->device_ref) goto fail;
    s->avdev=fc->device_ctx;
    s->vkdev=s->avdev->hwctx;
    if (!s->vkdev || !s->vkdev->get_proc_addr || !s->vkdev->act_dev
        || (s->vkdev->queue_flags&VK_DEVICE_QUEUE_CREATE_PROTECTED_BIT)
        || !svp_vk_sync_features(s->vkdev)) goto fail;
    s->device=s->vkdev->act_dev;
    bool found=false;
    for (int i=0;i<s->vkdev->nb_qf && i<64;i++) {
        AVVulkanDeviceQueueFamily q=s->vkdev->qf[i];
        if (q.idx>=0 && q.num>0 && (q.flags&VK_QUEUE_COMPUTE_BIT)) {
            s->family=q.idx;found=true;break;
        }
    }
    if (!found) goto fail;
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
    if (!s->vkdev->lock_queue || !s->vkdev->unlock_queue) goto fail;
#pragma GCC diagnostic pop
    PFN_vkGetDeviceProcAddr get=(PFN_vkGetDeviceProcAddr)s->vkdev->get_proc_addr(s->vkdev->inst,"vkGetDeviceProcAddr");
    if (!get) goto fail;
#define LOAD_VK(member,name) s->member=(PFN_vk##name)get(s->device,"vk" #name); if(!s->member) goto fail
    LOAD_VK(create_pool,CreateCommandPool);LOAD_VK(destroy_pool,DestroyCommandPool);
    LOAD_VK(allocate_commands,AllocateCommandBuffers);LOAD_VK(begin_commands,BeginCommandBuffer);
    LOAD_VK(end_commands,EndCommandBuffer);LOAD_VK(create_fence,CreateFence);
    LOAD_VK(destroy_fence,DestroyFence);LOAD_VK(wait_fences,WaitForFences);
    LOAD_VK(create_view,CreateImageView);LOAD_VK(destroy_view,DestroyImageView);
    LOAD_VK(barrier,CmdPipelineBarrier2);LOAD_VK(submit,QueueSubmit);
#undef LOAD_VK
    PFN_vkGetDeviceQueue2 get_queue=(PFN_vkGetDeviceQueue2)get(s->device,"vkGetDeviceQueue2");
    if (!get_queue) goto fail;
    VkDeviceQueueInfo2 qi={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_INFO_2,.flags=s->vkdev->queue_flags,
        .queueFamilyIndex=s->family,.queueIndex=0};
    get_queue(s->device,&qi,&s->queue);
    if (!s->queue) goto fail;
    s->width=img->w;s->height=img->h;s->source_rate=source;s->target_rate=target;
    s->engine=s->create(s->vkdev->inst,s->vkdev->phys_dev,s->device,s->vkdev->get_proc_addr,s->width,s->height);
    if (!s->engine) goto fail;
    s->output_hwframes=av_hwframe_ctx_alloc(s->device_ref);
    if (!s->output_hwframes) goto fail;
    AVHWFramesContext *out=(AVHWFramesContext *)s->output_hwframes->data;
    out->format=AV_PIX_FMT_VULKAN;out->sw_format=AV_PIX_FMT_RGBA;
    out->width=s->width;out->height=s->height;
    AVVulkanFramesContext *vfc=out->hwctx;
    vfc->usage=VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
    vfc->flags=AV_VK_FRAME_FLAG_DISABLE_MULTIPLANE;
    vfc->nb_layers=1;
    if (av_hwframe_ctx_init(s->output_hwframes)<0 || !vfc->lock_frame || !vfc->unlock_frame) goto fail;
    return s;
fail:
    svp_vk_destroy_state(s);return NULL;
}

static bool svp_vk_matches(struct svp_vk_mpv *s,struct mp_image *img)
{
    if (!svp_vk_eligible(img) || img->w!=(int)s->width || img->h!=(int)s->height) return false;
    AVHWFramesContext *fc=(AVHWFramesContext *)img->hwctx->data;
    return fc->device_ctx==s->avdev;
}
static bool svp_vk_ref(AVFrame *frame,bool write,struct svp_vk_ref *out)
{
    if (!frame || !frame->hw_frames_ctx || !frame->data[0]) return false;
    AVHWFramesContext *fc=(AVHWFramesContext *)frame->hw_frames_ctx->data;
    AVVulkanFramesContext *vfc=fc->hwctx;
    AVVkFrame *vkf=(AVVkFrame *)frame->data[0];
    unsigned count=0;
    while(count<AV_NUM_DATA_POINTERS && vkf->img[count]) count++;
    unsigned expected=(write||fc->sw_format==AV_PIX_FMT_RGBA)?1:fc->sw_format==AV_PIX_FMT_NV12?2:3;
    if (!count || (count!=1 && count!=expected) || !vfc->lock_frame || !vfc->unlock_frame)
        return false;
    *out=(struct svp_vk_ref){fc,vfc,vkf,write,count};
    return true;
}
static VkImageView svp_vk_view(struct svp_vk_mpv *s,struct svp_vk_job *j,VkImage img,VkFormat format,VkImageAspectFlags aspect)
{
    if (j->nviews>=SVP_VK_IMAGES) return VK_NULL_HANDLE;
    VkImageView view=VK_NULL_HANDLE;
    VkImageViewCreateInfo info={.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,.image=img,
        .viewType=VK_IMAGE_VIEW_TYPE_2D,.format=format,
        .subresourceRange={.aspectMask=aspect,.levelCount=1,.layerCount=1}};
    if (s->create_view(s->device,&info,NULL,&view)!=VK_SUCCESS) return VK_NULL_HANDLE;
    j->views[j->nviews++]=view;return view;
}
static bool svp_vk_picture(struct svp_vk_mpv *s,struct svp_vk_job *j,
    struct svp_vk_ref *r,int chroma,AmbientSvpVkPicture *p)
{
    if (r->fc->sw_format==AV_PIX_FMT_RGBA) {
        if (r->count!=1 || r->vfc->format[0]!=VK_FORMAT_R8G8B8A8_UNORM) return false;
        VkImageView view=svp_vk_view(s,j,r->frame->img[0],VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_ASPECT_COLOR_BIT);
        if (!view) return false;
        *p=(AmbientSvpVkPicture){view,VK_NULL_HANDLE,VK_NULL_HANDLE,2,0};
        return true;
    }
    unsigned planes=r->fc->sw_format==AV_PIX_FMT_NV12?2:3;
    VkFormat expected_multi=planes==2?VK_FORMAT_G8_B8R8_2PLANE_420_UNORM:VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM;
    if (r->count==1 && r->vfc->format[0]!=expected_multi) return false;
    VkImageView views[3]={0};
    for (unsigned i=0;i<planes;i++) {
        VkFormat format=i==1 && planes==2?VK_FORMAT_R8G8_UNORM:VK_FORMAT_R8_UNORM;
        unsigned index=r->count==1?0:i;
        VkImageAspectFlags aspect=r->count==1?(VK_IMAGE_ASPECT_PLANE_0_BIT<<i):VK_IMAGE_ASPECT_COLOR_BIT;
        if (r->count!=1 && r->vfc->format[index]!=format) return false;
        views[i]=svp_vk_view(s,j,r->frame->img[index],format,aspect);
        if (!views[i]) return false;
    }
    *p=(AmbientSvpVkPicture){views[0],views[1],views[2],planes==2,(uint32_t)chroma};
    return true;
}

/* Record and submit actual hardware frames. All descriptors and frame refs
 * are retained by a bounded job until its fence signals. The output AVVkFrame
 * timeline semaphore is advanced before returning it to MPV's renderer. */
static bool svp_vk_interpolate(struct svp_vk_mpv *s,struct mp_image *a,struct mp_image *b,
    const double *times,const float *phases,unsigned count,struct mp_image **outputs)
{
    if (!count || count>SVP_VK_PHASES || !svp_vk_matches(s,a) || !svp_vk_matches(s,b)
        || (a->params.repr.sys==PL_COLOR_SYSTEM_RGB)!=(b->params.repr.sys==PL_COLOR_SYSTEM_RGB)
        || svp_vk_matrix(a)!=svp_vk_matrix(b) || a->params.color.transfer!=b->params.color.transfer
        || a->params.color.primaries!=b->params.color.primaries) return false;
    svp_vk_reap(s,false);
    unsigned slot=0;while(slot<SVP_VK_JOBS && s->jobs[slot]) slot++;
    if (slot==SVP_VK_JOBS) return false;
    struct svp_vk_job *j=calloc(1,sizeof(*j));
    if (!j) return false;
    struct svp_vk_ref refs[SVP_VK_FRAMES];
    unsigned order[SVP_VK_FRAMES], unique=0, locked=0;
    VkCommandBuffer cmd=VK_NULL_HANDLE;
    VkImageView dst[SVP_VK_PHASES];
    AmbientSvpVkPicture src[2];
    VkSemaphore sems[SVP_VK_IMAGES];
    uint64_t waits[SVP_VK_IMAGES],signals[SVP_VK_IMAGES];
    VkPipelineStageFlags stages[SVP_VK_IMAGES];
    VkImageMemoryBarrier2 barriers[SVP_VK_IMAGES];
    unsigned nsem=0,nbar=0;

    j->frames[j->nframes++]=mp_image_to_av_frame(a);
    j->frames[j->nframes++]=mp_image_to_av_frame(b);
    for (unsigned i=0;i<2;i++) {
        if (!svp_vk_ref(j->frames[i],false,&refs[i])
            || !svp_vk_picture(s,j,&refs[i],svp_vk_chroma(i?b:a),&src[i])) goto fail;
    }
    for (unsigned i=0;i<count;i++) {
        AVFrame *frame=av_frame_alloc();
        j->frames[j->nframes++]=frame;
        if (!frame || av_hwframe_get_buffer(s->output_hwframes,frame,0)<0
            || !svp_vk_ref(frame,true,&refs[2+i])
            || refs[2+i].vfc->format[0]!=VK_FORMAT_R8G8B8A8_UNORM) goto fail;
        dst[i]=svp_vk_view(s,j,refs[2+i].frame->img[0],VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_ASPECT_COLOR_BIT);
        if (!dst[i]) goto fail;
        outputs[i]=mp_image_from_av_frame(frame);
        if (!outputs[i]) goto fail;
        mp_image_set_size(outputs[i],s->width,s->height);
        mp_image_copy_attributes(outputs[i],a);
        outputs[i]->params.repr.sys=PL_COLOR_SYSTEM_RGB;
        outputs[i]->params.repr.levels=PL_COLOR_LEVELS_FULL;
        outputs[i]->params.repr.alpha=PL_ALPHA_NONE;
        outputs[i]->params.chroma_location=PL_CHROMA_UNKNOWN;
        outputs[i]->pts=times[i];outputs[i]->pkt_duration=1.0/s->target_rate;
        outputs[i]->nominal_fps=s->target_rate;
    }
    VkCommandPoolCreateInfo pi={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags=VK_COMMAND_POOL_CREATE_TRANSIENT_BIT,.queueFamilyIndex=s->family};
    if (s->create_pool(s->device,&pi,NULL,&j->pool)!=VK_SUCCESS) goto fail;
    VkCommandBufferAllocateInfo ci={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,.commandPool=j->pool,
        .level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
    if (s->allocate_commands(s->device,&ci,&cmd)!=VK_SUCCESS) goto fail;
    VkFenceCreateInfo fi={.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (s->create_fence(s->device,&fi,NULL,&j->fence)!=VK_SUCCESS) goto fail;
    VkCommandBufferBeginInfo bi={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    if (s->begin_commands(cmd,&bi)!=VK_SUCCESS) goto fail;
    // Lock distinct frames in stable address order, exactly around recording
    // barriers and submission. No waits for completion while holding locks.
    for (unsigned i=0;i<j->nframes;i++) {
        unsigned k=0;
        while(k<unique && (uintptr_t)refs[order[k]].frame<(uintptr_t)refs[i].frame) k++;
        if (k<unique && refs[order[k]].frame==refs[i].frame) {
            if (refs[i].write || refs[order[k]].write) goto fail;
            continue;
        }
        memmove(order+k+1,order+k,(unique-k)*sizeof(*order));order[k]=i;unique++;
    }
    for (;locked<unique;locked++) {
        struct svp_vk_ref *r=&refs[order[locked]];
        r->vfc->lock_frame(r->fc,r->frame);
    }
    for (unsigned k=0;k<unique;k++) {
        struct svp_vk_ref *r=&refs[order[k]];
        for (unsigned i=0;i<r->count;i++) {
            AVVkFrame *v=r->frame;
            if ((v->queue_family[i]!=VK_QUEUE_FAMILY_IGNORED && v->queue_family[i]!=s->family)
                || !v->sem[i] || v->sem_value[i]==UINT64_MAX
                || (!r->write && v->layout[i]==VK_IMAGE_LAYOUT_UNDEFINED)
                || nbar>=SVP_VK_IMAGES) goto fail;
            for (unsigned n=0;n<nbar;n++) if (barriers[n].image==v->img[i]) goto fail;
            unsigned sem=0;while(sem<nsem && sems[sem]!=v->sem[i]) sem++;
            if (sem==nsem) {
                if (nsem>=SVP_VK_IMAGES) goto fail;
                sems[sem]=v->sem[i];waits[sem]=v->sem_value[i];
                stages[sem]=VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;nsem++;
            } else if (waits[sem]<v->sem_value[i]) waits[sem]=v->sem_value[i];
            signals[sem]=waits[sem]+1;
            barriers[nbar++]=(VkImageMemoryBarrier2){.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2,
                .srcStageMask=VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                // The timeline wait provides producer availability.
                // Do not put video-decode-only access flags on a compute queue.
                .srcAccessMask=0,
                .dstStageMask=VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                .dstAccessMask=r->write?VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT:VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                .oldLayout=v->layout[i],.newLayout=VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,
                .image=v->img[i],.subresourceRange={.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT,.levelCount=1,.layerCount=1}};
        }
    }
    VkDependencyInfo di={.sType=VK_STRUCTURE_TYPE_DEPENDENCY_INFO,.imageMemoryBarrierCount=nbar,.pImageMemoryBarriers=barriers};
    s->barrier(cmd,&di);
    j->pair=s->begin(s->engine,cmd,&src[0],&src[1]);
    if (!j->pair) goto fail;
    for (unsigned i=0;i<count;i++) if (s->render(j->pair,phases[i],svp_vk_matrix(a),dst[i])) goto fail;
    if (s->end_commands(cmd)!=VK_SUCCESS) goto fail;
    VkTimelineSemaphoreSubmitInfo ti={.sType=VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
        .waitSemaphoreValueCount=nsem,.pWaitSemaphoreValues=waits,.signalSemaphoreValueCount=nsem,.pSignalSemaphoreValues=signals};
    VkSubmitInfo si={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.pNext=&ti,.waitSemaphoreCount=nsem,
        .pWaitSemaphores=sems,.pWaitDstStageMask=stages,.commandBufferCount=1,.pCommandBuffers=&cmd,
        .signalSemaphoreCount=nsem,.pSignalSemaphores=sems};
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
    s->vkdev->lock_queue(s->avdev,s->family,0);
    VkResult submitted=s->submit(s->queue,1,&si,j->fence);
    s->vkdev->unlock_queue(s->avdev,s->family,0);
#pragma GCC diagnostic pop
    if (submitted!=VK_SUCCESS) goto fail;
    j->submitted=true;
    j->sealed=s->seal(j->pair,j->fence)==0;
    // The GPU now owns these resources. Publish its exact synchronization
    // state while FFmpeg's locks are still held.
    for (unsigned k=0;k<unique;k++) {
        struct svp_vk_ref *r=&refs[order[k]];
        for (unsigned i=0;i<r->count;i++) {
            AVVkFrame *v=r->frame;
            unsigned sem=0;while(sem<nsem && sems[sem]!=v->sem[i]) sem++;
            v->sem_value[i]=signals[sem];
            v->layout[i]=VK_IMAGE_LAYOUT_GENERAL;
            v->access[i]=r->write?VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT:VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
        }
    }
    while(locked) {struct svp_vk_ref *r=&refs[order[--locked]];r->vfc->unlock_frame(r->fc,r->frame);}
    s->jobs[slot]=j;
    return true;
fail:
    while(locked) {struct svp_vk_ref *r=&refs[order[--locked]];r->vfc->unlock_frame(r->fc,r->frame);}
    for (unsigned i=0;i<count;i++) mp_image_unrefp(&outputs[i]);
    svp_vk_job_free(s,j);
    return false;
}

static struct mp_image *svp_vk_take_output(struct svp_vk_mpv *s)
{
    if (!s || s->next_output==s->output_count) return NULL;
    struct mp_image *img=s->outputs[s->next_output];
    s->outputs[s->next_output++]=NULL;
    if (s->next_output==s->output_count) s->next_output=s->output_count=0;
    s->output_frames++;return img;
}
static struct mp_image *svp_vk_take_previous(struct svp_vk_mpv *s)
{
    if (!s) return NULL;
    struct mp_image *img=s->previous;s->previous=NULL;return img;
}
static bool svp_vk_pair_frames(struct svp_vk_mpv *s,struct mp_image *next,double end)
{
    double times[SVP_VK_PHASES];float phases[SVP_VK_PHASES];unsigned count=0;
    double duration=end-s->previous->pts;
    if (s->output_count || !(duration>0) || !isfinite(duration)) return false;
    uint64_t tick=s->tick;
    while (s->origin+(double)tick/s->target_rate<end-0.000001) {
        if (count==SVP_VK_PHASES || tick>=UINT64_C(1000000000)) return false;
        double time=s->origin+(double)tick++/s->target_rate;
        double phase=(time-s->previous->pts)/duration;
        if (phase< -0.25 || phase>1.0) return false;
        times[count]=time;phases[count++]=(float)fmax(0.0,phase);
    }
    if (!count || !svp_vk_interpolate(s,s->previous,next,times,phases,count,s->outputs)) return false;
    s->tick=tick;s->output_count=count;s->next_output=0;
    s->generated_frames+=count;return true;
}
// On success consumes img; on rejection caller still owns it.
static bool svp_vk_feed(struct svp_vk_mpv *s,struct mp_image *img)
{
    if (!s || !svp_vk_matches(s,img) || s->output_count) return false;
    if (!s->previous) {
        s->previous=img;s->origin=img->pts;s->tick=0;s->input_frames++;return true;
    }
    double expected=1.0/s->source_rate,delta=img->pts-s->previous->pts;
    if (fabs(delta-expected)>fmax(0.003,expected*0.25)
        || !svp_vk_pair_frames(s,img,img->pts)) return false;
    mp_image_unrefp(&s->previous);s->previous=img;s->input_frames++;return true;
}
static void svp_vk_eof(struct svp_vk_mpv *s)
{
    if (!s || !s->previous || s->output_count) return;
    if (svp_vk_pair_frames(s,s->previous,s->previous->pts+1.0/s->source_rate))
        mp_image_unrefp(&s->previous);
    else {
        s->outputs[0]=svp_vk_take_previous(s);s->output_count=1;s->next_output=0;
    }
}
#endif
