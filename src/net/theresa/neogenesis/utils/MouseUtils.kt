package net.theresa.neogenesis.utils

import libsrc.lwjglx.opengl.Display

class MouseUtils
{
    fun processMouse() {
        try {
            val var0 = Registry.mc!!.entityRenderer
            val var1 = Registry.mc!!.timer.renderPartialTicks
            if (Registry.mc!!.inGameHasFocus && Display.isActive()) {
                Registry.mc!!.mouseHelper.mouseXYChange()
                if (Registry.mc!!.theWorld != null) {
                    val var2 = Registry.mc!!.gameSettings.mouseSensitivity as Double * 0.6 + 0.2
                    val var4 = var2 * var2 * var2 * 8.0
                    var var6 = Registry.mc!!.mouseHelper.deltaX * var4
                    var var8 = Registry.mc!!.mouseHelper.deltaY * var4
                    var var10: Byte = 1
                    if (Registry.mc!!.gameSettings.invertMouse) {
                        var10 = -1
                    }

                    if (Registry.mc!!.gameSettings.smoothCamera) {
                        var0.smoothCamYaw = (var0.smoothCamYaw.toDouble() + var6).toFloat()
                        var0.smoothCamPitch = (var0.smoothCamPitch.toDouble() + var8).toFloat()
                        val var11 = var1 - var0.smoothCamPartialTicks
                        var0.smoothCamPartialTicks = var1
                        var6 = (var0.smoothCamFilterX * var11).toDouble()
                        var8 = (var0.smoothCamFilterY * var11).toDouble()
                    } else {
                        var0.smoothCamYaw = 0.0f
                        var0.smoothCamPitch = 0.0f
                    }

                    Registry.mc!!.thePlayer.setAngles(var6.toFloat(), (var8 * var10.toDouble()).toFloat())
                }
            }
        } catch (var12: Exception) { }
    }
}