#pragma once
#include <vulkan/vulkan.h>
#include <cstddef>
#include <cstdint>

namespace ambient::vlc_ahb {

// SDR-only GPU conversion pass. The immutable YCbCr sampler must outlive the
// descriptor layout/pipeline. The target must be an ordinary UNORM RGB image
// with COLOR_ATTACHMENT | SAMPLED usage, owned by this graphics queue.
// Caller retains this pass, imported image and target until its fence signals.
struct SampleDispatch {
    PFN_vkCreateDescriptorSetLayout create_layout{};
    PFN_vkDestroyDescriptorSetLayout destroy_layout{};
    PFN_vkCreatePipelineLayout create_pipeline_layout{};
    PFN_vkDestroyPipelineLayout destroy_pipeline_layout{};
    PFN_vkCreateDescriptorPool create_pool{};
    PFN_vkDestroyDescriptorPool destroy_pool{};
    PFN_vkAllocateDescriptorSets allocate_sets{};
    PFN_vkUpdateDescriptorSets update_sets{};
    PFN_vkCreateRenderPass create_render_pass{};
    PFN_vkDestroyRenderPass destroy_render_pass{};
    PFN_vkCreateFramebuffer create_framebuffer{};
    PFN_vkDestroyFramebuffer destroy_framebuffer{};
    PFN_vkCreateShaderModule create_shader{};
    PFN_vkDestroyShaderModule destroy_shader{};
    PFN_vkCreateGraphicsPipelines create_pipeline{};
    PFN_vkDestroyPipeline destroy_pipeline{};
    PFN_vkCmdBeginRenderPass begin_pass{};
    PFN_vkCmdEndRenderPass end_pass{};
    PFN_vkCmdBindPipeline bind_pipeline{};
    PFN_vkCmdBindDescriptorSets bind_sets{};
    PFN_vkCmdPushConstants push_constants{};
    PFN_vkCmdSetViewport set_viewport{};
    PFN_vkCmdSetScissor set_scissor{};
    PFN_vkCmdDraw draw{};
    bool valid() const {
        return create_layout && destroy_layout && create_pipeline_layout && destroy_pipeline_layout &&
            create_pool && destroy_pool && allocate_sets && update_sets && create_render_pass &&
            destroy_render_pass && create_framebuffer && destroy_framebuffer && create_shader &&
            destroy_shader && create_pipeline && destroy_pipeline && begin_pass && end_pass &&
            bind_pipeline && bind_sets && push_constants && set_viewport && set_scissor && draw;
    }
};

struct ShaderCode {
    const uint32_t *words{};
    size_t bytes{};
    bool valid() const { return words && bytes >= 20 && bytes % 4 == 0 && words[0] == 0x07230203; }
};

class SamplePass {
public:
    SamplePass(VkDevice device, SampleDispatch dispatch) : device_(device), d_(dispatch) {}
    SamplePass(const SamplePass &) = delete;
    SamplePass &operator=(const SamplePass &) = delete;
    ~SamplePass() { reset(); } // only after the caller has proven GPU retirement

    // combined_count must cover the implementation's combined-image-sampler
    // descriptor consumption for this conversion (not guessed from plane count).
    VkResult create(VkSampler immutable_sampler, VkImageView input, VkImageView output,
                    VkFormat output_format, uint32_t width, uint32_t height,
                    uint32_t combined_count, ShaderCode vert, ShaderCode frag) {
        if (!device_ || !d_.valid() || layout_ || !immutable_sampler || !input || !output ||
            input == output || !width || !height || !combined_count || !vert.valid() || !frag.valid())
            return VK_ERROR_INITIALIZATION_FAILED;
        if (output_format != VK_FORMAT_R8G8B8A8_UNORM && output_format != VK_FORMAT_B8G8R8A8_UNORM &&
            output_format != VK_FORMAT_R16G16B16A16_UNORM && output_format != VK_FORMAT_R16G16B16A16_SFLOAT)
            return VK_ERROR_FORMAT_NOT_SUPPORTED;

        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.descriptorCount = 1;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        binding.pImmutableSamplers = &immutable_sampler;
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = 1;
        dl.pBindings = &binding;
        VkResult result = d_.create_layout(device_, &dl, nullptr, &layout_);
        if (result != VK_SUCCESS) return fail(result);
        VkPushConstantRange range{VK_SHADER_STAGE_VERTEX_BIT, 0, 4 * sizeof(float)};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &layout_;
        pl.pushConstantRangeCount = 1;
        pl.pPushConstantRanges = &range;
        result = d_.create_pipeline_layout(device_, &pl, nullptr, &pipeline_layout_);
        if (result != VK_SUCCESS) return fail(result);
        VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, combined_count};
        VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pool.maxSets = 1;
        pool.poolSizeCount = 1;
        pool.pPoolSizes = &size;
        result = d_.create_pool(device_, &pool, nullptr, &pool_);
        if (result != VK_SUCCESS) return fail(result);
        VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        alloc.descriptorPool = pool_;
        alloc.descriptorSetCount = 1;
        alloc.pSetLayouts = &layout_;
        result = d_.allocate_sets(device_, &alloc, &set_);
        if (result != VK_SUCCESS) return fail(result);
        VkDescriptorImageInfo image{};
        image.imageView = input;
        image.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        write.dstSet = set_;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &image;
        d_.update_sets(device_, 1, &write, 0, nullptr);

        VkAttachmentDescription attachment{};
        attachment.format = output_format;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; // target contents intentionally discarded
        attachment.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &ref;
        VkSubpassDependency deps[2]{};
        deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        deps[0].dstSubpass = 0;
        deps[0].srcStageMask = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].srcSubpass = 0;
        deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].dstStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        deps[1].dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
        VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rp.attachmentCount = 1;
        rp.pAttachments = &attachment;
        rp.subpassCount = 1;
        rp.pSubpasses = &subpass;
        rp.dependencyCount = 2;
        rp.pDependencies = deps;
        result = d_.create_render_pass(device_, &rp, nullptr, &render_pass_);
        if (result != VK_SUCCESS) return fail(result);
        VkFramebufferCreateInfo fb{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fb.renderPass = render_pass_;
        fb.attachmentCount = 1;
        fb.pAttachments = &output;
        fb.width = width;
        fb.height = height;
        fb.layers = 1;
        result = d_.create_framebuffer(device_, &fb, nullptr, &framebuffer_);
        if (result != VK_SUCCESS) return fail(result);

        VkShaderModule modules[2]{};
        ShaderCode shaders[2] = {vert, frag};
        for (int i = 0; i < 2; ++i) {
            VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            sm.codeSize = shaders[i].bytes;
            sm.pCode = shaders[i].words;
            result = d_.create_shader(device_, &sm, nullptr, &modules[i]);
            if (result != VK_SUCCESS) {
                if (modules[0]) d_.destroy_shader(device_, modules[0], nullptr);
                return fail(result);
            }
        }
        VkPipelineShaderStageCreateInfo stages[2]{};
        for (int i = 0; i < 2; ++i) {
            stages[i].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stages[i].stage = i == 0 ? VK_SHADER_STAGE_VERTEX_BIT : VK_SHADER_STAGE_FRAGMENT_BIT;
            stages[i].module = modules[i];
            stages[i].pName = "main";
        }
        VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        vp.viewportCount = vp.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rs.polygonMode = VK_POLYGON_MODE_FILL;
        rs.cullMode = VK_CULL_MODE_NONE;
        rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rs.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState color{};
        color.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        cb.attachmentCount = 1;
        cb.pAttachments = &color;
        VkDynamicState dyns[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dyn{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dyn.dynamicStateCount = 2;
        dyn.pDynamicStates = dyns;
        VkGraphicsPipelineCreateInfo gp{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        gp.stageCount = 2;
        gp.pStages = stages;
        gp.pVertexInputState = &vi;
        gp.pInputAssemblyState = &ia;
        gp.pViewportState = &vp;
        gp.pRasterizationState = &rs;
        gp.pMultisampleState = &ms;
        gp.pColorBlendState = &cb;
        gp.pDynamicState = &dyn;
        gp.layout = pipeline_layout_;
        gp.renderPass = render_pass_;
        result = d_.create_pipeline(device_, VK_NULL_HANDLE, 1, &gp, nullptr, &pipeline_);
        for (auto module : modules) d_.destroy_shader(device_, module, nullptr);
        if (result != VK_SUCCESS) return fail(result);
        extent_ = {width, height};
        return VK_SUCCESS;
    }

    // Normalized crop covers exactly the visible decoder image. No hidden GL flip.
    // Orientation/display scaling remain in VLC/libplacebo, not this conversion.
    bool record(VkCommandBuffer cmd, const float (&crop)[4]) const {
        if (!cmd || !pipeline_ || !(crop[0] >= 0 && crop[1] >= 0 && crop[2] > 0 && crop[3] > 0 &&
            crop[0] + crop[2] <= 1.000001f && crop[1] + crop[3] <= 1.000001f)) return false;
        VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        begin.renderPass = render_pass_;
        begin.framebuffer = framebuffer_;
        begin.renderArea.extent = extent_;
        d_.begin_pass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
        d_.bind_pipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
        d_.bind_sets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_layout_, 0, 1, &set_, 0, nullptr);
        VkViewport viewport{0, 0, static_cast<float>(extent_.width), static_cast<float>(extent_.height), 0, 1};
        VkRect2D scissor{{0, 0}, extent_};
        d_.set_viewport(cmd, 0, 1, &viewport);
        d_.set_scissor(cmd, 0, 1, &scissor);
        d_.push_constants(cmd, pipeline_layout_, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(crop), crop);
        d_.draw(cmd, 3, 1, 0, 0);
        d_.end_pass(cmd);
        return true;
    }

private:
    VkResult fail(VkResult result) { reset(); return result; }
    void reset() {
        if (pipeline_) d_.destroy_pipeline(device_, pipeline_, nullptr);
        if (framebuffer_) d_.destroy_framebuffer(device_, framebuffer_, nullptr);
        if (render_pass_) d_.destroy_render_pass(device_, render_pass_, nullptr);
        if (pool_) d_.destroy_pool(device_, pool_, nullptr);
        if (pipeline_layout_) d_.destroy_pipeline_layout(device_, pipeline_layout_, nullptr);
        if (layout_) d_.destroy_layout(device_, layout_, nullptr);
        pipeline_ = VK_NULL_HANDLE; framebuffer_ = VK_NULL_HANDLE; render_pass_ = VK_NULL_HANDLE;
        pool_ = VK_NULL_HANDLE; pipeline_layout_ = VK_NULL_HANDLE; layout_ = VK_NULL_HANDLE;
        set_ = VK_NULL_HANDLE; extent_ = {};
    }
    VkDevice device_{};
    SampleDispatch d_{};
    VkDescriptorSetLayout layout_{};
    VkPipelineLayout pipeline_layout_{};
    VkDescriptorPool pool_{};
    VkDescriptorSet set_{};
    VkRenderPass render_pass_{};
    VkFramebuffer framebuffer_{};
    VkPipeline pipeline_{};
    VkExtent2D extent_{};
};
} // namespace ambient::vlc_ahb
