package net.theresa.render.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One frame in flight: a command pool with a single primary command buffer,
 * an image-available semaphore, a render-finished semaphore and a fence
 * created already signaled so the first frame's wait doesn't deadlock.
 */
public class VulkanFrame {

    private final VulkanContext ctx;

    // LWJGL models non-dispatchable handles (including VkCommandPool) as long.
    public long commandPool;
    public VkCommandBuffer commandBuffer;
    public long imageAvailable;
    public long renderFinished;
    public long fence;

    public VulkanFrame(VulkanContext ctx) {
        this.ctx = ctx;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.graphicsFamily);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(ctx.device, poolInfo, null, pPool), "vkCreateCommandPool");
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(ctx.device, allocInfo, pCommandBuffer), "vkAllocateCommandBuffers");
            commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), ctx.device);

            imageAvailable = createSemaphore(stack);
            renderFinished = createSemaphore(stack);
            fence = createFence(stack);
        }
    }

    private long createSemaphore(MemoryStack stack) {
        VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        LongBuffer pSemaphore = stack.mallocLong(1);
        check(vkCreateSemaphore(ctx.device, info, null, pSemaphore), "vkCreateSemaphore");
        return pSemaphore.get(0);
    }

    private long createFence(MemoryStack stack) {
        VkFenceCreateInfo info = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK_FENCE_CREATE_SIGNALED_BIT);
        LongBuffer pFence = stack.mallocLong(1);
        check(vkCreateFence(ctx.device, info, null, pFence), "vkCreateFence");
        return pFence.get(0);
    }

    public void resetFence() {
        check(vkResetFences(ctx.device, fence), "vkResetFences");
    }

    public void cleanup() {
        if (ctx.device == null) {
            return;
        }
        if (imageAvailable != NULL) {
            vkDestroySemaphore(ctx.device, imageAvailable, null);
            imageAvailable = NULL;
        }
        if (renderFinished != NULL) {
            vkDestroySemaphore(ctx.device, renderFinished, null);
            renderFinished = NULL;
        }
        if (fence != NULL) {
            vkDestroyFence(ctx.device, fence, null);
            fence = NULL;
        }
        if (commandPool != NULL) {
            vkDestroyCommandPool(ctx.device, commandPool, null);
            commandPool = NULL;
        }
    }

    private static void check(int vkResult, String what) {
        if (vkResult != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed with VkResult " + vkResult);
        }
    }
}
