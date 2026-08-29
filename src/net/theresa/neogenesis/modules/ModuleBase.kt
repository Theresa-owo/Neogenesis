package net.theresa.neogenesis.modules

import net.theresa.neogenesis.interfaces.IModule
import net.theresa.neogenesis.interfaces.modules.*
import net.theresa.neogenesis.modules.blockoverlay.BlockOverlay
import net.theresa.neogenesis.utils.JsonUtils
import net.minecraft.client.Minecraft
import net.optifine.util.Json
import kotlin.reflect.KClass

class ModuleBase {
    companion object {
        lateinit var Instance: ModuleBase
        lateinit var moduleSettings: MutableMap<String, MutableMap<String, Any>>

        fun serializeModules(modules: List<IModule>): MutableMap<String, MutableMap<String, Any>> {
            return modules.associate { module ->
                // 获取全限定类名（包名+类名）
                val className = module::class.qualifiedName ?: throw IllegalArgumentException("Module class missing qualified name")

                // 构建属性 Map
                val properties = mutableMapOf<String, Any>().apply {
                    // 添加基础属性
                    put("type", module.type.name)
                    put("name", module.name)

                    // 根据接口类型添加额外属性
                    when (module) {
                        is IToggleable -> put("toggled", module.toggled)
                        is IScrollable -> {
                            putAll(
                                mutableMapOf(
                                "minValue" to module.minValue,
                                "scrollValue" to module.scrollValue,
                                "maxValue" to module.maxValue,
                                "canDecimal" to module.canDecimal
                            )
                            )
                        }
                        is IEditable -> put("value", module.value)
                        is IChangeable -> {
                            putAll(
                                mutableMapOf(
                                "valueList" to module.valueList,
                                "selectedValue" to module.selectedValue
                            )
                            )
                        }
                    }
                    if (module is IExtendable)
                    {
                        put("childList", module.childList)
                    }
                }
                className to properties
            }.toMutableMap()
        }

        fun convertMap(
            inputMap: MutableMap<String, MutableMap<String, Any>>
        ): MutableMap<TypedModule, MutableMap<String, Any>> {
            return inputMap.mapKeys { (key, _) ->
                getTypedModuleInstance(key)
            }.toMutableMap()
        }

        private fun getTypedModuleInstance(className: String): TypedModule {
            val clazz = Class.forName(className)
            val instanceField = clazz.getField("Instance")
            return instanceField.get(null) as TypedModule
        }

        @JvmStatic
        fun debug()
        {
            //JsonUtils.saveModulesPropertiesToJson(serializeModules(ModuleFactory.modules), "all.json")
            //for (it in convertMap(serializeModules(ModuleFactory.modules))) println(it.key.name)
        }
    }
}