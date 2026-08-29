package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.events.Listener
import net.theresa.neogenesis.interfaces.modules.IToggleable
import net.minecraft.client.Minecraft


class Sneak : Listener, IToggleable
{
    override var name: String = "蹲下动画"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: Sneak
    }

    val START_HEIGHT: Float = 1.62f
    val END_HEIGHT: Float = 1.54f


    private var eyeHeight = 0f
    private var lastEyeHeight = 0f

    fun onTick() {
        if (!toggled) return
        lastEyeHeight = eyeHeight

        val player = Minecraft.getMinecraft().thePlayer
        if (player == null) {
            eyeHeight = START_HEIGHT
            return
        }

        if (player.isSneaking) {
            eyeHeight = END_HEIGHT
        } else if (eyeHeight < START_HEIGHT) {
            var delta = START_HEIGHT - eyeHeight
            delta *= 0.4.toFloat()
            eyeHeight = START_HEIGHT - delta
        }
    }

    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}