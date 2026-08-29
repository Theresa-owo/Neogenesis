package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class ItemPositionZ : IScrollable
{
    override var name: String = "手持物品位置 Z坐标"
    
    override var minValue: Double = -1.0
    override var scrollValue: Double = 0.0
    override var maxValue: Double = 1.0
    override var canDecimal: Boolean = true

    companion object
    {
        lateinit var Instance: ItemPositionZ
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}