package net.optifine.util;

import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.Chunk;

public class ChunkUtils {

    public static int getPrecipitationHeight(Chunk chunk, BlockPos pos) {
        int[] aint = chunk.precipitationHeightMap;

        if (aint != null && aint.length == 256) {
            int i = pos.getX() & 15;
            int j = pos.getZ() & 15;
            int k = i | j << 4;
            int l = aint[k];

            if (l >= 0) {
                return l;
            } else {
                BlockPos blockpos = chunk.getPrecipitationHeight(pos);
                return blockpos.getY();
            }
        } else {
            return -1;
        }
    }

}
