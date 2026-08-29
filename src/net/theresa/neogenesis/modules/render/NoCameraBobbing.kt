package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoCameraBobbing : IToggleable
{
    override var name: String = "开启视角摇晃时，让整个屏幕不抖动"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoCameraBobbing
    }

    init {
        Instance = this
    }
}