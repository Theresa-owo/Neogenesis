package net.theresa.neogenesis.modules.rawinput

import net.theresa.neogenesis.interfaces.modules.IToggleable
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment
import net.java.games.input.Mouse

class RawInput : IToggleable
{
    override var name: String = "原始输入"
    override var toggled: Boolean = true
    companion object
    {
        var mouse: Mouse? = null
        var dx: Double = 0.0
        var dy: Double = 0.0
        lateinit var Instance: RawInput
    }

    init {
        Instance = this
    }
}
