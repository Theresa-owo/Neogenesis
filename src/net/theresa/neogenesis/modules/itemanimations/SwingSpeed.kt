package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class SwingSpeed : IScrollable
{
    override var name: String = "挥手速度"
    
    override var minValue: Double = 0.0
    override var scrollValue: Double = 4.0
    override var maxValue: Double = 20.0
    override var canDecimal: Boolean = false

    companion object
    {
        lateinit var Instance: SwingSpeed
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}