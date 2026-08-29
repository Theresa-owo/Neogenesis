package net.theresa.neogenesis.modules.culling.particleculling;

import net.theresa.neogenesis.ClientMain;
import net.theresa.neogenesis.modules.culling.entityculling.EntityCulling;
import net.theresa.neogenesis.modules.culling.entityculling.Vec3d;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.*;

public class CullThread extends Thread {

    private double sleepOverhead = 0.0D;
    private final List<EntityFX> temporaryEntityFXStorage = new ArrayList<>();

    public CullThread() {
        setName("EntityFX Culling");
        setDaemon(true);
    }

    @Override
    public void run() {
        Minecraft mc = Minecraft.getMinecraft();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                long start = System.nanoTime();

                if (mc.theWorld != null) {
                    for (int i = 0; i < 4; i++) {
                        for (int j = 0; j < 2; j++) {
                            iterateEntityFXs(mc, mc.effectRenderer.fxLayers[i][j]);
                        }
                    }
                }

                double d = (System.nanoTime() - start) / 1_000_000.0D + sleepOverhead;
                long sleepTime = 10 - (long) d;

                sleepOverhead = d % 1.0D;

                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // thanks to Meldexun, minimizes CMEs and NSEEs when iterating over an fx layer
    private void iterateEntityFXs(Minecraft mc, List<EntityFX> deque) {
        try {
            Iterator<EntityFX> iterator = deque.iterator();

            while (iterator.hasNext()) {
                EntityFX particle;

                try {
                    particle = iterator.next();
                } catch (ConcurrentModificationException | NoSuchElementException e) {
                    break; // break the loop, as continuing it would just lead to more exceptions as the list backing the iterator got changed
                }

                if (particle != null) {
                    temporaryEntityFXStorage.add(particle);
                }
            }

            for (EntityFX particle : temporaryEntityFXStorage) {
                particle.culled = shouldCullEntityFX(particle, mc);
            }
        } finally {
            temporaryEntityFXStorage.clear();
        }
    }

    private boolean shouldCullEntityFX(EntityFX particle, Minecraft mc) {
        if (!DisableLightUpdates.Instance.getToggled() ||
            !Config.cullInSpectator && mc.thePlayer.isSpectator()) {
            return false;
        }

        Frustum camera = mc.entityRenderer.cameraCache;

        if (camera == null) {
            return false;
        }

        if (camera.isBoundingBoxInFrustum(particle.getEntityBoundingBox())) {
            if (Config.cullBehindBlocks) {
                Entity entity = mc.getRenderViewEntity();

                if (entity != null) {
                    return shouldCull(entity.worldObj,
                                      new Vec3d(entity.posX, entity.posY + entity.getEyeHeight(), entity.posZ),
                                      new Vec3d(particle.posX, particle.posY, particle.posZ));
                }
            }

            return false;
        }
        return true;
    }

    // adapted from World#rayTraceBlocks to be able to ray trace through blocks
    private boolean shouldCull(World world, Vec3d from, Vec3d to) {
        if (!Double.isNaN(from.x) && !Double.isNaN(from.y) && !Double.isNaN(from.z) && !Double.isNaN(to.x) &&
            !Double.isNaN(to.y) && !Double.isNaN(to.z)) {
            boolean opacityCheck = false;
            int blocks = 0;
            int toX = MathHelper.floor_double(to.x);
            int toY = MathHelper.floor_double(to.y);
            int toZ = MathHelper.floor_double(to.z);
            int checkX = MathHelper.floor_double(from.x);
            int checkY = MathHelper.floor_double(from.y);
            int checkZ = MathHelper.floor_double(from.z);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(checkX, checkY, checkZ);
            IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();

            if (state.getBlock().getCollisionBoundingBox(world, pos, state) != null &&
                block.canCollideCheck(state, false) &&
                state.getBlock().collisionRayTrace(world, pos, from.toVec3(), to.toVec3()) != null) {
                blocks++;
                opacityCheck = opacityCheck || Config.cullBehindGlass ||
                               (state.getBlock().isFullCube() && state.getBlock().isOpaqueCube());
            }

            int maxIterations = 50;

            while (maxIterations-- >= 0) {
                if (checkX == toX && checkY == toY && checkZ == toZ) {
                    return opacityCheck && (++blocks > Config.blockBuffer);
                }

                boolean wasXChanged = true;
                boolean wasYChanged = true;
                boolean wasZChanged = true;
                double d0 = 999.0D;
                double d1 = 999.0D;
                double d2 = 999.0D;

                if (toX > checkX) {
                    d0 = checkX + 1.0D;
                } else if (toX < checkX) {
                    d0 = checkX + 0.0D;
                } else {
                    wasXChanged = false;
                }

                if (toY > checkY) {
                    d1 = checkY + 1.0D;
                } else if (toY < checkY) {
                    d1 = checkY + 0.0D;
                } else {
                    wasYChanged = false;
                }

                if (toZ > checkZ) {
                    d2 = checkZ + 1.0D;
                } else if (toZ < checkZ) {
                    d2 = checkZ + 0.0D;
                } else {
                    wasZChanged = false;
                }

                double d3 = 999.0D;
                double d4 = 999.0D;
                double d5 = 999.0D;
                double d6 = to.x - from.x;
                double d7 = to.y - from.y;
                double d8 = to.z - from.z;

                if (wasXChanged) {
                    d3 = (d0 - from.x) / d6;
                }

                if (wasYChanged) {
                    d4 = (d1 - from.y) / d7;
                }

                if (wasZChanged) {
                    d5 = (d2 - from.z) / d8;
                }

                if (d3 == -0.0D) {
                    d3 = -1.0E-4D;
                }

                if (d4 == -0.0D) {
                    d4 = -1.0E-4D;
                }

                if (d5 == -0.0D) {
                    d5 = -1.0E-4D;
                }

                EnumFacing facing;

                if (d3 < d4 && d3 < d5) {
                    facing = toX > checkX ? EnumFacing.WEST : EnumFacing.EAST;
                    from.x = d0;
                    from.y += d7 * d3;
                    from.z += d8 * d3;
                } else if (d4 < d5) {
                    facing = toY > checkY ? EnumFacing.DOWN : EnumFacing.UP;
                    from.x += d6 * d4;
                    from.y = d1;
                    from.z += d8 * d4;
                } else {
                    facing = toZ > checkZ ? EnumFacing.NORTH : EnumFacing.SOUTH;
                    from.x += d6 * d5;
                    from.y += d7 * d5;
                    from.z = d2;
                }

                checkX = MathHelper.floor_double(from.x) - (facing == EnumFacing.EAST ? 1 : 0);
                checkY = MathHelper.floor_double(from.y) - (facing == EnumFacing.UP ? 1 : 0);
                checkZ = MathHelper.floor_double(from.z) - (facing == EnumFacing.SOUTH ? 1 : 0);
                pos.set(checkX, checkY, checkZ);
                state = world.getBlockState(pos);
                block = state.getBlock();

                if (state.getBlock().getMaterial() == Material.portal ||
                    state.getBlock().getCollisionBoundingBox(world, pos, state) != null &&
                    block.canCollideCheck(state, false) &&
                    state.getBlock().collisionRayTrace(world, pos, from.toVec3(), to.toVec3()) != null) {
                    opacityCheck = opacityCheck || Config.cullBehindGlass ||
                                   (state.getBlock().isFullCube() && state.getBlock().isOpaqueCube());

                    if (++blocks > Config.blockBuffer) {
                        return opacityCheck;
                    }
                }
            }

            return opacityCheck && blocks > Config.blockBuffer;
        }

        return false;
    }

}
