package net.theresa.neogenesis.modules.zoom

import net.theresa.neogenesis.interfaces.modules.IScrollable

class Magnification : IScrollable
{
    override var name: String = "Zoom放大倍数"
    override var minValue: Double = 1.1
    override var scrollValue: Double = 6.0
    override var maxValue: Double = 10.0
    override var canDecimal: Boolean = true

    companion object
    {
        lateinit var Instance: Magnification
    }

    init {
        Instance = this
    }
}