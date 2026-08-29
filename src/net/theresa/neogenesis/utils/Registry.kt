package net.theresa.neogenesis.utils

import net.theresa.neogenesis.ClientMain
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.WorldRenderer
import net.minecraft.util.BlockPos
import net.minecraft.world.World
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class Registry
{
    fun init() {
        mc = Minecraft.getMinecraft()
        tess = Tessellator.getInstance()
        render = tess!!.worldRenderer
    }

    companion object {
        var LOGGER: Logger = LogManager.getLogger(VersionManager.NAME)

        var mc: Minecraft? = null
        var tess: Tessellator? = null
        var render: WorldRenderer? = null
        var inited: Boolean = false
        lateinit var Instance: Registry


        fun eventRightClickBlock(world: World?, blockPos: BlockPos?): Boolean {
            val cancelled = false
            return !cancelled
        }

        fun eventRightClickAir(): Boolean {
            val cancelled = false
            return !cancelled
        }

        fun register(args: Array<String?>?) {
            inited = true
        }
    }
}
