package net.theresa.neogenesis.modules.zoom

import net.theresa.neogenesis.interfaces.modules.IScrollable

class SmoothZoomSpeed : IScrollable
{
    override var name: String = "Zoom放大速度"
    override var minValue: Double = 0.0
    override var scrollValue: Double = 1.0
    override var maxValue: Double = 3.0
    override var canDecimal: Boolean = true

    companion object
    {
        lateinit var Instance: SmoothZoomSpeed
    }

    init {
        Instance = this
    }
}