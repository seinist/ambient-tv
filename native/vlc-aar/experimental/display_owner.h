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
    uint64_t buffer_id;
    bool buffer_id_valid;
    uint32_t width, height; /* visible output dimensions */
    float crop[4]; /* normalized origin and extent */
    VkSamplerYcbcrModelConversion model;
    VkSamplerYcbcrRange range;
    bool high_precision; /* BT.2020 PQ/HLG: never quantize into 8-bit RGB */
    bool raw_yuv; /* opt-in native P010, preserve unexpanded code values */
};
struct ambient_ahb_output {
    const void *texture;
    size_t index;
    uint64_t generation;
    bool raw_yuv;
    bool metadata_origin_aligned; /* set only by verified VLC/AImage geometry */
    bool fel_coded_confirmed; /* identical coded-extent proof at bridge and renderer */
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
/* After flushing libplacebo: bounded proof of queue retirement or explicit
 * device loss. On false retain the entire renderer/device until process exit. */
bool ambient_ahb_retire(struct ambient_ahb_owner *);
/* Only destroy after retirement. False means caller must quarantine the
 * entire libplacebo context as well as this owner; never destroy its device. */
bool ambient_ahb_close(struct ambient_ahb_owner *);

#ifdef __cplusplus
}
#endif
