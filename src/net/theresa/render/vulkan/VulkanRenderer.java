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
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkOffset3D;
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
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.src.Config;
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
    private static final int PUSH_BLOCK_SIZE = 112; // mat4 mvp + vec4 chunkOrigin + vec4 eye/fogStart + vec4 fog

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
    private VulkanTexture lightmapTexture;
    private long descriptorSetLayout;
    private long descriptorPool;
    private long descriptorSet;
    /** Same atlas view sampled with the no-mip sampler; CUTOUT draws with this. */
    private long descriptorSetCutout;

    private long terrainLayout;
    private long terrainOpaquePipeline;
    private long terrainTranslucentPipeline;

    private long[] imageRenderFinished = new long[0];
    private boolean reloadQueued;
    private boolean prevF9Down;
    private boolean prevF10Down;
    private boolean prevF8Down;
    private int menuProbeFrames;
    private long menuProbeLast;
    private final long[] menuStageAcc = new long[5];
    private int staleDraws;
    private long drawRangeSkips;

    private static final int DUMP_FRAMES = 90;
    private int dumpRemaining;
    private int dumpIndex;
    private boolean dumpRecordedThisFrame;
    private long dumpBuffer;
    private long dumpMemory;
    private java.nio.ByteBuffer dumpMapped;

    private VulkanChunkStore chunkStore;
    private int frameCount;
    private int bisectDebug;

    private float fogStart = 64.0f;
    private float fogEnd = 120.0f;
    private float eyeX;
    private float eyeY;
    private float eyeZ;
    private double eyeXd;
    private double eyeYd;
    private double eyeZd;

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
        createImageRenderFinishedSemaphores();

        chunkStore = new VulkanChunkStore(context);
        VulkanWorldBridge.attach(chunkStore);

        initWorldTextures();
        Minecraft mc0 = Minecraft.getMinecraft();
        mc0.entityRenderer.updateLightmap(0.0f);
        lightmapTexture = new VulkanTexture(context,
                mc0.entityRenderer.getLightmapTexture().getGlTextureId(), 16, 16, 1,
                VK10.VK_FORMAT_R8G8B8A8_UNORM, false);
        createDescriptorResources();
        createTerrainPipelines();
        net.theresa.ui.NeoUI.INSTANCE.init(context, window, swapchain.imageFormat,
                swapchain.width, swapchain.height);
    }

    public void frame() {
        net.theresa.ui.NeoUI.INSTANCE.probeFrame();
        pollFramebufferSize();
        if (framebufferResized) {
            recreate();
        }
        if (chunkStore != null) {
            chunkStore.tickFrame();
        }

        boolean f9Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS;
        if (f9Down && !prevF9Down) {
            reloadQueued = true;
        }
        prevF9Down = f9Down;

        // F10 reloads the Lua UI scripts (screens, theme, animations)
        boolean f10Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F10) == GLFW.GLFW_PRESS;
        if (f10Down && !prevF10Down) {
            net.theresa.ui.NeoUI.INSTANCE.reloadLua();
        }
        prevF10Down = f10Down;

        // F8 arms a burst frame dump (raw BGRA files under frameDump/) used to
        // classify flicker offline via inter-frame pixel diffing
        boolean f8Down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
        if (f8Down && !prevF8Down && dumpRemaining == 0) {
            dumpRemaining = DUMP_FRAMES;
            new java.io.File("frameDump").mkdirs();
            System.out.println("[VkFrameDump] armed: " + DUMP_FRAMES + " frames -> frameDump/");
        }
        prevF8Down = f8Down;

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
        mc.entityRenderer.updateMouseLook(partialTicks,
                org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_FOCUSED) != 0);
        mc.entityRenderer.updateLightmap(partialTicks);
        if (lightmapTexture != null) {
            lightmapTexture.updateFromGL(getLightmapGlId());
        }
        mc.entityRenderer.setupCameraTransform(partialTicks, 2);
        Frustum frustum = new Frustum(ClippingHelperImpl.getInstance());
        Vec3 eye = view.getPositionEyes(partialTicks);
        frustum.setPosition(eye.xCoord, eye.yCoord, eye.zCoord);
        mc.renderGlobal.setupTerrain(view, partialTicks, frustum, frameCount++, mc.thePlayer.isSpectator());
        mc.renderGlobal.updateChunks(System.nanoTime() + 500_000_000L);

        Matrix4f mvp = computeCameraMatrix(mc, view, partialTicks, eye);
        float farPlane = mc.gameSettings.renderDistanceChunks * 16.0f;
        boolean noFog = Boolean.getBoolean("neogenesis.vkNoFog");
        fogStart = noFog ? 9_000.0f : farPlane * 0.7f;
        fogEnd = noFog ? 10_000.0f : farPlane * 1.0f;
        // double precision on the CPU: chunk origins are subtracted from the eye
        // BEFORE going to the GPU, avoiding float32 cancellation at far coordinates
        eyeXd = eye.xCoord;
        eyeYd = eye.yCoord;
        eyeZd = eye.zCoord;
        eyeX = (float) eyeXd;
        eyeY = (float) eyeYd;
        eyeZ = (float) eyeZd;

        VulkanFrame frame = frames[currentFrame];
        // block until this slot's previous frame finished; every resource of the
        // slot (fence, semaphores, command buffer) may then be safely reused
        VulkanContext.check(VK10.vkWaitForFences(context.device, frame.fence, true, 5_000_000_000L),
                "vkWaitForFences");
        frame.resetFence();
        int imageIndex = swapchain.acquire(frame.imageAvailable);
        if (imageIndex < 0) {
            recreate();
            return;
        }

        if (frameCount % 120 == 0) {
            if (frameCount > 0) {
                System.out.printf("[VulkanStaleWindow] staleDraws in last window = %d%n", staleDraws);
            }
            staleDraws = 0;
            List<RenderChunk> vis = Minecraft.getMinecraft().renderGlobal.getVulkanVisibleChunks();
            System.out.println(String.format(
                    "[VulkanDiag] frame=%d visible=%d hooks=%d uploads=%d staleDraws=%d store=%d"
                            + " rangeSkips=%d staleUploadDrops=%d eye=%.1f,%.1f,%.1f",
                    frameCount, vis.size(), VulkanWorldBridge.hookCalls, VulkanWorldBridge.uploadsSeen,
                    staleDraws, chunkStore.size(), drawRangeSkips, chunkStore.staleUploadDrops,
                    eyeX, eyeY, eyeZ));
            drawRangeSkips = 0;
        }

        if (reloadQueued) {
            reloadQueued = false;
            reloadPipelines();
        }

        recordWorldFrame(frame.commandBuffer, imageIndex, mvp);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(frame.imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(frame.commandBuffer))
                    .pSignalSemaphores(stack.longs(imageRenderFinished[imageIndex]));
            VulkanContext.check(VK10.vkQueueSubmit(context.graphicsQueue, submitInfo, frame.fence), "vkQueueSubmit");
        }

        if (dumpRecordedThisFrame) {
            // the readback copy ran inside this submit; block once, then write
            // the raw BGRA frame for offline inter-frame diffing
            VulkanContext.check(VK10.vkWaitForFences(context.device, frame.fence, true, 5_000_000_000L),
                    "vkWaitForFences (frame dump)");
            dumpRecordedThisFrame = false;
            dumpRemaining--;
            java.nio.ByteBuffer dumpView = dumpMapped.duplicate().order(java.nio.ByteOrder.nativeOrder());
            byte[] pixels = new byte[dumpView.remaining()];
            dumpView.get(pixels);
            java.nio.file.Path out = java.nio.file.Paths.get("frameDump",
                    String.format("frame_%05d_game%08d.raw", dumpIndex++, frameCount));
            try {
                java.nio.file.Files.write(out, pixels);
            } catch (java.io.IOException e) {
                System.err.println("[VkFrameDump] write failed: " + e);
                dumpRemaining = 0;
            }
            System.out.printf("[VkFrameDump] %s uploads=%d visible=%d%n", out.getFileName(),
                    VulkanWorldBridge.uploadsSeen,
                    Minecraft.getMinecraft().renderGlobal.getVulkanVisibleChunks().size());
        }

        boolean suboptimal = swapchain.present(context.presentQueue, imageRenderFinished[imageIndex], imageIndex);
        currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        if (suboptimal) {
            recreate();
        }
    }

    private void renderClearFrame() {
        long tStart = System.nanoTime();
        VulkanFrame frame = frames[currentFrame];
        VulkanContext.check(VK10.vkWaitForFences(context.device, frame.fence, true, 5_000_000_000L),
                "vkWaitForFences");
        long tFence = System.nanoTime();
        frame.resetFence();
        int imageIndex = swapchain.acquire(frame.imageAvailable);
        long tAcquire = System.nanoTime();

        long t0 = tAcquire;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            VulkanContext.check(VK10.vkBeginCommandBuffer(frame.commandBuffer, beginInfo), "vkBeginCommandBuffer");

            // Offscreen panorama + acrylic blur chain, then the main pass with
            // the UI drawn on top (menu background; no-op when world HUD empty)
            net.theresa.ui.NeoUI.INSTANCE.prepare(frame.commandBuffer);
            beginRenderPass(frame.commandBuffer, imageIndex, stack);
            net.theresa.ui.NeoUI.INSTANCE.renderInPass(frame.commandBuffer,
                    swapchain.width, swapchain.height);
            VK10.vkCmdEndRenderPass(frame.commandBuffer);
            VulkanContext.check(VK10.vkEndCommandBuffer(frame.commandBuffer), "vkEndCommandBuffer");
        }
        long t1 = System.nanoTime();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(frame.imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(frame.commandBuffer))
                    .pSignalSemaphores(stack.longs(imageRenderFinished[imageIndex]));
            VulkanContext.check(VK10.vkQueueSubmit(context.graphicsQueue, submitInfo, frame.fence), "vkQueueSubmit");
        }
        long t2 = System.nanoTime();

        boolean suboptimal = swapchain.present(context.presentQueue, imageRenderFinished[imageIndex], imageIndex);
        long t3 = System.nanoTime();

        // menu fps + stage breakdown probe (every 240 frames)
        menuProbeFrames++;
        menuStageAcc[0] += tFence - tStart;
        menuStageAcc[1] += tAcquire - tFence;
        menuStageAcc[2] += t1 - tAcquire;
        menuStageAcc[3] += t2 - t1;
        menuStageAcc[4] += t3 - t2;
        if (menuProbeFrames >= 240) {
            long now = System.nanoTime();
            if (menuProbeLast != 0) {
                double fps = menuProbeFrames * 1000.0 / ((now - menuProbeLast) / 1e6);
                System.out.printf(
                        "[VulkanMenu fps] %.0f | fenceWait %.2f acquire %.2f record %.2f submit %.2f present %.2f (ms avg)%n",
                        fps,
                        menuStageAcc[0] / 240.0 / 1e6, menuStageAcc[1] / 240.0 / 1e6,
                        menuStageAcc[2] / 240.0 / 1e6, menuStageAcc[3] / 240.0 / 1e6,
                        menuStageAcc[4] / 240.0 / 1e6);
            }
            menuProbeLast = now;
            menuProbeFrames = 0;
            java.util.Arrays.fill(menuStageAcc, 0);
        }

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

        // Vulkan clip space: Reversed-Z (1.0 at near, 0.0 at far) for near-infinite depth precision
        // Y points down (flip after building the GL-style matrix)
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fovDegrees), aspect, farPlane,
                0.05f, true);
        projection.mul(new Matrix4f().scale(1.0f, -1.0f, 1.0f));

        // mirrors the vanilla modelview orientation, WITHOUT the translate(-eye):
        // the camera-relative path below feeds the GPU (chunkOrigin - eye)
        // computed in double precision per chunk, so the float32 matrix never
        // cancels large world coordinates against each other
        Matrix4f viewMatrix = new Matrix4f()
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0f));

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
            boolean haveDescriptors = atlasTexture != null
                    && lightmapTexture != null
                    && descriptorSet != 0L;
            if (haveDescriptors && !bisect.contains("nodesc")) {
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

            // HUD layer over the terrain (same subpass; empty until HUD screens exist)
            net.theresa.ui.NeoUI.INSTANCE.renderInPass(commandBuffer,
                    swapchain.width, swapchain.height);

            VK10.vkCmdEndRenderPass(commandBuffer);

            dumpRecordedThisFrame = false;
            if (dumpRemaining > 0 && ensureDumpBuffer()) {
                long image = swapchain.images.get(imageIndex);
                // PRESENT_SRC -> TRANSFER_SRC, copy, back to PRESENT_SRC
                VkImageMemoryBarrier.Buffer toSrc = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(0)
                        .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                        .oldLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(image)
                        .subresourceRange(VkImageSubresourceRange.calloc(stack)
                                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                VK10.vkCmdPipelineBarrier(commandBuffer,
                        VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                        null, null, toSrc);
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack)
                        .bufferOffset(0)
                        .bufferRowLength(swapchain.width)
                        .bufferImageHeight(swapchain.height)
                        .imageSubresource(VkImageSubresourceLayers.calloc(stack)
                                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .imageOffset(VkOffset3D.calloc(stack).set(0, 0, 0))
                        .imageExtent(VkExtent3D.calloc(stack).set(swapchain.width, swapchain.height, 1));
                VK10.vkCmdCopyImageToBuffer(commandBuffer, image,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dumpBuffer, copy);
                VkImageMemoryBarrier.Buffer toPresent = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(0)
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(image)
                        .subresourceRange(VkImageSubresourceRange.calloc(stack)
                                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                VK10.vkCmdPipelineBarrier(commandBuffer,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                        null, null, toPresent);
                dumpRecordedThisFrame = true;
            }

            VulkanContext.check(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
        }
    }

    private void drawChunks(VkCommandBuffer commandBuffer, List<RenderChunk> visible, Matrix4f mvp, MemoryStack stack) {
        String mode = System.getProperty("neogenesis.vkBisect", "");
        LongBuffer bindBuffer = stack.mallocLong(1);
        LongBuffer bindOffset = stack.longs(0L);
        ByteBuffer push = stack.malloc(PUSH_BLOCK_SIZE);

        long boundPipeline = 0;
        // The traversal order of renderInfos changes with the view; under
        // strict-GREATER coplanar semantics (first draw wins) the winner of
        // equal-depth pixels must not depend on that order, so sort by chunk
        // position — fully deterministic, view-independent.
        visible.sort(java.util.Comparator.comparingInt((RenderChunk c) -> c.getPosition().getX())
                .thenComparingInt(c -> c.getPosition().getY())
                .thenComparingInt(c -> c.getPosition().getZ()));
        for (RenderChunk chunk : visible) {
            // a store entry keyed by a repositioned chunk object belongs to the
            // object's PREVIOUS position; drawing it would render the old
            // location's mesh at the new location until the rebuild uploads
            if (!net.theresa.render.vulkan.VulkanWorldBridge.hasFreshMesh(chunk)) {
                staleDraws++;
                continue;
            }
            long baseX = chunk.getPosition().getX();
            long baseY = chunk.getPosition().getY();
            long baseZ = chunk.getPosition().getZ();

            boolean drawTranslucent = Boolean.getBoolean("neogenesis.vkTranslucent");
            for (int layer = LAYER_SOLID; layer <= (drawTranslucent ? LAYER_TRANSLUCENT : LAYER_CUTOUT); layer++) {
                VulkanChunkStore.LayerSnapshot snapshot = chunkStore.getSnapshot(chunk, layer);
                if (snapshot == null) {
                    continue;
                }
                long buffer = snapshot.buffer;
                int count = snapshot.vertexCount;
                if ((long) count * VulkanChunkStore.VERTEX_STRIDE_BYTES > snapshot.bufferSize) {
                    // never let vkCmdDraw fetch past the buffer end; garbage
                    // vertices sample garbage atlas UVs (wrong-tile artifacts)
                    drawRangeSkips++;
                    continue;
                }

                if (atlasTexture != null && descriptorSet != 0L && !mode.contains("nodesc")) {
                    long wantedDescriptorSet = layer == LAYER_CUTOUT && descriptorSetCutout != 0L
                            ? descriptorSetCutout
                            : descriptorSet;
                    VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                            terrainLayout, 0, stack.longs(wantedDescriptorSet), null);
                }

                long wanted = layer == LAYER_TRANSLUCENT ? terrainTranslucentPipeline : terrainOpaquePipeline;
                if (wanted != boundPipeline) {
                    VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, wanted);
                    boundPipeline = wanted;
                }

                push.clear();
                mvp.get(push);
                push.position(64);
                // double-precision (origin - eye) per chunk: vertices stay small
                // numbers near the camera; a float32 -eye in the matrix would
                // cancel against large world coordinates and jitter vertices
                push.putFloat((float) (baseX - eyeXd)).putFloat((float) (baseY - eyeYd))
                        .putFloat((float) (baseZ - eyeZd)).putFloat(1.0f);
                push.position(80);
                push.putFloat(eyeX).putFloat(eyeY).putFloat(eyeZ).putFloat(fogStart);
                push.position(96);
                push.putFloat(fogEnd).putFloat(0.47f).putFloat(0.65f).putFloat(1.0f);
                push.flip();

                bindBuffer.put(0, buffer);
                VK10.vkCmdBindVertexBuffers(commandBuffer, 0, bindBuffer, bindOffset);
                if (mode.contains("nopush")) {
                    continue;
                }
                VK10.vkCmdPushConstants(commandBuffer, terrainLayout,
                        VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
                        0, push);
                if (mode.contains("binds")) {
                    continue;
                }
                VK10.vkCmdDraw(commandBuffer, count, 1, 0, 0);
            }
        }
    }

    /** Lazily allocates the host-visible readback buffer for frame dumps. */
    private boolean ensureDumpBuffer() {
        long needed = (long) swapchain.width * swapchain.height * 4;
        if (dumpBuffer != 0L && dumpMapped != null && dumpMapped.capacity() == needed) {
            return true;
        }
        freeDumpBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(needed)
                    .usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            VulkanContext.check(VK10.vkCreateBuffer(context.device, info, null, pBuffer), "vkCreateBuffer (dump)");
            dumpBuffer = pBuffer.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(context.device, dumpBuffer, reqs);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(reqs.size())
                    .memoryTypeIndex(context.memoryTypeIndex(reqs.memoryTypeBits(),
                            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            VulkanContext.check(VK10.vkAllocateMemory(context.device, alloc, null, pMemory), "vkAllocateMemory (dump)");
            dumpMemory = pMemory.get(0);
            VulkanContext.check(VK10.vkBindBufferMemory(context.device, dumpBuffer, dumpMemory, 0L),
                    "vkBindBufferMemory (dump)");

            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            VulkanContext.check(VK10.vkMapMemory(context.device, dumpMemory, 0, needed, 0, pData), "vkMapMemory (dump)");
            dumpMapped = pData.getByteBuffer(0, (int) needed);
            return true;
        }
    }

    private void freeDumpBuffer() {
        if (dumpMemory != 0L) {
            VK10.vkUnmapMemory(context.device, dumpMemory);
        }
        if (dumpBuffer != 0L) {
            VK10.vkDestroyBuffer(context.device, dumpBuffer, null);
        }
        if (dumpMemory != 0L) {
            VK10.vkFreeMemory(context.device, dumpMemory, null);
        }
        dumpBuffer = 0L;
        dumpMemory = 0L;
        dumpMapped = null;
    }

    private void beginRenderPass(VkCommandBuffer commandBuffer, int imageIndex, MemoryStack stack) {
        VkClearValue.Buffer clearValues = VkClearValue.calloc(2, stack);
        clearValues.get(0).color().float32(stack.floats(0.47f, 0.65f, 1.0f, 1.0f));
        // Reversed-Z: clear depth to 0.0f (furthest distance)
        clearValues.get(1).depthStencil().depth(0.0f);

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
        atlasTexture = new VulkanTexture(context, glId, dims[0], dims[1], mips,
                VK10.VK_FORMAT_R8G8B8A8_UNORM, true);
    }

    private int getLightmapGlId() {
        return Minecraft.getMinecraft().entityRenderer.getLightmapTexture().getGlTextureId();
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
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binding.get(0).binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            binding.get(1).binding(1)
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
                    .maxSets(2);
            poolInfo.pPoolSizes(VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(4));
            long[] pool = new long[1];
            VulkanContext.check(VK10.vkCreateDescriptorPool(context.device, poolInfo, null, pool),
                    "vkCreateDescriptorPool");
            descriptorPool = pool[0];

            // set 0: mip-sampled atlas (SOLID / CUTOUT_MIPPED / TRANSLUCENT);
            // set 1: identical but sampled with the no-mip sampler (CUTOUT)
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout, descriptorSetLayout));
            long[] sets = new long[2];
            VulkanContext.check(VK10.vkAllocateDescriptorSets(context.device, allocInfo, sets),
                    "vkAllocateDescriptorSets");
            descriptorSet = sets[0];
            descriptorSetCutout = sets[1];

            if (atlasTexture != null && lightmapTexture != null) {
                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo
                        .calloc(4, stack);
                imageInfo.get(0).sampler(atlasTexture.sampler)
                        .imageView(atlasTexture.view)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                imageInfo.get(1).sampler(lightmapTexture.sampler)
                        .imageView(lightmapTexture.view)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                imageInfo.get(2).sampler(atlasTexture.samplerNoMip)
                        .imageView(atlasTexture.view)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                imageInfo.get(3).sampler(lightmapTexture.sampler)
                        .imageView(lightmapTexture.view)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(4, stack);
                write.get(0).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(0)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfo.slice(0, 1));
                write.get(1).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(1)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfo.slice(1, 1));
                write.get(2).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSetCutout)
                        .dstBinding(0)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfo.slice(2, 1));
                write.get(3).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSetCutout)
                        .dstBinding(1)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfo.slice(3, 1));
                VK10.vkUpdateDescriptorSets(context.device, write, null);
            }
        }
    }

    /** Reads shaders_vk/<name> when present so shaders can be edited while running. */
    private String loadShaderSource(String name, String embeddedFallback) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("shaders_vk", name);
            if (java.nio.file.Files.exists(path)) {
                return new String(java.nio.file.Files.readAllBytes(path),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, embeddedFallback.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Vulkan] shader file " + name + " unavailable, using embedded: " + e);
        }
        return embeddedFallback;
    }

    /** Destroys terrain pipelines + layout and rebuilds from current shader sources. */
    public void reloadPipelines() {
        context.waitIdle();
        VK10.vkDestroyPipeline(context.device, terrainOpaquePipeline, null);
        terrainOpaquePipeline = 0L;
        VK10.vkDestroyPipeline(context.device, terrainTranslucentPipeline, null);
        terrainTranslucentPipeline = 0L;
        if (terrainLayout != 0L) {
            VK10.vkDestroyPipelineLayout(context.device, terrainLayout, null);
            terrainLayout = 0L;
        }
        try {
            createTerrainPipelines();
            System.out.println("[Vulkan] terrain shaders reloaded");
        } catch (Throwable t) {
            System.err.println("[Vulkan] shader reload failed, world will not draw until next successful reload: " + t);
            t.printStackTrace();
        }
        net.theresa.ui.NeoUI.INSTANCE.reloadPipelines();
    }

    private void createTerrainPipelines() {
        // W5-① complete terrain shading: atlas + vertex tint + lightmap sampling + distance fog
        String vertexGlsl = "#version 450\n"
                + "layout(push_constant) uniform Push { mat4 mvp; vec4 chunkOrigin; vec4 eye; vec4 fog; } push;\n"
                + "layout(location = 0) in vec3 inPos;\n"
                + "layout(location = 1) in vec4 inColor;\n"
                + "layout(location = 2) in vec2 inUV;\n"
                + "layout(location = 3) in vec2 inLM;\n"
                + "layout(location = 0) out vec3 vColor;\n"
                + "layout(location = 1) out vec2 vUV;\n"
                + "layout(location = 2) out vec2 vLM;\n"
                + "layout(location = 3) out float vDist;\n"
                + "void main() {\n"
                + "    // chunkOrigin is camera-relative (origin - eye, computed in double on the CPU)\n"
                + "    vec3 viewPos = push.chunkOrigin.xyz + inPos;\n"
                + "    gl_Position = push.mvp * vec4(viewPos, 1.0);\n"
                + "    vColor = inColor.rgb;\n"
                + "    vUV = inUV;\n"
                + "    vLM = inLM;\n"
                + "    vDist = length(viewPos);\n"
                + "}\n";
        String fragmentGlsl = "#version 450\n"
                + "layout(push_constant) uniform Push { mat4 mvp; vec4 chunkOrigin; vec4 eye; vec4 fog; } push;\n"
                + "layout(binding = 0) uniform sampler2D atlas;\n"
                + "layout(binding = 1) uniform sampler2D lightmap;\n"
                + "layout(location = 0) in vec3 vColor;\n"
                + "layout(location = 1) in vec2 vUV;\n"
                + "layout(location = 2) in vec2 vLM;\n"
                + "layout(location = 3) in float vDist;\n"
                + "layout(location = 0) out vec4 outColor;\n"
                + "void main() {\n"
                + "    vec4 base = texture(atlas, vUV);\n"
                + "    if (base.a < 0.1) discard;\n"
                + "    vec3 light = texture(lightmap, vLM.yx / 256.0 + vec2(0.004)).rgb;\n"
                + "    vec3 c = base.rgb * vColor * light;\n"
                + "    float f = clamp((vDist - push.eye.w) / max(push.fog.x - push.eye.w, 0.001), 0.0, 1.0);\n"
                + "    outColor = vec4(mix(c, push.fog.yzw, f), 1.0);\n"
                + "}\n";

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // one range covering the whole block: splitting by stage double-books the
            // vertex stage, which the spec forbids
            vertexGlsl = loadShaderSource("terrain.vert", vertexGlsl);
            fragmentGlsl = loadShaderSource("terrain.frag", fragmentGlsl);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0).size(PUSH_BLOCK_SIZE);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            long[] layout = new long[1];
            VulkanContext.check(VK10.vkCreatePipelineLayout(context.device, layoutInfo, null, layout),
                    "vkCreatePipelineLayout");
            terrainLayout = layout[0];

            terrainOpaquePipeline = buildTerrainPipeline(stack, vertexGlsl, fragmentGlsl, false);
            terrainTranslucentPipeline = buildTerrainPipeline(stack, vertexGlsl, fragmentGlsl, true);
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
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(4, stack);
            attributes.get(0).binding(0).location(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            attributes.get(1).binding(0).location(1).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
            attributes.get(2).binding(0).location(2).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(16);
            attributes.get(3).binding(0).location(3).format(VK10.VK_FORMAT_R16G16_USCALED).offset(24);
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
                    // Vanilla renders SOLID, CUTOUT_MIPPED (leaves), and CUTOUT (grass, flowers, ladders, snow)
                    // with GL_CULL_FACE enabled (GL_BACK, CCW).
                    // Plants and ladders define double-sided co-planar quads (both front and back faces).
                    // Without backface culling, both co-planar quads rasterize at the exact same depth,
                    // causing depth-fighting and texture flickering as camera moves/rotates.
                    // With Y-flipped projection matrix (Vulkan NDC), world CCW becomes screen CW.
                    .cullMode(blended ? VK10.VK_CULL_MODE_NONE : VK10.VK_CULL_MODE_BACK_BIT)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            // Reversed-Z: closer fragments have larger depth values in [1.0, 0.0].
            // STRICT GREATER mirrors vanilla GL_LESS semantics for coplanar
            // geometry (zero-thickness cross plants, overlay quads): the
            // FIRST draw wins, so the outcome cannot flip when the visible
            // chunk order shuffles with the view. GEQUAL would let the last
            // draw overwrite coplanar pixels, bubbling under-layers through.
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true)
                    .depthWriteEnable(true)
                    .depthCompareOp(VK10.VK_COMPARE_OP_GREATER);

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
                    org.lwjgl.vulkan.VkSubpassDependency.calloc(2, stack)
                            .srcSubpass(VK10.VK_SUBPASS_EXTERNAL)
                            .dstSubpass(0)
                            .srcStageMask(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .dstStageMask(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .srcAccessMask(0)
                            .dstAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            // NeoUI records offscreen backdrop passes into the same command
            // buffer before this pass; order those color writes before the GUI
            // pipelines' fragment reads of the blurred backdrop.
            dependency.get(1)
                    .srcSubpass(VK10.VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);

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

    private void createImageRenderFinishedSemaphores() {
        imageRenderFinished = new long[swapchain.images.size()];
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkSemaphoreCreateInfo info = org.lwjgl.vulkan.VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            for (int i = 0; i < imageRenderFinished.length; i++) {
                long[] semaphore = new long[1];
                VulkanContext.check(VK10.vkCreateSemaphore(context.device, info, null, semaphore),
                        "vkCreateSemaphore(renderFinished)");
                imageRenderFinished[i] = semaphore[0];
            }
        }
    }

    private void destroyImageRenderFinishedSemaphores() {
        for (int i = 0; i < imageRenderFinished.length; i++) {
            if (imageRenderFinished[i] != 0L) {
                VK10.vkDestroySemaphore(context.device, imageRenderFinished[i], null);
                imageRenderFinished[i] = 0L;
            }
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
        dumpRemaining = 0;
        freeDumpBuffer();
        for (long fb : framebuffers) {
            VK10.vkDestroyFramebuffer(context.device, fb, null);
        }
        framebuffers.clear();
        destroyImageRenderFinishedSemaphores();
        destroyDepthResources();
        swapchain.recreate();
        createDepthResources();
        createImageRenderFinishedSemaphores();
        createFramebuffers();
        net.theresa.ui.NeoUI.INSTANCE.onResized(swapchain.width, swapchain.height);
        framebufferResized = false;
    }

    public void onResize(int width, int height) {
        framebufferResized = true;
    }

    public void cleanup() {
        context.waitIdle();
        net.theresa.ui.NeoUI.INSTANCE.destroy();
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
        destroyImageRenderFinishedSemaphores();
        VulkanDebug.destroy(context.instance, debugMessenger);
        if (swapchain != null) {
            swapchain.cleanup();
        }
        if (context != null) {
            context.cleanup();
        }
    }
}
