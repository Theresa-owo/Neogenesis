package net.theresa.neogenesis.modules

import net.theresa.neogenesis.interfaces.IModule
import net.theresa.neogenesis.interfaces.modules.TypedModule
import org.reflections.Reflections
import java.lang.reflect.Modifier

object ModuleFactory {
    private val moduleInstances by lazy {
        mutableListOf<IModule>().apply {
            val packageName = "net.theresa.neogenesis.modules"
            Reflections(packageName).getSubTypesOf(IModule::class.java).forEach { clazz ->
                try {
                    val instanceField = clazz.getDeclaredField("Instance")
                    if (Modifier.isStatic(instanceField.modifiers)) {
                        add(instanceField.get(null) as IModule)
                    }
                } catch (e: Exception) {
                    System.err.println("Error loading module: ${clazz.simpleName}")
                    e.printStackTrace()
                }
            }
        }
    }

    val modules = mutableListOf<TypedModule>()
    //fun getModules(): List<TypedModule> = modules
}