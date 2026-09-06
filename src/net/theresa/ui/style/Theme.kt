package net.theresa.ui.style

import com.google.gson.JsonObject

/**
 * NeoUI theme: every visual constant the renderer needs, loaded from
 * assets/neogenesis/ui/theme.json in resource packs (moddable) with
 * code-level defaults as fallback.
 */
data class Theme(
    val surface: Long = 0xB310141B,         // frosted glass tint (ARGB)
    val surfaceBorder: Long = 0x26FFFFFF,    // hairline border over glass
    val surfaceSolid: Long = 0xF21A1E26,     // solid panel fill
    val accent: Long = 0xFF7C5CFF,           // primary button fill
    val accentEnd: Long = 0xFF6A49E8,        // primary button gradient end
    val ghost: Long = 0x4DFFFFFF,            // secondary button fill
    val ghostBorder: Long = 0x4DFFFFFF,
    val text: Long = 0xFFF2F3F5,
    val textMuted: Long = 0xFF9AA0A8,
    val shadow: Long = 0x66000000,
    val radius: Float = 16f,
    val baseScale: Float = 1f,
    val fontSize: Float = 20f,
    val entranceMs: Long = 280,
    val hoverMs: Long = 120,
) {
    val surfaceArgb: Int get() = surface.toInt()
    val surfaceBorderArgb: Int get() = surfaceBorder.toInt()
    val surfaceSolidArgb: Int get() = surfaceSolid.toInt()
    val accentArgb: Int get() = accent.toInt()
    val accentEndArgb: Int get() = accentEnd.toInt()
    val ghostArgb: Int get() = ghost.toInt()
    val ghostBorderArgb: Int get() = ghostBorder.toInt()
    val textArgb: Int get() = text.toInt()
    val textMutedArgb: Int get() = textMuted.toInt()
    val shadowArgb: Int get() = shadow.toInt()

    companion object {
        val DEFAULT = Theme()

        /** Loads theme overrides from the classpath; missing file = defaults. */
        fun load(): Theme {
            return try {
                val stream = Theme::class.java.getResourceAsStream("/assets/neogenesis/ui/theme.json")
                    ?: return DEFAULT
                val json = stream.use { it.readBytes().decodeToString() }
                fromJson(com.google.gson.JsonParser.parseString(json).asJsonObject)
            } catch (t: Throwable) {
                System.err.println("[NeoUI] theme load failed, using defaults: $t")
                DEFAULT
            }
        }

        private fun fromJson(o: JsonObject): Theme {
            val d = DEFAULT
            fun color(name: String, fallback: Long): Long =
                o.get(name)?.takeIf { it.isJsonPrimitive }?.let {
                    runCatching { java.lang.Long.decode(it.asString) }.getOrDefault(fallback)
                } ?: fallback
            fun num(name: String, fallback: Float): Float =
                o.get(name)?.takeIf { it.isJsonPrimitive }?.asFloat ?: fallback
            return Theme(
                surface = color("surface", d.surface),
                surfaceBorder = color("surfaceBorder", d.surfaceBorder),
                surfaceSolid = color("surfaceSolid", d.surfaceSolid),
                accent = color("accent", d.accent),
                accentEnd = color("accentEnd", d.accentEnd),
                ghost = color("ghost", d.ghost),
                ghostBorder = color("ghostBorder", d.ghostBorder),
                text = color("text", d.text),
                textMuted = color("textMuted", d.textMuted),
                shadow = color("shadow", d.shadow),
                radius = num("radius", d.radius),
                baseScale = num("baseScale", d.baseScale),
                fontSize = num("fontSize", d.fontSize),
                entranceMs = num("entranceMs", d.entranceMs.toFloat()).toLong(),
                hoverMs = num("hoverMs", d.hoverMs.toFloat()).toLong(),
            )
        }
    }
}
