/* Real WSI surface on Xvfb; exercises the production libplacebo patch,
 * not a mocked presentation semaphore pool. Software Vulkan may expose
 * only one graphics queue; report the actual topology explicitly. */
#define _POSIX_C_SOURCE 200809L
#define VK_USE_PLATFORM_XLIB_KHR
#include <X11/Xlib.h>
#include <libplacebo/vulkan.h>
#include <libplacebo/swapchain.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <stdatomic.h>

static atomic_uint validation_errors;
/* Faults are injected at the loaded device function boundary. A rejected
 * acquire never signals a semaphore or acquires an image in the real driver.
 * The next successful acquire must use a valid unused semaphore. */
static VkResult next_acquire=VK_SUCCESS;
static unsigned injected_acquires;
static PFN_vkAcquireNextImageKHR real_acquire;
static VKAPI_ATTR VkResult VKAPI_CALL acquire_fault(VkDevice device,VkSwapchainKHR swap,
    uint64_t timeout,VkSemaphore semaphore,VkFence fence,uint32_t *index)
{
    if(next_acquire!=VK_SUCCESS){
        VkResult res=next_acquire;next_acquire=VK_SUCCESS;injected_acquires++;
        return res;
    }
    return real_acquire(device,swap,timeout,semaphore,fence,index);
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL device_proc(VkDevice device,const char *name)
{
    PFN_vkVoidFunction fn=vkGetDeviceProcAddr(device,name);
    if(fn && !strcmp(name,"vkAcquireNextImageKHR")){
        real_acquire=(PFN_vkAcquireNextImageKHR)fn;
        return (PFN_vkVoidFunction)acquire_fault;
    }
    return fn;
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL instance_proc(VkInstance inst,const char *name)
{
    if(!strcmp(name,"vkGetDeviceProcAddr"))return (PFN_vkVoidFunction)device_proc;
    return vkGetInstanceProcAddr(inst,name);
}
static void log_message(void *opaque, enum pl_log_level level, const char *msg)
{
    (void)opaque;
    if(level <= PL_LOG_ERR) {
        fprintf(stderr,"%s\n",msg);
        if(strstr(msg,"Validation Error") || strstr(msg,"VUID-"))
            atomic_fetch_add(&validation_errors,1);
    }
}
static void pause_ms(void)
{
    struct timespec t={0,1000000};
    nanosleep(&t,NULL);
}
static unsigned present_frames(pl_swapchain sw,unsigned count)
{
    unsigned presented=0;
    for(unsigned attempts=0;attempts<5000 && presented<count;attempts++){
        struct pl_swapchain_frame f;
        if(!pl_swapchain_start_frame(sw,&f)){pause_ms();continue;}
        float color[4]={(presented%7)/7.f,.1f,.2f,1.f};
        pl_tex_clear(sw->gpu,f.fbo,color);
        assert(pl_swapchain_submit_frame(sw));
        if(pl_vulkan_swapchain_ambient_presented(sw))presented++;
        pl_swapchain_swap_buffers(sw);
    }
    return presented;
}
static void queue_zero_idle(pl_vulkan vk)
{
    VkQueue q;
    vkGetDeviceQueue(vk->device,vk->queue_graphics.index,0,&q);
    vk->lock_queue(vk,vk->queue_graphics.index,0);
    VkResult r=vkQueueWaitIdle(q);
    vk->unlock_queue(vk,vk->queue_graphics.index,0);
    assert(r==VK_SUCCESS);
}
static void run(Display *d,unsigned requested,bool experimental)
{
    Window w=XCreateSimpleWindow(d,DefaultRootWindow(d),0,0,96,64,0,0,0);
    XMapWindow(d,w);XSync(d,False);
    pl_log log=pl_log_create(PL_API_VER,&(struct pl_log_params){
        .log_cb=log_message,.log_level=PL_LOG_WARN});
    const char *exts[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_XLIB_SURFACE_EXTENSION_NAME};
    pl_vk_inst inst=pl_vk_inst_create(log,pl_vk_inst_params(.debug=true,.extensions=exts,.num_extensions=2));
    assert(inst);
    VkXlibSurfaceCreateInfoKHR ci={.sType=VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR,.dpy=d,.window=w};
    VkSurfaceKHR surface;
    assert(vkCreateXlibSurfaceKHR(inst->instance,&ci,NULL,&surface)==VK_SUCCESS);
    pl_vulkan vk=pl_vulkan_create(log,&(struct pl_vulkan_params){
        .instance=inst->instance,.surface=surface,.allow_software=true,.get_proc_addr=instance_proc,
        .async_compute=false,.async_transfer=false,.queue_count=requested});
    assert(vk && vk->num_queues==1);
    fprintf(stderr,"WSI requested queues=%u actual=%u experimental=%d\n",
            requested,vk->queue_graphics.count,experimental);
    pl_swapchain sw=pl_vulkan_create_swapchain(vk,pl_vulkan_swapchain_params(
        .surface=surface,.present_mode=VK_PRESENT_MODE_FIFO_KHR,.swapchain_depth=3));
    assert(sw);
    if(experimental)assert(pl_vulkan_swapchain_ambient_enable(sw));
    int width=96,height=64;
    assert(pl_swapchain_resize(sw,&width,&height));
    if(experimental)assert(!pl_vulkan_swapchain_ambient_enable(sw));
    assert(present_frames(sw,48)==48);
    for(unsigned i=0;i<3;i++){
        width=104+8*i;height=72+4*i;
        XResizeWindow(d,w,width,height);XSync(d,False);
        assert(pl_swapchain_resize(sw,&width,&height));
        assert(present_frames(sw,32)==32);
    }
    pl_gpu_flush(vk->gpu);
    if(experimental){
        // Finish pending commands before injecting a single acquire result.
        pl_gpu_finish(vk->gpu);
        unsigned prior=injected_acquires;
        struct pl_swapchain_frame f;
        next_acquire=VK_TIMEOUT;
        assert(!pl_swapchain_start_frame(sw,&f));
        assert(injected_acquires==prior+1);
        assert(present_frames(sw,12)==12);
        pl_gpu_finish(vk->gpu);
        prior=injected_acquires;
        next_acquire=VK_NOT_READY;
        assert(!pl_swapchain_start_frame(sw,&f));
        assert(injected_acquires==prior+1);
        assert(present_frames(sw,12)==12);
        pl_gpu_finish(vk->gpu);
        prior=injected_acquires;
        // Test a terminal acquire failure with a live driver. This is not
        // physical device loss: it verifies software state and no reuse.
        next_acquire=requested==1?VK_ERROR_SURFACE_LOST_KHR:VK_ERROR_DEVICE_LOST;
        assert(!pl_swapchain_start_frame(sw,&f));
        assert(injected_acquires==prior+1);
        assert(!pl_swapchain_start_frame(sw,&f));
        assert(injected_acquires==prior+1 && next_acquire==VK_SUCCESS);
        queue_zero_idle(vk);
        assert(pl_vulkan_swapchain_ambient_mark_retired(sw));
    }
    pl_swapchain_destroy(&sw);
    pl_vulkan_destroy(&vk);
    vkDestroySurfaceKHR(inst->instance,surface,NULL);
    pl_vk_inst_destroy(&inst);pl_log_destroy(&log);
    XDestroyWindow(d,w);XSync(d,False);
}
int main(void)
{
    Display *d=XOpenDisplay(NULL);assert(d);
    run(d,1,true);
    run(d,2,true);
    // Extended library with the experimental switch off must preserve WSI.
    run(d,1,false);
    XCloseDisplay(d);
    assert(atomic_load(&validation_errors)==0);
    puts("Real WSI acquire/present/resize/retirement and default route passed");
    return 0;
}
