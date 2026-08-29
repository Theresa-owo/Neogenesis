package net.theresa.render.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static net.theresa.render.vulkan.VulkanContext.check;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Device storage for chunk mesh vertex data on the Vulkan side. Minecraft's
 * WorldRenderer builds vertex bytes on worker threads; after the GL side has
 * consumed them, the same bytes are handed here keyed by the RenderChunk
 * object and render layer (SOLID=0, CUTOUT_MIPPED=1, CUTOUT=2, TRANSLUCENT=3).
 *
 * Each upload goes through a HOST_VISIBLE staging buffer into a device-local
 * vertex buffer via a one-shot copy command that is fence-waited before
 * returning, so any buffer handed out by {@link #getBuffer} is ready to be
 * bound in a render pass.
 */
public class VulkanChunkStore {

    public static final int LAYER_COUNT = 4;

    /** WorldRenderer chunk vertex format: pos 3f @0, color 4ub @12, tex 2f @16, lightmap 2s @24. */
    public static final int VERTEX_STRIDE_BYTES = 28;

    /** Buffers for one render layer of one chunk; entries may be null. */
    private static final class LayerData {
        long buffer;
        long memory;
        int vertexCount;
    }

    /** One copy within a (single-submit) batch: destination plus staging offset. */
    private static final class StagedCopy {
        final Object chunk;
        final int layer;
        final ByteBuffer data;
        final int vertexCount;
        final int bytes;
        final long dstBuffer;
        final long dstMemory;
        final long srcOffset;

        StagedCopy(Object chunk, int layer, ByteBuffer data, int vertexCount,
                   long dstBuffer, long dstMemory, long srcOffset) {
            this.chunk = chunk;
            this.layer = layer;
            this.data = data;
            this.vertexCount = vertexCount;
            this.bytes = data.remaining();
            this.dstBuffer = dstBuffer;
            this.dstMemory = dstMemory;
            this.srcOffset = srcOffset;
        }
    }

    private final VulkanContext ctx;

    /** Chunk identity is object identity: RenderChunk instances are stable. */
    private final Map<Object, LayerData[]> chunks = new IdentityHashMap<>();

    private long commandPool;
    private VkCommandBuffer commandBuffer;
    private long fence;

    public VulkanChunkStore(VulkanContext ctx) {
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

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            check(vkCreateFence(ctx.device, fenceInfo, null, pFence), "vkCreateFence");
            fence = pFence.get(0);
        }
    }

    /**
     * Copies {@code data} (its remaining bytes) into a device-local vertex
     * buffer for (chunk, layer), replacing any previous buffer for that key.
     * The replaced buffer is destroyed only after the copy has completed.
     */
    public void upload(Object chunk, int layer, ByteBuffer data, int vertexCount) {
        uploadBatch(Collections.singletonList(new UploadRequest(chunk, layer, data, vertexCount)));
    }

    /**
     * Batched upload: one shared staging buffer, one submit, one fence wait
     * for the whole list. Requests with empty data clear their layer instead.
     */
    public void uploadBatch(List<UploadRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        if (ctx.device == null) {
            throw new IllegalStateException("VulkanChunkStore used after destroy()");
        }

        // Allocate the destinations up front; buffers replaced by this batch
        // are collected so they can be destroyed only after the copy ran.
        List<StagedCopy> copies = new ArrayList<>(requests.size());
        List<LayerData> replaced = new ArrayList<>(requests.size());
        long stagingBytes = 0;
        for (UploadRequest request : requests) {
            validateLayer(request.layer);
            if (request.data == null || request.data.remaining() == 0) {
                replaced.add(clearLayer(request.chunk, request.layer));
                continue;
            }
            long[] dst = createBuffer(request.data.remaining(),
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            copies.add(new StagedCopy(request.chunk, request.layer, request.data, request.vertexCount,
                    dst[0], dst[1], stagingBytes));
            stagingBytes += request.data.remaining();
        }
        if (copies.isEmpty()) {
            retireAll(replaced);
            return;
        }
        if (stagingBytes > Integer.MAX_VALUE) {
            for (StagedCopy copy : copies) {
                destroyBuffer(copy.dstBuffer, copy.dstMemory);
            }
            retireAll(replaced);
            throw new IllegalArgumentException("Batched chunk upload too large: " + stagingBytes + " bytes");
        }

        // Publish the new buffers now; the old ones stay alive (and drawable)
        // until the fence tells us the GPU finished reading the staging data.
        for (StagedCopy copy : copies) {
            replaced.add(store(copy));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] staging = createBuffer(stagingBytes,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            long stagingBuffer = staging[0];
            long stagingMemory = staging[1];
            try {
                PointerBuffer pMap = stack.mallocPointer(1);
                check(vkMapMemory(ctx.device, stagingMemory, 0L, stagingBytes, 0, pMap), "vkMapMemory");
                ByteBuffer mapped = pMap.getByteBuffer(0, (int) stagingBytes);
                for (StagedCopy copy : copies) {
                    mapped.position((int) copy.srcOffset);
                    mapped.put(copy.data.duplicate());
                }
                vkUnmapMemory(ctx.device, stagingMemory);

                vkResetCommandBuffer(commandBuffer, 0);
                VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");

                // One copy per destination buffer, then a single barrier batch
                // making every destination readable as vertex attributes.
                for (StagedCopy copy : copies) {
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                            .srcOffset(copy.srcOffset)
                            .dstOffset(0L)
                            .size(copy.bytes);
                    vkCmdCopyBuffer(commandBuffer, stagingBuffer, copy.dstBuffer, region);
                }
                VkBufferMemoryBarrier.Buffer barriers = VkBufferMemoryBarrier.calloc(copies.size(), stack);
                for (int i = 0; i < copies.size(); i++) {
                    StagedCopy copy = copies.get(i);
                    barriers.get(i)
                            .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                            .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT)
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .buffer(copy.dstBuffer)
                            .offset(0L)
                            .size(VK_WHOLE_SIZE);
                }
                vkCmdPipelineBarrier(commandBuffer,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, 0,
                        null, barriers, null);
                check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");

                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(commandBuffer));
                check(vkResetFences(ctx.device, fence), "vkResetFences");
                check(vkQueueSubmit(ctx.graphicsQueue, submitInfo, fence), "vkQueueSubmit");
                check(vkWaitForFences(ctx.device, fence, true, 0xFFFFFFFFFFFFFFFFL), "vkWaitForFences");
            } finally {
                destroyBuffer(stagingBuffer, stagingMemory);
            }
        }
        // The copy has completed; the replaced buffers can go now.
        retireAll(replaced);
    }

    /** Removes and destroys every layer buffer of {@code chunk}. */
    public void remove(Object chunk) {
        if (ctx.device == null) {
            return;
        }
        LayerData[] layers = chunks.remove(chunk);
        if (layers != null) {
            for (LayerData data : layers) {
                if (data != null && data.buffer != 0L) {
                    retired.add(new Retired(data.buffer, data.memory, retireClock));
                }
            }
        }
    }

    /** World change: destroys every stored buffer. */
    public void clear() {
        if (ctx.device == null) {
            chunks.clear();
            return;
        }
        for (LayerData[] layers : chunks.values()) {
            for (LayerData data : layers) {
                if (data != null && data.buffer != 0L) {
                    retired.add(new Retired(data.buffer, data.memory, retireClock));
                }
            }
        }
        chunks.clear();
    }

    /** Buffer handle for (chunk, layer) or 0 when not present. */
    public long getBuffer(Object chunk, int layer) {
        LayerData data = lookup(chunk, layer);
        return data == null ? 0L : data.buffer;
    }

    /** Vertex count for (chunk, layer) or 0 when not present. */
    public int getVertexCount(Object chunk, int layer) {
        LayerData data = lookup(chunk, layer);
        return data == null ? 0 : data.vertexCount;
    }

    /** clear() plus releasing the store's own command pool and fence. */
    public void destroy() {
        for (LayerData[] layers : chunks.values()) {
            destroyAllLayers(layers);
        }
        chunks.clear();
        destroyAllRetired();
        if (ctx.device == null) {
            return;
        }
        if (commandBuffer != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkFreeCommandBuffers(ctx.device, commandPool, stack.pointers(commandBuffer));
            }
            commandBuffer = null;
        }
        if (commandPool != NULL) {
            vkDestroyCommandPool(ctx.device, commandPool, null);
            commandPool = NULL;
        }
        if (fence != NULL) {
            vkDestroyFence(ctx.device, fence, null);
            fence = NULL;
        }
    }

    private LayerData lookup(Object chunk, int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) {
            return null;
        }
        LayerData[] layers = chunks.get(chunk);
        return layers == null ? null : layers[layer];
    }

    private static void validateLayer(int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) {
            throw new IllegalArgumentException("Chunk render layer out of range: " + layer);
        }
    }

    /** Installs {@code copy}'s destination into the store, returning the replaced entry (or null). */
    private LayerData store(StagedCopy copy) {
        LayerData[] layers = chunks.computeIfAbsent(copy.chunk, key -> new LayerData[LAYER_COUNT]);
        LayerData previous = layers[copy.layer];
        LayerData fresh = new LayerData();
        fresh.buffer = copy.dstBuffer;
        fresh.memory = copy.dstMemory;
        fresh.vertexCount = copy.vertexCount;
        layers[copy.layer] = fresh;
        return previous;
    }

    /** Drops (but does not destroy) the stored entry for (chunk, layer), returning it (or null). */
    private LayerData clearLayer(Object chunk, int layer) {
        LayerData[] layers = chunks.get(chunk);
        if (layers == null) {
            return null;
        }
        LayerData previous = layers[layer];
        layers[layer] = null;
        boolean empty = true;
        for (LayerData entry : layers) {
            if (entry != null) {
                empty = false;
                break;
            }
        }
        if (empty) {
            chunks.remove(chunk);
        }
        return previous;
    }

    private void destroyAll(List<LayerData> replaced) {
        for (LayerData data : replaced) {
            if (data != null) {
                destroyBuffer(data.buffer, data.memory);
            }
        }
    }

    private static final int RETIRE_FRAMES = 4;

    private static final class Retired {
        final long buffer;
        final long memory;
        final long frame;

        Retired(long buffer, long memory, long frame) {
            this.buffer = buffer;
            this.memory = memory;
            this.frame = frame;
        }
    }

    private final java.util.ArrayList<Retired> retired = new ArrayList<>();
    private long retireClock;

    /** Advances the retirement clock; call once per rendered frame. */
    public void tickFrame() {
        retireClock++;
        java.util.Iterator<Retired> it = retired.iterator();
        while (it.hasNext()) {
            Retired r = it.next();
            if (retireClock - r.frame > RETIRE_FRAMES) {
                destroyBuffer(r.buffer, r.memory);
                it.remove();
            }
        }
    }

    private void retireAll(List<LayerData> replaced) {
        for (LayerData data : replaced) {
            if (data != null && data.buffer != 0L) {
                retired.add(new Retired(data.buffer, data.memory, retireClock));
            }
        }
    }

    private void destroyAllRetired() {
        for (Retired r : retired) {
            destroyBuffer(r.buffer, r.memory);
        }
        retired.clear();
    }

    private void destroyAllLayers(LayerData[] layers) {
        for (int i = 0; i < layers.length; i++) {
            LayerData data = layers[i];
            if (data != null) {
                destroyBuffer(data.buffer, data.memory);
                layers[i] = null;
            }
        }
    }

    /** Creates a buffer bound to freshly allocated memory; returns {buffer, memory}. */
    private long[] createBuffer(long size, int usage, int memoryFlags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(ctx.device, bufferInfo, null, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(ctx.device, buffer, requirements);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(ctx.memoryTypeIndex(requirements.memoryTypeBits(), memoryFlags));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(ctx.device, allocInfo, null, pMemory), "vkAllocateMemory");
            long memory = pMemory.get(0);
            check(vkBindBufferMemory(ctx.device, buffer, memory, 0L), "vkBindBufferMemory");
            return new long[]{buffer, memory};
        }
    }

    private void destroyBuffer(long buffer, long memory) {
        if (buffer != NULL) {
            vkDestroyBuffer(ctx.device, buffer, null);
        }
        if (memory != NULL) {
            vkFreeMemory(ctx.device, memory, null);
        }
    }

    /** A single chunk layer upload request for {@link #uploadBatch}. */
    public static final class UploadRequest {
        public final Object chunk;
        public final int layer;
        public final ByteBuffer data;
        public final int vertexCount;

        public UploadRequest(Object chunk, int layer, ByteBuffer data, int vertexCount) {
            this.chunk = chunk;
            this.layer = layer;
            this.data = data;
            this.vertexCount = vertexCount;
        }
    }
}
