package net.theresa.neogenesis.utils.moduleutils

import net.theresa.neogenesis.modules.itemanimations.ItemAnimations
import net.theresa.neogenesis.modules.itemanimations.SwingSpeed
import net.theresa.neogenesis.utils.Registry
import net.minecraft.potion.Potion
import kotlin.math.round

object SwingUtil {
    var isSwingInProgress: Boolean = false
    var swingProgressInt: Int = 0
    var prevSwingProgress: Float = 0.0f
    var swingProgress: Float = 0.0f

    fun update() {
        val i = armSwingAnimationEnd
        if (isSwingInProgress) {
            ++swingProgressInt
            if (swingProgressInt >= i) {
                swingProgressInt = 0
                isSwingInProgress = false
            }
        } else {
            swingProgressInt = 0
        }
        swingProgress = swingProgressInt.toFloat() / i.toFloat()
    }

    fun tick() {
        prevSwingProgress = swingProgress
    }

    val armSwingAnimationEnd: Int
        get() {
            val speedOverride: Int = 20 - (if (ItemAnimations.Instance.toggled) round(SwingSpeed.Instance.scrollValue).toInt() else 10)
            //println(speedOverride)
            return if (speedOverride == 0) {
                if (Registry.mc!!.thePlayer.isPotionActive(Potion.digSpeed))
                    6 - (1 + Registry.mc!!.thePlayer.getActivePotionEffect(Potion.digSpeed).getAmplifier())
                else
                    (if (Registry.mc!!.thePlayer.isPotionActive(Potion.digSlowdown)) 6 + (1 +
                            Registry.mc!!.thePlayer.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) *2 else 6)
            } else {
                speedOverride
            }
        }

    fun swing() {
        if (!isSwingInProgress || swingProgressInt >= armSwingAnimationEnd / 2 || swingProgressInt < 0) {
            swingProgressInt = -1
            isSwingInProgress = true
        }
    }

    fun getSwingProgress(partialTick: Float): Float {
        var f = swingProgress - prevSwingProgress
        if (f < 0.0f) {
            ++f
        }
        return prevSwingProgress + f * partialTick
    }
}
