#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <vulkan/vulkan.h>
#include <android/hardware_buffer.h>

#ifdef __cplusplus
extern "C" {
#endif

/* C boundary: VLC/libplacebo headers stay in the C translation unit. All
 * functions run on the display owner thread. Queue callbacks serialize raw
 * submissions with libplacebo on the SAME device and graphics queue. */
struct ambient_ahb_owner;
struct ambient_ahb_config {
    VkInstance instance;
    VkPhysicalDevice physical;
    VkDevice device;
    uint32_t family;
    PFN_vkGetInstanceProcAddr get_proc;
    void *opaque;
    void (*lock_queue)(void *);
    void (*unlock_queue)(void *);
    void *(*wrap)(void *, VkImage, VkFormat, uint32_t, uint32_t, VkImageUsageFlags);
    void (*release)(void *, void *, VkSemaphore);
    bool (*hold_and_flush)(void *, void *, VkSemaphore);
    void (*destroy_texture)(void *, void *);
    /* The caller must flush libplacebo before close. */
    const uint32_t *vert_words;
    size_t vert_bytes;
    const uint32_t *frag_words;
    size_t frag_bytes;
    uint32_t descriptor_count;
};

struct ambient_ahb_input {
    void *picture;
    int acquire_fd;
    void (*release_picture)(void *, int);
    AHardwareBuffer *buffer;
    uint32_t width, height; /* visible output dimensions */
    float crop[4]; /* normalized origin and extent */
    VkSamplerYcbcrModelConversion model;
    VkSamplerYcbcrRange range;
    bool high_precision; /* BT.2020 PQ/HLG: never quantize into 8-bit RGB */
};
struct ambient_ahb_output {
    const void *texture;
    size_t index;
    uint64_t generation;
};

struct ambient_ahb_owner *ambient_ahb_open(const struct ambient_ahb_config *);
/* Consumes input picture/fd on every return with a valid release callback.
 * VK_NOT_READY means bounded backpressure, not a blocking CPU wait.
 * On success the texture is already released to libplacebo with a GPU wait. */
VkResult ambient_ahb_prepare(struct ambient_ahb_owner *, const struct ambient_ahb_input *,
                             struct ambient_ahb_output *);
/* Call exactly once after the final use of the output in pl_render_image,
 * including its error path. No later rendering may reference that texture. */
bool ambient_ahb_finish(struct ambient_ahb_owner *, struct ambient_ahb_output);
void ambient_ahb_poll(struct ambient_ahb_owner *);
/* On false, ownership is uncertain: caller must not free native resources
 * through this owner. The experimental path must be disabled and torn down. */
bool ambient_ahb_close(struct ambient_ahb_owner *);

#ifdef __cplusplus
}
#endif
