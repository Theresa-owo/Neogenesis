package net.theresa.ui.font

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTruetype
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * One TrueType/OpenType font loaded through stb_truetype. Handles .ttf/.otf
 * and .ttc collections (index 0). All metrics are exposed in pixels for a
 * given pixel height.
 */
class StbTtfFont(val data: ByteBuffer, name: String) {

    private val info: STBTTFontinfo
    val name: String

    init {
        val offset = STBTruetype.stbtt_GetFontOffsetForIndex(data, 0)
        require(offset >= 0) { "$name: not a valid TrueType collection/file" }
        info = STBTTFontinfo.calloc()
        check(STBTruetype.stbtt_InitFont(info, data, offset)) { "$name: stbtt_InitFont failed" }
        this.name = name
    }

    /** Coverage scale so the font renders at [pixelHeight] pixels tall. */
    fun scaleForPixelHeight(pixelHeight: Float): Float =
        STBTruetype.stbtt_ScaleForPixelHeight(info, pixelHeight)

    fun verticalMetrics(scale: Float, metrics: FloatArray) {
        MemoryStack.stackPush().use { stack ->
            val ascent: IntBuffer = stack.mallocInt(1)
            val descent: IntBuffer = stack.mallocInt(1)
            val lineGap: IntBuffer = stack.mallocInt(1)
            STBTruetype.stbtt_GetFontVMetrics(info, ascent, descent, lineGap)
            metrics[0] = ascent.get(0) * scale
            metrics[1] = descent.get(0) * scale
            metrics[2] = lineGap.get(0) * scale
        }
    }

    /** (advance, leftBearing) in pixels. */
    fun horizontalMetrics(codepoint: Int, scale: Float, out: FloatArray) {
        MemoryStack.stackPush().use { stack ->
            val advance: IntBuffer = stack.mallocInt(1)
            val bearing: IntBuffer = stack.mallocInt(1)
            STBTruetype.stbtt_GetCodepointHMetrics(info, codepoint, advance, bearing)
            out[0] = advance.get(0) * scale
            out[1] = bearing.get(0) * scale
        }
    }

    fun kernAdvance(cp1: Int, cp2: Int, scale: Float): Float =
        STBTruetype.stbtt_GetCodepointKernAdvance(info, cp1, cp2) * scale

    fun glyphIndex(codepoint: Int): Int = STBTruetype.stbtt_FindGlyphIndex(info, codepoint)

    /**
     * Rasterizes the glyph's anti-aliased coverage bitmap (1 byte per pixel,
     * row 0 = top). Returns null for whitespace glyphs. [out] receives
     * (width, height, xOffset, yOffset) — offsets are relative to the pen
     * position, y positive DOWN (matches screen coordinates).
     */
    fun bakeBitmap(codepoint: Int, scale: Float, sizePx: Float): Bitmap? {
        MemoryStack.stackPush().use { stack ->
            val w: IntBuffer = stack.mallocInt(1)
            val h: IntBuffer = stack.mallocInt(1)
            val xoff: IntBuffer = stack.mallocInt(1)
            val yoff: IntBuffer = stack.mallocInt(1)
            val bitmap = STBTruetype.stbtt_GetCodepointBitmap(
                info, scale, scale, codepoint, w, h, xoff, yoff
            ) ?: return null
            val bw = w.get(0)
            val bh = h.get(0)
            if (bw <= 0 || bh <= 0) {
                STBTruetype.stbtt_FreeBitmap(bitmap, MemoryUtil.NULL)
                return null
            }
            val bytes = ByteArray(bw * bh)
            bitmap.get(bytes)
            // memAddress is position-relative: rewind so stbtt_FreeBitmap
            // releases the allocation's base pointer, not base+size.
            bitmap.position(0)
            STBTruetype.stbtt_FreeBitmap(bitmap, MemoryUtil.NULL)
            return Bitmap(bytes, bw, bh, xoff.get(0), yoff.get(0))
        }
    }

    class Bitmap(val bytes: ByteArray, val width: Int, val height: Int, val xoff: Int, val yoff: Int)

    companion object {
        /**
         * Demo font discovery: resource-pack fonts first (theme font family),
         * then common Windows system fonts (regular weight). The font FILE is
         * never bundled in the repo; mods can ship their own under
         * assets/&lt;ns&gt;/ui/fonts/.
         */
        fun loadDefault(): StbTtfFont {
            val candidates = listOf(
                "C:/Windows/Fonts/msyh.ttc" to "Microsoft YaHei",
                "C:/Windows/Fonts/msyhl.ttc" to "Microsoft YaHei Light",
                "C:/Windows/Fonts/simhei.ttf" to "SimHei",
                "C:/Windows/Fonts/arial.ttf" to "Arial",
            )
            for ((path, name) in candidates) {
                val file = File(path)
                if (!file.exists()) continue
                val bytes = file.readBytes()
                val buffer = org.lwjgl.system.MemoryUtil.memAlloc(bytes.size)
                buffer.put(bytes).flip()
                return try {
                    StbTtfFont(buffer, name)
                } catch (t: Throwable) {
                    System.err.println("[NeoUI] font $name unusable: $t")
                    org.lwjgl.system.MemoryUtil.memFree(buffer)
                    continue
                }
            }
            throw IllegalStateException("No usable TTF font found (tried $candidates)")
        }
    }
}
