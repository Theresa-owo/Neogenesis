package net.theresa.neogenesis.utils.moduleutils

import net.theresa.neogenesis.modules.freelook.FreeLook
import net.theresa.neogenesis.modules.freelook.IsHoldFreeLook
import net.minecraft.client.Minecraft


class FreeLookUtil
{

    companion object
    {
        @JvmField
        var Instance: FreeLookUtil? = null
    }

    val mc: Minecraft = Minecraft.getMinecraft()

    var perspectiveToggled: Boolean = false
    var prevState: Boolean = false
    var cameraYaw: Float = 0.0f
    var cameraPitch: Float = 0.0f
    private var previousPerspective = 0

    fun onPressed(down: Boolean) {
        if (FreeLook.Instance.toggled) {
            if (down) {
                cameraYaw = mc.thePlayer.rotationYaw
                cameraPitch = mc.thePlayer.rotationPitch

                if (perspectiveToggled) {
                    resetPerspective()
                } else {
                    enterPerspective()
                }

                mc.renderGlobal.setDisplayListEntitiesDirty()
            }
        } else if (perspectiveToggled) {
            resetPerspective()
        }
    }

    private fun enterPerspective() {
        perspectiveToggled = true

        previousPerspective = mc.gameSettings.thirdPersonView
    }

    fun resetPerspective() {
        perspectiveToggled = false
        prevState = false

        mc.gameSettings.thirdPersonView = previousPerspective

        mc.renderGlobal.setDisplayListEntitiesDirty()
    }

    fun overrideMouse(): Boolean {
        if (mc.inGameHasFocus) {
            if (!perspectiveToggled) return true

            mc.mouseHelper.mouseXYChange()

            //if (FreelookConfig.yaw) handleYaw()
            //if (FreelookConfig.pitch) handlePitch()

            handleYaw()
            handlePitch()

            mc.renderGlobal.setDisplayListEntitiesDirty()
        }
        return false
    }

    private fun handleYaw() {
        val sensitivity = calculateSensitivity()
        val yaw = mc.mouseHelper.deltaX * sensitivity

        //if (FreelookConfig.invertYaw) yaw = -yaw

        cameraYaw += yaw * 0.15f
    }

    private fun handlePitch() {
        val sensitivity = calculateSensitivity()
        val pitch = mc.mouseHelper.deltaY * sensitivity

        //if (FreelookConfig.invertPitch) pitch = -pitch

        cameraPitch += pitch * 0.15f

        //if (FreelookConfig.lockPitch) cameraPitch = max(-90.0, min(cameraPitch.toDouble(), 90.0)).toFloat()
    }

    private fun calculateSensitivity(): Float {
        val sensitivity = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f
        return sensitivity * sensitivity * sensitivity * 8.0f
    }
}