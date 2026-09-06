package net.theresa.ui.lua

import net.minecraft.client.Minecraft
import net.minecraft.client.audio.SoundCategory
import net.minecraft.client.multiplayer.GuiConnecting
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.resources.I18n
import net.minecraft.client.settings.GameSettings
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.world.WorldSettings
import net.minecraft.world.WorldType
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `game` Lua module: bridge from NeoUI screens to vanilla client state
 * (game settings engine, local worlds, saved server list).
 *
 * Exposed to Lua as the global `game` table:
 *   game.settings_list()          -> array of setting descriptors, full vanilla
 *                                    options coverage in five categories:
 *                                    { key, category, label,
 *                                      kind="slider"|"toggle"|"cycle",
 *                                      value, min, max, step, values (cycle labels) }
 *                                    game:   fov, gui_scale, max_fps, vsync,
 *                                            smooth_graphics, view_bobbing,
 *                                            particles, pause_on_lost_focus,
 *                                            reduced_debug
 *                                    video:  render_distance, mipmap, brightness,
 *                                            anaglyph, fbo, entity_shadows,
 *                                            clouds, vbo, fullscreen
 *                                    sound:  vol_master, vol_music, vol_records,
 *                                            vol_weather, vol_blocks, vol_hostile,
 *                                            vol_animals, vol_players, vol_ambient
 *                                    chat:   chat_visibility, chat_opacity,
 *                                            chat_scale, chat_width,
 *                                            chat_height_focused,
 *                                            chat_height_unfocused, chat_colors,
 *                                            chat_links, chat_links_prompt
 *                                    controls: invert_mouse, mouse_sensitivity,
 *                                            touchscreen, force_unicode_font
 *                                    The three chat geometry sliders speak PIXELS
 *                                    (the engine fields are normalized 0..1): the
 *                                    ranges mirror GuiNewChat.calculateChatbox* —
 *                                    width 40..320 px, heights 20..180 px.
 *                                    max_fps: value 0 means "Unlimited" (the engine
 *                                    stores that state as cap 260 + vsync on and
 *                                    getOptionFloatValue maps it back to 0).
 *   game.set_option(key, value)   -> writes through to GameSettings immediately
 *                                    (setOptionFloatValue / setSoundLevel /
 *                                    setOptionValue flip-or-delta / direct plain
 *                                    fields), saves options.txt, and returns the
 *                                    resolved value (numbers; toggles return 0/1).
 *   game.launch_world(name)       -> launchIntegratedServer(name, name, null)
 *                                    after verifying the save exists.
 *   game.create_world(name)       -> verifies the name is free against the
 *                                    save list, then launchIntegratedServer
 *                                    (name, name, WorldSettings(randomSeed,
 *                                    SURVIVAL, mapFeatures=true, hardcore=
 *                                    false, WorldType.DEFAULT)); cheats off.
 *   game.servers()                -> array of { index, name, ip, motd } from
 *                                    servers.dat (ServerList loads on construct).
 *   game.connect_server(index)    -> displayGuiScreen(GuiConnecting(currentScreen,
 *                                    mc, data)) for a 0-based index into the list.
 *   game.delete_world(name)       -> deleteWorldDirectory(name); returns success.
 *
 * Every entry point null-guards the Minecraft singleton/gameSettings and never
 * throws into the Lua runtime (errors are logged and reported as nil/false/{}).
 * All calls run on the client thread — Lua executes inside NeoUI tick/render.
 */
@LuaPlugin(name = "game")
class ApiGame {

    fun install(globals: Globals, runtime: LuaUiRuntime) {
        val api = LuaValue.tableOf()
        api.set("settings_list", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue = settingsList()
        })
        api.set("set_option", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue = setOption(args.arg1(), args.arg(2))
        })
        api.set("worlds", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue = worlds()
        })
        api.set("launch_world", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue = launchWorld(args.arg1())
        })
        api.set("create_world", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue = createWorld(args.arg1())
        })
        api.set("servers", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue = servers()
        })
        api.set("connect_server", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue = connectServer(args.arg1())
        })
        api.set("delete_world", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue = deleteWorld(args.arg1())
        })
        globals.set("game", api)
    }

    // ------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------

    private fun settingsList(): LuaValue {
        val list = LuaTable()
        try {
            val mc = mc() ?: return list
            val gs = mc.gameSettings ?: return list
            var i = 1

            fun add(t: LuaTable) { list.set(i++, t) }

            // ---- game ----
            add(slider("fov", "game", I18n.format("options.fov"),
                gs.getOptionFloatValue(GameSettings.Options.FOV).toDouble(), 30.0, 110.0, 1.0))
            add(cycle("gui_scale", "game", I18n.format("options.guiScale"), gs.guiScale.toDouble(), 0.0, 3.0,
                arrayOf("options.guiScale.auto", "options.guiScale.small", "options.guiScale.normal",
                    "options.guiScale.large")))
            // 0 is the canonical "Unlimited": the engine stores cap 260 + vsync
            // and getOptionFloatValue maps that state back to 0 (getOptionFloatValueOF)
            add(slider("max_fps", "game", I18n.format("options.framerateLimit"),
                gs.getOptionFloatValue(GameSettings.Options.FRAMERATE_LIMIT).toDouble(), 0.0, 260.0, 5.0))
            add(toggle("vsync", "game", I18n.format("options.vsync"),
                gs.getOptionOrdinalValue(GameSettings.Options.ENABLE_VSYNC)))
            add(toggle("smooth_graphics", "game", I18n.format("options.graphics"), gs.fancyGraphics))
            add(toggle("view_bobbing", "game", I18n.format("options.viewBobbing"),
                gs.getOptionOrdinalValue(GameSettings.Options.VIEW_BOBBING)))
            add(cycle("particles", "game", I18n.format("options.particles"), gs.particleSetting.toDouble(), 0.0, 2.0,
                arrayOf("options.particles.all", "options.particles.decreased", "options.particles.minimal")))
            add(toggle("pause_on_lost_focus", "game", "Pause on Lost Focus", gs.pauseOnLostFocus))
            add(toggle("reduced_debug", "game", I18n.format("options.reducedDebugInfo"),
                gs.getOptionOrdinalValue(GameSettings.Options.REDUCED_DEBUG_INFO)))

            // ---- video ----
            add(slider("render_distance", "video", I18n.format("options.renderDistance"),
                gs.getOptionFloatValue(GameSettings.Options.RENDER_DISTANCE).toDouble(), 2.0, 16.0, 1.0))
            add(slider("mipmap", "video", I18n.format("options.mipmapLevels"),
                gs.getOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS).toDouble(), 0.0, 4.0, 1.0))
            add(slider("brightness", "video", I18n.format("options.gamma"),
                gs.getOptionFloatValue(GameSettings.Options.GAMMA).toDouble(), 0.0, 1.0, 0.05))
            add(toggle("anaglyph", "video", I18n.format("options.anaglyph"),
                gs.getOptionOrdinalValue(GameSettings.Options.ANAGLYPH)))
            add(toggle("fbo", "video", I18n.format("options.fboEnable"),
                gs.getOptionOrdinalValue(GameSettings.Options.FBO_ENABLE)))
            add(toggle("entity_shadows", "video", I18n.format("options.entityShadows"),
                gs.getOptionOrdinalValue(GameSettings.Options.ENTITY_SHADOWS)))
            add(cycle("clouds", "video", I18n.format("options.renderClouds"), gs.clouds.toDouble(), 0.0, 2.0,
                arrayOf("options.off", "options.graphics.fast", "options.graphics.fancy")))
            add(toggle("vbo", "video", I18n.format("options.vbo"),
                gs.getOptionOrdinalValue(GameSettings.Options.USE_VBO)))
            add(toggle("fullscreen", "video", I18n.format("options.fullscreen"),
                gs.getOptionOrdinalValue(GameSettings.Options.USE_FULLSCREEN)))

            // ---- sound (1.8.9 GUI keys: soundCategory.<categoryName>) ----
            add(volume("vol_master", "sound", I18n.format("soundCategory.master"), SoundCategory.MASTER, gs))
            add(volume("vol_music", "sound", I18n.format("soundCategory.music"), SoundCategory.MUSIC, gs))
            add(volume("vol_records", "sound", I18n.format("soundCategory.record"), SoundCategory.RECORDS, gs))
            add(volume("vol_weather", "sound", I18n.format("soundCategory.weather"), SoundCategory.WEATHER, gs))
            add(volume("vol_blocks", "sound", I18n.format("soundCategory.block"), SoundCategory.BLOCKS, gs))
            add(volume("vol_hostile", "sound", I18n.format("soundCategory.hostile"), SoundCategory.MOBS, gs))
            add(volume("vol_animals", "sound", I18n.format("soundCategory.neutral"), SoundCategory.ANIMALS, gs))
            add(volume("vol_players", "sound", I18n.format("soundCategory.player"), SoundCategory.PLAYERS, gs))
            add(volume("vol_ambient", "sound", I18n.format("soundCategory.ambient"), SoundCategory.AMBIENT, gs))

            // ---- chat ----
            val visLabels = Array(3) { idx ->
                EntityPlayer.EnumChatVisibility.values().firstOrNull { it.chatVisibility == idx }?.resourceKey
                    ?: "options.chat.visibility.full"
            }
            add(cycle("chat_visibility", "chat", I18n.format("options.chat.visibility"),
                gs.chatVisibility.chatVisibility.toDouble(), 0.0, 2.0, visLabels))
            // geometry sliders speak PIXELS; engine fields are normalized 0..1
            // (GuiNewChat: width px = scale*280+40, height px = scale*160+20)
            add(slider("chat_opacity", "chat", I18n.format("options.chat.opacity"),
                gs.getOptionFloatValue(GameSettings.Options.CHAT_OPACITY).toDouble(), 0.0, 1.0, 0.05))
            add(slider("chat_scale", "chat", I18n.format("options.chat.scale"),
                gs.getOptionFloatValue(GameSettings.Options.CHAT_SCALE).toDouble(), 0.0, 1.0, 0.05))
            add(slider("chat_width", "chat", I18n.format("options.chat.width"),
                40.0 + gs.chatWidth * 280.0, 40.0, 320.0, 1.0))
            add(slider("chat_height_focused", "chat", I18n.format("options.chat.height.focused"),
                20.0 + gs.chatHeightFocused * 160.0, 20.0, 180.0, 1.0))
            add(slider("chat_height_unfocused", "chat", I18n.format("options.chat.height.unfocused"),
                20.0 + gs.chatHeightUnfocused * 160.0, 20.0, 180.0, 1.0))
            add(toggle("chat_colors", "chat", I18n.format("options.chat.color"),
                gs.getOptionOrdinalValue(GameSettings.Options.CHAT_COLOR)))
            add(toggle("chat_links", "chat", I18n.format("options.chat.links"),
                gs.getOptionOrdinalValue(GameSettings.Options.CHAT_LINKS)))
            add(toggle("chat_links_prompt", "chat", I18n.format("options.chat.links.prompt"),
                gs.getOptionOrdinalValue(GameSettings.Options.CHAT_LINKS_PROMPT)))

            // ---- controls ----
            add(toggle("invert_mouse", "controls", I18n.format("options.invertMouse"),
                gs.getOptionOrdinalValue(GameSettings.Options.INVERT_MOUSE)))
            add(slider("mouse_sensitivity", "controls", I18n.format("options.sensitivity"),
                gs.getOptionFloatValue(GameSettings.Options.SENSITIVITY).toDouble(), 0.0, 1.0, 0.05))
            add(toggle("touchscreen", "controls", I18n.format("options.touchscreen"),
                gs.getOptionOrdinalValue(GameSettings.Options.TOUCHSCREEN)))
            add(toggle("force_unicode_font", "controls", I18n.format("options.forceUnicodeFont"),
                gs.getOptionOrdinalValue(GameSettings.Options.FORCE_UNICODE_FONT)))
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] settings_list failed: $t")
        }
        return list
    }

    private fun slider(key: String, category: String, label: String, value: Double,
                       min: Double, max: Double, step: Double): LuaTable {
        val t = LuaTable()
        t.set("key", LuaValue.valueOf(key))
        t.set("category", LuaValue.valueOf(category))
        t.set("label", LuaValue.valueOf(label))
        t.set("kind", LuaValue.valueOf("slider"))
        t.set("value", LuaValue.valueOf(value))
        t.set("min", LuaValue.valueOf(min))
        t.set("max", LuaValue.valueOf(max))
        t.set("step", LuaValue.valueOf(step))
        return t
    }

    private fun toggle(key: String, category: String, label: String, value: Boolean): LuaTable {
        val t = LuaTable()
        t.set("key", LuaValue.valueOf(key))
        t.set("category", LuaValue.valueOf(category))
        t.set("label", LuaValue.valueOf(label))
        t.set("kind", LuaValue.valueOf("toggle"))
        t.set("value", LuaValue.valueOf(if (value) 1.0 else 0.0))
        return t
    }

    private fun cycle(key: String, category: String, label: String, value: Double,
                      min: Double, max: Double, labelKeys: Array<String>): LuaTable {
        val t = LuaTable()
        t.set("key", LuaValue.valueOf(key))
        t.set("category", LuaValue.valueOf(category))
        t.set("label", LuaValue.valueOf(label))
        t.set("kind", LuaValue.valueOf("cycle"))
        t.set("value", LuaValue.valueOf(value))
        t.set("min", LuaValue.valueOf(min))
        t.set("max", LuaValue.valueOf(max))
        val values = LuaTable()
        for ((idx, k) in labelKeys.withIndex()) {
            values.set(idx + 1, LuaValue.valueOf(I18n.format(k)))
        }
        t.set("values", values)
        return t
    }

    private fun volume(key: String, category: String, label: String,
                       cat: SoundCategory, gs: GameSettings): LuaTable =
        slider(key, category, label, gs.getSoundLevel(cat).toDouble(), 0.0, 1.0, 0.05)

    /** Enum-backed boolean: flip with vanilla's setOptionValue(1) only when the
     *  current state differs (setOptionValue toggles and applies side effects). */
    private fun setEnumBool(gs: GameSettings, option: GameSettings.Options, want: Boolean) {
        if (gs.getOptionOrdinalValue(option) != want) {
            gs.setOptionValue(option, 1)
        }
    }

    /** Plain-field boolean: assign + save (vanilla has no enum entry to route through). */
    private fun setPlainBool(gs: GameSettings, want: Boolean): LuaValue {
        gs.pauseOnLostFocus = want
        gs.saveOptions()
        return LuaValue.valueOf(if (gs.pauseOnLostFocus) 1.0 else 0.0)
    }

    private fun setOption(keyArg: LuaValue, valueArg: LuaValue): LuaValue {
        return try {
            val key = keyArg.optjstring("")
            val mc = mc() ?: return LuaValue.FALSE
            val gs = mc.gameSettings ?: return LuaValue.FALSE
            val wantBool = if (valueArg.isboolean()) valueArg.optboolean(true)
                           else valueArg.optdouble(0.0) != 0.0
            when (key) {
                // ---- game ----
                "fov" -> {
                    gs.setOptionFloatValue(GameSettings.Options.FOV, valueArg.optdouble(70.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.FOV).toDouble())
                }
                "max_fps" -> {
                    val v = valueArg.optdouble(120.0).coerceIn(0.0, 260.0).toFloat()
                    gs.setOptionFloatValue(GameSettings.Options.FRAMERATE_LIMIT, v)
                    gs.saveOptions()
                    // 0 = Unlimited (260 cap + vsync), per getOptionFloatValueOF
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.FRAMERATE_LIMIT).toDouble())
                }
                "gui_scale" -> if (valueArg.isboolean()) {
                    // boolean true -> vanilla-style cycle (+1 with wrap-around)
                    gs.setOptionValue(GameSettings.Options.GUI_SCALE, 1)
                    LuaValue.valueOf(gs.guiScale.toDouble())
                } else {
                    gs.guiScale = valueArg.optint(0).coerceIn(0, 3)
                    gs.saveOptions()
                    LuaValue.valueOf(gs.guiScale.toDouble())
                }
                "particles" -> {
                    gs.particleSetting = valueArg.optint(0).coerceIn(0, 2)
                    gs.saveOptions()
                    LuaValue.valueOf(gs.particleSetting.toDouble())
                }
                "vsync" -> {
                    setEnumBool(gs, GameSettings.Options.ENABLE_VSYNC, wantBool)
                    LuaValue.valueOf(if (gs.getOptionOrdinalValue(GameSettings.Options.ENABLE_VSYNC)) 1.0 else 0.0)
                }
                "smooth_graphics" -> {
                    setEnumBool(gs, GameSettings.Options.GRAPHICS, wantBool)
                    LuaValue.valueOf(if (gs.fancyGraphics) 1.0 else 0.0)
                }
                "view_bobbing" -> {
                    setEnumBool(gs, GameSettings.Options.VIEW_BOBBING, wantBool)
                    LuaValue.valueOf(if (gs.viewBobbing) 1.0 else 0.0)
                }
                "pause_on_lost_focus" -> setPlainBool(gs, wantBool)
                "reduced_debug" -> {
                    setEnumBool(gs, GameSettings.Options.REDUCED_DEBUG_INFO, wantBool)
                    LuaValue.valueOf(if (gs.reducedDebugInfo) 1.0 else 0.0)
                }

                // ---- video ----
                "render_distance" -> {
                    gs.setOptionFloatValue(GameSettings.Options.RENDER_DISTANCE, valueArg.optdouble(8.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.RENDER_DISTANCE).toDouble())
                }
                "mipmap" -> {
                    gs.setOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS, valueArg.optdouble(4.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS).toDouble())
                }
                "brightness" -> {
                    gs.setOptionFloatValue(GameSettings.Options.GAMMA,
                        valueArg.optdouble(0.0).coerceIn(0.0, 1.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.GAMMA).toDouble())
                }
                "anaglyph" -> {
                    setEnumBool(gs, GameSettings.Options.ANAGLYPH, wantBool)
                    LuaValue.valueOf(if (gs.anaglyph) 1.0 else 0.0)
                }
                "fbo" -> {
                    setEnumBool(gs, GameSettings.Options.FBO_ENABLE, wantBool)
                    LuaValue.valueOf(if (gs.fboEnable) 1.0 else 0.0)
                }
                "entity_shadows" -> {
                    setEnumBool(gs, GameSettings.Options.ENTITY_SHADOWS, wantBool)
                    LuaValue.valueOf(if (gs.entityShadows) 1.0 else 0.0)
                }
                "clouds" -> {
                    gs.clouds = valueArg.optint(2).coerceIn(0, 2)
                    gs.saveOptions()
                    LuaValue.valueOf(gs.clouds.toDouble())
                }
                "vbo" -> {
                    setEnumBool(gs, GameSettings.Options.USE_VBO, wantBool)
                    LuaValue.valueOf(if (gs.useVbo) 1.0 else 0.0)
                }
                "fullscreen" -> {
                    // setOptionValue flips fullScreen AND calls mc.toggleFullscreen()
                    // when the display is out of sync (vanilla GuiOptions behavior)
                    setEnumBool(gs, GameSettings.Options.USE_FULLSCREEN, wantBool)
                    LuaValue.valueOf(if (gs.getOptionOrdinalValue(GameSettings.Options.USE_FULLSCREEN)) 1.0 else 0.0)
                }

                // ---- sound ----
                "vol_master" -> setVolume(gs, SoundCategory.MASTER, valueArg)
                "vol_music" -> setVolume(gs, SoundCategory.MUSIC, valueArg)
                "vol_records" -> setVolume(gs, SoundCategory.RECORDS, valueArg)
                "vol_weather" -> setVolume(gs, SoundCategory.WEATHER, valueArg)
                "vol_blocks" -> setVolume(gs, SoundCategory.BLOCKS, valueArg)
                "vol_hostile" -> setVolume(gs, SoundCategory.MOBS, valueArg)
                "vol_animals" -> setVolume(gs, SoundCategory.ANIMALS, valueArg)
                "vol_players" -> setVolume(gs, SoundCategory.PLAYERS, valueArg)
                "vol_ambient" -> setVolume(gs, SoundCategory.AMBIENT, valueArg)

                // ---- chat ----
                "chat_visibility" -> {
                    val target = valueArg.optint(0).coerceIn(0, 2)
                    val cur = gs.chatVisibility.chatVisibility
                    // setOptionValue applies (cur + value) % 3 on the enum; a
                    // computed delta turns the relative cycle into an absolute set
                    val delta = ((target - cur) % 3 + 3) % 3
                    if (delta != 0) gs.setOptionValue(GameSettings.Options.CHAT_VISIBILITY, delta)
                    LuaValue.valueOf(gs.chatVisibility.chatVisibility.toDouble())
                }
                "chat_opacity" -> {
                    gs.setOptionFloatValue(GameSettings.Options.CHAT_OPACITY,
                        valueArg.optdouble(1.0).coerceIn(0.0, 1.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.CHAT_OPACITY).toDouble())
                }
                "chat_scale" -> {
                    gs.setOptionFloatValue(GameSettings.Options.CHAT_SCALE,
                        valueArg.optdouble(1.0).coerceIn(0.0, 1.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.CHAT_SCALE).toDouble())
                }
                "chat_width" -> {
                    val norm = (valueArg.optdouble(280.0).coerceIn(40.0, 320.0) - 40.0) / 280.0
                    gs.setOptionFloatValue(GameSettings.Options.CHAT_WIDTH, norm.toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(40.0 + gs.chatWidth * 280.0)
                }
                "chat_height_focused" -> {
                    val norm = (valueArg.optdouble(100.0).coerceIn(20.0, 180.0) - 20.0) / 160.0
                    gs.setOptionFloatValue(GameSettings.Options.CHAT_HEIGHT_FOCUSED, norm.toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(20.0 + gs.chatHeightFocused * 160.0)
                }
                "chat_height_unfocused" -> {
                    val norm = (valueArg.optdouble(90.0).coerceIn(20.0, 180.0) - 20.0) / 160.0
                    gs.setOptionFloatValue(GameSettings.Options.CHAT_HEIGHT_UNFOCUSED, norm.toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(20.0 + gs.chatHeightUnfocused * 160.0)
                }
                "chat_colors" -> {
                    setEnumBool(gs, GameSettings.Options.CHAT_COLOR, wantBool)
                    LuaValue.valueOf(if (gs.chatColours) 1.0 else 0.0)
                }
                "chat_links" -> {
                    setEnumBool(gs, GameSettings.Options.CHAT_LINKS, wantBool)
                    LuaValue.valueOf(if (gs.chatLinks) 1.0 else 0.0)
                }
                "chat_links_prompt" -> {
                    setEnumBool(gs, GameSettings.Options.CHAT_LINKS_PROMPT, wantBool)
                    LuaValue.valueOf(if (gs.chatLinksPrompt) 1.0 else 0.0)
                }

                // ---- controls ----
                "invert_mouse" -> {
                    setEnumBool(gs, GameSettings.Options.INVERT_MOUSE, wantBool)
                    LuaValue.valueOf(if (gs.invertMouse) 1.0 else 0.0)
                }
                "mouse_sensitivity" -> {
                    gs.setOptionFloatValue(GameSettings.Options.SENSITIVITY,
                        valueArg.optdouble(0.5).coerceIn(0.0, 1.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.SENSITIVITY).toDouble())
                }
                "touchscreen" -> {
                    setEnumBool(gs, GameSettings.Options.TOUCHSCREEN, wantBool)
                    LuaValue.valueOf(if (gs.touchscreen) 1.0 else 0.0)
                }
                "force_unicode_font" -> {
                    setEnumBool(gs, GameSettings.Options.FORCE_UNICODE_FONT, wantBool)
                    LuaValue.valueOf(if (gs.forceUnicodeFont) 1.0 else 0.0)
                }
                else -> LuaValue.NIL
            }
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] set_option failed: $t")
            LuaValue.FALSE
        }
    }

    private fun setVolume(gs: GameSettings, cat: SoundCategory, valueArg: LuaValue): LuaValue {
        val v = valueArg.optdouble(1.0).coerceIn(0.0, 1.0).toFloat()
        gs.setSoundLevel(cat, v)
        gs.saveOptions()
        return LuaValue.valueOf(gs.getSoundLevel(cat).toDouble())
    }

    // ------------------------------------------------------------------
    // worlds
    // ------------------------------------------------------------------

    private fun worlds(): LuaValue {
        val list = LuaTable()
        try {
            val mc = mc() ?: return list
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
            var i = 1
            for (s in mc.getSaveLoader().getSaveList()) {
                val t = LuaTable()
                t.set("name", LuaValue.valueOf(s.getFileName() ?: ""))
                t.set("displayName", LuaValue.valueOf(s.getDisplayName() ?: ""))
                t.set("lastPlayed", LuaValue.valueOf(fmt.format(Date(s.getLastTimePlayed()))))
                t.set("hardcore", LuaValue.valueOf(s.isHardcoreModeEnabled()))
                t.set("cheats", LuaValue.valueOf(s.getCheatsEnabled()))
                list.set(i, t)
                i++
            }
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] worlds failed: $t")
        }
        return list
    }

    private fun launchWorld(nameArg: LuaValue): LuaValue {
        return try {
            val name = nameArg.optjstring("")
            if (name.isEmpty()) return LuaValue.FALSE
            val mc = mc() ?: return LuaValue.FALSE
            var found = false
            for (s in mc.getSaveLoader().getSaveList()) {
                if (s.getFileName() == name) {
                    found = true
                    break
                }
            }
            if (!found) {
                System.err.println("[NeoUI game] launch_world: no such save '$name'")
                return LuaValue.FALSE
            }
            mc.launchIntegratedServer(name, name, null as WorldSettings?)
            LuaValue.TRUE
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] launch_world failed: $t")
            LuaValue.FALSE
        }
    }

    /**
     * create_world(name): generate a fresh save and launch it. The exact
     * 1.8.9 ctor is WorldSettings(seed, gameType, enableMapFeatures,
     * hardcoreMode, worldTypeIn) — survival, structures on, hardcore and
     * cheats off, random seed. Refuses taken names so Lua's "next free
     * World_<n>" guess can never clobber an existing save.
     */
    private fun createWorld(nameArg: LuaValue): LuaValue {
        return try {
            val name = nameArg.optjstring("")
            if (name.isEmpty()) return LuaValue.FALSE
            val mc = mc() ?: return LuaValue.FALSE
            for (s in mc.getSaveLoader().getSaveList()) {
                if (s.getFileName() == name) {
                    System.err.println("[NeoUI game] create_world: name already taken '$name'")
                    return LuaValue.FALSE
                }
            }
            val settings = WorldSettings(
                java.util.Random().nextLong(),
                WorldSettings.GameType.SURVIVAL,
                true,   // enableMapFeatures (villages, strongholds, ...)
                false,  // hardcoreMode
                WorldType.DEFAULT
            )
            mc.launchIntegratedServer(name, name, settings)
            LuaValue.TRUE
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] create_world failed: $t")
            LuaValue.FALSE
        }
    }

    private fun deleteWorld(nameArg: LuaValue): LuaValue {
        return try {
            val name = nameArg.optjstring("")
            if (name.isEmpty()) return LuaValue.FALSE
            val mc = mc() ?: return LuaValue.FALSE
            LuaValue.valueOf(mc.getSaveLoader().deleteWorldDirectory(name))
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] delete_world failed: $t")
            LuaValue.FALSE
        }
    }

    // ------------------------------------------------------------------
    // servers
    // ------------------------------------------------------------------

    private fun servers(): LuaValue {
        val list = LuaTable()
        try {
            val mc = mc() ?: return list
            // ServerList's constructor loads servers.dat
            val sl = ServerList(mc)
            var i = 1
            for (idx in 0 until sl.countServers()) {
                val d = sl.getServerData(idx)
                val t = LuaTable()
                t.set("index", LuaValue.valueOf(idx))
                t.set("name", LuaValue.valueOf(d.serverName ?: ""))
                t.set("ip", LuaValue.valueOf(d.serverIP ?: ""))
                t.set("motd", LuaValue.valueOf(d.serverMOTD ?: ""))
                list.set(i, t)
                i++
            }
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] servers failed: $t")
        }
        return list
    }

    private fun connectServer(indexArg: LuaValue): LuaValue {
        return try {
            val idx = indexArg.toint()
            val mc = mc() ?: return LuaValue.FALSE
            val sl = ServerList(mc)
            if (idx < 0 || idx >= sl.countServers()) return LuaValue.FALSE
            val data = sl.getServerData(idx)
            // parent screen may be null (Vulkan mode has no vanilla screen up);
            // GuiConnecting spawns its own connector thread and shows
            // GuiDisconnected(parent) on failure
            mc.displayGuiScreen(GuiConnecting(mc.currentScreen, mc, data))
            LuaValue.TRUE
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] connect_server failed: $t")
            LuaValue.FALSE
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun mc(): Minecraft? = Minecraft.getMinecraft()
}
