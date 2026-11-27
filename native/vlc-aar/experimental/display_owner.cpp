#include "display_owner.h"
#include "conversion_cache.h"
#include "submission.h"
#include "slot_pool.h"
#include "queue_transfer.h"
#include <array>
#include <optional>
#include <new>

using namespace ambient::vlc_ahb;
namespace {
constexpr size_t slot_count = 3;
using Pool = SlotPool<slot_count>;
using State = Pool::State;
struct Commands {
    PFN_vkGetDeviceQueue get_queue{};
    PFN_vkCreateCommandPool create_pool{};
    PFN_vkDestroyCommandPool destroy_pool{};
    PFN_vkAllocateCommandBuffers allocate{};
    PFN_vkResetCommandBuffer reset{};
    PFN_vkBeginCommandBuffer begin{};
    PFN_vkEndCommandBuffer end{};
    PFN_vkCmdPipelineBarrier barrier{};
    PFN_vkQueueWaitIdle idle{};
    PFN_vkCreatePipelineCache create_pipeline_cache{};
    PFN_vkDestroyPipelineCache destroy_pipeline_cache{};
};
struct Slot {
    std::optional<ConversionCacheEntry> cache;
    std::optional<FrameLease> lease;
    std::optional<Submission> submission;
    Pool::Ticket ticket{};
    VkCommandBuffer cmd{};
    VkSemaphore reclaimed{};
    VkFence reclaim_fence{};
    void *texture{};
    uint32_t width{}, height{};
    bool converted{};
};
struct QueueLock {
    const ambient_ahb_config &c;
    explicit QueueLock(const ambient_ahb_config &config) : c(config) { c.lock_queue(c.opaque); }
    ~QueueLock() { c.unlock_queue(c.opaque); }
};
}

struct ambient_ahb_owner {
    explicit ambient_ahb_owner(const ambient_ahb_config &config) : c(config) {}
    ambient_ahb_config c;
    Dispatch images{};
    TargetDispatch targets{};
    SampleDispatch samples{};
    QueueDispatch sync{};
    Commands commands{};
    VkQueue queue{};
    VkCommandPool command_pool{};
    VkPipelineCache pipeline_cache{};
    Pool pool;
    std::array<Slot, slot_count> slots;
    bool failed{};

    bool init() {
        auto get = reinterpret_cast<PFN_vkGetDeviceProcAddr>(c.get_proc(c.instance, "vkGetDeviceProcAddr"));
        if (!get) return false;
#define D(group, member, name) group.member = reinterpret_cast<PFN_vk##name>(get(c.device, "vk" #name)); if (!group.member) return false
#define I(group, member, name) group.member = reinterpret_cast<PFN_vk##name>(c.get_proc(c.instance, "vk" #name)); if (!group.member) return false
        D(images, properties, GetAndroidHardwareBufferPropertiesANDROID);
        D(images, create_image, CreateImage); D(images, destroy_image, DestroyImage);
        D(images, allocate_memory, AllocateMemory); D(images, free_memory, FreeMemory);
        D(images, bind_image, BindImageMemory);
        D(images, create_conversion, CreateSamplerYcbcrConversion);
        D(images, destroy_conversion, DestroySamplerYcbcrConversion);
        D(images, create_view, CreateImageView); D(images, destroy_view, DestroyImageView);
        D(images, create_sampler, CreateSampler); D(images, destroy_sampler, DestroySampler);
        I(targets, format_properties, GetPhysicalDeviceFormatProperties);
        I(targets, memory_properties, GetPhysicalDeviceMemoryProperties);
        targets.create_image = images.create_image; targets.destroy_image = images.destroy_image;
        targets.allocate_memory = images.allocate_memory; targets.free_memory = images.free_memory;
        targets.bind_image = images.bind_image; targets.create_view = images.create_view;
        targets.destroy_view = images.destroy_view;
        D(targets, memory_requirements, GetImageMemoryRequirements);
        D(samples, create_layout, CreateDescriptorSetLayout); D(samples, destroy_layout, DestroyDescriptorSetLayout);
        D(samples, create_pipeline_layout, CreatePipelineLayout); D(samples, destroy_pipeline_layout, DestroyPipelineLayout);
        D(samples, create_pool, CreateDescriptorPool); D(samples, destroy_pool, DestroyDescriptorPool);
        D(samples, allocate_sets, AllocateDescriptorSets); D(samples, update_sets, UpdateDescriptorSets);
        D(samples, create_render_pass, CreateRenderPass); D(samples, destroy_render_pass, DestroyRenderPass);
        D(samples, create_framebuffer, CreateFramebuffer); D(samples, destroy_framebuffer, DestroyFramebuffer);
        D(samples, create_shader, CreateShaderModule); D(samples, destroy_shader, DestroyShaderModule);
        D(samples, create_pipeline, CreateGraphicsPipelines); D(samples, destroy_pipeline, DestroyPipeline);
        D(samples, begin_pass, CmdBeginRenderPass); D(samples, end_pass, CmdEndRenderPass);
        D(samples, bind_pipeline, CmdBindPipeline); D(samples, bind_sets, CmdBindDescriptorSets);
        D(samples, push_constants, CmdPushConstants); D(samples, set_viewport, CmdSetViewport);
        D(samples, set_scissor, CmdSetScissor); D(samples, draw, CmdDraw);
        D(sync, create_semaphore, CreateSemaphore); D(sync, destroy_semaphore, DestroySemaphore);
        D(sync, create_fence, CreateFence); D(sync, destroy_fence, DestroyFence);
        D(sync, import_fd, ImportSemaphoreFdKHR); D(sync, export_fd, GetSemaphoreFdKHR);
        D(sync, submit, QueueSubmit); D(sync, fence_status, GetFenceStatus);
        D(commands, get_queue, GetDeviceQueue);
        D(commands, create_pool, CreateCommandPool); D(commands, destroy_pool, DestroyCommandPool);
        D(commands, allocate, AllocateCommandBuffers); D(commands, reset, ResetCommandBuffer);
        D(commands, begin, BeginCommandBuffer); D(commands, end, EndCommandBuffer);
        D(commands, barrier, CmdPipelineBarrier); D(commands, idle, QueueWaitIdle);
        D(commands, create_pipeline_cache, CreatePipelineCache);
        D(commands, destroy_pipeline_cache, DestroyPipelineCache);
#undef I
#undef D
        commands.get_queue(c.device, c.family, 0, &queue);
        if (!queue) return false;
        VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pc.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pc.queueFamilyIndex = c.family;
        if (commands.create_pool(c.device, &pc, nullptr, &command_pool) != VK_SUCCESS) return false;
        VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        alloc.commandPool = command_pool;
        alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        alloc.commandBufferCount = slot_count;
        std::array<VkCommandBuffer, slot_count> buffers{};
        if (commands.allocate(c.device, &alloc, buffers.data()) != VK_SUCCESS) return false;
        // Shared driver compilation cache across all buffers, layouts and slots.
        // Host access is serialized by the display owner thread. Failure is
        // nonfatal: per-buffer pass reuse still works without this cache.
        VkPipelineCacheCreateInfo cache_info{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
        if (commands.create_pipeline_cache(c.device, &cache_info, nullptr, &pipeline_cache) != VK_SUCCESS)
            pipeline_cache = VK_NULL_HANDLE;
        for (size_t i = 0; i < slot_count; ++i) {
            slots[i].cmd = buffers[i];
            slots[i].cache.emplace(c.physical, c.device, images, targets, samples,
                                   ShaderCode{c.vert_words, c.vert_bytes},
                                   ShaderCode{c.frag_words, c.frag_bytes}, pipeline_cache);
        }
        return true;
    }

    void clear_reclaim(Slot &s) {
        if (s.reclaim_fence) sync.destroy_fence(c.device, s.reclaim_fence, nullptr);
        if (s.reclaimed) sync.destroy_semaphore(c.device, s.reclaimed, nullptr);
        s.reclaim_fence = VK_NULL_HANDLE;
        s.reclaimed = VK_NULL_HANDLE;
    }
    void clear_texture(Slot &s) {
        if (s.texture) c.destroy_texture(c.opaque, s.texture);
        s.texture = nullptr;
    }
    void quarantine(Slot &s) {
        pool.quarantine(s.ticket);
        failed = true;
    }
    void cancel(Slot &s) {
        s.submission.reset();
        s.lease.reset();
        pool.cancel(s.ticket);
        s.ticket = {};
    }

    void poll() {
        for (auto &s : slots) {
            if (!s.ticket || pool.state(s.ticket.index) == State::Quarantined) continue;
            if (s.submission && !s.converted) {
                VkResult status = s.submission->poll();
                if (status == VK_NOT_READY) continue;
                if (status != VK_SUCCESS || !pool.converted(s.ticket)) {
                    quarantine(s);
                    continue;
                }
                s.converted = true;
                s.lease.reset();
            }
            if (pool.state(s.ticket.index) != State::Reclaiming) continue;
            VkResult status = sync.fence_status(c.device, s.reclaim_fence);
            if (status == VK_NOT_READY) continue;
            if (status != VK_SUCCESS || !s.converted ||
                !s.submission->retire_after_consumer(true) || !pool.reclaimed(s.ticket)) {
                quarantine(s);
                continue;
            }
            clear_reclaim(s);
            s.submission.reset();
            s.ticket = {};
        }
    }

    VkResult prepare(const ambient_ahb_input &in, ambient_ahb_output &out) {
        out = {};
        // Own the picture before any error path, including backpressure.
        if (failed || !in.picture || !in.buffer || in.acquire_fd < -1 ||
            !in.width || !in.height) {
            in.release_picture(in.picture, in.acquire_fd);
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        poll();
        if (failed) {
            in.release_picture(in.picture, in.acquire_fd);
            return VK_ERROR_DEVICE_LOST;
        }
        constexpr VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
        size_t preferred = slot_count;
        for (size_t i = 0; i < slot_count; ++i) {
            if (pool.state(i) == State::Free && slots[i].cache->matches(
                in.buffer, in.width, in.height, format, c.descriptor_count)) {
                preferred = i;
                break;
            }
        }
        auto ticket = pool.acquire(pool.epoch(), preferred);
        if (!ticket) {
            in.release_picture(in.picture, in.acquire_fd);
            return VK_NOT_READY;
        }
        Slot &s = slots[ticket.index];
        s.ticket = ticket;
        s.converted = false;
        s.lease.emplace(in.picture, in.acquire_fd, in.release_picture, false);
        if (s.texture && (s.width != in.width || s.height != in.height)) {
            // The held wrapper may still own image views; destroy it before
            // changing the target. GPU work for this slot is already retired.
            clear_texture(s);
        }
        VkResult result = s.cache->prepare_retired(in.buffer, in.width, in.height, format, c.descriptor_count);
        if (result != VK_SUCCESS) { cancel(s); return result; }
        if (!s.cache->imported()->color_matches(in.model, in.range)) {
            cancel(s);
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
        }
        if (!s.texture) {
            s.texture = c.wrap(c.opaque, s.cache->target().image(), format,
                               in.width, in.height, RgbTarget::usage);
            if (!s.texture) { cancel(s); return VK_ERROR_FORMAT_NOT_SUPPORTED; }
            s.width = in.width; s.height = in.height;
        }
        if ((result = commands.reset(s.cmd, 0)) != VK_SUCCESS) { cancel(s); return result; }
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if ((result = commands.begin(s.cmd, &begin)) != VK_SUCCESS) { cancel(s); return result; }
        auto image = s.cache->imported()->image();
        if (!record_foreign_acquire(commands.barrier, s.cmd, image, c.family) ||
            !s.cache->pass()->record(s.cmd, in.crop) ||
            !record_foreign_release(commands.barrier, s.cmd, image, c.family)) {
            cancel(s); return VK_ERROR_INITIALIZATION_FAILED;
        }
        if ((result = commands.end(s.cmd)) != VK_SUCCESS) { cancel(s); return result; }
        s.submission.emplace(c.device, sync);
        {
            QueueLock lock(c);
            result = s.submission->submit(queue, s.cmd, *s.lease);
        }
        if (result != VK_SUCCESS) {
            if (s.submission->uncertain()) {
                pool.submitted(ticket);
                quarantine(s);
            } else cancel(s);
            return result;
        }
        if (!pool.submitted(ticket) || !pool.begin_render_waiting_conversion(ticket)) {
            quarantine(s); return VK_ERROR_INITIALIZATION_FAILED;
        }
        VkSemaphore ready = s.submission->handoff_ready_semaphore();
        if (!ready) { quarantine(s); return VK_ERROR_INITIALIZATION_FAILED; }
        c.release(c.opaque, s.texture, ready);
        out = {s.texture, ticket.index, ticket.generation};
        return VK_SUCCESS;
    }

    bool finish(ambient_ahb_output out) {
        if (out.index >= slot_count || !out.texture) return false;
        Slot &s = slots[out.index];
        if (!s.ticket || s.ticket.generation != out.generation ||
            s.texture != out.texture || pool.state(out.index) != State::Rendering) return false;
        VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (sync.create_semaphore(c.device, &si, nullptr, &s.reclaimed) != VK_SUCCESS ||
            sync.create_fence(c.device, &fi, nullptr, &s.reclaim_fence) != VK_SUCCESS ||
            !c.hold_and_flush(c.opaque, s.texture, s.reclaimed)) {
            quarantine(s); return false;
        }
        // hold_and_flush enqueues the signal BEFORE this wait on the same queue.
        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo info{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        info.waitSemaphoreCount = 1;
        info.pWaitSemaphores = &s.reclaimed;
        info.pWaitDstStageMask = &stage;
        VkResult result;
        {
            QueueLock lock(c);
            result = sync.submit(queue, 1, &info, s.reclaim_fence);
        }
        if (result != VK_SUCCESS || !pool.begin_reclaim(s.ticket)) {
            quarantine(s); return false;
        }
        return true;
    }

    bool drain() {
        if (!queue) return true;
        VkResult result;
        { QueueLock lock(c); result = commands.idle(queue); }
        if (result != VK_SUCCESS) return false;
        for (auto &s : slots) {
            // Destroy the wrapper and its deferred dependencies before the
            // conversion semaphore it may still reference on a failed hold.
            clear_texture(s);
            if (s.submission && !s.submission->drain_after_idle(true)) return false;
            clear_reclaim(s);
            s.submission.reset();
            s.lease.reset();
            s.cache.reset();
        }
        return true;
    }
    ~ambient_ahb_owner() {
        if (pipeline_cache) commands.destroy_pipeline_cache(c.device, pipeline_cache, nullptr);
        if (command_pool) commands.destroy_pool(c.device, command_pool, nullptr);
    }
};

extern "C" ambient_ahb_owner *ambient_ahb_open(const ambient_ahb_config *c) {
    if (!c || !c->instance || !c->physical || !c->device || !c->get_proc ||
        !c->lock_queue || !c->unlock_queue || !c->wrap || !c->release ||
        !c->hold_and_flush || !c->destroy_texture || !c->descriptor_count ||
        !ShaderCode{c->vert_words, c->vert_bytes}.valid() ||
        !ShaderCode{c->frag_words, c->frag_bytes}.valid()) return nullptr;
    auto *owner = new (std::nothrow) ambient_ahb_owner(*c);
    if (!owner) return nullptr;
    if (!owner->init()) { delete owner; return nullptr; }
    return owner;
}
extern "C" VkResult ambient_ahb_prepare(ambient_ahb_owner *owner, const ambient_ahb_input *in,
                                         ambient_ahb_output *out) {
    if (out) *out = {};
    if (!in || !in->release_picture) return VK_ERROR_INITIALIZATION_FAILED;
    if (!owner || !out) {
        in->release_picture(in->picture, in->acquire_fd);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    return owner->prepare(*in, *out);
}
extern "C" bool ambient_ahb_finish(ambient_ahb_owner *owner, ambient_ahb_output out) {
    return owner && owner->finish(out);
}
extern "C" void ambient_ahb_poll(ambient_ahb_owner *owner) {
    if (owner) owner->poll();
}
extern "C" bool ambient_ahb_close(ambient_ahb_owner *owner) {
    if (!owner) return true;
    if (!owner->drain()) return false;
    delete owner;
    return true;
}
