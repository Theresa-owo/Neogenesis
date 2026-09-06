package net.theresa.ui.render

import net.minecraft.client.Minecraft
import net.theresa.render.vulkan.VulkanContext
import net.theresa.ui.NeoUI
import net.theresa.ui.font.FontEngine
import net.theresa.ui.hud.ItemIcons
import net.theresa.ui.scene.UiNode
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
    private var surfacePipeline = NULL
    private var textPipeline = NULL
    private var uiIconPipeline = NULL

    /** Icon pipeline descriptor sets: atlasIndex -> set, and the view it points at. */
    private val iconSets = HashMap<Int, Long>()
    private val iconSetViews = HashMap<Int, Long>()

    val font: FontEngine = FontEngine.create(ctx)
    private val ring = UiBufferRing(ctx)
    private val pageSets = ArrayList<Long>()
    private var uiFrameIndex = 0

    /** Background mode: dynamic blurred panorama (true) or static (false). */
    @Volatile
    var dynamicBackground = true

    /** Custom background image stretched fullscreen; null = solid color. */
    @Volatile
    var customBackground: UiTexture2D? = null

    // fullscreen background quad (6 verts, SURFACE_STRIDE layout), rewritten
    // every frame from CPU — trivial cost, keeps the ring for scene geometry
    private var fsQuadBuffer = NULL
    private var fsQuadMemory = NULL
    private var fsQuadMapped: ByteBuffer? = null

    private fun ensureFullscreenQuad() {
        if (fsQuadBuffer != NULL) return
        MemoryStack.stackPush().use { stack ->
            val info = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size((6 * SURFACE_STRIDE).toLong())
                .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val pBuffer = stack.mallocLong(1)
            VulkanContext.check(vkCreateBuffer(ctx.device, info, null, pBuffer), "vkCreateBuffer (fsQuad)")
            fsQuadBuffer = pBuffer.get(0)
            val reqs = org.lwjgl.vulkan.VkMemoryRequirements.calloc(stack)
            vkGetBufferMemoryRequirements(ctx.device, fsQuadBuffer, reqs)
            val alloc = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(
                    ctx.memoryTypeIndex(
                        reqs.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    )
                )
            val pMemory = stack.mallocLong(1)
            VulkanContext.check(vkAllocateMemory(ctx.device, alloc, null, pMemory), "vkAllocateMemory (fsQuad)")
            fsQuadMemory = pMemory.get(0)
            VulkanContext.check(vkBindBufferMemory(ctx.device, fsQuadBuffer, fsQuadMemory, 0), "vkBindBufferMemory (fsQuad)")
            val pMapped = stack.mallocPointer(1)
            VulkanContext.check(vkMapMemory(ctx.device, fsQuadMemory, 0, 6 * SURFACE_STRIDE.toLong(), 0, pMapped), "vkMapMemory (fsQuad)")
            fsQuadMapped = pMapped.getByteBuffer(0, 6 * SURFACE_STRIDE)
        }
    }

    private fun writeFullscreenQuad(w: Int, h: Int, argb: Int, uv01: Boolean) {
        val q = fsQuadMapped ?: return
        q.clear()
        fun v(px: Float, py: Float, uu: Float, vv: Float) {
            q.putFloat(px).putFloat(py)
            q.putFloat(uu).putFloat(vv)
            q.put(((argb shr 16) and 0xFF).toByte()).put(((argb shr 8) and 0xFF).toByte())
                .put((argb and 0xFF).toByte()).put(((argb shr 24) and 0xFF).toByte())
            q.putFloat(w.toFloat()).putFloat(h.toFloat()).putFloat(0f).putFloat(1f)
            q.put(0).put(0).put(0).put(0)
            q.put(0).put(0).put(0).put(0)
        }
        v(0f, 0f, 0f, 0f); v(0f, h.toFloat(), 0f, 1f); v(w.toFloat(), h.toFloat(), 1f, 1f)
        v(0f, 0f, 0f, 0f); v(w.toFloat(), h.toFloat(), 1f, 1f); v(w.toFloat(), 0f, 1f, 0f)
        q.position(0)
    }

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

    /**
     * Background loading, resource-pack aware:
     *  1. assets/neogenesis/ui/background.png (a 2:1 equirectangular image) if
     *     any pack provides one;
     *  2. otherwise the six vanilla panorama faces (minecraft namespace), which
     *     packs may also replace individually; baked into a 4096x2048 equirect
     *     with bilinear filtering.
     */
    private fun loadPanorama(): VulkanPanorama {
        val outW = 2048
        val outH = 1024
        val faces = ArrayList<Pair<Int, ByteBuffer>>(6)
        try {
            val rm = Minecraft.getMinecraft().getResourceManager()
            var custom: Pair<Int, ByteBuffer>? = null
            try {
                val res = rm.getResource(net.minecraft.util.ResourceLocation("neogenesis", "ui/background.png"))
                custom = VulkanPanorama.decodePngRgba(res.inputStream)
            } catch (_: Exception) {
                // no pack override, fall through to the vanilla panorama
            }
            if (custom != null) {
                val (w, buf) = custom!!
                val srcH = buf.remaining() / 4 / w
                val scaled = VulkanPanorama.stretch(buf, w, srcH, outW, outH)
                MemoryUtil.memFree(buf)
                System.out.println("[NeoUI] custom background loaded (${w}x$srcH -> ${outW}x$outH)")
                val tex = VulkanPanorama(ctx, scaled, outW, outH)
                MemoryUtil.memFree(scaled)
                return tex
            }
            for (i in 0 until 6) {
                val res = rm.getResource(
                    net.minecraft.util.ResourceLocation("minecraft", "textures/gui/title/background/panorama_$i.png")
                )
                faces.add(VulkanPanorama.decodePngRgba(res.inputStream))
            }
            val size = faces[0].first
            require(faces.all { it.first == size }) { "panorama faces differ in size" }
            val t0 = System.nanoTime()
            val equirect = VulkanPanorama.bakeEquirect(faces.map { it.second }, size, outW, outH)
            val ms = (System.nanoTime() - t0) / 1_000_000
            System.out.println("[NeoUI] panorama baked: 6x${size}x$size -> ${outW}x$outH in ${ms}ms")
            val tex = VulkanPanorama(ctx, equirect, outW, outH)
            MemoryUtil.memFree(equirect)
            return tex
        } finally {
            for ((_, buf) in faces) MemoryUtil.memFree(buf)
        }
    }

    /** Loads a custom background image from disk (stretched fullscreen). */
    fun loadCustomBackground(path: String) {
        try {
            val (w, buf) = VulkanPanorama.decodePngRgba(java.io.FileInputStream(path))
            val h = buf.remaining() / 4 / w
            val old = customBackground
            customBackground = UiTexture2D(ctx, w, h, buf, VK_FORMAT_R8G8B8A8_UNORM, true)
            MemoryUtil.memFree(buf)
            old?.destroy()
            System.out.println("[NeoUI] custom background loaded: $path (${w}x$h)")
        } catch (t: Throwable) {
            System.err.println("[NeoUI] custom background load failed: $t")
        }
    }

    /** Removes the custom background image (falls back to solid color). */
    fun clearCustomBackground() {
        customBackground?.destroy()
        customBackground = null
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
                .maxSets(32)
            poolInfo.pPoolSizes(
                VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(32)
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

            val textSpvVert = UiShaders.compile(UiShaders.load("surface", UiShaders.Stage.VERTEX, UiShaders.SURFACE_VERT), UiShaders.Stage.VERTEX, "ui_surface.vert")
            val textSpvFrag = UiShaders.compile(UiShaders.load("text", UiShaders.Stage.FRAGMENT, UiShaders.TEXT_FRAG), UiShaders.Stage.FRAGMENT, "ui_text.frag")
            textPipeline = buildPipeline(stack, textSpvVert, textSpvFrag, SURFACE_STRIDE, surfaceAttributes(stack))
            val surfaceSpvFrag = UiShaders.compile(UiShaders.load("surface", UiShaders.Stage.FRAGMENT, UiShaders.SURFACE_FRAG), UiShaders.Stage.FRAGMENT, "ui_surface.frag")
            surfacePipeline = buildPipeline(stack, textSpvVert, surfaceSpvFrag, SURFACE_STRIDE, surfaceAttributes(stack))
            val iconSpvFrag = UiShaders.compile(UiShaders.load("icon", UiShaders.Stage.FRAGMENT, ICON_FRAG), UiShaders.Stage.FRAGMENT, "ui_icon.frag")
            uiIconPipeline = buildPipeline(stack, textSpvVert, iconSpvFrag, SURFACE_STRIDE, surfaceAttributes(stack))
        }
    }

    /** F9 support: rebuild the UI pipelines from current shader sources. */
    fun reloadPipelines() {
        ctx.waitIdle()
        if (panoramaPipeline != NULL) { vkDestroyPipeline(ctx.device, panoramaPipeline, null); panoramaPipeline = NULL }
        if (blurPipeline != NULL) { vkDestroyPipeline(ctx.device, blurPipeline, null); blurPipeline = NULL }
        if (textPipeline != NULL) { vkDestroyPipeline(ctx.device, textPipeline, null); textPipeline = NULL }
        if (surfacePipeline != NULL) { vkDestroyPipeline(ctx.device, surfacePipeline, null); surfacePipeline = NULL }
        if (uiIconPipeline != NULL) { vkDestroyPipeline(ctx.device, uiIconPipeline, null); uiIconPipeline = NULL }
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
        // pixel coords with Y down: y=0 -> NDC -1 (top of the framebuffer in
        // Vulkan), y=h -> NDC +1 (bottom)
        Matrix4f().ortho(0f, width.toFloat(), 0f, height.toFloat(), -1f, 1f).get(push)
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
        ring.beginFrame()
        uiFrameIndex++
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

            // Passes 2-9: four H/V gaussian iterations — narrow taps, repeated,
            // approximate a wide smooth gaussian (kills residual square grain
            // from the low-res backdrop far better than a few wide taps)
            for (iteration in 0 until 4) {
                chain.beginPass(cmd, rt1)
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, blurPipeline)
                vkCmdBindDescriptorSets(
                    cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(rt0Set), null
                )
                pushConstants(cmd, stack, rt1.width, rt1.height, 1f / rt0.width, 1f / rt0.height, BLUR_SPREAD, 0f)
                vkCmdDraw(cmd, 3, 1, 0, 0)
                vkCmdEndRenderPass(cmd)

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
                // Menu background, three modes (switchable from Lua):
                //  1. custom image stretched fullscreen (uiIconPipeline, uv 0..1)
                //  2. solid theme color when dynamic backgrounds are off
                //  3. dynamic blurred panorama (default)
                ensureFullscreenQuad()
                val custom = customBackground
                when {
                    custom != null -> {
                        writeFullscreenQuad(width, height, -1, true)
                        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, uiIconPipeline)
                        val set = iconDescriptorSet(-1, custom)
                        vkCmdBindDescriptorSets(
                            cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(set), null
                        )
                        pushConstants(cmd, stack, width, height, 0f, 0f, 0f, 0f)
                        vkCmdBindVertexBuffers(cmd, 0, stack.longs(fsQuadBuffer), stack.longs(0))
                        vkCmdDraw(cmd, 6, 1, 0, 0)
                    }
                    !dynamicBackground -> {
                        val bg = net.theresa.ui.NeoUI.theme.surfaceSolidArgb
                        writeFullscreenQuad(width, height, bg or 0xFF000000.toInt(), false)
                        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, surfacePipeline)
                        vkCmdBindDescriptorSets(
                            cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(rt0Set), null
                        )
                        pushConstants(cmd, stack, width, height, 0f, 0f, 0f, 0f)
                        vkCmdBindVertexBuffers(cmd, 0, stack.longs(fsQuadBuffer), stack.longs(0))
                        vkCmdDraw(cmd, 6, 1, 0, 0)
                    }
                    else -> {
                        // Dynamic blurred panorama (default)
                        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, panoramaPipeline)
                        vkCmdBindDescriptorSets(
                            cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(panoramaSet), null
                        )
                        pushConstants(cmd, stack, width, height, 0f, 0f, 0f, 0f)
                        vkCmdDraw(cmd, 3, 1, 0, 0)
                    }
                }

                val screen = net.theresa.ui.screen.ScreenManager.current
                if (screen != null) {
                    renderScreenTree(cmd, stack, width, height, screen)
                }
            }
            // HUD screens (in-world) over the terrain
            if (!menuContext) {
                net.theresa.ui.hud.HudRenderer.screen?.let {
                    renderScreenTree(cmd, stack, width, height, it)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Screen tree encoding
    // ------------------------------------------------------------------

    private class Batch(val page: Int, val firstVertex: Int, var quads: Int)

    private fun renderScreenTree(
        cmd: VkCommandBuffer,
        stack: MemoryStack,
        width: Int,
        height: Int,
        screen: net.theresa.ui.screen.NeoScreen,
    ) {
        val view = ring.slot()
        val dp = height / 1080f * NeoUI.theme.baseScale
        screen.root.layoutFullscreen(dp, NeoUI.theme, width.toFloat(), height.toFloat())
        if (uiFrameIndex == 60) {
            screen.root.walk { n ->
                System.out.printf("[NeoUI tree] %-8s '%-14s' x=%.0f y=%.0f w=%.0f h=%.0f surface=%s shadow=%s%n",
                    n.type, n.text.take(12), n.x, n.y, n.width, n.height, n.drawsSurface, n.shadow)
            }
        }

        val surfaceBatches = ArrayList<Batch>()
        val textBatches = ArrayList<Batch>()
        val iconBatches = ArrayList<Batch>()
        var curSurface: Batch? = null
        var curText: Batch? = null
        var curIcon: Batch? = null

        // screen entrance: whole tree slides up + fades in (smoothstepped)
        val entrance = screen.entranceT
        val entranceLift = (1f - entrance) * 70f

        fun startOrContinue(list: ArrayList<Batch>, cur: Batch?, page: Int): Batch {
            val startVertex = ring.usedBytes(view) / SURFACE_STRIDE
            val last = list.lastOrNull()
            return if (cur != null && last === cur && cur.page == page && cur.firstVertex + cur.quads == startVertex) {
                cur
            } else {
                Batch(page, startVertex, 0).also { list.add(it) }
            }
        }

        fun vert(px: Float, py: Float, uu: Float, vv: Float, argb: Int,
                 rw: Float, rh: Float, radius: Float, mode: Float, gradEnd: Int, border: Int) {
            view.putFloat(px).putFloat(py)
            view.putFloat(uu).putFloat(vv)
            view.put(((argb shr 16) and 0xFF).toByte()).put(((argb shr 8) and 0xFF).toByte())
                .put((argb and 0xFF).toByte()).put((((argb shr 24) and 0xFF) * entrance).toInt().toByte())
            view.putFloat(rw).putFloat(rh).putFloat(radius).putFloat(mode)
            view.put(((gradEnd shr 16) and 0xFF).toByte()).put(((gradEnd shr 8) and 0xFF).toByte())
                .put((gradEnd and 0xFF).toByte()).put((((gradEnd shr 24) and 0xFF) * entrance).toInt().toByte())
            view.put(((border shr 16) and 0xFF).toByte()).put(((border shr 8) and 0xFF).toByte())
                .put((border and 0xFF).toByte()).put((((border shr 24) and 0xFF) * entrance).toInt().toByte())
        }

        /** MD3 state layer: mixes a fill toward white by the hover/press amount. */
        fun stateLayer(color: Int, node: UiNode): Int {
            val t = node.hoverT * 0.12f + if (node.pressed) 0.10f else 0f
            if (t <= 0f) return color
            fun ch(v: Int, shift: Int): Int {
                val c = (v shr shift) and 0xFF
                return (c + ((255 - c) * t).toInt()).coerceAtMost(255)
            }
            return (ch(color, 24) shl 24) or (ch(color, 16) shl 16) or (ch(color, 8) shl 8) or ch(color, 0)
        }

        fun surfaceQuad(x: Float, y: Float, w: Float, h: Float, node: UiNode, mode: Float, shift: Float) {
            curSurface = startOrContinue(surfaceBatches, curSurface, 0)
            val lift = node.hoverT * 3f
            // press feedback: the surface shrinks 2% around its center
            val pressScale = 1f - node.pressedT * 0.02f
            val qw = w * pressScale; val qh = h * pressScale
            val fill = stateLayer(node.fillColor, node)
            val fillEnd = stateLayer(node.fillEndColor, node)
            val x0 = x + (w - qw) / 2; val y0 = y + (h - qh) / 2 - lift + shift
            val x1 = x0 + qw; val y1 = y0 + qh
            fun v(px: Float, py: Float, uu: Float, vv: Float) = vert(px, py, uu, vv, fill, qw, qh, node.radius * pressScale, mode, fillEnd, node.borderColor)
            // fills: local pixel coords (uv), gradient t = uv.y / h
            v(x0, y0, 0f, 0f); v(x0, y1, 0f, qh); v(x1, y1, qw, qh)
            v(x0, y0, 0f, 0f); v(x1, y1, qw, qh); v(x1, y0, qw, 0f)
            curSurface!!.quads++
        }

        fun shadowQuad(x: Float, y: Float, w: Float, h: Float, node: UiNode, shift: Float) {
            curSurface = startOrContinue(surfaceBatches, curSurface, 0)
            val s = node.shadowSpread
            val x0 = x - s; val y0 = y - s + node.hoverT * 3f + shift
            val x1 = x + w + s; val y1 = y + h + s + node.hoverT * 3f + shift
            fun v(px: Float, py: Float, uu: Float, vv: Float) = vert(px, py, uu, vv, node.shadowColor, w, h, node.radius, 3f, node.shadowColor, 0)
            v(x0, y0, 0f, 0f); v(x0, y1, 0f, h); v(x1, y1, w, h)
            v(x0, y0, 0f, 0f); v(x1, y1, w, h); v(x1, y0, w, 0f)
            curSurface!!.quads++
        }

        fun textRun(text: String, startX: Float, baseline: Float, sizeI: Int, defaultColor: Int,
                    isShadow: Boolean, bold: Boolean, letterSpacing: Float) {
            var penX = startX
            var prev = -1
            for ((seg, argb) in FontEngine.parseColorCodes(text, defaultColor)) {
                for (ch in seg) {
                    val cp = ch.code
                    val g = font.getGlyph(cp, sizeI.toFloat(), bold)
                    if (prev >= 0) penX += font.kern(prev, cp, sizeI.toFloat(), bold)
                    if (g.page >= 0 && g.width > 0) {
                        curText = startOrContinue(textBatches, curText, g.page)
                        val x0 = penX + g.xoff
                        val y0 = baseline + g.yoff
                        val x1 = x0 + g.width
                        val y1 = y0 + g.height
                        fun v(px: Float, py: Float, uu: Float, vv: Float) = vert(px, py, uu, vv, argb, 0f, 0f, 0f, 0f, 0, 0)
                        v(x0, y0, g.u0, g.v0); v(x0, y1, g.u0, g.v1); v(x1, y1, g.u1, g.v1)
                        v(x0, y0, g.u0, g.v0); v(x1, y1, g.u1, g.v1); v(x1, y0, g.u1, g.v0)
                        curText!!.quads++
                    }
                    penX += g.advance + letterSpacing
                    prev = cp
                }
            }
        }

        /** Icon quad: uv = sprite rect inside the item atlas, tint = node fill. */
        fun iconQuad(node: UiNode, shift: Float) {
            val spec = ItemIcons.specFor(node) ?: return
            curIcon = startOrContinue(iconBatches, curIcon, spec.atlasIndex)
            val x0 = node.x
            val y0 = node.y + shift
            val x1 = x0 + node.width
            val y1 = y0 + node.height
            fun v(px: Float, py: Float, uu: Float, vv: Float) =
                vert(px, py, uu, vv, node.fillColor, 0f, 0f, 0f, 0f, 0, 0)
            v(x0, y0, spec.u0, spec.v0); v(x0, y1, spec.u0, spec.v1); v(x1, y1, spec.u1, spec.v1)
            v(x0, y0, spec.u0, spec.v0); v(x1, y1, spec.u1, spec.v1); v(x1, y0, spec.u1, spec.v0)
            curIcon!!.quads++
        }

        /** Records all pending batches under the current scissor state and
         *  clears the pending lists (called at clip-area boundaries). */
        fun flushBatches() {
            if (surfaceBatches.isEmpty() && textBatches.isEmpty() && iconBatches.isEmpty()) return
            font.flushUploads()
            if (surfaceBatches.isNotEmpty()) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, surfacePipeline)
                vkCmdBindDescriptorSets(
                    cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(rt0Set), null
                )
                pushConstants(cmd, stack, width, height, 0f, 0f, 0f, 0f)
                vkCmdBindVertexBuffers(cmd, 0, stack.longs(ring.buffer), stack.longs(ring.slotOffset()))
                for (b in surfaceBatches) {
                    vkCmdDraw(cmd, b.quads * 6, 1, b.firstVertex, 0)
                }
                surfaceBatches.clear(); curSurface = null
            }
            if (iconBatches.isNotEmpty()) {
                val atlasTex = try {
                    ItemIcons.ensureAtlas(ctx)
                } catch (t: Throwable) {
                    System.err.println("[NeoUI] icon atlas unavailable: $t")
                    null
                }
                if (atlasTex != null) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, uiIconPipeline)
                    pushConstants(cmd, stack, width, height, 0f, 0f, 0f, 0f)
                    vkCmdBindVertexBuffers(cmd, 0, stack.longs(ring.buffer), stack.longs(ring.slotOffset()))
                    for (b in iconBatches) {
                        val set = iconDescriptorSet(b.page, atlasTex)
                        if (set == NULL) continue
                        vkCmdBindDescriptorSets(
                            cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(set), null
                        )
                        vkCmdDraw(cmd, b.quads * 6, 1, b.firstVertex, 0)
                    }
                    iconBatches.clear(); curIcon = null
                }
            }
            if (textBatches.isNotEmpty()) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, textPipeline)
                pushConstants(cmd, stack, width, height, 0f, 0f, 0f, 0f)
                vkCmdBindVertexBuffers(cmd, 0, stack.longs(ring.buffer), stack.longs(ring.slotOffset()))
                for (b in textBatches) {
                    if (b.page >= font.pageCount()) continue
                    vkCmdBindDescriptorSets(
                        cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
                        stack.longs(pageSet(b.page)), null
                    )
                    vkCmdDraw(cmd, b.quads * 6, 1, b.firstVertex, 0)
                }
                textBatches.clear(); curText = null
            }
        }

        fun renderNode(node: UiNode, shift: Float) {
            if (!node.visible) return
            if (node.drawsSurface) {
                if (node.shadow) shadowQuad(node.x, node.y, node.width, node.height, node, shift)
                val mode = if (node.style == UiNode.STYLE_GLASS) 2f else 1f
                surfaceQuad(node.x, node.y, node.width, node.height, node, mode, shift)
            }
            if (node.type == "icon") iconQuad(node, shift)
            if (node.text.isNotEmpty()) {
                val sizeI = (node.textSize * dp).toInt().coerceAtLeast(1)
                val spacingPx = node.letterSpacing * dp
                val ascent = font.ascent(sizeI.toFloat(), node.bold)
                val desc = font.lineHeight(sizeI.toFloat(), node.bold) - ascent
                val baseline = node.y + shift + (node.height - (ascent + desc)) / 2 + ascent
                val measured = font.measure(node.text, sizeI.toFloat(), node.bold, spacingPx)
                val startX = node.x + (node.width - measured) / 2
                val shadowOff = 1.2f
                if (node.textShadow) {
                    val shArgb = (0x80 shl 24) or (((node.textColor and 0x00FFFFFF) shr 2) and 0x00FFFFFF)
                    textRun(node.text, startX + shadowOff, baseline + shadowOff, sizeI, shArgb, true, node.bold, spacingPx)
                }
                textRun(node.text, startX, baseline, sizeI, node.textColor, false, node.bold, spacingPx)
            }
            if (node.clip) {
                // scroll area: flush pending batches under the current scissor,
                // clip to this node's bounds, draw children shifted by
                // -scrollY, flush again, restore. Children keep their layout
                // positions; the shift moves them relative to the clip window.
                flushBatches()
                val cx = node.x.toInt().coerceIn(0, width)
                val cy = (node.y + shift).toInt().coerceIn(0, height)
                val cw = node.width.toInt().coerceIn(0, width - cx)
                val chh = node.height.toInt().coerceIn(0, height - cy)
                val childShift = shift - node.scrollY
                if (cw > 0 && chh > 0) {
                    val viewport = org.lwjgl.vulkan.VkViewport.calloc(1, stack)
                        .x(cx.toFloat()).y(cy.toFloat()).width(cw.toFloat()).height(chh.toFloat())
                        .minDepth(0f).maxDepth(1f)
                    val sc = org.lwjgl.vulkan.VkRect2D.calloc(1, stack)
                        .offset(org.lwjgl.vulkan.VkOffset2D.calloc(stack).set(cx, cy))
                        .extent { it.set(cw, chh) }
                    vkCmdSetScissor(cmd, 0, sc)
                    vkCmdSetViewport(cmd, 0, viewport)
                    for (c in node.children) renderNode(c, childShift)
                    flushBatches()
                    // restore full swapchain scissor/viewport
                    val fvp = org.lwjgl.vulkan.VkViewport.calloc(1, stack)
                        .x(0f).y(0f).width(width.toFloat()).height(height.toFloat())
                        .minDepth(0f).maxDepth(1f)
                    val fsc = org.lwjgl.vulkan.VkRect2D.calloc(1, stack)
                        .offset(org.lwjgl.vulkan.VkOffset2D.calloc(stack).set(0, 0))
                        .extent { it.set(width, height) }
                    vkCmdSetScissor(cmd, 0, fsc)
                    vkCmdSetViewport(cmd, 0, fvp)
                }
            } else {
                for (c in node.children) renderNode(c, shift)
            }
        }

        renderNode(screen.root, entranceLift)
        flushBatches()
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** Lazily allocates the descriptor set for a glyph atlas page. */
    private fun pageSet(page: Int): Long {
        while (pageSets.size <= page) pageSets.add(NULL)
        if (pageSets[page] != NULL) return pageSets[page]
        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout))
            val set = stack.mallocLong(1)
            VulkanContext.check(
                vkAllocateDescriptorSets(ctx.device, allocInfo, set), "vkAllocateDescriptorSets (atlas page)"
            )
            val info = VkDescriptorImageInfo.calloc(1, stack)
            info.get(0).sampler(font.pageTexture(page).sampler)
                .imageView(font.pageTexture(page).view)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            val write = VkWriteDescriptorSet.calloc(1, stack)
            write.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set.get(0))
                .dstBinding(0)
                .descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(info)
            vkUpdateDescriptorSets(ctx.device, write, null)
            pageSets[page] = set.get(0)
            return set.get(0)
        }
    }

    /**
     * Descriptor set for an item atlas texture (combined image sampler,
     * CLAMP_TO_EDGE — UiTexture2D's sampler clamps, keeping half-texel sprite
     * edges from bleeding into neighbours). Re-allocated (and re-pointed) when
     * the atlas was re-copied, e.g. after a resource-pack reload.
     */
    private fun iconDescriptorSet(atlasIndex: Int, tex: net.theresa.ui.render.UiTexture2D): Long {
        val existing = iconSets[atlasIndex]
        if (existing != null && existing != NULL && iconSetViews[atlasIndex] == tex.view) return existing
        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout))
            val set = stack.mallocLong(1)
            VulkanContext.check(
                vkAllocateDescriptorSets(ctx.device, allocInfo, set), "vkAllocateDescriptorSets (icon atlas)"
            )
            val info = VkDescriptorImageInfo.calloc(1, stack)
            info.get(0).sampler(tex.sampler)
                .imageView(tex.view)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            val write = VkWriteDescriptorSet.calloc(1, stack)
            write.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set.get(0))
                .dstBinding(0)
                .descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(info)
            vkUpdateDescriptorSets(ctx.device, write, null)
            iconSets[atlasIndex] = set.get(0)
            iconSetViews[atlasIndex] = tex.view
            return set.get(0)
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

    // menu fps probe: frames counted between prints (no world path)
    private var probeFrames = 0
    private var probeLast = 0L

    /** Called once per frame for every path; prints menu fps every 240 frames. */
    fun probeFrame() {
        probeFrames++
        if (probeFrames >= 240) {
            val now = System.nanoTime()
            if (probeLast != 0L) {
                val fps = probeFrames.toDouble() / ((now - probeLast) / 1e9)
                System.out.printf("[NeoUI fps] %.0f%n", fps)
            }
            probeLast = now
            probeFrames = 0
        }
    }

    fun destroy() {
        if (ctx.device == null) return
        if (panoramaPipeline != NULL) vkDestroyPipeline(ctx.device, panoramaPipeline, null)
        if (blurPipeline != NULL) vkDestroyPipeline(ctx.device, blurPipeline, null)
        if (textPipeline != NULL) vkDestroyPipeline(ctx.device, textPipeline, null)
        if (surfacePipeline != NULL) vkDestroyPipeline(ctx.device, surfacePipeline, null)
        if (pipelineLayout != NULL) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null)
        if (descriptorPool != NULL) vkDestroyDescriptorPool(ctx.device, descriptorPool, null)
        if (descriptorSetLayout != NULL) vkDestroyDescriptorSetLayout(ctx.device, descriptorSetLayout, null)
        ring.destroy()
        font.destroy()
        chain.destroy()
        panorama.destroy()
        ItemIcons.destroy()
    }

    companion object {
        /** Gaussian spread in source texels per tap step. */
        const val BLUR_SPREAD = 2.2f

        /** pos2f + uv2f + tint4ub + rect4f + gradEnd4ub + border4ub. */
        const val SURFACE_STRIDE = 44

        /**
         * Icon pipeline fragment source (embedded fallback for
         * shaders_vk/ui_icon.frag): samples the copied vanilla item atlas;
         * vertex tint rgb multiplies the texture color, tint alpha scales it.
         */
        val ICON_FRAG = """
            #version 450
            layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
            layout(binding = 0) uniform sampler2D atlas;
            layout(location = 0) in vec2 vUv;
            layout(location = 1) in vec4 vTint;
            layout(location = 0) out vec4 outColor;
            void main() {
                vec4 tex = texture(atlas, vUv);
                outColor = vec4(tex.rgb * vTint.rgb, tex.a * vTint.a);
            }
        """.trimIndent() + "\n"

        /** Vertex attribute descriptions shared by surface/text pipelines. */
        private fun surfaceAttributes(stack: MemoryStack): VkVertexInputAttributeDescription.Buffer {
            val attrs = VkVertexInputAttributeDescription.calloc(6, stack)
            attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0)
            attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(8)
            attrs.get(2).binding(0).location(2).format(VK_FORMAT_R8G8B8A8_UNORM).offset(16)
            attrs.get(3).binding(0).location(3).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(20)
            attrs.get(4).binding(0).location(4).format(VK_FORMAT_R8G8B8A8_UNORM).offset(36)
            attrs.get(5).binding(0).location(5).format(VK_FORMAT_R8G8B8A8_UNORM).offset(40)
            return attrs
        }
    }
}
