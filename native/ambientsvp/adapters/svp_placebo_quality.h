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
struct svp_placebo_quality {
    void *lib;
    AmbientSvpVk *engine;
    void (*destroy)(AmbientSvpVk *);
    AmbientSvpVk *(*create)(VkInstance,VkPhysicalDevice,VkDevice,PFN_vkGetInstanceProcAddr,
        uint32_t,uint32_t,uint32_t,const AmbientSvpQuality *);
    int (*render)(const AmbientSvpVk *,VkQueue,uint32_t,const VkImage *,uint32_t,
        const VkSemaphore *,VkSemaphore,uint64_t,float);
    VkSemaphore done;
    uint64_t value;
    int w,h;
    AmbientSvpQuality options;
};
static inline void svp_placebo_quality_destroy(pl_gpu gpu,struct svp_placebo_quality *s)
{
    // All output/source textures must already have been destroyed by
    // their respective renderer owners before destroying ext-deps.
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
static inline bool svp_placebo_quality_render(pl_gpu gpu,struct svp_placebo_quality *s,
    pl_tex a,pl_tex b,pl_tex previous,pl_tex next,pl_tex *out,float phase,const AmbientSvpQuality *options)
{
    if(!a||!b||!options||!out||a==b||!isfinite(phase)||phase<=0||phase>=1||
        a->params.w!=b->params.w||a->params.h!=b->params.h)return false;
    pl_vulkan vk=pl_vulkan_get(gpu);
    // hold_ex submits on GRAPHICS. Restrict to a common compute-capable
    // family to avoid relying on undocumented external ownership barriers.
    if(!vk||!vk->lock_queue||!vk->unlock_queue||!vk->queue_compute.count||
        vk->queue_compute.index!=vk->queue_graphics.index)return false;
    if(!s->lib){
        s->lib=dlopen("libambientsvp.so",RTLD_NOW|RTLD_LOCAL);
        if(!s->lib)return false;
        s->create=dlsym(s->lib,"ambient_svp_vk_create_quality");
        s->destroy=dlsym(s->lib,"ambient_svp_vk_destroy");
        s->render=dlsym(s->lib,"ambient_svp_vk_render_held");
        if(!s->create||!s->destroy||!s->render){dlclose(s->lib);s->lib=NULL;return false;}
    }
    const int w=a->params.w,h=a->params.h;
    if(!s->engine||w!=s->w||h!=s->h||memcmp(&s->options,options,sizeof(*options))){
        if(s->engine)s->destroy(s->engine);
        s->engine=s->create(vk->instance,vk->phys_device,vk->device,vk->get_proc_addr,w,h,3,options);
        if(!s->engine)return false;
        s->w=w;s->h=h;s->options=*options;
    }
    if(!s->done)s->done=pl_vulkan_sem_create(gpu,pl_vulkan_sem_params(.type=VK_SEMAPHORE_TYPE_TIMELINE));
    if(!s->done||s->value==UINT64_MAX||!svp_placebo_quality_target(gpu,out,w,h,true))return false;
    pl_tex tex[5]={a,b,*out,NULL,NULL};
    unsigned count=3;
    if(previous&&next&&previous!=a&&previous!=b&&next!=a&&next!=b&&previous!=next&&
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
    unsigned held=0;bool ok=false;
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
    int rc=s->render(s->engine,queue,family,images,count,holds,s->done,s->value+1,phase);
    vk->unlock_queue(vk,family,0);
    if(rc==0){s->value++;ok=true;}
cleanup:
    // The native helper returns only after fence completion or confirmed
    // device loss. On pre-submit failure retire libplacebo's hold signals.
    if(!ok)pl_gpu_finish(gpu);
    for(unsigned i=0;i<held;i++)
        pl_vulkan_release_ex(gpu,pl_vulkan_release_params(.tex=tex[i],.layout=VK_IMAGE_LAYOUT_GENERAL,
            .qf=VK_QUEUE_FAMILY_IGNORED,.semaphore={ok?s->done:VK_NULL_HANDLE,ok?s->value:0}));
    for(unsigned i=0;i<count;i++)if(holds[i])pl_vulkan_sem_destroy(gpu,&holds[i]);
    return ok;
}
#endif
