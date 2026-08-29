package net.theresa.render.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A Vulkan copy of a live OpenGL 2D texture (with mipmaps). Mip levels are read
 * back from GL with glGetTexImage on the GL thread (an OpenGL context is current
 * there), staged in one HOST_VISIBLE buffer and copied into an optimal-tiling
 * R8G8B8A8_SRGB image via a one-shot command buffer on the graphics queue:
 * UNDEFINED -&gt; TRANSFER_DST before the copies, TRANSFER_DST -&gt; SHADER_READ_ONLY
 * after them.
 */
public class VulkanTexture {

    private static VulkanContext activeContext;

    private final VulkanContext ctx;

    public long image = NULL;
    public long memory = NULL;
    public long view = NULL;
    public long sampler = NULL;
    public int width;
    public int height;
    public int levels;

    /** Owns the short-lived command buffers used by (re)uploads. */
    private long uploadPool = NULL;

    public VulkanTexture(VulkanContext ctx, int glTextureId, int width, int height, int levels) {
        this.ctx = ctx;
        this.width = width;
        this.height = height;
        this.levels = Math.max(1, levels);
        activeContext = ctx;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            createImage(stack);
            allocateAndBindMemory(stack);
            createView(stack);
            createSampler(stack);
        }
        uploadFromGL(glTextureId, true);
    }

    /**
     * Convenience for callers that do not carry the context around; uses the
     * context of the most recently constructed VulkanTexture.
     */
    public static VulkanTexture fromGl(int glTextureId, int width, int height, int levels) {
        if (activeContext == null) {
            throw new IllegalStateException("No VulkanContext available: construct a VulkanTexture with an explicit context once before calling fromGl");
        }
        return new VulkanTexture(activeContext, glTextureId, width, height, levels);
    }

    /**
     * Re-reads all mip levels from the GL texture and re-uploads them into the
     * existing image (which must be in SHADER_READ_ONLY layout between updates).
     */
    public void updateFromGL(int glTextureId) {
        if (image == NULL) {
            throw new IllegalStateException("VulkanTexture was destroyed");
        }
        uploadFromGL(glTextureId, false);
    }

    private void uploadFromGL(int glTextureId, boolean initial) {
        // Per-level dimensions of the source mip chain and the total byte size.
        int[] dims = new int[levels * 2];
        long totalSize = 0;
        for (int level = 0; level < levels; level++) {
            int w = Math.max(1, width >> level);
            int h = Math.max(1, height >> level);
            dims[level * 2] = w;
            dims[level * 2 + 1] = h;
            totalSize += (long) w * h * 4;
        }
        if (totalSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("Texture too large to stage in one buffer: " + totalSize + " bytes");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] staging = createStagingBuffer(stack, totalSize);
            try {
                // Map once and read every mip level straight into the mapped
                // memory: glGetTexImage can write into any direct ByteBuffer.
                PointerBuffer pData = stack.mallocPointer(1);
                check(vkMapMemory(ctx.device, staging[1], 0, totalSize, 0, pData), "vkMapMemory");
                ByteBuffer mapped = pData.getByteBuffer(0, (int) totalSize);

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
                int offset = 0;
                for (int level = 0; level < levels; level++) {
                    int bytes = dims[level * 2] * dims[level * 2 + 1] * 4;
                    ByteBuffer slice = MemoryUtil.memSlice(mapped, offset, bytes);
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, slice);
                    offset += bytes;
                }
                vkUnmapMemory(ctx.device, staging[1]);

                recordAndSubmit(stack, staging[0], dims, initial);
            } finally {
                vkDestroyBuffer(ctx.device, staging[0], null);
                vkFreeMemory(ctx.device, staging[1], null);
            }
        }
    }

    /** HOST_VISIBLE | HOST_COHERENT buffer big enough for all mip levels. */
    private long[] createStagingBuffer(MemoryStack stack, long size) {
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuffer = stack.mallocLong(1);
        check(vkCreateBuffer(ctx.device, bufferInfo, null, pBuffer), "vkCreateBuffer");
        long buffer = pBuffer.get(0);

        VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(ctx.device, buffer, reqs);
        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(ctx.memoryTypeIndex(reqs.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(ctx.device, allocInfo, null, pMemory), "vkAllocateMemory");
        long bufferMemory = pMemory.get(0);

        check(vkBindBufferMemory(ctx.device, buffer, bufferMemory, 0), "vkBindBufferMemory");
        return new long[]{buffer, bufferMemory};
    }

    private void recordAndSubmit(MemoryStack stack, long stagingBuffer, int[] dims, boolean initial) {
        ensureUploadPool(stack);

        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(uploadPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
        PointerBuffer pCommandBuffer = stack.mallocPointer(1);
        check(vkAllocateCommandBuffers(ctx.device, allocInfo, pCommandBuffer), "vkAllocateCommandBuffers");
        VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), ctx.device);

        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        check(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");

        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(levels)
                .baseArrayLayer(0)
                .layerCount(1);

        // Top of record: make the image writable (UNDEFINED discards, which is
        // fine because every mip is fully overwritten; on re-upload the image is
        // in SHADER_READ_ONLY and may still be in use by an in-flight frame).
        VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(initial ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(range)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
        vkCmdPipelineBarrier(commandBuffer,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                null, null, toTransfer);

        // One tightly packed region per mip level; rowPitch is w*4 because the
        // staging bytes are laid out with level sizes halving each level.
        VkBufferImageCopy.Buffer copies = VkBufferImageCopy.calloc(levels, stack);
        long offset = 0;
        for (int level = 0; level < levels; level++) {
            int w = dims[level * 2];
            int h = dims[level * 2 + 1];
            copies.get(level)
                    .bufferOffset(offset)
                    .bufferRowLength(0)
                    .bufferImageHeight(0)
                    .imageSubresource(VkImageSubresourceLayers.calloc(stack)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(level)
                            .baseArrayLayer(0)
                            .layerCount(1))
                    .imageOffset(it -> it.set(0, 0, 0))
                    .imageExtent(it -> it.set(w, h, 1));
            offset += (long) w * h * 4;
        }
        vkCmdCopyBufferToImage(commandBuffer, stagingBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copies);

        // Make the upload visible to shader sampling.
        VkImageMemoryBarrier.Buffer toShader = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(range)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        vkCmdPipelineBarrier(commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                null, null, toShader);

        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");

        VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commandBuffer));
        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
        LongBuffer pFence = stack.mallocLong(1);
        check(vkCreateFence(ctx.device, fenceInfo, null, pFence), "vkCreateFence");
        long fence = pFence.get(0);
        try {
            check(vkQueueSubmit(ctx.graphicsQueue, submitInfo, fence), "vkQueueSubmit");
            check(vkWaitForFences(ctx.device, fence, true, ~0L), "vkWaitForFences");
        } finally {
            vkDestroyFence(ctx.device, fence, null);
        }
        vkFreeCommandBuffers(ctx.device, uploadPool, commandBuffer);
    }

    private void ensureUploadPool(MemoryStack stack) {
        if (uploadPool != NULL) {
            return;
        }
        VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(ctx.graphicsFamily);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateCommandPool(ctx.device, poolInfo, null, pPool), "vkCreateCommandPool");
        uploadPool = pPool.get(0);
    }

    private void createImage(MemoryStack stack) {
        VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_SRGB)
                .extent(it -> it.set(width, height, 1))
                .mipLevels(levels)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        LongBuffer pImage = stack.mallocLong(1);
        check(vkCreateImage(ctx.device, info, null, pImage), "vkCreateImage");
        image = pImage.get(0);
    }

    private void allocateAndBindMemory(MemoryStack stack) {
        VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(ctx.device, image, reqs);
        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(ctx.memoryTypeIndex(reqs.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(ctx.device, allocInfo, null, pMemory), "vkAllocateMemory");
        memory = pMemory.get(0);
        check(vkBindImageMemory(ctx.device, image, memory, 0), "vkBindImageMemory");
    }

    private void createView(MemoryStack stack) {
        VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_SRGB)
                .subresourceRange(VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(levels)
                        .baseArrayLayer(0)
                        .layerCount(1));
        LongBuffer pView = stack.mallocLong(1);
        check(vkCreateImageView(ctx.device, info, null, pView), "vkCreateImageView");
        view = pView.get(0);
    }

    private void createSampler(MemoryStack stack) {
        VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .mipLodBias(0.0f)
                .anisotropyEnable(false)
                .minLod(0.0f)
                .maxLod(Boolean.getBoolean("neogenesis.vkLod0") ? 0.0f : levels);
        LongBuffer pSampler = stack.mallocLong(1);
        check(vkCreateSampler(ctx.device, info, null, pSampler), "vkCreateSampler");
        sampler = pSampler.get(0);
    }

    /** Null-safe teardown of sampler, view, memory, image and the upload pool. */
    public void destroy() {
        if (ctx.device == null) {
            return;
        }
        if (sampler != NULL) {
            vkDestroySampler(ctx.device, sampler, null);
            sampler = NULL;
        }
        if (view != NULL) {
            vkDestroyImageView(ctx.device, view, null);
            view = NULL;
        }
        if (memory != NULL) {
            vkFreeMemory(ctx.device, memory, null);
            memory = NULL;
        }
        if (image != NULL) {
            vkDestroyImage(ctx.device, image, null);
            image = NULL;
        }
        if (uploadPool != NULL) {
            vkDestroyCommandPool(ctx.device, uploadPool, null);
            uploadPool = NULL;
        }
    }

    private static void check(int vkResult, String what) {
        if (vkResult != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed with VkResult " + vkResult);
        }
    }
}
