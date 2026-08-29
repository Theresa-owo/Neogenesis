package net.theresa.render.vulkan;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;

/**
 * Vulkan world renderer (W4): depth-tested terrain from the vanilla chunk
 * pipeline. Runs on the client thread; GL calls it makes (setupCameraTransform,
 * atlas reads) land on the hidden companion context.
 */
public class VulkanRenderer {

    private static final int MAX_FRAMES_IN_FLIGHT = 2;
    private static final int LAYER_SOLID = 0;
    private static final int LAYER_CUTOUT_MIPPED = 1;
    private static final int LAYER_CUTOUT = 2;
    private static final int LAYER_TRANSLUCENT = 3;
    private static final int BLOCK_STRIDE = 28; // pos3f + color4ub + uv2f + lightmap2s
    private static final int PUSH_BLOCK_SIZE = 80; // mat4 mvp + vec4 chunkOrigin

    private VulkanContext context;
    private VulkanSwapchain swapchain;
    private VulkanFrame[] frames;
    private int currentFrame;

    private long renderPass;
    private final List<Long> framebuffers = new ArrayList<>();

    private long[] depthImages = new long[0];
    private long[] depthMemories = new long[0];
    private long[] depthViews = new long[0];
    private int depthFormat;

    private VulkanTexture atlasTexture;
    private long descriptorSetLayout;
    private long descriptorPool;
    private long descriptorSet;

    private long terrainLayout;
    private long terrainOpaquePipeline;
    private long terrainTranslucentPipeline;

    private VulkanChunkStore chunkStore;
    private int frameCount;
    private int bisectDebug;

    private long window;
    private int framebufferWidth = -1;
    private int framebufferHeight = -1;
    private boolean framebufferResized;
    private long debugMessenger;

    public void init(long window, int width, int height) {
        this.window = window;
        this.context = new VulkanContext(window);
        this.swapchain = new VulkanSwapchain(context, window);
        debugMessenger = VulkanDebug.setup(context.instance);

        depthFormat = findSupportedDepthFormat();
        renderPass = createRenderPass();
        createDepthResources();
        createFramebuffers();

        frames = new VulkanFrame[MAX_FRAMES_IN_FLIGHT];
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            frames[i] = new VulkanFrame(context);
        }

        chunkStore = new VulkanChunkStore(context);
        VulkanWorldBridge.attach(chunkStore);

        initWorldTextures();
        createDescriptorResources();
        createTerrainPipelines();
    }

    public void frame() {
        pollFramebufferSize();
        if (framebufferResized) {
            recreate();
        }
        if (chunkStore != null) {
            chunkStore.tickFrame();
        }

        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (mc.theWorld == null || view == null) {
            renderClearFrame();
            return;
        }

        // Drive the vanilla chunk pipeline on the companion GL context: camera
        // matrices, frustum culling, chunk (re)compilation and upload draining.
        // Our ChunkRenderDispatcher hook mirrors uploads into the chunk store.
        float partialTicks = mc.timer.renderPartialTicks;
        mc.entityRenderer.setupCameraTransform(partialTicks, 2);
        Frustum frustum = new Frustum(ClippingHelperImpl.getInstance());
        Vec3 eye = view.getPositionEyes(partialTicks);
        frustum.setPosition(eye.xCoord, eye.yCoord, eye.zCoord);
        mc.renderGlobal.setupTerrain(view, partialTicks, frustum, frameCount++, mc.thePlayer.isSpectator());
        mc.renderGlobal.updateChunks(System.nanoTime() + 500_000_000L);

        Matrix4f mvp = computeCameraMatrix(mc, view, partialTicks, eye);

        VulkanFrame frame = frames[currentFrame];
        frame.resetFence();
        int imageIndex = swapchain.acquire(frame.imageAvailable, frame.fence);
        if (imageIndex < 0) {
            recreate();
            return;
        }

        recordWorldFrame(frame.commandBuffer, imageIndex, mvp);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(frame.imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(frame.commandBuffer))
                    .pSignalSemaphores(stack.longs(frame.renderFinished));
            VulkanContext.check(VK10.vkQueueSubmit(context.graphicsQueue, submitInfo, frame.fence), "vkQueueSubmit");
        }

        boolean suboptimal = swapchain.present(context.presentQueue, frame.renderFinished, imageIndex);
        currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        if (suboptimal) {
            recreate();
        }
    }

    private void renderClearFrame() {
        VulkanFrame frame = frames[currentFrame];
        frame.resetFence();
        int imageIndex = swapchain.acquire(frame.imageAvailable, frame.fence);
        if (imageIndex < 0) {
            recreate();
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            VulkanContext.check(VK10.vkBeginCommandBuffer(frame.commandBuffer, beginInfo), "vkBeginCommandBuffer");

            beginRenderPass(frame.commandBuffer, imageIndex, stack);
            VK10.vkCmdEndRenderPass(frame.commandBuffer);
            VulkanContext.check(VK10.vkEndCommandBuffer(frame.commandBuffer), "vkEndCommandBuffer");

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(frame.imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(frame.commandBuffer))
                    .pSignalSemaphores(stack.longs(frame.renderFinished));
            VulkanContext.check(VK10.vkQueueSubmit(context.graphicsQueue, submitInfo, frame.fence), "vkQueueSubmit");
        }

        boolean suboptimal = swapchain.present(context.presentQueue, frame.renderFinished, imageIndex);
        currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        if (suboptimal) {
            recreate();
        }
    }

    private Matrix4f computeCameraMatrix(Minecraft mc, Entity view, float partialTicks, Vec3 eye) {
        float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partialTicks;
        float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partialTicks;
        float fovDegrees = mc.gameSettings.fovSetting;
        float farPlane = mc.gameSettings.renderDistanceChunks * 16.0f * 4.0f;
        float aspect = (float) swapchain.width / (float) swapchain.height;

        // Vulkan clip space: depth 0..1, Y points down (flip after building the GL-style matrix)
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fovDegrees), aspect, 0.05f,
                farPlane, true);
        projection.mul(new Matrix4f().scale(1.0f, -1.0f, 1.0f));

        // mirrors the vanilla modelview: rotateX(pitch) * rotateY(yaw+180) * translate(-eye)
        Matrix4f viewMatrix = new Matrix4f()
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0f))
                .translate((float) -eye.xCoord, (float) -eye.yCoord, (float) -eye.zCoord);

        return projection.mul(viewMatrix, new Matrix4f());
    }

    private void recordWorldFrame(VkCommandBuffer commandBuffer, int imageIndex, Matrix4f mvp) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            VulkanContext.check(VK10.vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");

            beginRenderPass(commandBuffer, imageIndex, stack);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0.0f).y(0.0f)
                    .width((float) swapchain.width)
                    .height((float) swapchain.height)
                    .minDepth(0.0f).maxDepth(1.0f);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack)
                    .offset(VkOffset2D.calloc(stack).set(0, 0))
                    .extent(VkExtent2D.calloc(stack).set(swapchain.width, swapchain.height));
            VK10.vkCmdSetViewport(commandBuffer, 0, viewport);
            VK10.vkCmdSetScissor(commandBuffer, 0, scissor);

            String bisect = System.getProperty("neogenesis.vkBisect", "");
            if ((atlasTexture != null && descriptorSet != 0L) && !bisect.contains("nodesc")) {
                VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, terrainLayout, 0,
                        stack.longs(descriptorSet), null);
            }

            List<RenderChunk> visible = Minecraft.getMinecraft().renderGlobal.getVulkanVisibleChunks();
            if (!System.getProperty("neogenesis.vkBisect", "").contains("nodraw")) {
                if (bisectDebug == 0 && !visible.isEmpty()) {
                    bisectDebug = 1;
                    System.out.println("[VulkanBisect] visible chunks=" + visible.size()
                            + " atlas=" + (atlasTexture != null) + " descSet=" + descriptorSet);
                }
                drawChunks(commandBuffer, visible, mvp, stack);
            }

            VK10.vkCmdEndRenderPass(commandBuffer);
            VulkanContext.check(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
        }
    }

    private void drawChunks(VkCommandBuffer commandBuffer, List<RenderChunk> visible, Matrix4f mvp, MemoryStack stack) {
        String mode = System.getProperty("neogenesis.vkBisect", "");
        LongBuffer bindBuffer = stack.mallocLong(1);
        LongBuffer bindOffset = stack.longs(0L);
        ByteBuffer push = stack.malloc(PUSH_BLOCK_SIZE);

        long boundPipeline = 0;
        for (RenderChunk chunk : visible) {
            long baseX = chunk.getPosition().getX();
            long baseY = chunk.getPosition().getY();
            long baseZ = chunk.getPosition().getZ();

            for (int layer = LAYER_SOLID; layer <= LAYER_TRANSLUCENT; layer++) {
                long buffer = chunkStore.getBuffer(chunk, layer);
                if (buffer == 0L) {
                    continue;
                }
                int count = chunkStore.getVertexCount(chunk, layer);
                if (count <= 0) {
                    continue;
                }

                long wanted = layer == LAYER_TRANSLUCENT ? terrainTranslucentPipeline : terrainOpaquePipeline;
                if (wanted != boundPipeline) {
                    VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, wanted);
                    boundPipeline = wanted;
                }

                push.clear();
                mvp.get(push);
                push.position(64);
                push.putFloat((float) baseX).putFloat((float) baseY).putFloat((float) baseZ).putFloat(1.0f);
                push.flip();

                bindBuffer.put(0, buffer);
                VK10.vkCmdBindVertexBuffers(commandBuffer, 0, bindBuffer, bindOffset);
                if (mode.contains("nopush")) {
                    continue;
                }
                VK10.vkCmdPushConstants(commandBuffer, terrainLayout, VK10.VK_SHADER_STAGE_VERTEX_BIT, 0, push);
                if (mode.contains("binds")) {
                    continue;
                }
                VK10.vkCmdDraw(commandBuffer, count, 1, 0, 0);
            }
        }
    }

    private void beginRenderPass(VkCommandBuffer commandBuffer, int imageIndex, MemoryStack stack) {
        VkClearValue.Buffer clearValues = VkClearValue.calloc(2, stack);
        clearValues.get(0).color().float32(stack.floats(0.47f, 0.65f, 1.0f, 1.0f));
        clearValues.get(1).depthStencil().depth(1.0f);

        VkRenderPassBeginInfo beginRenderPass = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(renderPass)
                .framebuffer(framebuffers.get(imageIndex))
                .renderArea(VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).set(0, 0))
                        .extent(VkExtent2D.calloc(stack).set(swapchain.width, swapchain.height)))
                .pClearValues(clearValues);

        VK10.vkCmdBeginRenderPass(commandBuffer, beginRenderPass, VK10.VK_SUBPASS_CONTENTS_INLINE);
    }

    // ------------------------------------------------------------------ setup

    private void initWorldTextures() {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.renderer.texture.ITextureObject atlas =
                mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture);
        if (atlas == null) {
            return;
        }
        int glId = atlas.getGlTextureId();
        int[] dims = queryGlTextureSize(glId);
        int mips = Math.max(1, mc.gameSettings.mipmapLevels + 1);
        atlasTexture = new VulkanTexture(context, glId, dims[0], dims[1], mips);
    }

    private int[] queryGlTextureSize(int glId) {
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glId);
        int[] value = new int[1];
        org.lwjgl.opengl.GL11.glGetTexLevelParameteriv(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH, value);
        int width = value[0];
        org.lwjgl.opengl.GL11.glGetTexLevelParameteriv(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT, value);
        int height = value[0];
        return new int[] { width, height };
    }

    private void createDescriptorResources() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo layoutInfo =
                    org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                            .pBindings(binding);
            long[] layout = new long[1];
            VulkanContext.check(VK10.vkCreateDescriptorSetLayout(context.device, layoutInfo, null, layout),
                    "vkCreateDescriptorSetLayout");
            descriptorSetLayout = layout[0];

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1);
            poolInfo.pPoolSizes(VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1));
            long[] pool = new long[1];
            VulkanContext.check(VK10.vkCreateDescriptorPool(context.device, poolInfo, null, pool),
                    "vkCreateDescriptorPool");
            descriptorPool = pool[0];

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            long[] set = new long[1];
            VulkanContext.check(VK10.vkAllocateDescriptorSets(context.device, allocInfo, set),
                    "vkAllocateDescriptorSets");
            descriptorSet = set[0];

            if (atlasTexture != null) {
                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(atlasTexture.sampler)
                        .imageView(atlasTexture.view)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
                write.get(0).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(0)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfo);
                VK10.vkUpdateDescriptorSets(context.device, write, null);
            }
        }
    }

    private void createTerrainPipelines() {
        String vertexGlsl = "#version 450\n"
                + "layout(push_constant) uniform Push { mat4 mvp; vec4 chunkOrigin; } push;\n"
                + "layout(location = 0) in vec3 inPos;\n"
                + "layout(location = 1) in vec4 inColor;\n"
                + "layout(location = 2) in vec2 inUV;\n"
                + "layout(location = 0) out vec3 vColor;\n"
                + "layout(location = 1) out vec2 vUV;\n"
                + "void main() {\n"
                + "    gl_Position = push.mvp * vec4(push.chunkOrigin.xyz + inPos, 1.0);\n"
                + "    vColor = inColor.rgb;\n"
                + "    vUV = inUV;\n"
                + "}\n";
        String fragmentOpaque = "#version 450\n"
                + "layout(binding = 0) uniform sampler2D atlas;\n"
                + "layout(location = 0) in vec3 vColor;\n"
                + "layout(location = 1) in vec2 vUV;\n"
                + "layout(location = 0) out vec4 outColor;\n"
                + "void main() {\n"
                + "    vec4 c = texture(atlas, vUV) * vec4(vColor, 1.0);\n"
                + "    if (c.a < 0.1) discard;\n"
                + "    outColor = vec4(c.rgb, 1.0);\n"
                + "}\n";
        String fragmentTranslucent = "#version 450\n"
                + "layout(binding = 0) uniform sampler2D atlas;\n"
                + "layout(location = 0) in vec3 vColor;\n"
                + "layout(location = 1) in vec2 vUV;\n"
                + "layout(location = 0) out vec4 outColor;\n"
                + "void main() {\n"
                + "    outColor = texture(atlas, vUV) * vec4(vColor, 1.0);\n"
                + "}\n";

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(PUSH_BLOCK_SIZE);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            long[] layout = new long[1];
            VulkanContext.check(VK10.vkCreatePipelineLayout(context.device, layoutInfo, null, layout),
                    "vkCreatePipelineLayout");
            terrainLayout = layout[0];

            terrainOpaquePipeline = buildTerrainPipeline(stack, vertexGlsl, fragmentOpaque, false);
            terrainTranslucentPipeline = buildTerrainPipeline(stack, vertexGlsl, fragmentTranslucent, true);
        }
    }

    private long buildTerrainPipeline(MemoryStack stack, String vertexGlsl, String fragmentGlsl, boolean blended) {
        long vertexModule = createShaderModule(vertexGlsl, VulkanShaders.Stage.VERTEX);
        long fragmentModule = createShaderModule(fragmentGlsl, VulkanShaders.Stage.FRAGMENT);
        try {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(BLOCK_STRIDE).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(3, stack);
            attributes.get(0).binding(0).location(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            attributes.get(1).binding(0).location(1).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
            attributes.get(2).binding(0).location(2).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(16);
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

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true)
                    .depthWriteEnable(!blended)
                    .depthCompareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0).colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                    | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
            if (blended) {
                blendAttachment.get(0).blendEnable(true)
                        .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ZERO)
                        .alphaBlendOp(VK10.VK_BLEND_OP_ADD);
            } else {
                blendAttachment.get(0).blendEnable(false)
                        .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ZERO);
            }
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(blendAttachment);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
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
                            .pDepthStencilState(depthStencil)
                            .pColorBlendState(colorBlend)
                            .pDynamicState(dynamicState)
                            .layout(terrainLayout)
                            .renderPass(renderPass)
                            .subpass(0);

            LongBuffer pipelines = stack.mallocLong(1);
            VulkanContext.check(VK10.vkCreateGraphicsPipelines(context.device, 0L, pipelineInfo, null, pipelines),
                    "vkCreateGraphicsPipelines");
            return pipelines.get(0);
        } finally {
            VK10.vkDestroyShaderModule(context.device, vertexModule, null);
            VK10.vkDestroyShaderModule(context.device, fragmentModule, null);
        }
    }

    private long createShaderModule(String glsl, VulkanShaders.Stage stage) {
        byte[] spirv = VulkanShaders.compileGlslToSpv(glsl, stage, "neogenesis.terrain." + stage.name().toLowerCase());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer code = stack.malloc(spirv.length);
            code.put(spirv).flip();
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(code);
            long[] module = new long[1];
            VulkanContext.check(VK10.vkCreateShaderModule(context.device, info, null, module), "vkCreateShaderModule");
            return module[0];
        }
    }

    private int findSupportedDepthFormat() {
        int[] candidates = { VK10.VK_FORMAT_D32_SFLOAT, VK10.VK_FORMAT_D32_SFLOAT_S8_UINT,
                VK10.VK_FORMAT_D24_UNORM_S8_UINT, VK10.VK_FORMAT_D16_UNORM };
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int format : candidates) {
                VkFormatProperties props = VkFormatProperties.calloc(stack);
                VK10.vkGetPhysicalDeviceFormatProperties(context.physicalDevice, format, props);
                int features = props.optimalTilingFeatures();
                if ((features & VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    return format;
                }
            }
        }
        throw new IllegalStateException("No supported depth format found");
    }

    private long createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
            attachments.get(0).format(swapchain.imageFormat)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            attachments.get(1).format(depthFormat)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0).layout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(1).layout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK10.VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef)
                    .pDepthStencilAttachment(depthRef);

            org.lwjgl.vulkan.VkSubpassDependency.Buffer dependency =
                    org.lwjgl.vulkan.VkSubpassDependency.calloc(1, stack)
                            .srcSubpass(VK10.VK_SUBPASS_EXTERNAL)
                            .dstSubpass(0)
                            .srcStageMask(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .dstStageMask(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .srcAccessMask(0)
                            .dstAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            long[] renderPass = new long[1];
            VulkanContext.check(VK10.vkCreateRenderPass(context.device, info, null, renderPass),
                    "vkCreateRenderPass");
            return renderPass[0];
        }
    }

    private void createDepthResources() {
        int count = swapchain.images.size();
        depthImages = new long[count];
        depthMemories = new long[count];
        depthViews = new long[count];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < count; i++) {
                VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK10.VK_IMAGE_TYPE_2D)
                        .format(depthFormat)
                        .extent(VkExtent3D.calloc(stack).set(swapchain.width, swapchain.height, 1))
                        .mipLevels(1).arrayLayers(1)
                        .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
                long[] image = new long[1];
                VulkanContext.check(VK10.vkCreateImage(context.device, info, null, image), "vkCreateImage(depth)");
                depthImages[i] = image[0];

                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                VK10.vkGetImageMemoryRequirements(context.device, depthImages[i], requirements);
                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(requirements.size())
                        .memoryTypeIndex(context.memoryTypeIndex(requirements.memoryTypeBits(),
                                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
                long[] memory = new long[1];
                VulkanContext.check(VK10.vkAllocateMemory(context.device, allocInfo, null, memory),
                        "vkAllocateMemory(depth)");
                depthMemories[i] = memory[0];
                VulkanContext.check(VK10.vkBindImageMemory(context.device, depthImages[i], depthMemories[i], 0L),
                        "vkBindImageMemory(depth)");

                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(depthImages[i])
                        .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                        .format(depthFormat)
                        .subresourceRange(VkImageSubresourceRange.calloc(stack)
                                .aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT)
                                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                long[] view = new long[1];
                VulkanContext.check(VK10.vkCreateImageView(context.device, viewInfo, null, view),
                        "vkCreateImageView(depth)");
                depthViews[i] = view[0];
            }
        }
    }

    private void destroyDepthResources() {
        for (int i = 0; i < depthViews.length; i++) {
            if (depthViews[i] != 0L) {
                VK10.vkDestroyImageView(context.device, depthViews[i], null);
            }
            if (depthImages[i] != 0L) {
                VK10.vkDestroyImage(context.device, depthImages[i], null);
            }
            if (depthMemories[i] != 0L) {
                VK10.vkFreeMemory(context.device, depthMemories[i], null);
            }
        }
        depthImages = new long[0];
        depthMemories = new long[0];
        depthViews = new long[0];
    }

    private void createFramebuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < swapchain.imageViews.size(); i++) {
                LongBuffer attachments = stack.longs(swapchain.imageViews.get(i), depthViews[i]);
                VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(renderPass)
                        .pAttachments(attachments)
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

    // ------------------------------------------------------------- lifecycle

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
    }

    private void recreate() {
        context.waitIdle();
        for (long fb : framebuffers) {
            VK10.vkDestroyFramebuffer(context.device, fb, null);
        }
        framebuffers.clear();
        destroyDepthResources();
        swapchain.recreate();
        createDepthResources();
        createFramebuffers();
        framebufferResized = false;
    }

    public void onResize(int width, int height) {
        framebufferResized = true;
    }

    public void cleanup() {
        context.waitIdle();
        VulkanWorldBridge.detach();
        if (chunkStore != null) {
            chunkStore.destroy();
            chunkStore = null;
        }
        for (long fb : framebuffers) {
            VK10.vkDestroyFramebuffer(context.device, fb, null);
        }
        framebuffers.clear();
        destroyDepthResources();
        if (renderPass != 0L) {
            VK10.vkDestroyRenderPass(context.device, renderPass, null);
            renderPass = 0L;
        }
        if (terrainOpaquePipeline != 0L) {
            VK10.vkDestroyPipeline(context.device, terrainOpaquePipeline, null);
        }
        if (terrainTranslucentPipeline != 0L) {
            VK10.vkDestroyPipeline(context.device, terrainTranslucentPipeline, null);
        }
        if (terrainLayout != 0L) {
            VK10.vkDestroyPipelineLayout(context.device, terrainLayout, null);
        }
        if (descriptorPool != 0L) {
            VK10.vkDestroyDescriptorPool(context.device, descriptorPool, null);
        }
        if (descriptorSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(context.device, descriptorSetLayout, null);
        }
        if (atlasTexture != null) {
            atlasTexture.destroy();
            atlasTexture = null;
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
}
