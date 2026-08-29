package net.theresa.neogenesis.modules.rawinput

import libsrc.lwjglx.input.Mouse
import libsrc.lwjglx.opengl.Display
import net.minecraft.util.MouseHelper


class RawMouseHelper : MouseHelper() {
    override fun grabMouseCursor() {
        Mouse.setGrabbed(true)
        this.deltaX = 0.0.toInt()
        this.deltaY = 0.0.toInt()
        RawInput.dx = 0.0
        RawInput.dy = 0.0
    }

    override fun ungrabMouseCursor() {
        Mouse.setCursorPosition(Display.getWidth() / 2, Display.getHeight() / 2)
        Mouse.setGrabbed(false)
        this.deltaX = 0.0.toInt()
        this.deltaY = 0.0.toInt()
        RawInput.dx = 0.0
        RawInput.dy = 0.0
    }

    override fun mouseXYChange() {
        this.deltaX = Mouse.getDX()
        this.deltaY = Mouse.getDY()
        RawInput.dx = 0.0
        RawInput.dy = 0.0
    }

    companion object {
        var rawInput: Boolean = false

        init {
            rawInput = RawInput.Instance.toggled
        }
    }
}
