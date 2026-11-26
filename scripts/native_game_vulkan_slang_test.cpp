// SPDX-License-Identifier: GPL-3.0-or-later
// Real offscreen Slang smoke through the production C-API host, not a GLSL substitute.
#include "vulkanslangpipeline.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

using libretrodroid::VulkanSlangPipeline;

static void require(bool value, const std::string& message) {
    if (!value) throw std::runtime_error(message);
}
static void check(VkResult result, const char* operation) {
    require(result == VK_SUCCESS, std::string(operation) + ": " + std::to_string(result));
}

// Only librashader receives this dispatch proxy; fixture images/commands still
// use the real loader. Each armed site returns one legal allocation failure,
// then forwards normally, including when the runtime builds passes in parallel.
enum class Fault { None, DescriptorSets, GraphicsPipeline, Framebuffer };
static std::atomic<Fault> armedFault { Fault::None };
static std::atomic<unsigned> injectedFaults { 0 };
static bool inject(Fault site) {
    if (!armedFault.compare_exchange_strong(site, Fault::None)) return false;
    ++injectedFaults;
    return true;
}
static void arm(Fault site) { injectedFaults = 0; armedFault = site; }
static VKAPI_ATTR VkResult VKAPI_CALL faultDescriptorSets(VkDevice device,
    const VkDescriptorSetAllocateInfo* info, VkDescriptorSet* sets) {
    if (inject(Fault::DescriptorSets)) {
        std::fill_n(sets, info->descriptorSetCount, VK_NULL_HANDLE);
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    return vkAllocateDescriptorSets(device, info, sets);
}
static VKAPI_ATTR VkResult VKAPI_CALL faultGraphicsPipeline(VkDevice device, VkPipelineCache cache,
    uint32_t count, const VkGraphicsPipelineCreateInfo* info,
    const VkAllocationCallbacks* allocator, VkPipeline* pipelines) {
    if (inject(Fault::GraphicsPipeline)) {
        std::fill_n(pipelines, count, VK_NULL_HANDLE);
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    return vkCreateGraphicsPipelines(device, cache, count, info, allocator, pipelines);
}
static VKAPI_ATTR VkResult VKAPI_CALL faultFramebuffer(VkDevice device,
    const VkFramebufferCreateInfo* info, const VkAllocationCallbacks* allocator, VkFramebuffer* frame) {
    if (inject(Fault::Framebuffer)) {
        *frame = VK_NULL_HANDLE;
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    return vkCreateFramebuffer(device, info, allocator, frame);
}
static PFN_vkVoidFunction faultFunction(const char* name) {
    if (std::strcmp(name, "vkAllocateDescriptorSets") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(faultDescriptorSets);
    if (std::strcmp(name, "vkCreateGraphicsPipelines") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(faultGraphicsPipeline);
    if (std::strcmp(name, "vkCreateFramebuffer") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(faultFramebuffer);
    return nullptr;
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL faultDeviceProc(VkDevice device, const char* name) {
    if (const auto replacement = faultFunction(name)) return replacement;
    return vkGetDeviceProcAddr(device, name);
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL faultInstanceProc(VkInstance instance, const char* name) {
    if (std::strcmp(name, "vkGetDeviceProcAddr") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(faultDeviceProc);
    if (const auto replacement = faultFunction(name)) return replacement;
    return vkGetInstanceProcAddr(instance, name);
}

struct Device {
    VkInstance instance {};
    VkPhysicalDevice physical {};
    VkDevice device {};
    VkQueue queue {};
    VkCommandPool pool {};
    VkCommandBuffer commands {};
    VkBuffer staging {};
    VkDeviceMemory stagingMemory {};
    void* mapped {};
    static constexpr VkDeviceSize READBACK_OFFSET = 65536;
    static constexpr VkDeviceSize STAGING_SIZE = READBACK_OFFSET + 256 * 256 * 4;

    Device() {
        VkApplicationInfo app { VK_STRUCTURE_TYPE_APPLICATION_INFO };
        app.pApplicationName = "Ambient TV real Slang GPU smoke";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo instanceInfo { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
        instanceInfo.pApplicationInfo = &app;
        check(vkCreateInstance(&instanceInfo, nullptr, &instance), "create instance");
        uint32_t count = 0;
        check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "enumerate device count");
        require(count != 0, "no Vulkan device; install mesa-vulkan-drivers");
        std::vector<VkPhysicalDevice> devices(count);
        check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "enumerate devices");
        physical = devices.front();
        VkPhysicalDeviceProperties properties {};
        vkGetPhysicalDeviceProperties(physical, &properties);
        std::cout << "Vulkan device: " << properties.deviceName << std::endl;
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
        std::vector<VkQueueFamilyProperties> families(count);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families.data());
        uint32_t family = 0;
        while (family < count && !(families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) ++family;
        require(family < count, "no graphics queue");
        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
        queueInfo.queueFamilyIndex = family;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        VkDeviceCreateInfo deviceInfo { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        check(vkCreateDevice(physical, &deviceInfo, nullptr, &device), "create device");
        vkGetDeviceQueue(device, family, 0, &queue);
        VkCommandPoolCreateInfo poolInfo { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = family;
        check(vkCreateCommandPool(device, &poolInfo, nullptr, &pool), "create command pool");
        VkCommandBufferAllocateInfo commandInfo { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
        commandInfo.commandPool = pool;
        commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commandInfo.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device, &commandInfo, &commands), "allocate commands");
        VkBufferCreateInfo bufferInfo { VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
        bufferInfo.size = STAGING_SIZE;
        bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        check(vkCreateBuffer(device, &bufferInfo, nullptr, &staging), "create staging buffer");
        VkMemoryRequirements requirements {};
        vkGetBufferMemoryRequirements(device, staging, &requirements);
        stagingMemory = allocate(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        check(vkBindBufferMemory(device, staging, stagingMemory, 0), "bind staging memory");
        check(vkMapMemory(device, stagingMemory, 0, VK_WHOLE_SIZE, 0, &mapped), "map staging memory");
    }
    ~Device() {
        if (device) {
            vkDeviceWaitIdle(device);
            if (mapped) vkUnmapMemory(device, stagingMemory);
            if (staging) vkDestroyBuffer(device, staging, nullptr);
            if (stagingMemory) vkFreeMemory(device, stagingMemory, nullptr);
            if (pool) vkDestroyCommandPool(device, pool, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (instance) vkDestroyInstance(instance, nullptr);
    }
    VkDeviceMemory allocate(const VkMemoryRequirements& requirements, VkMemoryPropertyFlags flags) {
        VkPhysicalDeviceMemoryProperties properties {};
        vkGetPhysicalDeviceMemoryProperties(physical, &properties);
        uint32_t type = 0;
        while (type < properties.memoryTypeCount &&
               (!(requirements.memoryTypeBits & (1u << type)) ||
                (properties.memoryTypes[type].propertyFlags & flags) != flags)) ++type;
        require(type < properties.memoryTypeCount, "no compatible memory type");
        VkMemoryAllocateInfo info { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
        info.allocationSize = requirements.size;
        info.memoryTypeIndex = type;
        VkDeviceMemory memory {};
        check(vkAllocateMemory(device, &info, nullptr, &memory), "allocate memory");
        return memory;
    }
    void begin() {
        check(vkResetCommandBuffer(commands, 0), "reset commands");
        VkCommandBufferBeginInfo info { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(commands, &info), "begin commands");
    }
    void submit() {
        check(vkEndCommandBuffer(commands), "end commands");
        VkSubmitInfo info { VK_STRUCTURE_TYPE_SUBMIT_INFO };
        info.commandBufferCount = 1;
        info.pCommandBuffers = &commands;
        check(vkQueueSubmit(queue, 1, &info, VK_NULL_HANDLE), "submit");
        check(vkQueueWaitIdle(queue), "wait for render/readback");
    }
    void barrier(VkImage image, VkImageLayout before, VkImageLayout after,
                 VkAccessFlags sourceAccess, VkAccessFlags destinationAccess,
                 VkPipelineStageFlags sourceStage, VkPipelineStageFlags destinationStage) {
        VkImageMemoryBarrier info { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        info.srcAccessMask = sourceAccess;
        info.dstAccessMask = destinationAccess;
        info.oldLayout = before;
        info.newLayout = after;
        info.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        info.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        info.image = image;
        info.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        vkCmdPipelineBarrier(commands, sourceStage, destinationStage, 0, 0, nullptr, 0, nullptr, 1, &info);
    }
};

struct Image {
    Device& owner;
    VkImage image {};
    VkDeviceMemory memory {};
    Image(Device& owner, uint32_t width, uint32_t height) : owner(owner) {
        VkImageCreateInfo info { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
        info.imageType = VK_IMAGE_TYPE_2D;
        info.format = VK_FORMAT_R8G8B8A8_UNORM;
        info.extent = { width, height, 1 };
        info.mipLevels = 1;
        info.arrayLayers = 1;
        info.samples = VK_SAMPLE_COUNT_1_BIT;
        info.tiling = VK_IMAGE_TILING_OPTIMAL;
        info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                     VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        check(vkCreateImage(owner.device, &info, nullptr, &image), "create image");
        VkMemoryRequirements requirements {};
        vkGetImageMemoryRequirements(owner.device, image, &requirements);
        memory = owner.allocate(requirements, 0);
        check(vkBindImageMemory(owner.device, image, memory, 0), "bind image memory");
    }
    ~Image() {
        vkDestroyImage(owner.device, image, nullptr);
        vkFreeMemory(owner.device, memory, nullptr);
    }
};

static void fillChart(void* output, uint32_t width, uint32_t height) {
    const uint8_t colors[][3] {
        {224,32,16}, {32,208,64}, {16,64,224}, {224,192,32},
        {192,32,208}, {32,208,208}, {224,128,64}, {96,48,160}
    };
    auto* pixels = static_cast<uint8_t*>(output);
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            auto* pixel = pixels + (size_t(y)*width+x)*4;
            if (y >= height/2) {
                pixel[0] = pixel[1] = pixel[2] = uint8_t(16 + x*224/(width-1));
            } else {
                const auto& color = colors[std::min(7u, x*8/width)];
                std::memcpy(pixel, color, 3);
            }
            pixel[3] = 255;
        }
    }
}

static void renderFrame(Device& d, VulkanSlangPipeline& pipeline, unsigned frame,
                        bool expectAllocationFailure = false) {
    // The second frame shrinks both source and output while retaining the chain.
    const uint32_t sourceWidth = frame == 0 ? 32 : 16;
    const uint32_t sourceHeight = frame == 0 ? 24 : 12;
    const uint32_t contentWidth = frame == 0 ? 128 : 80;
    const uint32_t contentHeight = frame == 0 ? 96 : 60;
    const uint32_t border = 16;
    const uint32_t targetWidth = contentWidth + 2*border;
    const uint32_t targetHeight = contentHeight + 2*border;
    Image input(d, sourceWidth, sourceHeight);
    Image target(d, targetWidth, targetHeight);
    fillChart(d.mapped, sourceWidth, sourceHeight);
    std::memset(static_cast<uint8_t*>(d.mapped) + Device::READBACK_OFFSET, 0xcd,
                size_t(targetWidth)*targetHeight*4);
    d.begin();
    d.barrier(input.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
              0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy upload {};
    upload.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    upload.imageExtent = { sourceWidth, sourceHeight, 1 };
    vkCmdCopyBufferToImage(d.commands, d.staging, input.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &upload);
    d.barrier(input.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
              VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    d.barrier(target.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
              0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    // Deliberately poisonous previous contents: the final pass must clear every
    // letterbox pixel even though it only draws within the 16px inset viewport.
    VkClearColorValue poison {};
    poison.float32[0] = poison.float32[2] = poison.float32[3] = 1.0f;
    const VkImageSubresourceRange range { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    vkCmdClearColorImage(d.commands, target.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &poison, 1, &range);
    d.barrier(target.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
              VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    const bool recorded = pipeline.record(d.commands, frame, input.image, VK_FORMAT_R8G8B8A8_UNORM,
                            sourceWidth, sourceHeight, target.image, VK_FORMAT_R8G8B8A8_UNORM,
                            targetWidth, targetHeight, float(border), float(border),
                            contentWidth, contentHeight, 4.0f/3.0f, 60.0);
    if (!recorded || expectAllocationFailure) {
        // These commands were never submitted. Discard them BEFORE releasing
        // the failed chain's views/framebuffers and the fixture's input images.
        check(vkResetCommandBuffer(d.commands, 0), "discard incomplete shader frame");
        pipeline.releaseGpu();
        require(expectAllocationFailure && !recorded && injectedFaults == 1,
                "frame record failed unexpectedly or allocation fault was not reached/rejected");
        return;
    }
    d.barrier(target.image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
              VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
              VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy download {};
    download.bufferOffset = Device::READBACK_OFFSET;
    download.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    download.imageExtent = { targetWidth, targetHeight, 1 };
    vkCmdCopyImageToBuffer(d.commands, target.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.staging, 1, &download);
    VkBufferMemoryBarrier host { VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER };
    host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    host.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    host.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    host.buffer = d.staging;
    host.offset = Device::READBACK_OFFSET;
    host.size = size_t(targetWidth)*targetHeight*4;
    vkCmdPipelineBarrier(d.commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 0, nullptr, 1, &host, 0, nullptr);
    d.submit();
    const auto* pixels = static_cast<const uint8_t*>(d.mapped) + Device::READBACK_OFFSET;
    std::set<uint32_t> contentColors;
    size_t visible = 0;
    for (uint32_t y = 0; y < targetHeight; ++y) {
        for (uint32_t x = 0; x < targetWidth; ++x) {
            const auto* pixel = pixels + (size_t(y)*targetWidth+x)*4;
            const uint32_t rgb = uint32_t(pixel[0]) << 16 | uint32_t(pixel[1]) << 8 | pixel[2];
            const bool outside = x < border || x >= border+contentWidth ||
                                 y < border || y >= border+contentHeight;
            if (outside) {
                require(rgb == 0, "nonblack border at " + std::to_string(x) + "," + std::to_string(y));
            } else {
                contentColors.insert(rgb);
                if (rgb != 0) ++visible;
            }
        }
    }
    require(contentColors.size() >= 2, "shader produced a uniform/empty image");
    require(visible > size_t(contentWidth)*contentHeight/4, "shader produced mostly empty content");
    // Alpha is deliberately not asserted: librashader clears RGBA(0,0,0,0),
    // while the production Android swapchain requires opaque composition.
}

static void allocationFailures(Device& device, const char* preset) {
    for (const auto site : {Fault::DescriptorSets, Fault::GraphicsPipeline}) {
        const char* name = site == Fault::DescriptorSets ? "descriptor sets" : "graphics pipeline";
        std::cout << "Slang injected constructor failure: " << name << std::endl;
        VulkanSlangPipeline pipeline;
        pipeline.setPresetPath(preset);
        arm(site);
        const bool ready = pipeline.ensure(device.instance, device.physical, device.device,
                                            device.queue, faultInstanceProc);
        armedFault = Fault::None;
        check(vkDeviceWaitIdle(device.device), "wait after rejected chain construction");
        pipeline.releaseGpu();
        require(!ready && injectedFaults == 1, std::string("constructor fault was not reached/rejected: ") + name);
    }
    std::cout << "Slang injected frame failure: framebuffer" << std::endl;
    VulkanSlangPipeline pipeline;
    pipeline.setPresetPath(preset);
    require(pipeline.ensure(device.instance, device.physical, device.device, device.queue,
                             faultInstanceProc), "fault fixture chain creation failed");
    arm(Fault::Framebuffer);
    renderFrame(device, pipeline, 0, true);
    armedFault = Fault::None;
    // Validation runs through final VkDevice destruction in Device::~Device:
    // leaked constructor layouts/pools/cache or early-frame views fail the runner.
    std::cout << "PASS 2 real Slang constructor allocation failures / 1 unsubmitted frame failure\n";
}

int main(int argc, char** argv) {
    try {
        require(argc > 1, "usage: vulkan-slang-test ABSOLUTE_PRESET_PATH ...");
        Device device;
        for (int i = 1; i < argc; ++i) {
            std::cout << "Slang GPU: " << argv[i] << std::endl;
            VulkanSlangPipeline pipeline;
            pipeline.setPresetPath(argv[i]);
            const libretrodroid::SlangFrameGeometry invalid {32, 24, 160, 128, 16, 16, 160, 96};
            const libretrodroid::SlangFrameGeometry valid {32, 24, 160, 128, 16, 16, 128, 96};
            require(!pipeline.ensure(device.instance, device.physical, device.device, device.queue,
                                     vkGetInstanceProcAddr, &invalid) && !pipeline.ready() && !pipeline.failed(),
                    "invalid geometry allocated or poisoned the chain");
            require(pipeline.ensure(device.instance, device.physical, device.device, device.queue,
                                     vkGetInstanceProcAddr, &valid), "ensure failed for " + std::string(argv[i]));
            require(!pipeline.ensure(device.instance, device.physical, device.device, device.queue,
                                     vkGetInstanceProcAddr, &invalid) && pipeline.ready() && !pipeline.failed(),
                    "invalid resize destroyed or poisoned the ready chain");
            renderFrame(device, pipeline, 0);
            renderFrame(device, pipeline, 1);
            pipeline.releaseGpu(); // all submissions completed before chain/device destruction
        }
        std::cout << "PASS " << argc-1 << " real Slang presets / " << 2*(argc-1)
                  << " GPU frames; color-chart content, 16px black borders and shrinking source/target\n";
        allocationFailures(device, argv[1]);
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "FAIL: " << exception.what() << '\n';
        return 1;
    }
}
