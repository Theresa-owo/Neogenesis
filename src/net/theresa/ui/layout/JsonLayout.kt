package net.theresa.ui.layout

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.client.resources.I18n
import net.theresa.ui.NeoUI
import net.theresa.ui.scene.UiNode
import net.theresa.ui.scene.Widgets

/**
 * Loads declarative screen layouts from resource packs:
 * assets/&lt;ns&gt;/ui/screens/&lt;id&gt;.json — the moddable surface of the UI.
 *
 * Property model (all sizes dp):
 * { "type": "column", "w": 380 | "match" | "wrap", "h": 56,
 *   "anchor": [0.5, 0.5], "pivot": [0.5, 0.5], "offset": [0, 12],
 *   "spacing": 8, "padding": 16, "gravity": "center",
 *   "style": "glass" | "primary" | "ghost" | "solid", "radius": 14,
 *   "shadow": false, "text": "..." | {"i18n": "menu.singleplayer"},
 *   "textSize": 17, "textColor": "#FFF2F3F5", "onClick": "open:singleplayer",
 *   "children": [ ... ] }
 */
object JsonLayout {

    /** Registered node builders; mods can add their own widget types. */
    val widgetTypes = LinkedHashMap<String, () -> UiNode>()

    init {
        widgetTypes["box"] = { Widgets.panel("box", UiNode.STYLE_SOLID) }
        widgetTypes["panel"] = { Widgets.panel("panel", UiNode.STYLE_GLASS) }
        widgetTypes["column"] = { Widgets.column() }
        widgetTypes["row"] = { Widgets.row() }
        widgetTypes["label"] = { Widgets.label("") }
        widgetTypes["button"] = { Widgets.button("") }
        widgetTypes["spacer"] = { Widgets.spacer(0f) }
    }

    fun registerWidgetType(name: String, factory: () -> UiNode) {
        widgetTypes[name] = factory
    }

    /** Loads a screen layout from the classpath. */
    fun loadScreen(id: String): UiNode {
        val path = "/assets/neogenesis/ui/screens/$id.json"
        val stream = JsonLayout::class.java.getResourceAsStream(path)
            ?: throw IllegalStateException("layout not found: $path")
        val json = stream.use { it.readBytes().decodeToString() }
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        return build(root, NeoUI.theme)
    }

    fun build(o: JsonObject, theme: net.theresa.ui.style.Theme): UiNode {
        val typeName = o.get("type")?.asString ?: "box"
        val factory = widgetTypes[typeName]
            ?: throw IllegalArgumentException("unknown widget type '$typeName' (registered: ${widgetTypes.keys})")
        val node = factory()

        o.get("style")?.asString?.let { s ->
            node.style = when (s) {
                "glass" -> UiNode.STYLE_GLASS
                "primary" -> UiNode.STYLE_PRIMARY
                "ghost" -> UiNode.STYLE_GHOST
                else -> UiNode.STYLE_SOLID
            }
            net.theresa.ui.scene.Widgets.applyThemeStyle(node)
        }
        o.get("w")?.let { applySize(it, node, true) }
        o.get("h")?.let { applySize(it, node, false) }
        o.getAsJsonArray("anchor")?.let {
            node.anchorX = it[0].asFloat; node.anchorY = it[1].asFloat
        }
        o.getAsJsonArray("pivot")?.let {
            node.pivotX = it[0].asFloat; node.pivotY = it[1].asFloat
        }
        o.getAsJsonArray("offset")?.let {
            node.offsetX = it[0].asFloat; node.offsetY = it[1].asFloat
        }
        o.get("spacing")?.asFloat?.let { node.spacing = it }
        o.get("padding")?.asFloat?.let { node.padding = it }
        o.get("gravity")?.asString?.let {
            node.gravity = when (it) {
                "start" -> UiNode.GRAVITY_START
                "end" -> UiNode.GRAVITY_END
                else -> UiNode.GRAVITY_CENTER
            }
        }
        o.get("radius")?.asFloat?.let { node.radius = it }
        o.get("shadow")?.asBoolean?.let { node.shadow = it }
        o.get("textSize")?.asFloat?.let { node.textSize = it }
        o.get("textColor")?.asString?.let { node.textColor = parseColor(it) }
        o.get("text")?.let { node.text = resolveText(it) }
        o.get("onClick")?.asString?.let { action -> node.onClick = { NeoUI.handleAction(action) } }

        o.getAsJsonArray("children")?.forEach { child ->
            node.add(build(child.asJsonObject, theme))
        }
        return node
    }

    private fun applySize(e: JsonElement, node: UiNode, horizontal: Boolean) {
        when {
            e.isJsonPrimitive && e.asJsonPrimitive.isString -> {
                val mode = when (e.asString) {
                    "match" -> UiNode.SIZE_MATCH
                    "wrap" -> UiNode.SIZE_WRAP
                    else -> UiNode.SIZE_FIXED
                }
                if (horizontal) node.widthMode = mode else node.heightMode = mode
            }
            else -> {
                val v = e.asFloat
                if (horizontal) { node.dpWidth = v; node.widthMode = UiNode.SIZE_FIXED }
                else { node.dpHeight = v; node.heightMode = UiNode.SIZE_FIXED }
            }
        }
    }

    /** "text" is either a literal string or {"i18n": "key"} for localization. */
    private fun resolveText(e: JsonElement): String {
        if (e.isJsonPrimitive) return e.asString
        val i18n = e.asJsonObject.get("i18n")?.asString
        return if (i18n != null) I18n.format(i18n) else ""
    }

    fun parseColor(hex: String): Int {
        var s = hex.removePrefix("#")
        val argb = when (s.length) {
            6 -> 0xFF000000L or s.toLong(16)
            8 -> s.toLong(16)
            else -> 0xFFFFFFFF
        }
        return argb.toInt()
    }
}
