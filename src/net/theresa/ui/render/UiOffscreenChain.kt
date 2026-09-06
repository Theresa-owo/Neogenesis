package net.theresa.ui.render

import net.theresa.render.vulkan.VulkanContext
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.KHRSwapchain
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkFramebufferCreateInfo
import org.lwjgl.vulkan.VkOffset2D
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkRenderPassBeginInfo
import org.lwjgl.vulkan.VkViewport

/**
 * Quarter-resolution offscreen color targets for the acrylic backdrop chain:
 *
 *   panorama pass -> rt0, blurH (sample rt0) -> rt1, blurV (sample rt1) -> rt0
 *
 * Glass panels then sample rt0 in screen space inside the main render pass.
 * All offscreen passes share one render pass whose attachment transitions to
 * SHADER_READ_ONLY at the end, with an EXTERNAL->0 dependency that makes the
 * previous pass' writes visible to this pass' fragment shader.
 */
class UiOffscreenChain(private val ctx: VulkanContext, private val imageFormat: Int) {

    var renderPass = NULL; private set
    var rt0: OffscreenTarget? = null
    var rt1: OffscreenTarget? = null

    fun create(width: Int, height: Int) {
        renderPass = createOffscreenRenderPass()
        resize(width, height)
    }

    fun resize(width: Int, height: Int) {
        val w = maxOf(1, width / BACKDROP_SCALE)
        val h = maxOf(1, height / BACKDROP_SCALE)
        rt0?.destroy()
        rt1?.destroy()
        rt0 = OffscreenTarget(ctx, renderPass, imageFormat, w, h)
        rt1 = OffscreenTarget(ctx, renderPass, imageFormat, w, h)
    }

    fun destroy() {
        rt0?.destroy(); rt0 = null
        rt1?.destroy(); rt1 = null
        if (renderPass != NULL) {
            vkDestroyRenderPass(ctx.device, renderPass, null)
            renderPass = NULL
        }
    }

    /** Begins an offscreen pass on `target` with its own viewport/scissor. */
    fun beginPass(cmd: VkCommandBuffer, target: OffscreenTarget) {
        MemoryStack.stackPush().use { stack ->
            val begin = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(renderPass)
                .framebuffer(target.framebuffer)
                .renderArea(
                    org.lwjgl.vulkan.VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).set(0, 0))
                        .extent { it.set(target.width, target.height) }
                )
            val clear = org.lwjgl.vulkan.VkClearValue.calloc(1, stack)
            clear.get(0).color().float32(stack.floats(0f, 0f, 0f, 1f))
            begin.pClearValues(clear)
            vkCmdBeginRenderPass(cmd, begin, VK_SUBPASS_CONTENTS_INLINE)

            val viewport = VkViewport.calloc(1, stack)
                .x(0f).y(0f).width(target.width.toFloat()).height(target.height.toFloat())
                .minDepth(0f).maxDepth(1f)
            val scissor = VkRect2D.calloc(1, stack)
                .offset(VkOffset2D.calloc(stack).set(0, 0))
                .extent { it.set(target.width, target.height) }
            vkCmdSetViewport(cmd, 0, viewport)
            vkCmdSetScissor(cmd, 0, scissor)
        }
    }

    private fun createOffscreenRenderPass(): Long {
        MemoryStack.stackPush().use { stack ->
            val attachment = org.lwjgl.vulkan.VkAttachmentDescription.calloc(1, stack)
                .format(imageFormat)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                // UNDEFINED discards last frame's content; every pass fully
                // overwrites. A target written twice per frame (rt0) may legally
                // arrive in SHADER_READ_ONLY from its earlier pass.
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)

            val colorRef = org.lwjgl.vulkan.VkAttachmentReference.calloc(1, stack)
                .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)

            val subpass = org.lwjgl.vulkan.VkSubpassDescription.calloc(1, stack)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .colorAttachmentCount(1)
                .pColorAttachments(colorRef)

            // Orders the PREVIOUS pass' color writes (and the layout transition
            // to SHADER_READ_ONLY) before this pass' fragment reads.
            val dependency = org.lwjgl.vulkan.VkSubpassDependency.calloc(1, stack)
                .srcSubpass(VK_SUBPASS_EXTERNAL)
                .dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)

            val info = org.lwjgl.vulkan.VkRenderPassCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                .pAttachments(attachment)
                .pSubpasses(subpass)
                .pDependencies(dependency)

            val pass = stack.mallocLong(1)
            VulkanContext.check(vkCreateRenderPass(ctx.device, info, null, pass), "vkCreateRenderPass (NeoUI offscreen)")
            return pass.get(0)
        }
    }

    companion object {
        /** Backdrop targets render at 1/BACKDROP_SCALE of the swapchain extent. */
        const val BACKDROP_SCALE = 4
    }
}

/** A single offscreen color attachment: image + memory + view + framebuffer. */
class OffscreenTarget(
    private val ctx: VulkanContext,
    renderPass: Long,
    format: Int,
    val width: Int,
    val height: Int,
) {
    val image: Long
    val memory: Long
    val view: Long
    val framebuffer: Long

    init {
        MemoryStack.stackPush().use { stack ->
            val info = org.lwjgl.vulkan.VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .extent { it.set(width, height, 1) }
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            val pImage = stack.mallocLong(1)
            VulkanContext.check(vkCreateImage(ctx.device, info, null, pImage), "vkCreateImage (NeoUI RT)")
            image = pImage.get(0)

            val reqs = org.lwjgl.vulkan.VkMemoryRequirements.calloc(stack)
            vkGetImageMemoryRequirements(ctx.device, image, reqs)
            val allocInfo = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(ctx.memoryTypeIndex(reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            val pMemory = stack.mallocLong(1)
            VulkanContext.check(vkAllocateMemory(ctx.device, allocInfo, null, pMemory), "vkAllocateMemory (NeoUI RT)")
            memory = pMemory.get(0)
            VulkanContext.check(vkBindImageMemory(ctx.device, image, memory, 0), "vkBindImageMemory (NeoUI RT)")

            val viewInfo = org.lwjgl.vulkan.VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
                .subresourceRange(
                    org.lwjgl.vulkan.VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
                )
            val pView = stack.mallocLong(1)
            VulkanContext.check(vkCreateImageView(ctx.device, viewInfo, null, pView), "vkCreateImageView (NeoUI RT)")
            view = pView.get(0)

            val fbInfo = VkFramebufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                .renderPass(renderPass)
                .attachmentCount(1)
                .pAttachments(stack.longs(view))
                .width(width)
                .height(height)
                .layers(1)
            val pFb = stack.mallocLong(1)
            VulkanContext.check(vkCreateFramebuffer(ctx.device, fbInfo, null, pFb), "vkCreateFramebuffer (NeoUI RT)")
            framebuffer = pFb.get(0)
        }
    }

    fun destroy() {
        if (ctx.device == null) return
        if (framebuffer != NULL) vkDestroyFramebuffer(ctx.device, framebuffer, null)
        if (view != NULL) vkDestroyImageView(ctx.device, view, null)
        if (memory != NULL) vkFreeMemory(ctx.device, memory, null)
        if (image != NULL) vkDestroyImage(ctx.device, image, null)
    }
}
