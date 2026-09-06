package net.theresa.ui.font

import net.theresa.render.vulkan.VulkanContext
import net.theresa.ui.render.UiTexture2D
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Text engine: lazy glyph atlas (shelf packing, multiple R8 pages), metrics
 * and formatting-code parsing.
 *
 * Glyphs bake on first use at integer pixel sizes (ASCII prebaked, CJK on
 * demand), so a fresh page texture only appears when the current one fills.
 * Pages are exposed for descriptor binding and drawing.
 */
class FontEngine private constructor(private val ctx: VulkanContext, private val font: StbTtfFont) {

    class Glyph(
        val page: Int,
        val u0: Float, val v0: Float, val u1: Float, val v1: Float,
        val width: Int, val height: Int,
        val xoff: Int, val yoff: Int,
        val advance: Float,
    )

    private data class Slot(val page: Int, val x: Int, val y: Int, val w: Int, val h: Int, val region: ByteBuffer)

    private val cache = ConcurrentHashMap<Long, Glyph>()
    private val pages = ArrayList<UiTexture2D>()
    private val pendingRegionUpdates = ArrayList<Slot>()

    // shelf packer state per page
    private val shelfX = ArrayList<Int>()
    private val shelfY = ArrayList<Int>()
    private val shelfRowH = ArrayList<Int>()

    /** Pixels from line top to the baseline at [sizePx]. */
    fun ascent(sizePx: Float): Float {
        val m = FloatArray(3)
        font.verticalMetrics(scaleFor(sizePx), m)
        return m[0]
    }

    fun lineHeight(sizePx: Float): Float {
        val m = FloatArray(3)
        font.verticalMetrics(scaleFor(sizePx), m)
        return m[0] - m[1] + m[2]
    }

    private fun scaleFor(sizePx: Float): Float = font.scaleForPixelHeight(sizePx)

    /** Advance of one codepoint (without kerning) in pixels. */
    fun advance(cp: Int, sizePx: Float): Float {
        val out = FloatArray(2)
        font.horizontalMetrics(cp, scaleFor(sizePx), out)
        return out[0]
    }

    fun kern(prev: Int, next: Int, sizePx: Float): Float = font.kernAdvance(prev, next, scaleFor(sizePx))

    fun hasGlyph(cp: Int): Boolean = font.glyphIndex(cp) != 0

    /** Page count (for descriptor allocation bounds). */
    fun pageCount(): Int = pages.size

    fun pageTexture(page: Int): UiTexture2D = pages[page]

    fun getGlyph(codepoint: Int, sizePx: Float): Glyph {
        val sizeI = sizePx.toInt().coerceAtLeast(1)
        val key = (sizeI.toLong() shl 32) or (codepoint.toLong() and 0xFFFFFFFFL)
        return cache[key] ?: bake(codepoint, sizeI, key)
    }

    @Synchronized
    private fun bake(codepoint: Int, sizeI: Int, key: Long): Glyph {
        cache[key]?.let { return it }
        val scale = font.scaleForPixelHeight(sizeI.toFloat())
        val advance = FloatArray(2)
        font.horizontalMetrics(codepoint, scale, advance)
        val bitmap = font.bakeBitmap(codepoint, scale, sizeI.toFloat())

        val glyph: Glyph
        if (bitmap == null) {
            // whitespace: advance only
            glyph = Glyph(-1, 0f, 0f, 0f, 0f, 0, 0, 0, 0, advance[0])
        } else {
            val pad = 1
            val w = bitmap.width + pad * 2
            val h = bitmap.height + pad * 2

            var page = pages.size - 1
            if (page < 0) {
                createPage()
                page = 0
            }
            if (shelfX[page] + w > PAGE_SIZE) {
                // next shelf row
                shelfY[page] += shelfRowH[page]
                shelfRowH[page] = 0
                shelfX[page] = 0
            }
            if (shelfY[page] + h > PAGE_SIZE) {
                createPage()
                page = pages.size - 1
            }
            val sx = shelfX[page]; val sy = shelfY[page]
            shelfX[page] += w
            if (h > shelfRowH[page]) shelfRowH[page] = h

            // blit the coverage bitmap into a zero-padded region
            val region = MemoryUtil.memAlloc(w * h)
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val inside = col >= pad && col < pad + bitmap.width && row >= pad && row < pad + bitmap.height
                    region.put(if (inside) bitmap.bytes[(row - pad) * bitmap.width + (col - pad)] else 0)
                }
            }
            region.flip()
            pendingRegionUpdates.add(Slot(page, sx, sy, w, h, region))

            glyph = Glyph(
                page,
                sx.toFloat() / PAGE_SIZE, sy.toFloat() / PAGE_SIZE,
                (sx + w).toFloat() / PAGE_SIZE, (sy + h).toFloat() / PAGE_SIZE,
                w, h,
                bitmap.xoff - pad, bitmap.yoff - pad,
                advance[0],
            )
        }
        cache[key] = glyph
        return glyph
    }

    /** Uploads glyphs baked since the last flush (call once per frame before drawing). */
    @Synchronized
    fun flushUploads() {
        if (pendingRegionUpdates.isEmpty()) return
        for (slot in pendingRegionUpdates) {
            val tex = pages[slot.page]
            tex.updateRegion(slot.x, slot.y, slot.w, slot.h, slot.region)
            MemoryUtil.memFree(slot.region)
        }
        pendingRegionUpdates.clear()
    }

    private fun createPage() {
        val buf = emptyPage()
        pages.add(UiTexture2D(ctx, PAGE_SIZE, PAGE_SIZE, buf, VK_FORMAT_R8_UNORM, true))
        MemoryUtil.memFree(buf)
        shelfX.add(0); shelfY.add(0); shelfRowH.add(0)
    }

    private fun emptyPage(): ByteBuffer {
        val buf = MemoryUtil.memAlloc(PAGE_SIZE * PAGE_SIZE)
        for (i in 0 until PAGE_SIZE * PAGE_SIZE) buf.put(0)
        buf.flip()
        return buf
    }

    fun destroy() {
        for (p in pages) p.destroy()
        pages.clear()
        cache.clear()
    }

    companion object {
        const val PAGE_SIZE = 2048

        /** Vanilla § color table (indices 0-15 follow "0123456789abcdef"). */
        val COLOR_CODES = intArrayOf(
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        )

        /**
         * Splits [text] into (text, argb) segments, resolving §0-§f color
         * codes (§r resets to [defaultColor]). Other codes (§l etc.) are
         * kept literally for later styling passes.
         */
        fun parseColorCodes(text: String, defaultColor: Int): List<Pair<String, Int>> {
            val segments = ArrayList<Pair<String, Int>>()
            var color = defaultColor
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '§' && i + 1 < text.length) {
                    val code = text[i + 1]
                    val idx = "0123456789abcdef".indexOf(code.lowercaseChar())
                    if (idx >= 0) {
                        if (sb.isNotEmpty()) {
                            segments.add(sb.toString() to color)
                            sb.clear()
                        }
                        color = COLOR_CODES[idx] or 0xFF000000.toInt()
                        i += 2
                        continue
                    }
                    if (code == 'r') {
                        if (sb.isNotEmpty()) {
                            segments.add(sb.toString() to color)
                            sb.clear()
                        }
                        color = defaultColor
                        i += 2
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            if (sb.isNotEmpty()) segments.add(sb.toString() to color)
            return segments
        }

        fun create(ctx: VulkanContext): FontEngine = FontEngine(ctx, StbTtfFont.loadDefault())
    }
}
