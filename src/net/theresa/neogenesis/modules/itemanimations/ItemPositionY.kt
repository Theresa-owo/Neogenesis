package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class ItemPositionY : IScrollable
{
    override var name: String = "手持物品位置 Y坐标"
    
    override var minValue: Double = -1.0
    override var scrollValue: Double = 0.3
    override var maxValue: Double = 1.0
    override var canDecimal: Boolean = true

    companion object
    {
        lateinit var Instance: ItemPositionY
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}