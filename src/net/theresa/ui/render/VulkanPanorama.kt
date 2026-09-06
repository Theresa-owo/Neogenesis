package net.theresa.ui.render

import net.theresa.render.vulkan.VulkanContext
import org.joml.Matrix3f
import org.joml.Vector3f
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferImageCopy
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
import java.awt.image.BufferedImage
import java.io.InputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The menu panorama as a single equirectangular texture.
 *
 * The vanilla panorama PNGs are six face images rendered with custom matrices
 * (GuiMainMenu.drawPanorama: modelview RotX(180) * RotZ(90) * per-face rotation,
 * 120-degree perspective, y-down image rows). They are NOT standard cube faces,
 * so instead of fighting cube-map face conventions we bake the six faces into
 * one equirectangular map once at startup, using exactly the vanilla matrices
 * for the direction -> face/uv mapping. Runtime sampling is then a plain
 * atan2/acos lookup with none of the cube orientation hazards.
 */
class VulkanPanorama(private val ctx: VulkanContext, equirect: ByteBuffer, val width: Int, val height: Int) {

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
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .extent { it.set(width, height, 1) }
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            val pImage = stack.mallocLong(1)
            check(vkCreateImage(ctx.device, info, null, pImage), "vkCreateImage (panorama)")
            image = pImage.get(0)

            val reqs = VkMemoryRequirements.calloc(stack)
            vkGetImageMemoryRequirements(ctx.device, image, reqs)
            val allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(ctx.memoryTypeIndex(reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            val pMemory = stack.mallocLong(1)
            check(vkAllocateMemory(ctx.device, allocInfo, null, pMemory), "vkAllocateMemory (panorama)")
            memory = pMemory.get(0)
            check(vkBindImageMemory(ctx.device, image, memory, 0), "vkBindImageMemory (panorama)")

            val viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .subresourceRange(
                    VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
                )
            val pView = stack.mallocLong(1)
            check(vkCreateImageView(ctx.device, viewInfo, null, pView), "vkCreateImageView (panorama)")
            view = pView.get(0)

            val samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                // U wraps across the atan2 seam; V never leaves [0,1]
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .minLod(0.0f).maxLod(0.0f)
            val pSampler = stack.mallocLong(1)
            check(vkCreateSampler(ctx.device, samplerInfo, null, pSampler), "vkCreateSampler (panorama)")
            sampler = pSampler.get(0)

            uploadPixels(stack, equirect)
        }
    }

    private fun uploadPixels(stack: MemoryStack, pixels: ByteBuffer) {
        val totalSize = pixels.remaining().toLong()

        val bufferInfo = VkBufferCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            .size(totalSize)
            .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val pBuffer = stack.mallocLong(1)
        check(vkCreateBuffer(ctx.device, bufferInfo, null, pBuffer), "vkCreateBuffer (panorama staging)")
        val staging = pBuffer.get(0)
        val reqs = VkMemoryRequirements.calloc(stack)
        vkGetBufferMemoryRequirements(ctx.device, staging, reqs)
        val allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(reqs.size())
            .memoryTypeIndex(
                ctx.memoryTypeIndex(
                    reqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                )
            )
        val pMemory = stack.mallocLong(1)
        check(vkAllocateMemory(ctx.device, allocInfo, null, pMemory), "vkAllocateMemory (panorama staging)")
        val stagingMemory = pMemory.get(0)
        check(vkBindBufferMemory(ctx.device, staging, stagingMemory, 0), "vkBindBufferMemory (panorama staging)")

        try {
            val pData = stack.mallocPointer(1)
            check(vkMapMemory(ctx.device, stagingMemory, 0, totalSize, 0, pData), "vkMapMemory (panorama)")
            val mapped = pData.getByteBuffer(0, totalSize.toInt())
            mapped.put(pixels.duplicate())
            vkUnmapMemory(ctx.device, stagingMemory)

            ensureUploadPool(stack)
            val cmdAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(uploadPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
            val pCmd = stack.mallocPointer(1)
            check(vkAllocateCommandBuffers(ctx.device, cmdAlloc, pCmd), "vkAllocateCommandBuffers (panorama)")
            val cmd = VkCommandBuffer(pCmd.get(0), ctx.device)

            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            check(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer (panorama)")

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
            vkCmdPipelineBarrier(
                cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer
            )

            val copy = VkBufferImageCopy.calloc(1, stack)
            copy.get(0)
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageSubresource(
                    VkImageSubresourceLayers.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1)
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
            vkCmdPipelineBarrier(
                cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, toShader
            )

            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer (panorama)")

            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmd))
            val fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            val pFence = stack.mallocLong(1)
            check(vkCreateFence(ctx.device, fenceInfo, null, pFence), "vkCreateFence (panorama)")
            val fence = pFence.get(0)
            try {
                check(vkQueueSubmit(ctx.graphicsQueue, submitInfo, fence), "vkQueueSubmit (panorama)")
                check(vkWaitForFences(ctx.device, fence, true, -1L), "vkWaitForFences (panorama)")
            } finally {
                vkDestroyFence(ctx.device, fence, null)
            }
            vkFreeCommandBuffers(ctx.device, uploadPool, cmd)
        } finally {
            vkDestroyBuffer(ctx.device, staging, null)
            vkFreeMemory(ctx.device, stagingMemory, null)
        }
    }

    private fun ensureUploadPool(stack: MemoryStack) {
        if (uploadPool != NULL) return
        val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT or VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            .queueFamilyIndex(ctx.graphicsFamily)
        val pPool = stack.mallocLong(1)
        check(vkCreateCommandPool(ctx.device, poolInfo, null, pPool), "vkCreateCommandPool (panorama)")
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
        /**
         * Bakes six face images (RGBA, same size) into an equirectangular map.
         *
         * Vanilla draws each face as the quad (-1..1, z=+1) under modelview
         * base*RotF (base = RotX(180)*RotZ(90), per-face RotF per
         * GuiMainMenu.drawPanorama). In orbit space each quad is therefore an
         * affine image of (u,v): P_orb(u,v) = rotF*(2u-1, 2v-1, 1). We compute
         * the four corners per face, intersect each equirect direction with the
         * quad's plane and solve the 2x2 affine system for (u,v) — no
         * hand-derived per-face orientation constants.
         */
        fun bakeEquirect(faces: List<ByteBuffer>, faceSize: Int, outW: Int = 2048, outH: Int = 1024): ByteBuffer {
            val base = Matrix3f().rotateX(PI.toFloat()).rotateZ((PI / 2).toFloat())
            val rot = arrayOf(
                Matrix3f(base),
                Matrix3f(base).rotateY((PI / 2).toFloat()),
                Matrix3f(base).rotateY(PI.toFloat()),
                Matrix3f(base).rotateY((-PI / 2).toFloat()),
                Matrix3f(base).rotateX((PI / 2).toFloat()),
                Matrix3f(base).rotateX((-PI / 2).toFloat()),
            )

            // Per-face quad geometry in orbit space.
            val c00 = Array(6) { Vector3f() }
            val eu = Array(6) { Vector3f() }
            val ev = Array(6) { Vector3f() }
            val normal = Array(6) { Vector3f() }
            val planeW = FloatArray(6)
            for (f in 0 until 6) {
                val m = rot[f]
                val a = m.transform(Vector3f(-1f, -1f, 1f))   // (u=0, v=0)
                val b = m.transform(Vector3f(1f, -1f, 1f))    // (u=1, v=0)
                val c = m.transform(Vector3f(-1f, 1f, 1f))    // (u=0, v=1)
                c00[f] = a
                eu[f] = Vector3f(b).sub(a)
                ev[f] = Vector3f(c).sub(a)
                normal[f] = Vector3f(eu[f]).cross(ev[f]).normalize()
                planeW[f] = normal[f].dot(a)
            }

            val out = MemoryUtil.memAlloc(outW * outH * 4)
            val eps = 1e-4f
            for (py in 0 until outH) {
                val theta = ((py + 0.5) / outH) * PI           // 0 = +Y pole (top row)
                val sy = sin(theta); val cy = cos(theta)
                for (px in 0 until outW) {
                    val phi = ((px + 0.5) / outW) * 2.0 * PI
                    val dx = (sy * cos(phi)).toFloat()
                    val dy = cy.toFloat()
                    val dz = (sy * sin(phi)).toFloat()

                    var bestF = -1
                    var bestU = 0f; var bestV = 0f
                    for (f in 0 until 6) {
                        val n = normal[f]
                        val denom = n.x * dx + n.y * dy + n.z * dz
                        if (kotlin.math.abs(denom) < eps) continue
                        val t = planeW[f] / denom
                        if (t <= 0f) continue
                        // E = t*d; solve E = c00 + u*eu + v*ev
                        val sx = t * dx - c00[f].x
                        val sy2 = t * dy - c00[f].y
                        val sz = t * dz - c00[f].z
                        val a11 = eu[f].dot(eu[f]); val a12 = eu[f].dot(ev[f])
                        val a22 = ev[f].dot(ev[f])
                        val b1 = sx * eu[f].x + sy2 * eu[f].y + sz * eu[f].z
                        val b2 = sx * ev[f].x + sy2 * ev[f].y + sz * ev[f].z
                        val det = a11 * a22 - a12 * a12
                        if (kotlin.math.abs(det) < eps) continue
                        val u = (a22 * b1 - a12 * b2) / det
                        val v = (a11 * b2 - a12 * b1) / det
                        if (u >= -eps && u <= 1f + eps && v >= -eps && v <= 1f + eps) {
                            bestF = f; bestU = u; bestV = v
                            break
                        }
                    }

                    var r = 0; var g = 0; var b = 0
                    if (bestF >= 0) {
                        // bilinear sample of the face at (bestU, bestV)
                        val fx = bestU.coerceIn(0f, 1f) * faceSize - 0.5f
                        val fy = bestV.coerceIn(0f, 1f) * faceSize - 0.5f
                        val x0 = kotlin.math.floor(fx).toInt().coerceIn(0, faceSize - 1)
                        val y0 = kotlin.math.floor(fy).toInt().coerceIn(0, faceSize - 1)
                        val x1 = kotlin.math.min(x0 + 1, faceSize - 1)
                        val y1 = kotlin.math.min(y0 + 1, faceSize - 1)
                        val tx = (fx - kotlin.math.floor(fx)).coerceIn(0f, 1f)
                        val ty = (fy - kotlin.math.floor(fy)).coerceIn(0f, 1f)
                        val face = faces[bestF]
                        fun texel(cx: Int, cy: Int, ch: Int): Float {
                            val v = face.get((cy * faceSize + cx) * 4 + ch).toInt() and 0xFF
                            return v.toFloat()
                        }
                        for (ch in 0 until 3) {
                            val top = texel(x0, y0, ch) * (1 - tx) + texel(x1, y0, ch) * tx
                            val bot = texel(x0, y1, ch) * (1 - tx) + texel(x1, y1, ch) * tx
                            val v = (top * (1 - ty) + bot * ty).toInt()
                            when (ch) {
                                0 -> r = v
                                1 -> g = v
                                else -> b = v
                            }
                        }
                    }
                    out.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(0xFF.toByte())
                }
            }
            out.flip()
            return out
        }

        /** Stretches a raw RGBA buffer to a new size (for pack-provided backgrounds). */
        fun stretch(src: ByteBuffer, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteBuffer {
            val img = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
            val argbSrc = BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until srcH) {
                for (x in 0 until srcW) {
                    val i = (y * srcW + x) * 4
                    val r = src.get(i).toInt() and 0xFF
                    val g = src.get(i + 1).toInt() and 0xFF
                    val b = src.get(i + 2).toInt() and 0xFF
                    val a = src.get(i + 3).toInt() and 0xFF
                    argbSrc.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }
            val g2 = img.createGraphics()
            g2.drawImage(argbSrc, 0, 0, dstW, dstH, null)
            g2.dispose()
            val out = MemoryUtil.memAlloc(dstW * dstH * 4)
            val px = IntArray(dstW * dstH)
            img.getRGB(0, 0, dstW, dstH, px, 0, dstW)
            for (p in px) {
                out.put(((p shr 16) and 0xFF).toByte())
                out.put(((p shr 8) and 0xFF).toByte())
                out.put(p.and(0xFF).toByte())
                out.put(((p shr 24) and 0xFF).toByte())
            }
            out.flip()
            return out
        }

        /** Decodes a PNG stream into tightly packed RGBA bytes. */
        fun decodePngRgba(stream: InputStream): Pair<Int, ByteBuffer> {            val src = ImageIO.read(stream) ?: throw IllegalStateException("unreadable panorama image")
            val w = src.width
            val h = src.height
            val argb = if (src.type == BufferedImage.TYPE_INT_ARGB) src
            else {
                val conv = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g = conv.createGraphics()
                g.drawImage(src, 0, 0, null)
                g.dispose()
                conv
            }
            val pixels = IntArray(w * h)
            argb.getRGB(0, 0, w, h, pixels, 0, w)
            val out = MemoryUtil.memAlloc(w * h * 4)
            for (p in pixels) {
                out.put(((p shr 16) and 0xFF).toByte())
                out.put(((p shr 8) and 0xFF).toByte())
                out.put((p and 0xFF).toByte())
                out.put(((p shr 24) and 0xFF).toByte())
            }
            out.flip()
            return Pair(w, out)
        }
    }
}

private fun check(vkResult: Int, what: String) {
    if (vkResult != VK_SUCCESS) {
        throw IllegalStateException("$what failed with VkResult $vkResult")
    }
}
