package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class ItemScale : IScrollable
{
    override var name: String = "手持物品大小"
    
    override var minValue: Double = 0.1
    override var scrollValue: Double = 0.5
    override var maxValue: Double = 2.0
    override var canDecimal: Boolean = true

    companion object
    {
        lateinit var Instance: ItemScale
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}