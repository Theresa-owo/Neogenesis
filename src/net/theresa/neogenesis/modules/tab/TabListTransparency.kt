package net.theresa.neogenesis.modules.tab

import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class TabListTransparency : IScrollable
{
    override var name: String = "TabList透明度"
    
    override var minValue: Double = -50.0
    override var scrollValue: Double = 0.0
    override var maxValue: Double = 50.0
    override var canDecimal: Boolean = false

    companion object
    {
        lateinit var Instance: TabListTransparency
    }

    init {
        Instance = this
    }
}