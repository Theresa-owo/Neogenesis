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
    public static int staleDraws;

    private static final java.util.IdentityHashMap<RenderChunk, int[]> uploadedPositions =
            new java.util.IdentityHashMap<>();

    public static void uploadChunk(RenderChunk chunk, int layerOrdinal, ByteBuffer data, int vertexCount) {
        hookCalls++;
        if (store == null) {
            nullStoreCalls++;
            return;
        }
        if (data != null && vertexCount > 0) {
            uploadsSeen++;
            uploadedPositions.put(chunk, new int[] {
                    chunk.getPosition().getX(), chunk.getPosition().getY(), chunk.getPosition().getZ()});
            store.upload(chunk, layerOrdinal, data, vertexCount);
        }
    }

    /** True when the store holds a mesh for this chunk built at its CURRENT position. */
    public static boolean hasFreshMesh(RenderChunk chunk) {
        int[] pos = uploadedPositions.get(chunk);
        if (pos == null) {
            return false;
        }
        return pos[0] == chunk.getPosition().getX() && pos[1] == chunk.getPosition().getY()
                && pos[2] == chunk.getPosition().getZ();
    }

    /**
     * Called by the chunk worker with the CURRENT CompiledChunk state: layers that
     * became empty must drop their stored mesh, otherwise the old mesh keeps
     * rendering as a ghost chunk.
     */
    public static void markLayerStates(RenderChunk chunk, boolean[] layerStarted) {
        if (store != null) {
            store.markLayerStates(chunk, layerStarted);
        }
    }

    public static void removeChunk(RenderChunk chunk) {
        uploadedPositions.remove(chunk);
        if (store != null) {
            store.remove(chunk);
        }
    }

    public static void clearWorld() {
        uploadedPositions.clear();
        if (store != null) {
            store.clear();
        }
    }


}
