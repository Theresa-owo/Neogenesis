package net.theresa.neogenesis.gui

import net.theresa.neogenesis.ClientMain
import net.minecraft.client.Minecraft
import net.minecraft.util.ResourceLocation
import java.io.InputStream
import java.net.URL

class ResourcesManager
{
    companion object
    {
        private const val RESOURCE_DOMAIN: String = "neogenesis"
        val CLIENT_ICON_16X: ResourceLocation = ResourceLocation(RESOURCE_DOMAIN,"icons/icon_16x.png")
        val CLIENT_ICON_32X: ResourceLocation = ResourceLocation(RESOURCE_DOMAIN,"icons/icon_32x.png")
        val CLIENT_ICON_64X: ResourceLocation = ResourceLocation(RESOURCE_DOMAIN,"icons/icon_64x.png")
        val CLIENT_ICON_128X: ResourceLocation = ResourceLocation(RESOURCE_DOMAIN,"icons/icon_128x.png")
        val CLIENT_ICON_256X: ResourceLocation = ResourceLocation(RESOURCE_DOMAIN,"icons/icon_256x.png")

        @JvmStatic
        fun getResourcePath(location: ResourceLocation): String
        {
            return "${location.resourceDomain}/${location.resourcePath}"
        }

        @JvmStatic
        fun getResourceStream(location: ResourceLocation): InputStream?
        {
            val s = getResourcePath(location)
            val url = ClientMain::class.java.classLoader.getResource(s) ?: return null
            return url.openStream()
        }
    }
}