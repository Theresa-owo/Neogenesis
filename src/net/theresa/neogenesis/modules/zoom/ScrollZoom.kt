package net.theresa.neogenesis.modules.zoom

import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.IToggleable

class ScrollZoom : IScrollable
{
    override var name: String = "滚轮缩放速度"
    override var minValue: Double = 1.0
    override var scrollValue: Double = 1.0
    override var maxValue: Double = 5.0
    override var canDecimal: Boolean = false

    companion object
    {
        lateinit var Instance: ScrollZoom
    }

    init {
        Instance = this
    }
}