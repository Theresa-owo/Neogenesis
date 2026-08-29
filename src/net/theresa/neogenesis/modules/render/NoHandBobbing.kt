package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoHandBobbing : IToggleable
{
    override var name: String = "开启视角摇晃时，让你的手不抖动"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoHandBobbing
    }

    init {
        Instance = this
    }
}