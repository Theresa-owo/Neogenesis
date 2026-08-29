package net.theresa.render.vulkan;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.chunk.RenderChunk;

/**
 * Static seam between the vanilla chunk pipeline and the Vulkan chunk store.
 * Minecraft code calls into these hooks; the bridge forwards to the live
 * VulkanChunkStore when the Vulkan backend owns a world, and no-ops otherwise.
 */
public final class VulkanWorldBridge {

    private static VulkanChunkStore store;

    private VulkanWorldBridge() {
    }

    public static void attach(VulkanChunkStore chunkStore) {
        store = chunkStore;
    }

    public static void detach() {
        store = null;
    }

    public static boolean isActive() {
        return store != null;
    }

    /**
     * Mirrors a chunk layer upload. layerOrdinal follows
     * EnumWorldBlockLayer.ordinal(): 0 SOLID, 1 CUTOUT_MIPPED, 2 CUTOUT, 3 TRANSLUCENT.
     */
    public static int uploadsSeen;
    public static int hookCalls;
    public static int nullStoreCalls;

    public static void uploadChunk(RenderChunk chunk, int layerOrdinal, ByteBuffer data, int vertexCount) {
        hookCalls++;
        if (store == null) {
            nullStoreCalls++;
            return;
        }
        if (data != null && vertexCount > 0) {
            uploadsSeen++;
            store.upload(chunk, layerOrdinal, data, vertexCount);
        }
    }

    public static void removeChunk(RenderChunk chunk) {
        if (store != null) {
            store.remove(chunk);
        }
    }

    public static void clearWorld() {
        if (store != null) {
            store.clear();
        }
    }
}
