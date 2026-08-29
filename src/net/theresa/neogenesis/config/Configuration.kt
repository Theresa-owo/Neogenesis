package net.theresa.neogenesis.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.theresa.neogenesis.modules.Modifier
import net.theresa.neogenesis.modules.ModuleBase
import net.theresa.neogenesis.modules.ModuleFactory
import net.theresa.neogenesis.utils.JsonUtils
import java.io.File

class Configuration
{
    companion object
    {
        const val CONFIG_PATH = "neogenesis/configs/"
        const val MODULE_CONFIG_NAME = "module_config.json"
        @JvmField
        val configPath = File(CONFIG_PATH)
        @JvmField
        val moduleConfigFile = File(CONFIG_PATH + MODULE_CONFIG_NAME)
        @JvmStatic
        fun initConfigs()
        {
            if (!configPath.exists()) configPath.mkdirs()
            if (!moduleConfigFile.exists())
            {
                moduleConfigFile.createNewFile()
                writeOriginModuleConfig()
            }
            loadConfig()
        }

        @JvmStatic
        private fun loadConfig()
        {
            Modifier.applyModuleValuesSafely(ModuleBase.convertMap(JsonUtils.readModuleConfig()))
            ModuleBase.moduleSettings = JsonUtils.readModuleConfig()
        }

        @JvmStatic
        fun loadConfigToCurrentModules()
        {
            loadConfig()
        }

        @JvmStatic
        fun updateModuleSettings()
        {
            JsonUtils.saveModulesPropertiesToJson(ModuleBase.serializeModules(ModuleFactory.modules), moduleConfigFile)
        }

        @JvmStatic
        fun writeOriginModuleConfig()
        {
            JsonUtils.saveModulesPropertiesToJson(ModuleBase.serializeModules(ModuleFactory.modules), moduleConfigFile)
        }
    }
}