package net.theresa.neogenesis.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.theresa.neogenesis.config.Configuration
import java.io.File


object JsonUtils
{
    fun saveModulesPropertiesToJson(modulesProperties: MutableMap<String, MutableMap<String, Any>>, file: File) {

        val gson = Gson()
        val json = gson.toJson(modulesProperties)

        file.writeText(json)
    }

    fun readModuleConfig(): MutableMap<String, MutableMap<String, Any>> {
        return Configuration.moduleConfigFile.reader().use { reader ->
            val gson = Gson()
            val type = object : TypeToken<MutableMap<String, MutableMap<String, Any>>>() {}.type
            gson.fromJson(reader, type)
        }
    }
}