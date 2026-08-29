package net.theresa.neogenesis.modules.zoom

import net.theresa.neogenesis.interfaces.modules.IToggleable

class SmoothZoom : IToggleable
{
    override var name: String = "平滑缩放"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: SmoothZoom
    }

    init {
        Instance = this
    }
}