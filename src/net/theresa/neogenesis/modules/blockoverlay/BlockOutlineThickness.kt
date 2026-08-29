package net.theresa.neogenesis.modules.blockoverlay

import net.theresa.neogenesis.interfaces.modules.IEditable
import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class BlockOutlineThickness : IScrollable
{
    override var name: String = "方块边框粗细"
    override var minValue: Double = 1.0
    override var scrollValue: Double = 5.0
    override var maxValue: Double = 10.0
    override var canDecimal: Boolean = true

    companion object
    {
        lateinit var Instance: BlockOutlineThickness
    }
    
    init {
        Instance = this
        BlockOverlay.regSubClass(this)
    }
}