package net.theresa.ui.render

import net.theresa.render.vulkan.VulkanContext
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import java.nio.ByteBuffer

/**
 * Persistently mapped host-visible vertex ring with one slot per in-flight
 * frame. Recording writes quad vertices into the current slot and issues
 * draws with [firstVertex] offsets; the GPU reads the slot only while that
 * frame is in flight, so alternating slots with MAX_FRAMES_IN_FLIGHT=2 makes
 * overwrite safe without fences or reallocation. Hot path allocates nothing.
 */
class UiBufferRing(private val ctx: VulkanContext, slotCount: Int = 2, val slotBytes: Long = 2L * 1024 * 1024) {

    val buffer: Long
    val memory: Long
    private val mapped: ByteBuffer
    private var frameIndex = 0

    init {
        MemoryStack.stackPush().use { stack ->
            val info = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(slotBytes * slotCount)
                .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            val pBuffer = stack.mallocLong(1)
            VulkanContext.check(vkCreateBuffer(ctx.device, info, null, pBuffer), "vkCreateBuffer (UiBufferRing)")
            buffer = pBuffer.get(0)

            val reqs = VkMemoryRequirements.calloc(stack)
            vkGetBufferMemoryRequirements(ctx.device, buffer, reqs)
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
            VulkanContext.check(vkAllocateMemory(ctx.device, alloc, null, pMemory), "vkAllocateMemory (UiBufferRing)")
            memory = pMemory.get(0)
            VulkanContext.check(vkBindBufferMemory(ctx.device, buffer, memory, 0), "vkBindBufferMemory (UiBufferRing)")

            val pMapped = stack.mallocPointer(1)
            VulkanContext.check(vkMapMemory(ctx.device, memory, 0, slotBytes * slotCount, 0, pMapped), "vkMapMemory (UiBufferRing)")
            mapped = pMapped.getByteBuffer(0, (slotBytes * slotCount).toInt())
        }
    }

    /** Advances to the next slot; call once per frame before encoding. */
    fun beginFrame() {
        frameIndex = (frameIndex + 1) % 2
    }

    /**
     * A duplicate view over this frame's slot, positioned at its start. The
     * caller writes vertices sequentially (position tracks the write cursor)
     * and must stay within [slotBytes].
     */
    fun slot(): ByteBuffer = mapped.duplicate().order(java.nio.ByteOrder.nativeOrder())
        .clear()
        .position((frameIndex * slotBytes).toInt())
        .limit(((frameIndex + 1) * slotBytes).toInt())

    /** Byte offset of the current slot inside the underlying buffer. */
    fun slotOffset(): Long = frameIndex * slotBytes

    /** Current slot byte usage (for firstVertex/vertexCount maths). */
    fun usedBytes(view: ByteBuffer): Int = view.position() - (frameIndex * slotBytes).toInt()

    fun destroy() {
        if (ctx.device == null) return
        // mapped memory belongs to vkMapMemory (freed with the memory object)
        if (buffer != NULL) vkDestroyBuffer(ctx.device, buffer, null)
        if (memory != NULL) vkFreeMemory(ctx.device, memory, null)
    }
}
