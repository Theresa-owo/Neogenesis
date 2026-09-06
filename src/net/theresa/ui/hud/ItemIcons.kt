package net.theresa.ui.hud

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.renderer.texture.TextureMap
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.util.EnumFacing
import net.theresa.render.vulkan.VulkanContext
import net.theresa.ui.render.UiTexture2D
import net.theresa.ui.scene.UiNode
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM

/**
 * Atlas-space UV rect for one item icon. [atlasIndex] selects the Vulkan
 * atlas texture the uv rect belongs to (0 = the vanilla stitched block/item
 * atlas, the only atlas in 1.8's TextureMap).
 */
class IconInfo(val atlasIndex: Int, val u0: Float, val v0: Float, val u1: Float, val v1: Float)

/**
 * Item icon source for the Lua HUD: copies the vanilla GL stitched atlas
 * (TextureMap "textures/atlas/blocks.png" — items and blocks share one atlas
 * in 1.8) into a Vulkan [UiTexture2D] once, and resolves per-[ItemStack]
 * sprite UVs through the item model mesher.
 *
 * All entry points run on the client thread: the GL readback needs an OpenGL
 * context current (the same pattern VulkanTexture.uploadFromGL uses), and the
 * one-shot Vulkan staging submit matches UiTexture2D's own upload path.
 */
object ItemIcons {

    /** Per-node icon draw spec, registered from Lua (hudapi.show_hud / hudapi.set_icon). */
    class IconSpec(val atlasIndex: Int, val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    private val iconSpecs = HashMap<UiNode, IconSpec>()

    fun registerSpec(node: UiNode, spec: IconSpec) {
        iconSpecs[node] = spec
    }

    fun specFor(node: UiNode): IconSpec? = iconSpecs[node]

    /** Drops specs for nodes that are no longer part of the shown HUD tree. */
    fun pruneSpecs(keep: Collection<UiNode>) {
        iconSpecs.keys.retainAll(keep)
    }

    fun clearSpecs() {
        iconSpecs.clear()
    }

    // ------------------------------------------------------------------
    // Vanilla GL atlas -> Vulkan
    // ------------------------------------------------------------------

    private var atlas: UiTexture2D? = null
    private var atlasGlId = 0
    private var atlasW = 0
    private var atlasH = 0

    /**
     * The Vulkan copy of the vanilla atlas, created lazily on the first HUD
     * frame and re-copied only when the GL texture object (or its size)
     * changes — i.e. after a resource-pack reload. Block/item textures are
     * otherwise static, so the steady-state cost is two int comparisons.
     */
    @JvmStatic
    fun ensureAtlas(ctx: VulkanContext): UiTexture2D? {
        val current = atlas
        try {
            val mc = Minecraft.getMinecraft() ?: return null
            val texManager = mc.textureManager ?: return null
            val glTexture = texManager.getTexture(TextureMap.locationBlocksTexture) ?: return null
            val glId = glTexture.glTextureId
            val map = mc.textureMapBlocks ?: return null
            val w = map.atlasWidth
            val h = map.atlasHeight
            if (glId <= 0 || w <= 0 || h <= 0) return null
            if (current != null && glId == atlasGlId && w == atlasW && h == atlasH) return current

            current?.destroy()
            atlas = null

            val bytes = w * h * 4
            val pixels = MemoryUtil.memAlloc(bytes)
            try {
                // Bind the atlas, read mip 0 as RGBA8, restore the caller's
                // binding (glGetTexImage reads whatever GL_TEXTURE_2D holds).
                val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId)
                try {
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
                } finally {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
                }
                pixels.clear()
                val tex = UiTexture2D(ctx, w, h, pixels, VK_FORMAT_R8G8B8A8_UNORM, false)
                atlas = tex
                atlasGlId = glId
                atlasW = w
                atlasH = h
                System.out.println("[NeoUI] item icon atlas -> Vulkan: " + w + "x" + h)
                return tex
            } finally {
                MemoryUtil.memFree(pixels)
            }
        } catch (t: Throwable) {
            System.err.println("[NeoUI] icon atlas copy failed: $t")
            return null
        }
    }

    // ------------------------------------------------------------------
    // Per-ItemStack icon UVs
    // ------------------------------------------------------------------

    /** icon, metadata -> uv rect; misses re-resolve until a sprite is found. */
    private val iconCache = HashMap<Item, HashMap<Int, IconInfo>>()

    /** Null for an empty stack or when no sprite is (yet) resolvable. */
    @JvmStatic
    fun iconFor(stack: ItemStack?): IconInfo? {
        if (stack == null) return null
        val item = stack.item ?: return null
        val meta = stack.metadata
        val cached = iconCache[item]?.get(meta)
        if (cached != null) return cached
        val info = computeIcon(item, meta) ?: return null
        iconCache.getOrPut(item) { HashMap() }[meta] = info
        return info
    }

    private fun computeIcon(item: Item, meta: Int): IconInfo? {
        return try {
            val sprite = spriteFor(item, meta) ?: return null
            IconInfo(0, sprite.minU, sprite.minV, sprite.maxU, sprite.maxV)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 1.8.9 icon resolution: the item's baked GUI model. Flat generated items
     * carry their texture in the general quads; block models fall back to the
     * particle texture, then any face quad.
     */
    private fun spriteFor(item: Item, meta: Int): TextureAtlasSprite? {
        val mc = Minecraft.getMinecraft() ?: return null
        val renderItem = mc.renderItem ?: return null
        val model = renderItem.itemModelMesher.getItemModel(ItemStack(item, 1, meta)) ?: return null

        val general = model.generalQuads
        if (general != null) {
            for (quad in general) {
                val sprite = quad.sprite ?: continue
                return sprite
            }
        }
        val particle = model.particleTexture
        if (particle != null) return particle
        for (face in EnumFacing.values()) {
            val quads = model.getFaceQuads(face) ?: continue
            for (quad in quads) {
                val sprite = quad.sprite ?: continue
                return sprite
            }
        }
        return null
    }

    fun destroy() {
        atlas?.destroy()
        atlas = null
        atlasGlId = 0
        atlasW = 0
        atlasH = 0
        iconSpecs.clear()
        iconCache.clear()
    }
}
