package net.theresa.ui.render

import net.theresa.render.vulkan.VulkanContext
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDescriptorImageInfo
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo
import org.lwjgl.vulkan.VkDescriptorPoolSize
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo
import org.lwjgl.vulkan.VkPushConstantRange
import org.lwjgl.vulkan.VkVertexInputAttributeDescription
import org.lwjgl.vulkan.VkVertexInputBindingDescription
import org.lwjgl.vulkan.VkWriteDescriptorSet
import org.joml.Matrix4f
import java.nio.ByteBuffer

/**
 * NeoUI Vulkan renderer: acrylic backdrop chain (baked panorama equirect -> quarter
 * res gaussian blur) + UI pipelines drawing inside the existing main render
 * pass with blending on and depth test off.
 */
class UiRenderer(
    private val ctx: VulkanContext,
    private val window: Long,
    imageFormat: Int,
    width: Int,
    height: Int,
) {
    private val panorama: VulkanPanorama = loadPanorama()
    private val chain = UiOffscreenChain(ctx, imageFormat)

    private var descriptorSetLayout = NULL
    private var descriptorPool = NULL
    private var panoramaSet = NULL
    private var rt0Set = NULL
    private var rt1Set = NULL
    private var rt0View = NULL
    private var rt1View = NULL

    private var pipelineLayout = NULL
    private var panoramaPipeline = NULL
    private var blurPipeline = NULL

    private val startTime = GLFW.glfwGetTime()

    init {
        chain.create(width, height)
        createDescriptorResources(imageFormat)
        createPipelineLayout()
        createPipelines()
        System.out.println("[NeoUI] renderer ready: $width x $height, backdrop "
                + chain.rt0?.width + "x" + chain.rt0?.height)
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    private fun loadPanorama(): VulkanPanorama {
        val faces = ArrayList<Pair<Int, ByteBuffer>>(6)
        try {
            for (i in 0 until 6) {
                val stream = javaClass.getResourceAsStream(
                    "/assets/minecraft/textures/gui/title/background/panorama_$i.png"
                ) ?: throw IllegalStateException("panorama_$i.png not found on classpath")
                stream.use { faces.add(VulkanPanorama.decodePngRgba(it)) }
            }
            val size = faces[0].first
            require(faces.all { it.first == size }) { "panorama faces differ in size" }
            val t0 = System.nanoTime()
            val equirect = VulkanPanorama.bakeEquirect(faces.map { it.second }, size)
            val ms = (System.nanoTime() - t0) / 1_000_000
            System.out.println("[NeoUI] panorama baked: 6x${size}x$size -> 2048x1024 in ${ms}ms")
            val tex = VulkanPanorama(ctx, equirect, 2048, 1024)
            MemoryUtil.memFree(equirect)
            return tex
        } finally {
            for ((_, buf) in faces) MemoryUtil.memFree(buf)
        }
    }

    private fun createDescriptorResources(imageFormat: Int) {
        MemoryStack.stackPush().use { stack ->
            val binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
            val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(binding)
            val pLayout = stack.mallocLong(1)
            VulkanContext.check(
                vkCreateDescriptorSetLayout(ctx.device, layoutInfo, null, pLayout),
                "vkCreateDescriptorSetLayout (NeoUI)"
            )
            descriptorSetLayout = pLayout.get(0)

            val poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(6)
            poolInfo.pPoolSizes(
                VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(6)
            )
            val pPool = stack.mallocLong(1)
            VulkanContext.check(
                vkCreateDescriptorPool(ctx.device, poolInfo, null, pPool), "vkCreateDescriptorPool (NeoUI)"
            )
            descriptorPool = pPool.get(0)

            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout, descriptorSetLayout, descriptorSetLayout))
            val sets = stack.mallocLong(3)
            VulkanContext.check(
                vkAllocateDescriptorSets(ctx.device, allocInfo, sets), "vkAllocateDescriptorSets (NeoUI)"
            )
            panoramaSet = sets.get(0)
            rt0Set = sets.get(1)
            rt1Set = sets.get(2)
            rt0View = chain.rt0!!.view
            rt1View = chain.rt1!!.view
            writeSamplerDescriptors()
        }
    }

    private fun writeSamplerDescriptors() {
        MemoryStack.stackPush().use { stack ->
            val info = VkDescriptorImageInfo.calloc(3, stack)
            info.get(0).sampler(panorama.sampler).imageView(panorama.view)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            info.get(1).sampler(panorama.sampler).imageView(rt0View)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            info.get(2).sampler(panorama.sampler).imageView(rt1View)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)

            val writes = VkWriteDescriptorSet.calloc(3, stack)
            for (i in 0 until 3) {
                writes.get(i)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(if (i == 0) panoramaSet else if (i == 1) rt0Set else rt1Set)
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(info.slice(i, 1))
            }
            vkUpdateDescriptorSets(ctx.device, writes, null)
        }
    }

    private fun createPipelineLayout() {
        MemoryStack.stackPush().use { stack ->
            val pushRange = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT)
                .offset(0)
                .size(UiShaders.PUSH_SIZE)
            val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout))
                .pPushConstantRanges(pushRange)
            val pLayout = stack.mallocLong(1)
            VulkanContext.check(
                vkCreatePipelineLayout(ctx.device, layoutInfo, null, pLayout), "vkCreatePipelineLayout (NeoUI)"
            )
            pipelineLayout = pLayout.get(0)
        }
    }

    // ------------------------------------------------------------------
    // Pipelines
    // ------------------------------------------------------------------

    private fun buildPipeline(
        stack: MemoryStack,
        vertSpv: ByteArray,
        fragSpv: ByteArray,
        vertexStride: Int,
        attributes: VkVertexInputAttributeDescription.Buffer?,
    ): Long {
        val vertModule = UiShaders.createModule(ctx.device, vertSpv)
        val fragModule = UiShaders.createModule(ctx.device, fragSpv)
        try {
            val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            val pMain = stack.UTF8("main")
            stages.get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(vertModule)
                .pName(pMain)
            stages.get(1)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragModule)
                .pName(pMain)

            var vertexInput: VkPipelineVertexInputStateCreateInfo? = null
            if (attributes != null) {
                val binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0)
                    .stride(vertexStride)
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
                vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(binding)
                    .pVertexAttributeDescriptions(attributes)
            } else {
                vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
            }

            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)

            val rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .lineWidth(1.0f)

            val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

            val depthStencil = org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(false)
                .depthWriteEnable(false)

            val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
            blendAttachment.get(0).colorWriteMask(
                VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT
                        or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT
            )
            blendAttachment.get(0).blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .alphaBlendOp(VK_BLEND_OP_ADD)
            val colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(blendAttachment)

            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1)
            val dynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)
            val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(dynamicStates)

            val info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pRasterizationState(rasterization)
                .pMultisampleState(multisample)
                .pDepthStencilState(depthStencil)
                .pColorBlendState(colorBlend)
                .pViewportState(viewportState)
                .pDynamicState(dynamicState)
                .layout(pipelineLayout)
                .renderPass(chain.renderPass)   // compatibility-only for main-pass pipelines: layout/blend must match
                .subpass(0)
                .basePipelineHandle(NULL)

            val pPipeline = stack.mallocLong(1)
            VulkanContext.check(
                vkCreateGraphicsPipelines(ctx.device, NULL, info, null, pPipeline), "vkCreateGraphicsPipelines (NeoUI)"
            )
            return pPipeline.get(0)
        } finally {
            vkDestroyShaderModule(ctx.device, vertModule, null)
            vkDestroyShaderModule(ctx.device, fragModule, null)
        }
    }

    private fun createPipelines() {
        MemoryStack.stackPush().use { stack ->
            val vertSpv = UiShaders.compile(UiShaders.load("fullscreen", UiShaders.Stage.VERTEX, UiShaders.FULLSCREEN_VERT), UiShaders.Stage.VERTEX, "ui_fullscreen.vert")
            val panoramaSpv = UiShaders.compile(UiShaders.load("panorama", UiShaders.Stage.FRAGMENT, UiShaders.PANORAMA_FRAG), UiShaders.Stage.FRAGMENT, "ui_panorama.frag")
            val blurSpv = UiShaders.compile(UiShaders.load("blur", UiShaders.Stage.FRAGMENT, UiShaders.BLUR_FRAG), UiShaders.Stage.FRAGMENT, "ui_blur.frag")

            panoramaPipeline = buildPipeline(stack, vertSpv, panoramaSpv, 0, null)
            blurPipeline = buildPipeline(stack, vertSpv, blurSpv, 0, null)
        }
    }

    /** F9 support: rebuild the UI pipelines from current shader sources. */
    fun reloadPipelines() {
        ctx.waitIdle()
        if (panoramaPipeline != NULL) { vkDestroyPipeline(ctx.device, panoramaPipeline, null); panoramaPipeline = NULL }
        if (blurPipeline != NULL) { vkDestroyPipeline(ctx.device, blurPipeline, null); blurPipeline = NULL }
        createPipelines()
        System.out.println("[NeoUI] pipelines reloaded")
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    private fun pushConstants(cmd: VkCommandBuffer, stack: MemoryStack, width: Int, height: Int,
                              params1x: Float, params1y: Float, params1z: Float, params1w: Float) {
        val push = stack.malloc(UiShaders.PUSH_SIZE)
        push.clear()
        Matrix4f().ortho(0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f).get(push)
        push.position(64)
        push.putFloat(width.toFloat()).putFloat(height.toFloat())
        push.putFloat((GLFW.glfwGetTime() - startTime).toFloat())
        push.putFloat(if (height > 0) width.toFloat() / height.toFloat() else 1f)
        push.position(80)
        push.putFloat(params1x).putFloat(params1y).putFloat(params1z).putFloat(params1w)
        push.flip()
        vkCmdPushConstants(
            cmd, pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT, 0, push
        )
    }

    /** Records the offscreen panorama + blur passes; call before the main render pass. */
    fun prepare(cmd: VkCommandBuffer) {
        val rt0 = chain.rt0 ?: return
        val rt1 = chain.rt1 ?: return

        MemoryStack.stackPush().use { stack ->
            // Pass 1: rotating panorama -> rt0
            chain.beginPass(cmd, rt0)
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, panoramaPipeline)
            vkCmdBindDescriptorSets(
                cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(panoramaSet), null
            )
            pushConstants(cmd, stack, rt0.width, rt0.height, 0f, 0f, 0f, 0f)
            vkCmdDraw(cmd, 3, 1, 0, 0)
            vkCmdEndRenderPass(cmd)

            // Pass 2: horizontal blur rt0 -> rt1
            chain.beginPass(cmd, rt1)
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, blurPipeline)
            vkCmdBindDescriptorSets(
                cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(rt0Set), null
            )
            pushConstants(cmd, stack, rt1.width, rt1.height, 1f / rt0.width, 1f / rt0.height, BLUR_SPREAD, 0f)
            vkCmdDraw(cmd, 3, 1, 0, 0)
            vkCmdEndRenderPass(cmd)

            // Pass 3: vertical blur rt1 -> rt0 (final backdrop)
            chain.beginPass(cmd, rt0)
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, blurPipeline)
            vkCmdBindDescriptorSets(
                cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(rt1Set), null
            )
            pushConstants(cmd, stack, rt0.width, rt0.height, 1f / rt1.width, 1f / rt1.height, 0f, BLUR_SPREAD)
            vkCmdDraw(cmd, 3, 1, 0, 0)
            vkCmdEndRenderPass(cmd)
        }
    }

    /**
     * Records UI draws inside the main render pass. With no world loaded the
     * menu is shown (background + screens); in-world it becomes the HUD layer
     * (empty until HUD screens are built).
     */
    fun renderInPass(cmd: VkCommandBuffer, width: Int, height: Int, menuContext: Boolean) {
        MemoryStack.stackPush().use { stack ->
            // The dynamic viewport state is shared across all passes recorded
            // into this command buffer; the offscreen passes leave it at the
            // quarter-res backdrop size, so re-target the full swapchain here.
            val viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack)
                .x(0f).y(0f).width(width.toFloat()).height(height.toFloat())
                .minDepth(0f).maxDepth(1f)
            val scissor = org.lwjgl.vulkan.VkRect2D.calloc(1, stack)
                .offset(org.lwjgl.vulkan.VkOffset2D.calloc(stack).set(0, 0))
                .extent { it.set(width, height) }
            vkCmdSetViewport(cmd, 0, viewport)
            vkCmdSetScissor(cmd, 0, scissor)

            if (menuContext) {
                // Menu background: the panorama sampled full screen.
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, panoramaPipeline)
                vkCmdBindDescriptorSets(
                    cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(panoramaSet), null
                )
                pushConstants(cmd, stack, width, height, 0f, 0f, 0f, 0f)
                vkCmdDraw(cmd, 3, 1, 0, 0)
            }
            // Scene widget draws are appended here (M3/M4).
        }
    }

    /** Swapchain resized: rebuild quarter-res targets and re-point descriptors. */
    fun onResized(width: Int, height: Int) {
        ctx.waitIdle()
        chain.resize(width, height)
        rt0View = chain.rt0!!.view
        rt1View = chain.rt1!!.view
        writeSamplerDescriptors()
    }

    fun destroy() {
        if (ctx.device == null) return
        if (panoramaPipeline != NULL) vkDestroyPipeline(ctx.device, panoramaPipeline, null)
        if (blurPipeline != NULL) vkDestroyPipeline(ctx.device, blurPipeline, null)
        if (pipelineLayout != NULL) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null)
        if (descriptorPool != NULL) vkDestroyDescriptorPool(ctx.device, descriptorPool, null)
        if (descriptorSetLayout != NULL) vkDestroyDescriptorSetLayout(ctx.device, descriptorSetLayout, null)
        chain.destroy()
        panorama.destroy()
    }

    companion object {
        /** Gaussian spread in source texels per tap step. */
        const val BLUR_SPREAD = 3.0f
    }
}
