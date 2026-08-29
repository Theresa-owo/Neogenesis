package net.theresa.neogenesis.utils.moduleutils

import net.theresa.neogenesis.modules.zoom.ScrollZoom
import net.theresa.neogenesis.modules.zoom.SmoothZoomSpeed
import net.minecraft.util.MathHelper
import kotlin.math.pow

object ZoomUtil {
    var isZooming: Boolean = false
    var currentModifier: Double = 1.0
    var toModifier: Double = 1.0
    var lastTime: Long = 0
    var scrollCount: Int = 0
    var zoomModifier: Double = 1.0

    fun calcSmoothZoom() {
        val time = System.currentTimeMillis()
        val speed: Double = SmoothZoomSpeed.Instance.scrollValue
        if (lastTime == 0L) {
            lastTime = time
        }
        if (speed == 0.0) {
            currentModifier = toModifier
        } else {
            var percent = 1.0 - 0.5.pow((time - lastTime) * speed / 50.0)
            percent = MathHelper.clamp_double(percent, 0.0, 1.0)
            currentModifier = currentModifier + (toModifier - currentModifier) * percent
        }
        lastTime = time
    }

    fun getZoomModifier(zoomModifier: Double, isZoomMode: Boolean): Double {
        isZooming = isZoomMode
        ZoomUtil.zoomModifier = zoomModifier
        if (!isZooming) {
            scrollCount = 0
            toModifier = 1.0
        } else {
            scroll(0L)
        }
        calcSmoothZoom()
        currentModifier = MathHelper.clamp_double(currentModifier, 0.01, 1.0)
        return currentModifier
    }

    fun scroll(scroll: Long): Boolean {
        val scrollSpeed: Double = ScrollZoom.Instance.scrollValue
        if (scrollSpeed != 1.0 && isZooming) {
            if (scroll > 0 && toModifier > 0.01) {
                --scrollCount
            } else if (scroll < 0 && toModifier < 1.0) {
                ++scrollCount
            }
            toModifier = zoomModifier * scrollSpeed.pow(scrollCount.toDouble())
            toModifier = MathHelper.clamp_double(toModifier, 0.01, 1.0)
            return false
        } else {
            toModifier = 1.0
        }
        return true
    }
}
