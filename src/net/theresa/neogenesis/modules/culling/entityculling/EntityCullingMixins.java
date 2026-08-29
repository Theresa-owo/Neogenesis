package net.theresa.neogenesis.modules.culling.entityculling;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import org.lwjgl.opengl.GL11;

public class EntityCullingMixins {

    public static int tileRendered = 0;
    public static int tileCulled = 0;
    public static int entityRendered = 0;
    public static int entityCulled = 0;
    public static int particleRendered = 0;
    public static int particleCulled = 0;

    public static void resetCounter() {
        tileRendered = 0;
        tileCulled = 0;
        entityRendered = 0;
        entityCulled = 0;
        particleRendered = 0;
        particleCulled = 0;
    }

    public static String formatAndReset() {
        String string =
                String.format("(E%d+%d, T%d+%d, P%d+%d)", entityRendered, entityCulled, tileRendered, tileCulled,
                              particleRendered, particleCulled);
        resetCounter();
        return string;
    }

    public static boolean TileEntityRendererDispatcher_renderTileEntityAt_Odddf(TileEntity tileEntity) {
        if (!tileEntity.isForcedVisible() && tileEntity.isCulled()) {
            tileCulled++;
            return true;
        } else {
            tileRendered++;
            return false;
        }
    }

    public static boolean RenderManager_doRenderEntity_Odddfft(RenderManager renderManager, Entity entity, double x,
            double y, double z) {
        if (!entity.isForcedVisible() && entity.isCulled()) {
            if (entity instanceof EntityLivingBase && Config.renderNametagsThroughWalls) {
                Render render = renderManager.getEntityRenderObject(entity);
                if (render.canRenderName(entity)) {
                    RenderHelper.enableStandardItemLighting();
                    GlStateManager.enableDepth();
                    GlStateManager.depthFunc(GL11.GL_LEQUAL);
                    render.renderName(entity, x, y, z);
                }
            }
            entityCulled++;
            return true;
        } else {
            entity.setOutOfCamera(false);
            entityRendered++;
            return false;
        }
    }

}
