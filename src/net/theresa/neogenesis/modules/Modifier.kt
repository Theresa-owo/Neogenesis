package net.theresa.neogenesis.modules

import net.theresa.neogenesis.config.Configuration
import net.theresa.neogenesis.interfaces.modules.*
import net.theresa.neogenesis.utils.JsonUtils

class Modifier
{
    companion object
    {
        @JvmStatic
        fun TypedModule.updateModuleValues(newValue: Any) {
            when (this) {
                is IToggleable -> {
                    require(newValue is Boolean) { "Toggleable模块需要Boolean类型参数" }
                    this.toggled = newValue
                    println(this::class.qualifiedName)
                    val innerMap = ModuleBase.moduleSettings[this::class.qualifiedName]
                    innerMap?.set("toggled", newValue)
                    ModuleBase.moduleSettings[this::class.qualifiedName ?: ""] = innerMap!!
                }
                is IScrollable -> {
                    require(newValue is Double) { "Scrollable模块需要Double类型参数" }
                    this.scrollValue = newValue
                    val innerMap = ModuleBase.moduleSettings[this::class.qualifiedName]
                    innerMap?.set("scrollValue", newValue)
                    ModuleBase.moduleSettings[this::class.qualifiedName ?: ""] = innerMap!!
                }
                is IChangeable -> {
                    require(newValue is Int) { "Changeable模块需要Int类型参数" }
                    this.selectedValue = newValue
                    val innerMap = ModuleBase.moduleSettings[this::class.qualifiedName]
                    innerMap?.set("selectedValue", newValue)
                    ModuleBase.moduleSettings[this::class.qualifiedName ?: ""] = innerMap!!
                }
                is IEditable -> {
                    require(newValue is String) { "Editable模块需要String类型参数" }
                    this.value = newValue
                    val innerMap = ModuleBase.moduleSettings[this::class.qualifiedName]
                    innerMap?.set("value", newValue)
                    ModuleBase.moduleSettings[this::class.qualifiedName ?: ""] = innerMap!!
                }
            }
            JsonUtils.saveModulesPropertiesToJson(ModuleBase.moduleSettings, Configuration.moduleConfigFile)
        }

        fun applyModuleValuesSafely(configMap: Map<TypedModule, Map<String, Any>>) {
            configMap.forEach { (module, config) ->
                try {
                    when (module) {
                        is IToggleable -> handleToggleable(module, config)
                        is IScrollable -> handleScrollable(module, config)
                        is IEditable -> handleEditable(module, config)
                        is IChangeable -> handleChangeable(module, config)
                    }
                } catch (e: Exception) {
                    println("Error applying config for ${module.name}: ${e.message}")
                }
            }
        }

        private fun handleToggleable(module: IToggleable, config: Map<String, Any>) {
            config.getBoolean("toggled")?.let { module.toggled = it }
        }

        private fun handleScrollable(module: IScrollable, config: Map<String, Any>) {
            with(module) {
                minValue = config.getDouble("minValue") ?: minValue
                scrollValue = config.getDouble("scrollValue") ?: scrollValue
                maxValue = config.getDouble("maxValue") ?: maxValue
                canDecimal = config.getBoolean("canDecimal") ?: canDecimal
            }
        }

        private fun handleEditable(module: IEditable, config: Map<String, Any>) {
            config.getString("value")?.let {
                module.value = it
            }
        }

        private fun handleChangeable(module: IChangeable, config: Map<String, Any>) {
            with(module) {
                config.getList<String>("valueList")?.let {
                    valueList = it.toMutableList()
                }
                config.getInt("selectedValue")?.let {
                    selectedValue = it.coerceIn(0 until valueList.size)
                }
            }
        }

        // 扩展函数实现安全类型转换
        private fun Map<String, Any>.getString(key: String) = this[key] as? String
        private fun Map<String, Any>.getBoolean(key: String) = this[key] as? Boolean
        private fun Map<String, Any>.getInt(key: String) = when (val value = this[key]) {
            is Int -> value
            is Double -> value.toInt()
            else -> null
        }
        private fun Map<String, Any>.getDouble(key: String) = when (val value = this[key]) {
            is Double -> value
            is Int -> value.toDouble()
            else -> null
        }
        private inline fun <reified T> Map<String, Any>.getList(key: String) =
            (this[key] as? List<*>)?.filterIsInstance<T>()
    }
}