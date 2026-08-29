package net.optifine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityMagmaCube;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.src.Config;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

public class DynamicLights {

    private static DynamicLightsMap mapDynamicLights = new DynamicLightsMap();
    private static Map<Class, Integer> mapEntityLightLevels = new HashMap();
    private static Map<Item, Integer> mapItemLightLevels = new HashMap();
    private static long timeUpdateMs = 0L;
    private static boolean initialized;

    public static void entityAdded(Entity entityIn, RenderGlobal renderGlobal) {
    }

    public static void entityRemoved(Entity entityIn, RenderGlobal renderGlobal) {
        synchronized (mapDynamicLights) {
            DynamicLight dynamiclight = mapDynamicLights.remove(entityIn.getEntityId());

            if (dynamiclight != null) {
                dynamiclight.updateLitChunks(renderGlobal);
            }
        }
    }

    public static void update(RenderGlobal renderGlobal) {
        long i = System.currentTimeMillis();

        if (i >= timeUpdateMs + 50L) {
            timeUpdateMs = i;

            if (!initialized) {
                initialize();
            }

            synchronized (mapDynamicLights) {
                updateMapDynamicLights(renderGlobal);

                if (mapDynamicLights.size() > 0) {
                    List<DynamicLight> list = mapDynamicLights.valueList();

                    for (DynamicLight element : list) {
                        DynamicLight dynamiclight = (DynamicLight) element;
                        dynamiclight.update(renderGlobal);
                    }
                }
            }
        }
    }

    private static void initialize() {
        initialized = true;
        mapEntityLightLevels.clear();
        mapItemLightLevels.clear();

        if (mapEntityLightLevels.size() > 0) {
            Config.dbg("DynamicLights entities: " + mapEntityLightLevels.size());
        }

        if (mapItemLightLevels.size() > 0) {
            Config.dbg("DynamicLights items: " + mapItemLightLevels.size());
        }
    }

    private static void updateMapDynamicLights(RenderGlobal renderGlobal) {
        World world = renderGlobal.getWorld();

        if (world != null) {
            for (Entity entity : world.getLoadedEntityList()) {
                int i = getLightLevel(entity);

                if (i > 0) {
                    int j = entity.getEntityId();
                    DynamicLight dynamiclight = mapDynamicLights.get(j);

                    if (dynamiclight == null) {
                        dynamiclight = new DynamicLight(entity);
                        mapDynamicLights.put(j, dynamiclight);
                    }
                } else {
                    int k = entity.getEntityId();
                    DynamicLight dynamiclight1 = mapDynamicLights.remove(k);

                    if (dynamiclight1 != null) {
                        dynamiclight1.updateLitChunks(renderGlobal);
                    }
                }
            }
        }
    }

    public static int getCombinedLight(BlockPos pos, int combinedLight) {
        double d0 = getLightLevel(pos);
        return getCombinedLight(d0, combinedLight);
    }

    public static int getCombinedLight(Entity entity, int combinedLight) {
        double d0 = (double) getLightLevel(entity);
        return getCombinedLight(d0, combinedLight);
    }

    public static int getCombinedLight(double lightPlayer, int combinedLight) {
        if (lightPlayer > 0.0D) {
            int i = (int) (lightPlayer * 16.0D);
            int j = combinedLight & 255;

            if (i > j) {
                combinedLight = combinedLight & -256;
                combinedLight = combinedLight | i;
            }
        }

        return combinedLight;
    }

    public static double getLightLevel(BlockPos pos) {
        double d0 = 0.0D;

        synchronized (mapDynamicLights) {
            List<DynamicLight> list = mapDynamicLights.valueList();
            int i = list.size();

            for (int j = 0; j < i; ++j) {
                DynamicLight dynamiclight = (DynamicLight) list.get(j);
                int k = dynamiclight.getLastLightLevel();

                if (k > 0) {
                    double d1 = dynamiclight.getLastPosX();
                    double d2 = dynamiclight.getLastPosY();
                    double d3 = dynamiclight.getLastPosZ();
                    double d4 = (double) pos.getX() - d1;
                    double d5 = (double) pos.getY() - d2;
                    double d6 = (double) pos.getZ() - d3;
                    double d7 = d4 * d4 + d5 * d5 + d6 * d6;

                    if (dynamiclight.isUnderwater() && !Config.isClearWater()) {
                        k = Config.limit(k - 2, 0, 15);
                        d7 *= 2.0D;
                    }

                    if (d7 <= 56.25D) {
                        double d8 = Math.sqrt(d7);
                        double d9 = 1.0D - d8 / 7.5D;
                        double d10 = d9 * (double) k;

                        if (d10 > d0) {
                            d0 = d10;
                        }
                    }
                }
            }
        }

        return Config.limit(d0, 0.0D, 15.0D);
    }

    public static int getLightLevel(ItemStack itemStack) {
        if (itemStack == null) {
            return 0;
        }
        Item item = itemStack.getItem();

        if (item instanceof ItemBlock) {
            ItemBlock itemblock = (ItemBlock) item;
            Block block = itemblock.getBlock();

            if (block != null) {
                return block.getLightValue();
            }
        }

        if (item == Items.lava_bucket) {
            return Blocks.lava.getLightValue();
        }
        if (item != Items.blaze_rod && item != Items.blaze_powder) {
            if (item == Items.glowstone_dust || item == Items.prismarine_crystals || item == Items.magma_cream) {
                return 8;
            } else if (item == Items.nether_star) {
                return Blocks.beacon.getLightValue() / 2;
            } else {
                if (!mapItemLightLevels.isEmpty()) {
                    Integer integer = (Integer) mapItemLightLevels.get(item);

                    if (integer != null) {
                        return integer;
                    }
                }

                return 0;
            }
        } else {
            return 10;
        }
    }

    public static int getLightLevel(Entity entity) {
        if (entity == Config.getMinecraft().getRenderViewEntity() && !Config.isDynamicHandLight()) {
            return 0;
        }
        if (entity instanceof EntityPlayer) {
            EntityPlayer entityplayer = (EntityPlayer) entity;

            if (entityplayer.isSpectator()) {
                return 0;
            }
        }

        if (entity.isBurning()) {
            return 15;
        }
        if (!mapEntityLightLevels.isEmpty()) {
            Integer integer = (Integer) mapEntityLightLevels.get(entity.getClass());

            if (integer != null) {
                return integer;
            }
        }

        if (entity instanceof EntityFireball || entity instanceof EntityTNTPrimed) {
            return 15;
        } else if (entity instanceof EntityBlaze) {
            EntityBlaze entityblaze = (EntityBlaze) entity;
            return entityblaze.func_70845_n() ? 15 : 10;
        } else if (entity instanceof EntityMagmaCube) {
            EntityMagmaCube entitymagmacube = (EntityMagmaCube) entity;
            return (double) entitymagmacube.squishFactor > 0.6D ? 13 : 8;
        } else {
            if (entity instanceof EntityCreeper) {
                EntityCreeper entitycreeper = (EntityCreeper) entity;

                if ((double) entitycreeper.getCreeperFlashIntensity(0.0F) > 0.001D) {
                    return 15;
                }
            }

            if (entity instanceof EntityLivingBase) {
                EntityLivingBase entitylivingbase = (EntityLivingBase) entity;
                ItemStack itemstack2 = entitylivingbase.getHeldItem();
                int i = getLightLevel(itemstack2);
                ItemStack itemstack1 = entitylivingbase.getEquipmentInSlot(4);
                int j = getLightLevel(itemstack1);
                return Math.max(i, j);
            } else if (entity instanceof EntityItem) {
                EntityItem entityitem = (EntityItem) entity;
                ItemStack itemstack = getItemStack(entityitem);
                return getLightLevel(itemstack);
            } else {
                return 0;
            }
        }
    }

    public static void removeLights(RenderGlobal renderGlobal) {
        synchronized (mapDynamicLights) {
            List<DynamicLight> list = mapDynamicLights.valueList();

            for (DynamicLight element : list) {
                DynamicLight dynamiclight = (DynamicLight) element;
                dynamiclight.updateLitChunks(renderGlobal);
            }

            mapDynamicLights.clear();
        }
    }

    public static void clear() {
        synchronized (mapDynamicLights) {
            mapDynamicLights.clear();
        }
    }

    public static int getCount() {
        synchronized (mapDynamicLights) {
            return mapDynamicLights.size();
        }
    }

    public static ItemStack getItemStack(EntityItem entityItem) {
        return entityItem.getDataWatcher().getWatchableObjectItemStack(10);
    }

}
