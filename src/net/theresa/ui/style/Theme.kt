package net.theresa.ui.style

import com.google.gson.JsonObject

/**
 * NeoUI theme following the Material Design 3 dark baseline
 * (https://m3.material.io/components): primary/secondary-container tonal
 * colors, surface containers, 28dp extra-large shape scale for cards and
 * full-radius pills for buttons. Loaded from
 * assets/neogenesis/ui/theme.json in resource packs (moddable) with
 * code-level defaults as fallback.
 */
data class Theme(
    val surface: Long = 0xC0FFFFFF,          // glass tint over blurred backdrop
    val surfaceBorder: Long = 0x14000000,    // hairline border over glass
    val surfaceSolid: Long = 0xF2211F26,     // surface-container-high fill
    val accent: Long = 0xFFD0BCFF,           // MD3 dark primary
    val accentEnd: Long = 0xFFD0BCFF,        // MD3 is flat; kept for gradients
    val onAccent: Long = 0xFF381E72,         // MD3 on-primary
    val ghost: Long = 0x804A4458,            // secondary-container @ 50%
    val ghostBorder: Long = 0x00000000,      // tonal buttons have no outline
    val text: Long = 0xFF1F2126,             // on-surface
    val textMuted: Long = 0xFF6E7078,        // on-surface-variant
    val shadow: Long = 0x52000000,
    val radius: Float = 28f,                 // MD3 extra-large shape
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
    val onAccentArgb: Int get() = onAccent.toInt()
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
                onAccent = color("onAccent", d.onAccent),
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
