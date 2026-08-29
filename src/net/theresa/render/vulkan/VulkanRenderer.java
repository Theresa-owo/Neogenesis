package net.theresa.render.vulkan;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

/**
 * Milestone-1 Vulkan backend: swapchain clear + present with frames in flight
 * and resize-safe swapchain recreation. World rendering does not go through
 * this yet; Minecraft branches to RenderSystem.frame() instead of the GL path.
 */
public class VulkanRenderer {

    private static final int MAX_FRAMES_IN_FLIGHT = 2;

    private VulkanContext context;
    private VulkanSwapchain swapchain;
    private VulkanFrame[] frames;
    private int currentFrame;

    private long renderPass;
    private final List<Long> framebuffers = new ArrayList<>();

    private long window;
    private int framebufferWidth = -1;
    private int framebufferHeight = -1;
    private boolean framebufferResized;

    private long initTimeNanos;
    private long debugMessenger;

    private long pipeline;
    private long pipelineLayout;
    private long vertexBuffer;
    private long vertexMemory;
    private java.nio.ByteBuffer vertexMapped;

    public void init(long window, int width, int height) {
        this.window = window;
        this.context = new VulkanContext(window);
        this.swapchain = new VulkanSwapchain(context, window);
        debugMessenger = VulkanDebug.setup(context.instance);

        this.renderPass = createRenderPass();
        createFramebuffers();

        this.frames = new VulkanFrame[MAX_FRAMES_IN_FLIGHT];
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            frames[i] = new VulkanFrame(context);
        }
        this.initTimeNanos = System.nanoTime();
        createVertexBuffer();
        createGraphicsPipeline();
    }

    public void frame() {
        pollFramebufferSize();
        VulkanFrame frame = frames[currentFrame];

        frame.resetFence();
        int imageIndex = swapchain.acquire(frame.imageAvailable, frame.fence);
        if (imageIndex < 0) {
            recreate();
            return;
        }

        recordClearCommands(frame.commandBuffer, imageIndex);

        VkSubmitInfo submitInfo = VkSubmitInfo.calloc();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            submitInfo.sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pWaitSemaphores(stack.longs(frame.imageAvailable));
            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            submitInfo.pCommandBuffers(stack.pointers(frame.commandBuffer));
            submitInfo.pSignalSemaphores(stack.longs(frame.renderFinished));
            int err = VK10.vkQueueSubmit(context.graphicsQueue, submitInfo, frame.fence);
            VulkanContext.check(err, "vkQueueSubmit");
        } finally {
            submitInfo.free();
        }

        boolean suboptimal = swapchain.present(context.presentQueue, frame.renderFinished, imageIndex);
        currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        if (suboptimal) {
            recreate();
        }
    }

    public void onResize(int width, int height) {
        framebufferResized = true;
    }

    public void cleanup() {
        context.waitIdle();
        for (long fb : framebuffers) {
            VK10.vkDestroyFramebuffer(context.device, fb, null);
        }
        framebuffers.clear();
        if (renderPass != 0L) {
            VK10.vkDestroyRenderPass(context.device, renderPass, null);
            renderPass = 0L;
        }
        if (frames != null) {
            for (VulkanFrame frame : frames) {
                frame.cleanup();
            }
        }
        VulkanDebug.destroy(context.instance, debugMessenger);
        if (swapchain != null) {
            swapchain.cleanup();
        }
        if (context != null) {
            context.cleanup();
        }
    }

    private void recreate() {
        context.waitIdle();
        for (long fb : framebuffers) {
            VK10.vkDestroyFramebuffer(context.device, fb, null);
        }
        framebuffers.clear();
        swapchain.recreate();
        createFramebuffers();
        framebufferResized = false;
    }

    private void pollFramebufferSize() {
        int[] w = new int[1];
        int[] h = new int[1];
        GLFW.glfwGetFramebufferSize(window, w, h);
        if (w[0] == 0 || h[0] == 0) {
            return;
        }
        if (w[0] != framebufferWidth || h[0] != framebufferHeight) {
            framebufferWidth = w[0];
            framebufferHeight = h[0];
            if (framebufferWidth != swapchain.width || framebufferHeight != swapchain.height) {
                framebufferResized = true;
            }
        }
        if (framebufferResized) {
            recreate();
        }
    }

    private void createFramebuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (long imageView : swapchain.imageViews) {
                VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(renderPass)
                        .pAttachments(stack.longs(imageView))
                        .width(swapchain.width)
                        .height(swapchain.height)
                        .layers(1);
                long[] framebuffer = new long[1];
                VulkanContext.check(VK10.vkCreateFramebuffer(context.device, info, null, framebuffer),
                        "vkCreateFramebuffer");
                framebuffers.add(framebuffer[0]);
            }
        }
    }

    private long createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer color = VkAttachmentDescription.calloc(1, stack)
                    .format(swapchain.imageFormat)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK10.VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(color)
                    .pSubpasses(subpass);

            long[] renderPass = new long[1];
            VulkanContext.check(VK10.vkCreateRenderPass(context.device, info, null, renderPass),
                    "vkCreateRenderPass");
            return renderPass[0];
        }
    }

    private void recordClearCommands(VkCommandBuffer commandBuffer, int imageIndex) {
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            beginInfo.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            VulkanContext.check(VK10.vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");

            // Slowly cycling clear colour: proves frames actually present while there is
            // no geometry yet (a static colour would be indistinguishable from a hang).
            float seconds = (System.nanoTime() - initTimeNanos) / 1.0e9f;
            float r = 0.10f + 0.08f * (0.5f + 0.5f * (float) Math.sin(seconds * 1.7));
            float g = 0.12f + 0.08f * (0.5f + 0.5f * (float) Math.sin(seconds * 1.3 + 2.0f));
            float b = 0.16f + 0.10f * (0.5f + 0.5f * (float) Math.sin(seconds * 1.1 + 4.0f));

            VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
            clearValue.color().float32(stack.floats(r, g, b, 1.0f));

            VkRenderPassBeginInfo beginRenderPass = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffers.get(imageIndex))
                    .renderArea(VkRect2D.calloc(stack).offset(VkOffset2D.calloc(stack).set(0, 0))
                            .extent(VkExtent2D.calloc(stack).set(swapchain.width, swapchain.height)))
                    .pClearValues(clearValue);

            VK10.vkCmdBeginRenderPass(commandBuffer, beginRenderPass, VK10.VK_SUBPASS_CONTENTS_INLINE);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0.0f).y(0.0f)
                    .width((float) swapchain.width)
                    .height((float) swapchain.height)
                    .minDepth(0.0f).maxDepth(1.0f);
            org.lwjgl.vulkan.VkRect2D.Buffer scissor = org.lwjgl.vulkan.VkRect2D.calloc(1, stack)
                    .offset(org.lwjgl.vulkan.VkOffset2D.calloc(stack).set(0, 0))
                    .extent(org.lwjgl.vulkan.VkExtent2D.calloc(stack).set(swapchain.width, swapchain.height));
            VK10.vkCmdSetViewport(commandBuffer, 0, viewport);
            VK10.vkCmdSetScissor(commandBuffer, 0, scissor);

            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            LongBuffer vertexBuffers = stack.longs(vertexBuffer);
            LongBuffer offsets = stack.longs(0L);
            VK10.vkCmdBindVertexBuffers(commandBuffer, 0, vertexBuffers, offsets);
            VK10.vkCmdPushConstants(commandBuffer, pipelineLayout, VK10.VK_SHADER_STAGE_VERTEX_BIT, 0,
                    pushConstantData(seconds * 0.7f, (float) swapchain.width / (float) swapchain.height));
            VK10.vkCmdDraw(commandBuffer, 3, 1, 0, 0);

            VK10.vkCmdEndRenderPass(commandBuffer);
            VulkanContext.check(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
        } finally {
            beginInfo.free();
        }
    }
    private static final int VERTEX_SIZE_FLOATS = 5; // vec2 position + vec3 color

    private ByteBuffer pushConstantData(float angleRadians, float aspect) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.ByteBuffer data = stack.malloc(8);
            data.putFloat(angleRadians).putFloat(aspect);
            return (java.nio.ByteBuffer) data.flip();
        }
    }

    private void createVertexBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long bytes = 3L * VERTEX_SIZE_FLOATS * 4L;

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bytes)
                    .usage(VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            long[] buffer = new long[1];
            VulkanContext.check(VK10.vkCreateBuffer(context.device, bufferInfo, null, buffer), "vkCreateBuffer");
            vertexBuffer = buffer[0];

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(context.device, vertexBuffer, requirements);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(context.memoryTypeIndex(requirements.memoryTypeBits(),
                            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            long[] memory = new long[1];
            VulkanContext.check(VK10.vkAllocateMemory(context.device, allocInfo, null, memory), "vkAllocateMemory");
            vertexMemory = memory[0];
            VulkanContext.check(VK10.vkBindBufferMemory(context.device, vertexBuffer, vertexMemory, 0L),
                    "vkBindBufferMemory");

            PointerBuffer mapPointer = stack.mallocPointer(1);
            VulkanContext.check(VK10.vkMapMemory(context.device, vertexMemory, 0L, bytes, 0, mapPointer),
                    "vkMapMemory");
            vertexMapped = mapPointer.getByteBuffer(0, (int) bytes);
            vertexMapped.putFloat(0, -0.5f).putFloat(4, -0.5f);
            vertexMapped.putFloat(8, 1.0f).putFloat(12, 0.0f).putFloat(16, 0.0f);
            vertexMapped.putFloat(20, 0.5f).putFloat(24, -0.5f);
            vertexMapped.putFloat(28, 0.0f).putFloat(32, 1.0f).putFloat(36, 0.0f);
            vertexMapped.putFloat(40, 0.0f).putFloat(44, 0.5f);
            vertexMapped.putFloat(48, 0.0f).putFloat(52, 0.0f).putFloat(56, 1.0f);
        }
    }

    private long createShaderModule(String glsl, VulkanShaders.Stage stage) {
        byte[] spirv = VulkanShaders.compileGlslToSpv(glsl, stage, "neogenesis." + stage.name().toLowerCase());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.ByteBuffer code = stack.malloc(spirv.length);
            code.put(spirv).flip();
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(code);
            long[] module = new long[1];
            VulkanContext.check(VK10.vkCreateShaderModule(context.device, info, null, module), "vkCreateShaderModule");
            return module[0];
        }
    }

    private void createGraphicsPipeline() {
        String vertexGlsl = "#version 450\n"
                + "layout(push_constant) uniform Push { float angle; float aspect; } push;\n"
                + "layout(location = 0) in vec2 inPos;\n"
                + "layout(location = 1) in vec3 inColor;\n"
                + "layout(location = 0) out vec3 fragColor;\n"
                + "void main() {\n"
                + "    float c = cos(push.angle);\n"
                + "    float s = sin(push.angle);\n"
                + "    vec2 p = vec2(inPos.x * c - inPos.y * s, inPos.x * s + inPos.y * c);\n"
                + "    gl_Position = vec4(p.x / push.aspect, p.y, 0.0, 1.0);\n"
                + "    fragColor = inColor;\n"
                + "}\n";
        String fragmentGlsl = "#version 450\n"
                + "layout(location = 0) in vec3 fragColor;\n"
                + "layout(location = 0) out vec4 outColor;\n"
                + "void main() { outColor = vec4(fragColor, 1.0); }\n";

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long vertexModule = createShaderModule(vertexGlsl, VulkanShaders.Stage.VERTEX);
            long fragmentModule = createShaderModule(fragmentGlsl, VulkanShaders.Stage.FRAGMENT);
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(VERTEX_SIZE_FLOATS * 4).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(2, stack);
            attributes.get(0).binding(0).location(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(0);
            attributes.get(1).binding(0).location(1).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(8);
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(binding)
                    .pVertexAttributeDescriptions(attributes);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                    .cullMode(VK10.VK_CULL_MODE_NONE)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .blendEnable(false)
                    .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                    .colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(blendAttachment);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(8);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pPushConstantRanges(pushRange);
            long[] layout = new long[1];
            VulkanContext.check(VK10.vkCreatePipelineLayout(context.device, layoutInfo, null, layout),
                    "vkCreatePipelineLayout");
            pipelineLayout = layout[0];

            org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo viewportState =
                    org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                            .viewportCount(1).scissorCount(1);

            org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                    org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo.calloc(1, stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                            .pStages(stages)
                            .pVertexInputState(vertexInput)
                            .pInputAssemblyState(inputAssembly)
                            .pViewportState(viewportState)
                            .pRasterizationState(rasterization)
                            .pMultisampleState(multisample)
                            .pColorBlendState(colorBlend)
                            .pDynamicState(dynamicState)
                            .layout(pipelineLayout)
                            .renderPass(renderPass)
                            .subpass(0);

            LongBuffer pipelines = stack.mallocLong(1);
            VulkanContext.check(VK10.vkCreateGraphicsPipelines(context.device, 0L, pipelineInfo, null, pipelines),
                    "vkCreateGraphicsPipelines");
            pipeline = pipelines.get(0);

            VK10.vkDestroyShaderModule(context.device, vertexModule, null);
            VK10.vkDestroyShaderModule(context.device, fragmentModule, null);
        }
    }
}
