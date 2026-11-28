/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Common zero-pixel-copy libplacebo -> native SVP bridge for MPV FEL
 * and VLC HDR reconstruction. The renderer owns every pl_tex.
 */
#ifndef AMBIENTSVP_PLACEBO_QUALITY_H
#define AMBIENTSVP_PLACEBO_QUALITY_H
#include <dlfcn.h>
#include <string.h>
#include <math.h>
#include <libplacebo/vulkan.h>
#include "ambientsvp_vulkan.h"
struct svp_placebo_hdr {
    uint32_t count;
    AmbientSvpHdrWindow windows[3];
    AmbientSvpHdrTrack tracks[3];
    uint32_t axes[3][3],rects[3][4],ellipses[3][3];
    int32_t shifts[3][2];
    AmbientSvpHdrResult results[3];
};
#define SVP_PLACEBO_PENDING 4
struct svp_placebo_pending {
    AmbientSvpVkHeld *job;
    AmbientSvpHdrPass *capture;
    uint64_t index;
    unsigned count;
    pl_tex textures[5];
    VkSemaphore holds[5];
};
struct svp_placebo_quality {
    void *lib;
    AmbientSvpVk *engine;
    void (*destroy)(AmbientSvpVk *);
    AmbientSvpVk *(*create)(VkInstance,VkPhysicalDevice,VkDevice,PFN_vkGetInstanceProcAddr,
        uint32_t,uint32_t,uint32_t,const AmbientSvpQuality *);
    int (*render)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float);
    int (*render_hdr)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float,const AmbientSvpHdrWindow *,
        const AmbientSvpHdrTrack *,const uint32_t (*)[3],AmbientSvpHdrResult *,
        uint32_t (*)[4],uint32_t (*)[3],int32_t (*)[2],uint32_t);
    int (*render_pass)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float,const AmbientSvpHdrWindow *,
        const AmbientSvpHdrTrack *,const uint32_t (*)[3],AmbientSvpHdrResult *,
        uint32_t (*)[4],uint32_t (*)[3],int32_t (*)[2],uint32_t,AmbientSvpHdrPass *,uint64_t);
    int (*submit)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float,AmbientSvpVkHeld **);
    int (*retire)(AmbientSvpVkHeld *,uint32_t);
    int (*retire_status)(AmbientSvpVkHeld *,uint32_t);
    int (*submit_cached)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float,AmbientSvpVkHeld **,uint64_t);
    uint64_t generation;
    int (*submit_capture)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float,const AmbientSvpHdrWindow *,const AmbientSvpHdrTrack *,
        const uint32_t (*)[3],uint32_t,AmbientSvpHdrPass *,uint64_t,AmbientSvpVkHeld **,uint64_t);
    int (*retire_capture)(AmbientSvpVkHeld *,uint32_t,AmbientSvpHdrPass *,uint64_t);
    bool capture_failed,device_lost;
    int (*submit_stats)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float,const AmbientSvpHdrWindow *,const AmbientSvpHdrTrack *,
        const uint32_t (*)[3],uint32_t,AmbientSvpHdrPass *,uint64_t,AmbientSvpVkHeld **,uint64_t);
    int (*collect_stats)(AmbientSvpVkHeld *,uint32_t,AmbientSvpHdrResult *,uint32_t (*)[4],
        uint32_t (*)[3],int32_t (*)[2],uint32_t,AmbientSvpHdrPass *,uint64_t);
    struct svp_placebo_pending pending[SVP_PLACEBO_PENDING];
    unsigned pending_count;
    VkSemaphore done;
    uint64_t value;
    int w,h;
    AmbientSvpQuality options;
};
// Retire native image views before a renderer-owned texture can be
// destroyed or recreated. No queue mutex is held during fence waits.
static inline bool svp_placebo_retire_one(pl_gpu gpu,struct svp_placebo_quality *s,unsigned i,unsigned wait)
{
    struct svp_placebo_pending *p=&s->pending[i];
    unsigned mode=wait==2&&!s->retire_status?0:wait;
    int rc=p->capture&&!s->capture_failed?s->retire_capture(p->job,mode,p->capture,p->index):
        s->retire_status?s->retire_status(p->job,mode):s->retire(p->job,mode);
    if(rc!=1&&rc!=2&&rc!=3)return false;
    if(rc==3)s->device_lost=true;
    if(rc!=1)s->capture_failed=true;
    for(unsigned j=0;j<p->count;j++)pl_vulkan_sem_destroy(gpu,&p->holds[j]);
    --s->pending_count;
    memmove(p,p+1,(s->pending_count-i)*sizeof(*p));
    s->pending[s->pending_count]=(struct svp_placebo_pending){0};
    return true;
}
static inline void svp_placebo_quality_retire_texture(pl_gpu gpu,struct svp_placebo_quality *s,pl_tex texture)
{
    while(s->pending_count){
        bool wait=texture==NULL;
        for(unsigned i=0;i<s->pending_count;i++)
            for(unsigned j=0;j<s->pending[i].count;j++)wait|=s->pending[i].textures[j]==texture;
        if(!svp_placebo_retire_one(gpu,s,0,wait))break;
    }
}
static inline void svp_placebo_quality_source_changed(pl_gpu gpu,struct svp_placebo_quality *s,pl_tex texture)
{
    if(texture)svp_placebo_quality_retire_texture(gpu,s,texture);
    // Zero disables cache after theoretical generation wrap.
    if(s->generation!=UINT64_MAX)++s->generation;
    else s->submit_cached=NULL;
}
static inline void svp_placebo_quality_destroy(pl_gpu gpu,struct svp_placebo_quality *s)
{
    svp_placebo_quality_retire_texture(gpu,s,NULL);
    // Renderer dependencies on the shared timeline may outlive native jobs.
    if(s->done){pl_gpu_finish(gpu);pl_vulkan_sem_destroy(gpu,&s->done);}
    if(s->engine)s->destroy(s->engine);
    if(s->lib)dlclose(s->lib);
    *s=(struct svp_placebo_quality){0};
}
static inline bool svp_placebo_quality_target(pl_gpu gpu,pl_tex *tex,int w,int h,bool storage)
{
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,16,0,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|
        (storage?PL_FMT_CAP_STORABLE:0));
    return w>0&&h>0&&fmt&&pl_tex_recreate(gpu,tex,pl_tex_params(
        .w=w,.h=h,.format=fmt,.sampleable=true,.renderable=true,.storable=storage));
}
static inline bool svp_placebo_quality_render_pass(pl_gpu gpu,struct svp_placebo_quality *s,
    pl_tex a,pl_tex b,pl_tex previous,pl_tex next,pl_tex *out,float phase,const AmbientSvpQuality *options,struct svp_placebo_hdr *hdr,AmbientSvpHdrPass *pass,uint64_t index,bool capture)
{
    if(s->device_lost||!a||!b||!options||!out||(!pass&&a==b)||!isfinite(phase)||phase<0||phase>=1||(!pass&&phase==0)|| (a==b&&phase!=0)||
        a->params.w!=b->params.w||a->params.h!=b->params.h)return false;
    pl_vulkan vk=pl_vulkan_get(gpu);
    // hold_ex submits on GRAPHICS. Restrict to a common compute-capable
    // family to avoid relying on undocumented external ownership barriers.
    if(!vk||!vk->get_proc_addr||!vk->lock_queue||!vk->unlock_queue||!vk->queue_compute.count||
        vk->queue_compute.flags||vk->queue_graphics.flags||
        vk->queue_compute.index!=vk->queue_graphics.index)return false;
    if(!s->lib){
        s->lib=dlopen("libambientsvp.so",RTLD_NOW|RTLD_LOCAL);
        if(!s->lib)return false;
        s->create=dlsym(s->lib,"ambient_svp_vk_create_quality");
        s->destroy=dlsym(s->lib,"ambient_svp_vk_destroy");
        s->render=dlsym(s->lib,"ambient_svp_vk_render_held");
        s->render_hdr=dlsym(s->lib,"ambient_svp_vk_render_held_hdr");
        s->render_pass=dlsym(s->lib,"ambient_svp_vk_render_held_pass");
        s->submit=dlsym(s->lib,"ambient_svp_vk_submit_held");
        s->retire=dlsym(s->lib,"ambient_svp_vk_retire_held");
        s->retire_status=dlsym(s->lib,"ambient_svp_vk_retire_held_status");
        s->submit_cached=dlsym(s->lib,"ambient_svp_vk_submit_held_cached");
        s->submit_capture=dlsym(s->lib,"ambient_svp_vk_submit_held_capture");
        s->retire_capture=dlsym(s->lib,"ambient_svp_vk_retire_held_capture");
        if(!s->retire_capture)s->submit_capture=NULL;
        s->submit_stats=dlsym(s->lib,"ambient_svp_vk_submit_held_stats");
        s->collect_stats=dlsym(s->lib,"ambient_svp_vk_collect_held_stats");
        if(!s->collect_stats||!s->retire)s->submit_stats=NULL;
        if(!s->retire){s->submit=NULL;s->submit_cached=NULL;s->submit_capture=NULL;}
        if(!s->create||!s->destroy||!s->render){dlclose(s->lib);s->lib=NULL;return false;}
    }
    if(hdr&&(!s->render_hdr||!hdr->count||hdr->count>3))return false;
    if(pass&&(!s->render_pass||!hdr||hdr->count<1||hdr->count>3))return false;
    const int w=a->params.w,h=a->params.h;
    if(!s->engine||w!=s->w||h!=s->h||memcmp(&s->options,options,sizeof(*options))){
        svp_placebo_quality_retire_texture(gpu,s,NULL);
        if(s->engine)s->destroy(s->engine);
        s->engine=s->create(vk->instance,vk->phys_device,vk->device,vk->get_proc_addr,w,h,3,options);
        if(!s->engine)return false;
        s->w=w;s->h=h;s->options=*options;
    }
    // Poll all existing jobs, then apply bounded backpressure outside
    // the queue lock. The legacy and metadata paths also share the budget.
    while(s->pending_count&&svp_placebo_retire_one(gpu,s,0,false)){}
    if(s->device_lost||(capture&&s->capture_failed))return false;
    if(s->pending_count==SVP_PLACEBO_PENDING&&!svp_placebo_retire_one(gpu,s,0,2))return false;
    if(s->device_lost||(capture&&s->capture_failed))return false;
    if(*out&&((*out)->params.w!=w||(*out)->params.h!=h))
        svp_placebo_quality_retire_texture(gpu,s,*out);
    if(!s->done)s->done=pl_vulkan_sem_create(gpu,pl_vulkan_sem_params(.type=VK_SEMAPHORE_TYPE_TIMELINE));
    if(!s->done||s->value==UINT64_MAX||*out==a||*out==b||
        (previous&&*out==previous)||(next&&*out==next)||
        !svp_placebo_quality_target(gpu,out,w,h,true))return false;
    pl_tex tex[5]={a,b,*out,NULL,NULL};
    unsigned count=3;
    if(a==b){tex[1]=*out;count=2;}
    if(count==3&&previous&&next&&previous!=a&&previous!=b&&next!=a&&next!=b&&previous!=next&&
        previous->params.w==w&&previous->params.h==h&&next->params.w==w&&next->params.h==h){
        tex[2]=previous;tex[3]=next;tex[4]=*out;count=5;
    }
    VkImage images[5]={0};VkSemaphore holds[5]={0};
    for(unsigned i=0;i<count;i++){
        VkFormat format;VkImageUsageFlags usage;
        images[i]=pl_vulkan_unwrap(gpu,tex[i],&format,&usage);
        if(!images[i]||format!=VK_FORMAT_R16G16B16A16_SFLOAT||
            !(usage&(i==count-1?VK_IMAGE_USAGE_STORAGE_BIT:VK_IMAGE_USAGE_SAMPLED_BIT)))return false;
        for(unsigned j=0;j<i;j++)if(images[i]==images[j])return false;
    }
    unsigned held=0;bool ok=false,retired=false;
    AmbientSvpVkHeld *job=NULL;
    bool split_stats=!capture&&hdr&&s->submit_stats;
    for(unsigned i=0;i<count;i++){
        holds[i]=pl_vulkan_sem_create(gpu,pl_vulkan_sem_params(.type=VK_SEMAPHORE_TYPE_BINARY));
        if(!holds[i]||!pl_vulkan_hold_ex(gpu,pl_vulkan_hold_params(.tex=tex[i],
            .layout=VK_IMAGE_LAYOUT_GENERAL,.qf=VK_QUEUE_FAMILY_IGNORED,.semaphore={holds[i],0})))goto cleanup;
        held++;
    }
    PFN_vkGetDeviceProcAddr gdpa=(PFN_vkGetDeviceProcAddr)vk->get_proc_addr(vk->instance,"vkGetDeviceProcAddr");
    PFN_vkGetDeviceQueue get_queue=gdpa?(PFN_vkGetDeviceQueue)gdpa(vk->device,"vkGetDeviceQueue"):NULL;
    VkQueue queue=VK_NULL_HANDLE;uint32_t family=vk->queue_compute.index;
    PFN_vkQueueWaitIdle wait_idle=gdpa?(PFN_vkQueueWaitIdle)gdpa(vk->device,"vkQueueWaitIdle"):NULL;
    if(!get_queue||!wait_idle)goto cleanup;
    get_queue(vk->device,family,0,&queue);
    if(!queue)goto cleanup;
    vk->lock_queue(vk,family,0);
    int rc=split_stats?s->submit_stats(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,
        hdr->windows,hdr->tracks,hdr->axes,hdr->count,pass,index,&job,s->generation):
        capture&&pass&&s->submit_capture?s->submit_capture(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,
        hdr->windows,hdr->tracks,hdr->axes,hdr->count,pass,index,&job,s->generation):
        pass?s->render_pass(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,
        hdr->windows,hdr->tracks,hdr->axes,hdr->results,hdr->rects,hdr->ellipses,hdr->shifts,hdr->count,pass,index):
        hdr?s->render_hdr(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,
        hdr->windows,hdr->tracks,hdr->axes,hdr->results,hdr->rects,hdr->ellipses,hdr->shifts,hdr->count):
        s->submit_cached?s->submit_cached(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,&job,s->generation):
        s->submit?s->submit(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,&job):
        s->render(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase);
    // Defensive panic recovery: if the native boundary could not
    // establish its own retirement, prove queue retirement here before
    // releasing any renderer texture. Never free an in-flight image.
    if(rc==-2){VkResult status;do{status=wait_idle(queue);}while(status!=VK_SUCCESS&&status!=VK_ERROR_DEVICE_LOST);}
    vk->unlock_queue(vk,family,0);
    if(rc==0||rc==1){s->value++;retired=true;ok=rc==0;}
    // Metadata belongs to this exact output, but collection no longer
    // holds the external Vulkan queue lock while waiting for the fence.
    if(split_stats&&job){
        int result=s->collect_stats(job,s->retire_status?2:1,hdr->results,hdr->rects,hdr->ellipses,hdr->shifts,hdr->count,pass,index);
        if(result==1||result==2||result==3)job=NULL;
        // On timeout keep the ticket and its textures alive, and fall back
        // to the original source instead of presenting unmeasured HDR.
        if(result==3)s->device_lost=true;
        ok=ok&&result==1;
    }
cleanup:
    // A queued timeline signal protects async jobs. On pre-submit
    // failure retire libplacebo's hold signals before releasing textures.
    if(!retired)pl_gpu_finish(gpu);
    for(unsigned i=0;i<held;i++)
        pl_vulkan_release_ex(gpu,pl_vulkan_release_params(.tex=tex[i],.layout=VK_IMAGE_LAYOUT_GENERAL,
            .qf=VK_QUEUE_FAMILY_IGNORED,.semaphore={retired?s->done:VK_NULL_HANDLE,retired?s->value:0}));
    if(job){
        struct svp_placebo_pending *p=&s->pending[s->pending_count++];
        p->job=job;p->count=count;p->capture=capture?pass:NULL;p->index=index;
        memcpy(p->textures,tex,count*sizeof(*tex));
        memcpy(p->holds,holds,count*sizeof(*holds));
    }else{
        for(unsigned i=0;i<count;i++)if(holds[i])pl_vulkan_sem_destroy(gpu,&holds[i]);
    }
    return ok;
}
static inline bool svp_placebo_quality_render_hdr(pl_gpu gpu,struct svp_placebo_quality *s,
    pl_tex a,pl_tex b,pl_tex previous,pl_tex next,pl_tex *out,float phase,const AmbientSvpQuality *options,struct svp_placebo_hdr *hdr)
{
    return svp_placebo_quality_render_pass(gpu,s,a,b,previous,next,out,phase,options,hdr,NULL,0,false);
}
static inline bool svp_placebo_quality_render(pl_gpu gpu,struct svp_placebo_quality *s,
    pl_tex a,pl_tex b,pl_tex previous,pl_tex next,pl_tex *out,float phase,const AmbientSvpQuality *options)
{
    return svp_placebo_quality_render_hdr(gpu,s,a,b,previous,next,out,phase,options,NULL);
}
#endif
