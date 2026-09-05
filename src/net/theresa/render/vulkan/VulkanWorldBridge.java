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

    public static void uploadChunk(RenderChunk chunk, int layerOrdinal, ByteBuffer data, int vertexCount,
            long generation, net.minecraft.util.BlockPos builtPosition) {
        hookCalls++;
        if (store == null) {
            nullStoreCalls++;
            return;
        }
        int bytes = data == null ? 0 : data.remaining();
        long expectedBytes = (long) vertexCount * VulkanChunkStore.VERTEX_STRIDE_BYTES;
        if (data == null || vertexCount <= 0 || bytes != expectedBytes || vertexCount % 3 != 0) {
            throw new IllegalArgumentException("Invalid Vulkan chunk upload: chunk=" + chunk
                    + " layer=" + layerOrdinal + " generation=" + generation
                    + " bytes=" + bytes + " expectedBytes=" + expectedBytes
                    + " vertexCount=" + vertexCount);
        }
        if (!chunk.getPosition().equals(builtPosition)) {
            System.err.println("[VulkanChunk] rejected moved upload: chunk=" + chunk
                    + " layer=" + layerOrdinal + " generation=" + generation
                    + " bytes=" + bytes + " builtAt=" + builtPosition
                    + " current=" + chunk.getPosition());
            return;
        }
        uploadsSeen++;
        if (CRC_ENABLED) {
            // proves whether the CPU-side mesh bytes changed between the deep
            // copy (capture) and the upload reaching the store
            System.out.printf("[VkMeshCrc] chunk=%s layer=%d gen=%d crc=%08x%n",
                    chunk.getPosition(), layerOrdinal, generation, meshCrc(data));
        }
        uploadedPositions.put(chunk, new int[] {
                builtPosition.getX(), builtPosition.getY(), builtPosition.getZ()});
        store.upload(chunk, layerOrdinal, data, vertexCount, generation);
    }

    private static final boolean CRC_ENABLED = Boolean.getBoolean("neogenesis.vkMeshCrc");

    /** Whether the mesh CRC diagnostics are switched on. */
    public static boolean isCrcEnabled() {
        return CRC_ENABLED;
    }

    /** CRC32 over the full mesh payload, diagnostics only. */
    public static long meshCrc(ByteBuffer data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        ByteBuffer dup = data.duplicate();
        byte[] chunkBuf = new byte[Math.min(4096, dup.remaining())];
        while (dup.hasRemaining()) {
            int n = Math.min(chunkBuf.length, dup.remaining());
            dup.get(chunkBuf, 0, n);
            crc.update(chunkBuf, 0, n);
        }
        return crc.getValue();
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
            store.markLayerStates(chunk, layerStarted, chunk.getVulkanGeneration());
        }
    }

    /**
     * Worker-thread half of the deferred layer-state sync: records what the
     * latest compile says about each layer WITHOUT touching the store. The
     * states are applied later on the upload thread, atomically with the first
     * queued upload of that compile, so no empty-then-refill window exists.
     */
    private static final class PendingLayerState {
        final boolean[] layerStarted;
        final long generation;
        final int x;
        final int y;
        final int z;

        PendingLayerState(boolean[] layerStarted, long generation, net.minecraft.util.BlockPos position) {
            this.layerStarted = layerStarted.clone();
            this.generation = generation;
            this.x = position.getX();
            this.y = position.getY();
            this.z = position.getZ();
        }

        boolean matches(RenderChunk chunk) {
            return chunk.getPosition().getX() == x && chunk.getPosition().getY() == y
                    && chunk.getPosition().getZ() == z;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<RenderChunk, PendingLayerState> pendingLayerStates =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void setPendingLayerStates(RenderChunk chunk, boolean[] layerStarted, long generation,
            net.minecraft.util.BlockPos position) {
        pendingLayerStates.compute(chunk, (ignored, previous) -> previous == null || generation >= previous.generation
                ? new PendingLayerState(layerStarted, generation, position) : previous);
    }

    /** Upload-thread half: applies and clears only the matching generation and position. */
    public static void applyPendingLayerStates(RenderChunk chunk, long generation) {
        PendingLayerState state = pendingLayerStates.get(chunk);
        if (state == null || state.generation != generation) {
            return;
        }
        if (!state.matches(chunk)) {
            pendingLayerStates.remove(chunk, state);
            System.err.println("[VulkanChunk] rejected pending layer state for moved chunk=" + chunk
                    + " generation=" + generation + " builtAt=" + state.x + "," + state.y + "," + state.z);
            return;
        }
        if (pendingLayerStates.remove(chunk, state) && store != null) {
            store.markLayerStates(chunk, state.layerStarted, state.generation);
        }
    }

    public static void removeChunk(RenderChunk chunk) {
        uploadedPositions.remove(chunk);
        pendingLayerStates.remove(chunk);
        if (store != null) {
            store.remove(chunk);
        }
    }

    public static void clearWorld() {
        uploadedPositions.clear();
        pendingLayerStates.clear();
        if (store != null) {
            store.clear();
        }
    }


}
