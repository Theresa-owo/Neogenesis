package net.theresa.neogenesis.modules.culling.entityculling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;

public class Provider implements DataProvider {

    private final Minecraft client = Minecraft.getMinecraft();
    private WorldClient world = null;

    public Provider() {
    }

    public boolean prepareChunk(int chunkX, int chunkZ) {
        this.world = this.client.theWorld;
        return this.world != null;
    }

    public boolean isOpaqueFullCube(int x, int y, int z) {
        return this.world.getBlockState(new BlockPos(x, y, z)).getBlock().isOpaqueCube();
    }

    public void cleanup() {
        this.world = null;
    }

}
