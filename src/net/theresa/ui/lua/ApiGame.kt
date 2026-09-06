package net.theresa.ui.lua

import net.minecraft.client.Minecraft
import net.minecraft.client.audio.SoundCategory
import net.minecraft.client.multiplayer.GuiConnecting
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.resources.I18n
import net.minecraft.client.settings.GameSettings
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
 *   game.settings_list()          -> array of setting descriptors:
 *                                    { key, label, kind="slider"|"toggle"|"cycle",
 *                                      value, min, max, step, values (cycle labels) }
 *                                    for FOV, Music/Sound volume, VSync, Smooth
 *                                    Graphics, GUI Scale and Max Framerate.
 *   game.set_option(key, value)   -> writes through to GameSettings immediately
 *                                    (setOptionFloatValue / setSoundLevel /
 *                                    setOptionValue / guiScale field), saves
 *                                    options.txt, and returns the resolved value.
 *   game.worlds()                 -> array of { name, displayName, lastPlayed,
 *                                    hardcore, cheats } from the save loader.
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

            list.set(i++, slider("fov", I18n.format("options.fov"),
                gs.getOptionFloatValue(GameSettings.Options.FOV).toDouble(), 30.0, 110.0, 1.0))
            list.set(i++, slider("music", I18n.format("options.music"),
                gs.getSoundLevel(SoundCategory.MUSIC).toDouble(), 0.0, 1.0, 0.05))
            list.set(i++, slider("sound", I18n.format("options.sounds"),
                gs.getSoundLevel(SoundCategory.MASTER).toDouble(), 0.0, 1.0, 0.05))
            list.set(i++, slider("max_fps", I18n.format("options.framerateLimit"),
                gs.getOptionFloatValue(GameSettings.Options.FRAMERATE_LIMIT).toDouble(), 30.0, 260.0, 5.0))

            val scaleLabels = LuaTable()
            scaleLabels.set(1, LuaValue.valueOf(I18n.format("options.guiScale.auto")))
            scaleLabels.set(2, LuaValue.valueOf(I18n.format("options.guiScale.small")))
            scaleLabels.set(3, LuaValue.valueOf(I18n.format("options.guiScale.normal")))
            scaleLabels.set(4, LuaValue.valueOf(I18n.format("options.guiScale.large")))
            val scale = LuaTable()
            scale.set("key", LuaValue.valueOf("gui_scale"))
            scale.set("label", LuaValue.valueOf(I18n.format("options.guiScale")))
            scale.set("kind", LuaValue.valueOf("cycle"))
            scale.set("value", LuaValue.valueOf(gs.guiScale))
            scale.set("min", LuaValue.valueOf(0.0))
            scale.set("max", LuaValue.valueOf(3.0))
            scale.set("values", scaleLabels)
            list.set(i++, scale)

            list.set(i++, toggle("vsync", I18n.format("options.vsync"),
                gs.getOptionOrdinalValue(GameSettings.Options.ENABLE_VSYNC)))
            list.set(i, toggle("smooth_graphics", I18n.format("options.graphics"),
                gs.fancyGraphics))
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] settings_list failed: $t")
        }
        return list
    }

    private fun slider(key: String, label: String, value: Double, min: Double, max: Double, step: Double): LuaTable {
        val t = LuaTable()
        t.set("key", LuaValue.valueOf(key))
        t.set("label", LuaValue.valueOf(label))
        t.set("kind", LuaValue.valueOf("slider"))
        t.set("value", LuaValue.valueOf(value))
        t.set("min", LuaValue.valueOf(min))
        t.set("max", LuaValue.valueOf(max))
        t.set("step", LuaValue.valueOf(step))
        return t
    }

    private fun toggle(key: String, label: String, value: Boolean): LuaTable {
        val t = LuaTable()
        t.set("key", LuaValue.valueOf(key))
        t.set("label", LuaValue.valueOf(label))
        t.set("kind", LuaValue.valueOf("toggle"))
        t.set("value", LuaValue.valueOf(value))
        return t
    }

    private fun setOption(keyArg: LuaValue, valueArg: LuaValue): LuaValue {
        return try {
            val key = keyArg.optjstring("")
            val mc = mc() ?: return LuaValue.FALSE
            val gs = mc.gameSettings ?: return LuaValue.FALSE
            when (key) {
                "fov" -> {
                    gs.setOptionFloatValue(GameSettings.Options.FOV, valueArg.optdouble(70.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.FOV).toDouble())
                }
                "music" -> {
                    gs.setSoundLevel(SoundCategory.MUSIC, valueArg.optdouble(1.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getSoundLevel(SoundCategory.MUSIC).toDouble())
                }
                "sound" -> {
                    gs.setSoundLevel(SoundCategory.MASTER, valueArg.optdouble(1.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getSoundLevel(SoundCategory.MASTER).toDouble())
                }
                "max_fps" -> {
                    gs.setOptionFloatValue(GameSettings.Options.FRAMERATE_LIMIT, valueArg.optdouble(120.0).toFloat())
                    gs.saveOptions()
                    LuaValue.valueOf(gs.getOptionFloatValue(GameSettings.Options.FRAMERATE_LIMIT).toDouble())
                }
                "gui_scale" -> if (valueArg.isboolean()) {
                    // boolean true -> vanilla-style cycle (+1 with wrap-around)
                    gs.setOptionValue(GameSettings.Options.GUI_SCALE, 1)
                    LuaValue.valueOf(gs.guiScale)
                } else {
                    gs.guiScale = valueArg.optint(0).coerceIn(0, 3)
                    gs.saveOptions()
                    LuaValue.valueOf(gs.guiScale)
                }
                "vsync" -> {
                    if (gs.getOptionOrdinalValue(GameSettings.Options.ENABLE_VSYNC) != valueArg.optboolean(true)) {
                        // setOptionValue flips the flag, applies vsync to Display and saves
                        gs.setOptionValue(GameSettings.Options.ENABLE_VSYNC, 1)
                    }
                    LuaValue.valueOf(gs.getOptionOrdinalValue(GameSettings.Options.ENABLE_VSYNC))
                }
                "smooth_graphics" -> {
                    if (gs.fancyGraphics != valueArg.optboolean(true)) {
                        gs.setOptionValue(GameSettings.Options.GRAPHICS, 1)
                    }
                    LuaValue.valueOf(gs.fancyGraphics)
                }
                else -> LuaValue.NIL
            }
        } catch (t: Throwable) {
            System.err.println("[NeoUI game] set_option failed: $t")
            LuaValue.FALSE
        }
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
