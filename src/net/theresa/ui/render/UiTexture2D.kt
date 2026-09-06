package net.theresa.ui.render

import net.theresa.render.vulkan.VulkanContext
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkImageSubresourceLayers
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkSamplerCreateInfo
import org.lwjgl.vulkan.VkSubmitInfo
import java.nio.ByteBuffer

/**
 * Minimal CPU-pixel 2D texture for UI content (glyph atlas pages, widget
 * images). Single mip, optimal tiling, dedicated allocation; uploaded through
 * a staging buffer with a one-shot submit + fence wait (same proven pattern
 * as VulkanTexture/VulkanPanorama).
 */
class UiTexture2D(
    private val ctx: VulkanContext,
    val width: Int,
    val height: Int,
    pixels: ByteBuffer,
    format: Int,
    linearFilter: Boolean = true,
) {
    var image = NULL; private set
    var memory = NULL; private set
    var view = NULL; private set
    var sampler = NULL; private set
    private var uploadPool = NULL

    init {
        MemoryStack.stackPush().use { stack ->
            val info = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .extent { it.set(width, height, 1) }
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            val pImage = stack.mallocLong(1)
            VulkanContext.check(vkCreateImage(ctx.device, info, null, pImage), "vkCreateImage (UiTexture2D)")
            image = pImage.get(0)

            val reqs = VkMemoryRequirements.calloc(stack)
            vkGetImageMemoryRequirements(ctx.device, image, reqs)
            val alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(ctx.memoryTypeIndex(reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            val pMemory = stack.mallocLong(1)
            VulkanContext.check(vkAllocateMemory(ctx.device, alloc, null, pMemory), "vkAllocateMemory (UiTexture2D)")
            memory = pMemory.get(0)
            VulkanContext.check(vkBindImageMemory(ctx.device, image, memory, 0), "vkBindImageMemory (UiTexture2D)")

            val viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
                .subresourceRange(
                    VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
                )
            val pView = stack.mallocLong(1)
            VulkanContext.check(vkCreateImageView(ctx.device, viewInfo, null, pView), "vkCreateImageView (UiTexture2D)")
            view = pView.get(0)

            val filter = if (linearFilter) VK_FILTER_LINEAR else VK_FILTER_NEAREST
            val samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(filter)
                .minFilter(filter)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0.0f).maxLod(0.0f)
            val pSampler = stack.mallocLong(1)
            VulkanContext.check(vkCreateSampler(ctx.device, samplerInfo, null, pSampler), "vkCreateSampler (UiTexture2D)")
            sampler = pSampler.get(0)

            upload(stack, pixels)
        }
    }

    private fun upload(stack: MemoryStack, pixels: ByteBuffer) {
        val totalSize = pixels.remaining().toLong()

        val bufferInfo = VkBufferCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            .size(totalSize)
            .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val pBuffer = stack.mallocLong(1)
        VulkanContext.check(vkCreateBuffer(ctx.device, bufferInfo, null, pBuffer), "vkCreateBuffer (UiTexture2D staging)")
        val staging = pBuffer.get(0)
        val reqs = VkMemoryRequirements.calloc(stack)
        vkGetBufferMemoryRequirements(ctx.device, staging, reqs)
        val alloc = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(reqs.size())
            .memoryTypeIndex(
                ctx.memoryTypeIndex(
                    reqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                )
            )
        val pMemory = stack.mallocLong(1)
        VulkanContext.check(vkAllocateMemory(ctx.device, alloc, null, pMemory), "vkAllocateMemory (UiTexture2D staging)")
        val stagingMemory = pMemory.get(0)
        VulkanContext.check(vkBindBufferMemory(ctx.device, staging, stagingMemory, 0), "vkBindBufferMemory (UiTexture2D staging)")

        try {
            val pMapped = stack.mallocPointer(1)
            VulkanContext.check(vkMapMemory(ctx.device, stagingMemory, 0, totalSize, 0, pMapped), "vkMapMemory (UiTexture2D)")
            pMapped.getByteBuffer(0, totalSize.toInt()).put(pixels.duplicate())
            vkUnmapMemory(ctx.device, stagingMemory)

            ensureUploadPool(stack)
            val cmdAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(uploadPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
            val pCmd = stack.mallocPointer(1)
            VulkanContext.check(vkAllocateCommandBuffers(ctx.device, cmdAlloc, pCmd), "vkAllocateCommandBuffers (UiTexture2D)")
            val cmd: VkCommandBuffer = VkCommandBuffer(pCmd.get(0), ctx.device)

            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            VulkanContext.check(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer (UiTexture2D)")

            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)

            val toTransfer = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(range)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer)

            val copy = VkBufferImageCopy.calloc(1, stack)
            copy.get(0)
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageSubresource(
                    VkImageSubresourceLayers.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1)
                )
                .imageOffset { it.set(0, 0, 0) }
                .imageExtent { it.set(width, height, 1) }
            vkCmdCopyBufferToImage(cmd, staging, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy)

            val toShader = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(range)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, toShader)

            VulkanContext.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer (UiTexture2D)")

            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmd))
            val fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            val pFence = stack.mallocLong(1)
            VulkanContext.check(vkCreateFence(ctx.device, fenceInfo, null, pFence), "vkCreateFence (UiTexture2D)")
            val fence = pFence.get(0)
            try {
                VulkanContext.check(vkQueueSubmit(ctx.graphicsQueue, submitInfo, fence), "vkQueueSubmit (UiTexture2D)")
                VulkanContext.check(vkWaitForFences(ctx.device, fence, true, -1L), "vkWaitForFences (UiTexture2D)")
            } finally {
                vkDestroyFence(ctx.device, fence, null)
            }
            vkFreeCommandBuffers(ctx.device, uploadPool, cmd)
        } finally {
            vkDestroyBuffer(ctx.device, staging, null)
            vkFreeMemory(ctx.device, stagingMemory, null)
        }
    }

    /**
     * Updates a sub-region of an already-sampled texture (glyph atlas
     * streaming). Safe on the same graphics queue: submits are ordered, and a
     * freshly baked glyph is never sampled by an older frame.
     */
    fun updateRegion(x: Int, y: Int, w: Int, h: Int, pixels: ByteBuffer) {
        val totalSize = pixels.remaining().toLong()
        MemoryStack.stackPush().use { stack ->
            val bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(totalSize)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val pBuffer = stack.mallocLong(1)
            VulkanContext.check(vkCreateBuffer(ctx.device, bufferInfo, null, pBuffer), "vkCreateBuffer (UiTexture2D region)")
            val staging = pBuffer.get(0)
            val reqs = VkMemoryRequirements.calloc(stack)
            vkGetBufferMemoryRequirements(ctx.device, staging, reqs)
            val alloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(
                    ctx.memoryTypeIndex(
                        reqs.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    )
                )
            val pMemory = stack.mallocLong(1)
            VulkanContext.check(vkAllocateMemory(ctx.device, alloc, null, pMemory), "vkAllocateMemory (UiTexture2D region)")
            val stagingMemory = pMemory.get(0)
            VulkanContext.check(vkBindBufferMemory(ctx.device, staging, stagingMemory, 0), "vkBindBufferMemory (UiTexture2D region)")

            try {
                val pMapped = stack.mallocPointer(1)
                VulkanContext.check(vkMapMemory(ctx.device, stagingMemory, 0, totalSize, 0, pMapped), "vkMapMemory (UiTexture2D region)")
                pMapped.getByteBuffer(0, totalSize.toInt()).put(pixels.duplicate())
                vkUnmapMemory(ctx.device, stagingMemory)

                ensureUploadPool(stack)
                val cmdAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(uploadPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1)
                val pCmd = stack.mallocPointer(1)
                VulkanContext.check(vkAllocateCommandBuffers(ctx.device, cmdAlloc, pCmd), "vkAllocateCommandBuffers (UiTexture2D region)")
                val cmd: VkCommandBuffer = VkCommandBuffer(pCmd.get(0), ctx.device)

                val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
                VulkanContext.check(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer (UiTexture2D region)")

                val range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)

                val toTransfer = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .subresourceRange(range)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer)

                val copy = VkBufferImageCopy.calloc(1, stack)
                copy.get(0)
                    .bufferOffset(0)
                .bufferRowLength(w)              // tightly packed region rows
                    .bufferImageHeight(0)
                    .imageSubresource(
                        VkImageSubresourceLayers.calloc(stack)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0).baseArrayLayer(0).layerCount(1)
                    )
                    .imageOffset { it.set(x, y, 0) }
                    .imageExtent { it.set(w, h, 1) }
                vkCmdCopyBufferToImage(cmd, staging, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy)

                val toShader = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .subresourceRange(range)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, toShader)

                VulkanContext.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer (UiTexture2D region)")

                val submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd))
                val fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                val pFence = stack.mallocLong(1)
                VulkanContext.check(vkCreateFence(ctx.device, fenceInfo, null, pFence), "vkCreateFence (UiTexture2D region)")
                val fence = pFence.get(0)
                try {
                    VulkanContext.check(vkQueueSubmit(ctx.graphicsQueue, submitInfo, fence), "vkQueueSubmit (UiTexture2D region)")
                    VulkanContext.check(vkWaitForFences(ctx.device, fence, true, -1L), "vkWaitForFences (UiTexture2D region)")
                } finally {
                    vkDestroyFence(ctx.device, fence, null)
                }
                vkFreeCommandBuffers(ctx.device, uploadPool, cmd)
            } finally {
                vkDestroyBuffer(ctx.device, staging, null)
                vkFreeMemory(ctx.device, stagingMemory, null)
            }
        }
    }

    private fun ensureUploadPool(stack: MemoryStack) {
        if (uploadPool != NULL) return
        val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT or VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            .queueFamilyIndex(ctx.graphicsFamily)
        val pPool = stack.mallocLong(1)
        VulkanContext.check(vkCreateCommandPool(ctx.device, poolInfo, null, pPool), "vkCreateCommandPool (UiTexture2D)")
        uploadPool = pPool.get(0)
    }

    fun destroy() {
        if (ctx.device == null) return
        if (sampler != NULL) { vkDestroySampler(ctx.device, sampler, null); sampler = NULL }
        if (view != NULL) { vkDestroyImageView(ctx.device, view, null); view = NULL }
        if (memory != NULL) { vkFreeMemory(ctx.device, memory, null); memory = NULL }
        if (image != NULL) { vkDestroyImage(ctx.device, image, null); image = NULL }
        if (uploadPool != NULL) { vkDestroyCommandPool(ctx.device, uploadPool, null); uploadPool = NULL }
    }

    companion object {
        /** Allocates a tightly packed RGBA byte buffer (helper for callers). */
        fun allocRgba(w: Int, h: Int): ByteBuffer = MemoryUtil.memAlloc(w * h * 4)
    }
}
