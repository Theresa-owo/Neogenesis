package net.theresa.ui.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** Marks an object providing a Lua API module; install(globals, runtime) is
 *  called once when the Lua runtime boots (discovered via Reflections). */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class LuaPlugin(val name: String)

/**
 * Registry seam for Lua API modules. Parallel feature teams add their own
 * @LuaPlugin objects in their own files (ApiPlayer, ApiGame, ApiHud, ...) and
 * never need to touch LuaUiRuntime.
 */
object LuaApiRegistry {

    private val installers = ArrayList<(Globals, LuaUiRuntime) -> Unit>()

    fun addInstaller(installer: (Globals, LuaUiRuntime) -> Unit) {
        synchronized(installers) { installers.add(installer) }
    }

    fun installAll(globals: Globals, runtime: LuaUiRuntime) {
        // discovered annotated objects
        try {
            val reflections = org.reflections.Reflections("net.theresa.ui.lua")
            for (cls in reflections.getTypesAnnotatedWith(LuaPlugin::class.java)) {
                try {
                    val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                    val method = cls.methods.firstOrNull { it.name == "install" }
                    method?.invoke(instance, globals, runtime)
                    System.out.println("[NeoUI lua] plugin installed: " + cls.simpleName)
                } catch (t: Throwable) {
                    System.err.println("[NeoUI lua] plugin " + cls.simpleName + " failed to install: $t")
                }
            }
        } catch (t: Throwable) {
            System.err.println("[NeoUI lua] plugin scan failed: $t")
        }
        // programmatic installers
        synchronized(installers) {
            for (installer in installers) installer(globals, runtime)
        }
    }

    /** Convenience for building a module table from Kotlin. */
    fun tableOf(vararg pairs: Pair<String, LuaValue>): LuaTable {
        val t = LuaTable()
        for ((k, v) in pairs) t.set(k, v)
        return t
    }
}
