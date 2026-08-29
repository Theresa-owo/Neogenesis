package net.theresa.neogenesis.modules.culling.entityculling;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ConcurrentModificationException;
import java.util.Iterator;

public class CullTask implements Runnable {

    public boolean requestCull = false;
    private final OcclusionCullingInstance culling;
    private final Minecraft client = Minecraft.getMinecraft();
    private final int sleepDelay;
    private final int hitboxLimit;
    public long lastTime;
    private final Vec3d lastPos;
    private final Vec3d aabbMin;
    private final Vec3d aabbMax;

    public CullTask(OcclusionCullingInstance culling) {
        this.sleepDelay = Config.sleepDelay;
        this.hitboxLimit = Config.hitboxLimit;
        this.lastTime = 0L;
        this.lastPos = new Vec3d(0.0, 0.0, 0.0);
        this.aabbMin = new Vec3d(0.0, 0.0, 0.0);
        this.aabbMax = new Vec3d(0.0, 0.0, 0.0);
        this.culling = culling;
    }

    public void run() {
        while (this.client != null && EntityCulling.Instance.getToggled()) {
            try {
                long start111 = System.nanoTime();
                if (EntityCulling.Instance.getToggled() && this.client.theWorld != null &&
                    this.client.thePlayer != null && this.client.thePlayer.ticksExisted > 10 &&
                    this.client.getRenderViewEntity() != null) {
                    Vec3 cameraMC;
                    if (Config.debugMode) {
                        cameraMC = this.getPositionEyes(this.client.thePlayer, 0.0F);
                    } else {
                        cameraMC = this.getCameraPos();
                    }

                    if (this.requestCull || cameraMC.xCoord != this.lastPos.x || cameraMC.yCoord != this.lastPos.y ||
                        cameraMC.zCoord != this.lastPos.z) {
                        long start = System.currentTimeMillis();
                        this.requestCull = false;
                        this.lastPos.set(cameraMC.xCoord, cameraMC.yCoord, cameraMC.zCoord);
                        Vec3d camera = this.lastPos;
                        this.culling.resetCache();
                        boolean noCulling =
                                this.client.thePlayer.noClip || this.client.gameSettings.thirdPersonView != 0;
                        Iterator<TileEntity> iterator = this.client.theWorld.loadedTileEntityList.iterator();

                        while (iterator.hasNext()) {
                            TileEntity entry;
                            try {
                                entry = iterator.next();
                            } catch (ConcurrentModificationException | NullPointerException var14) {
                                break;
                            }

                            if (!entry.isForcedVisible()) {
                                if (noCulling) {
                                    entry.setCulled(false);
                                } else if (entry.getDistanceSq(cameraMC.xCoord, cameraMC.yCoord, cameraMC.zCoord) <
                                           4096.0) {
                                    AxisAlignedBB boundingBox = entry.getBlockType()
                                                                     .getSelectedBoundingBox(this.client.theWorld,
                                                                                             entry.getPos());
                                    if (!this.setBoxAndCheckLimits(entry, boundingBox)) {
                                        if (Config.debugMode) {
                                            System.out.println("Currently processing tileentity " +
                                                               entry.getBlockType().getUnlocalizedName());
                                        }

                                        boolean visible =
                                                this.culling.isAABBVisible(this.aabbMin, this.aabbMax, camera);
                                        entry.setCulled(!visible);
                                    }
                                }
                            }
                            Thread.yield();
                        }

                        Iterator<Entity> iterable = this.client.theWorld.getLoadedEntityList().iterator();

                        while (iterable.hasNext()) {
                            Entity entity;
                            try {
                                entity = iterable.next();
                            } catch (ConcurrentModificationException | NullPointerException var13) {
                                break;
                            }

                            if (entity != null) {
                                if (!entity.isForcedVisible()) {
                                    if (noCulling) {
                                        entity.setCulled(false);
                                    } else if (getPositionVector(entity).squareDistanceTo(cameraMC) >
                                               (double) (Config.tracingDistance * Config.tracingDistance)) {
                                        entity.setCulled(false);
                                    } else {
                                        AxisAlignedBB boundingBox = entity.getEntityBoundingBox();
                                        if (!this.setBoxAndCheckLimits(entity, boundingBox)) {
                                            if (Config.debugMode) {
                                                System.out.println("Currently processing entity " + entity.getName());
                                            }

                                            boolean visible =
                                                    this.culling.isAABBVisible(this.aabbMin, this.aabbMax, camera);
                                            entity.setCulled(!visible);
                                        }
                                    }
                                }
                            }
                        }

                        this.lastTime = System.currentTimeMillis() - start;
                    }
                }

                double d = (System.nanoTime() - start111) / 1_000_000.0D + sleepOverhead;
                long sleepTime = this.sleepDelay - (long) d;

                sleepOverhead = d % 1.0D;

                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            } catch (Exception var15) {
                var15.printStackTrace();
            }
        }

        System.out.println("Shutting down culling task!");
    }

    private double sleepOverhead = 0.0D;

    private boolean setBoxAndCheckLimits(Cullable cullable, AxisAlignedBB boundingBox) {
        if (!(boundingBox.maxX - boundingBox.minX > (double) this.hitboxLimit) &&
            !(boundingBox.maxY - boundingBox.minY > (double) this.hitboxLimit) &&
            !(boundingBox.maxZ - boundingBox.minZ > (double) this.hitboxLimit)) {
            this.aabbMin.set(boundingBox.minX, boundingBox.minY, boundingBox.minZ);
            this.aabbMax.set(boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ);
            return false;
        } else {
            cullable.setCulled(false);
            return true;
        }
    }

    public static Vec3 getPositionVector(Entity e) {
        return new Vec3(e.posX, e.posY, e.posZ);
    }

    public Vec3 getPositionEyes(Entity e, float partialTicks) {
        if (partialTicks == 1.0F) {
            return new Vec3(e.posX, e.posY + (double) e.getEyeHeight(), e.posZ);
        } else {
            double d0 = e.prevPosX + (e.posX - e.prevPosX) * (double) partialTicks;
            double d1 = e.prevPosY + (e.posY - e.prevPosY) * (double) partialTicks + (double) e.getEyeHeight();
            double d2 = e.prevPosZ + (e.posZ - e.prevPosZ) * (double) partialTicks;
            return new Vec3(d0, d1, d2);
        }
    }

    private Vec3 getCameraPos() {
        return this.getPositionEyes(this.client.getRenderViewEntity(), 0.0F);
    }

}
