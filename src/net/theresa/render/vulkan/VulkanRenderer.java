package net.theresa.render.vulkan;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
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
            VK10.vkCmdEndRenderPass(commandBuffer);
            VulkanContext.check(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
        } finally {
            beginInfo.free();
        }
    }
}
