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
    uint64_t index,stats_token,capture_end;
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
    uint64_t source_ids[4];
    int (*set_source_ids)(const AmbientSvpVk *,const uint64_t (*)[4]);
    bool generation_exhausted;
    int (*submit_capture)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float,const AmbientSvpHdrWindow *,const AmbientSvpHdrTrack *,
        const uint32_t (*)[3],uint32_t,AmbientSvpHdrPass *,uint64_t,AmbientSvpVkHeld **,uint64_t);
    int (*retire_capture)(AmbientSvpVkHeld *,uint32_t,AmbientSvpHdrPass *,uint64_t);
    int (*retire_capture_group)(AmbientSvpVkHeld *,uint32_t,AmbientSvpHdrPass *,uint64_t,uint64_t);
    uint64_t capture_end;
    bool capture_failed,device_lost,stalled;
    bool terminal_uncertain;
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
static inline void svp_placebo_remove_pending(pl_gpu gpu,struct svp_placebo_quality *s,unsigned i)
{
    struct svp_placebo_pending *p=&s->pending[i];
    for(unsigned j=0;j<p->count;j++)pl_vulkan_sem_destroy(gpu,&p->holds[j]);
    --s->pending_count;
    memmove(p,p+1,(s->pending_count-i)*sizeof(*p));
    s->pending[s->pending_count]=(struct svp_placebo_pending){0};
}
// The scene accumulator is CPU-only. Native GPU tickets never retain
// it; remove every borrowed pointer before closing an aborted scene.
static inline void svp_placebo_cancel_capture(struct svp_placebo_quality *s,AmbientSvpHdrPass *pass)
{
    for(unsigned i=0;i<s->pending_count;i++)if(s->pending[i].capture==pass){
        s->pending[i].capture=NULL;s->pending[i].capture_end=0;
    }
}
static inline void svp_placebo_discard_stats(struct svp_placebo_quality *s,uint64_t token)
{
    for(unsigned i=0;i<s->pending_count;i++)if(s->pending[i].stats_token==token)s->pending[i].stats_token=0;
}
static inline bool svp_placebo_collect_stats(pl_gpu gpu,struct svp_placebo_quality *s,uint64_t token,
    struct svp_placebo_hdr *hdr,AmbientSvpHdrPass *pass,uint64_t index)
{
    if(!token||!hdr||!s->collect_stats||s->device_lost||s->terminal_uncertain)return false;
    for(unsigned i=0;i<s->pending_count;i++){
        struct svp_placebo_pending *p=&s->pending[i];
        if(p->stats_token!=token)continue;
        if(p->index!=index)return false;
        int rc=s->collect_stats(p->job,s->retire_status?2:1,hdr->results,hdr->rects,hdr->ellipses,hdr->shifts,hdr->count,pass,index);
        if(rc==3)s->device_lost=true;
        if(rc==1||rc==2||rc==3)svp_placebo_remove_pending(gpu,s,i);
        else {p->stats_token=0;s->stalled=true;} // keep GPU ownership, abandon metadata
        return rc==1;
    }
    return false;
}
static inline bool svp_placebo_retire_one(pl_gpu gpu,struct svp_placebo_quality *s,unsigned i,unsigned wait)
{
    if(s->terminal_uncertain)return false;
    struct svp_placebo_pending *p=&s->pending[i];
    // A renderer-owned statistics result must survive automatic polling.
    // Destruction may explicitly discard it, but backpressure cannot.
    if(p->stats_token){if(wait!=1)return false;p->stats_token=0;}
    unsigned mode=wait==2&&!s->retire_status?0:wait;
    int rc=p->capture&&!s->capture_failed?(p->capture_end&&s->retire_capture_group?
        s->retire_capture_group(p->job,mode,p->capture,p->index,p->capture_end):s->retire_capture(p->job,mode,p->capture,p->index)):
        s->retire_status?s->retire_status(p->job,mode):s->retire(p->job,mode);
    if(rc!=1&&rc!=2&&rc!=3){if(wait==2)s->stalled=true;return false;}
    if(rc==3)s->device_lost=true;
    if(rc!=1&&p->capture)s->capture_failed=true;
    svp_placebo_remove_pending(gpu,s,i);
    return true;
}
// Both live replacement and terminal retirement use a bounded fence wait.
// The caller must retain the complete owner if retirement fails.
static inline bool svp_placebo_quality_try_retire_texture(pl_gpu gpu,struct svp_placebo_quality *s,pl_tex texture)
{
    if(s->terminal_uncertain)return false;
    while(s->pending_count){
        bool needed=texture==NULL;
        for(unsigned i=0;i<s->pending_count;i++)
            for(unsigned j=0;j<s->pending[i].count;j++)needed|=s->pending[i].textures[j]==texture;
        if(!needed)return true;
        if(s->pending[0].stats_token)return false;
        if(!svp_placebo_retire_one(gpu,s,0,2))return false;
    }
    return true;
}
static inline void svp_placebo_quality_source_changed(pl_gpu gpu,struct svp_placebo_quality *s,pl_tex texture)
{
    (void)gpu;(void)texture;
    // Content writes are ordered by the libplacebo timeline; only actual
    // allocation replacement must retire native image views.
    // Zero disables cache after theoretical generation wrap.
    if(s->generation_exhausted)return;
    if(s->generation!=UINT64_MAX)++s->generation;
    else{s->generation=0;s->generation_exhausted=true;}
}
static inline bool svp_placebo_quality_destroy(pl_gpu gpu,struct svp_placebo_quality *s)
{
    for(unsigned i=0;i<s->pending_count;i++)s->pending[i].stats_token=0;
    if(!svp_placebo_quality_try_retire_texture(gpu,s,NULL))return false;
    // Renderer dependencies on the shared timeline may outlive native jobs.
    if(s->done){pl_gpu_finish(gpu);pl_vulkan_sem_destroy(gpu,&s->done);}
    if(s->engine)s->destroy(s->engine);
    if(s->lib)dlclose(s->lib);
    *s=(struct svp_placebo_quality){0};
    return true;
}
static inline bool svp_placebo_quality_target(pl_gpu gpu,struct svp_placebo_quality *s,pl_tex *tex,int w,int h,bool storage)
{
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,16,0,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE|
        (storage?PL_FMT_CAP_STORABLE:0));
    if(w<=0||h<=0||!fmt)return false;
    if(*tex&&(*tex)->params.w==w&&(*tex)->params.h==h&&(*tex)->params.format==fmt&&
        (*tex)->params.sampleable&&(*tex)->params.renderable&&(!storage||(*tex)->params.storable))return true;
    if(*tex&&!svp_placebo_quality_try_retire_texture(gpu,s,*tex))return false;
    if(s->device_lost||s->terminal_uncertain)return false;
    return pl_tex_recreate(gpu,tex,pl_tex_params(.w=w,.h=h,.format=fmt,.sampleable=true,.renderable=true,.storable=storage));
}
static inline bool svp_placebo_quality_render_pass_ex(pl_gpu gpu,struct svp_placebo_quality *s,
    pl_tex a,pl_tex b,pl_tex previous,pl_tex next,pl_tex *out,float phase,const AmbientSvpQuality *options,struct svp_placebo_hdr *hdr,AmbientSvpHdrPass *pass,uint64_t index,bool capture,uint64_t *deferred_token)
{
    if(deferred_token)*deferred_token=0;
    if(s->terminal_uncertain)return false;
    // Do not pay a fresh blocking timeout for every output on a stuck
    // driver. Recover only after nonblocking polling proves retirement.
    if(s->stalled){
        while(s->pending_count&&svp_placebo_retire_one(gpu,s,0,0)){}
        if(s->pending_count)return false;
        s->stalled=false;
    }
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
        s->set_source_ids=dlsym(s->lib,"ambient_svp_vk_set_held_source_ids");
        s->submit_capture=dlsym(s->lib,"ambient_svp_vk_submit_held_capture");
        s->retire_capture=dlsym(s->lib,"ambient_svp_vk_retire_held_capture");
        s->retire_capture_group=dlsym(s->lib,"ambient_svp_vk_retire_held_capture_group");
        if(!s->retire_capture)s->submit_capture=NULL;
        s->submit_stats=dlsym(s->lib,"ambient_svp_vk_submit_held_stats");
        s->collect_stats=dlsym(s->lib,"ambient_svp_vk_collect_held_stats");
        if(!s->collect_stats||!s->retire)s->submit_stats=NULL;
        if(!s->retire){s->submit=NULL;s->submit_cached=NULL;s->submit_capture=NULL;}
        if(!s->create||!s->destroy||!s->render){dlclose(s->lib);s->lib=NULL;return false;}
    }
    // No synchronous renderer ABI fallback: native queue waits must not run
    // under libplacebo's live queue lock.
    if(!s->retire_status||!s->submit_cached)return false;
    if(hdr&&(!hdr->count||hdr->count>3))return false;
    if(pass&&!hdr)return false;
    if(capture&&(!pass||!s->submit_capture||!s->retire_capture||
        (s->capture_end&&!s->retire_capture_group)))return false;
    if(!capture&&hdr&&(!s->submit_stats||!s->collect_stats))return false;
    const int w=a->params.w,h=a->params.h;
    if(!s->engine||w!=s->w||h!=s->h||memcmp(&s->options,options,sizeof(*options))){
        // Incompatible quality/geometry must not consume old stats as
        // new output. Abandon them, then retire only with bounded waits.
        for(unsigned i=0;i<s->pending_count;i++)s->pending[i].stats_token=0;
        if(!svp_placebo_quality_try_retire_texture(gpu,s,NULL)||s->device_lost)return false;
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
    if(!s->done)s->done=pl_vulkan_sem_create(gpu,pl_vulkan_sem_params(.type=VK_SEMAPHORE_TYPE_TIMELINE));
    if(!s->done||s->value==UINT64_MAX||*out==a||*out==b||
        (previous&&*out==previous)||(next&&*out==next)||
        !svp_placebo_quality_target(gpu,s,out,w,h,true))return false;
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
    if(!get_queue)goto cleanup;
    get_queue(vk->device,family,0,&queue);
    if(!queue)goto cleanup;
    vk->lock_queue(vk,family,0);
    if(s->set_source_ids)s->set_source_ids(s->engine,count==5?&s->source_ids:NULL);
    int rc=split_stats?s->submit_stats(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,
        hdr->windows,hdr->tracks,hdr->axes,hdr->count,pass,index,&job,s->generation):
        capture&&pass&&s->submit_capture?s->submit_capture(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,
        hdr->windows,hdr->tracks,hdr->axes,hdr->count,pass,index,&job,s->generation):
        s->submit_cached(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase,&job,s->generation);
    // The ABI cannot prove whether submission happened. Retain all images,
    // semaphores and the device; neither player may attempt another GPU call.
    if(rc==-2){
        s->terminal_uncertain=true;
        struct svp_placebo_pending *p=&s->pending[s->pending_count++];
        p->job=job;p->count=held;
        memcpy(p->textures,tex,held*sizeof(*tex));
        memcpy(p->holds,holds,held*sizeof(*holds));
        vk->unlock_queue(vk,family,0);
        return false;
    }
    vk->unlock_queue(vk,family,0);
    if(rc==0||rc==1){s->value++;retired=true;ok=rc==0;}
    // Metadata belongs to this exact output, but collection no longer
    // holds the external Vulkan queue lock while waiting for the fence.
    if(split_stats&&job&&deferred_token&&ok){
        *deferred_token=s->value;
    }else if(split_stats&&job&&ok){
        int result=s->collect_stats(job,s->retire_status?2:1,hdr->results,hdr->rects,hdr->ellipses,hdr->shifts,hdr->count,pass,index);
        if(result==1||result==2||result==3)job=NULL;
        // On timeout keep the ticket and its textures alive, and fall back
        // to the original source instead of presenting unmeasured HDR.
        if(result==3)s->device_lost=true;
        if(result<=0)s->stalled=true;
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
        p->job=job;p->count=count;p->capture=capture&&ok?pass:NULL;p->index=index;
        if(capture&&!ok)s->capture_failed=true;
        p->capture_end=capture?s->capture_end:0;
        p->stats_token=deferred_token?*deferred_token:0;
        memcpy(p->textures,tex,count*sizeof(*tex));
        memcpy(p->holds,holds,count*sizeof(*holds));
    }else{
        for(unsigned i=0;i<count;i++)if(holds[i])pl_vulkan_sem_destroy(gpu,&holds[i]);
    }
    return ok;
}
static inline bool svp_placebo_quality_render_pass(pl_gpu gpu,struct svp_placebo_quality *s,
    pl_tex a,pl_tex b,pl_tex previous,pl_tex next,pl_tex *out,float phase,const AmbientSvpQuality *options,struct svp_placebo_hdr *hdr,AmbientSvpHdrPass *pass,uint64_t index,bool capture)
{
    return svp_placebo_quality_render_pass_ex(gpu,s,a,b,previous,next,out,phase,options,hdr,pass,index,capture,NULL);
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
