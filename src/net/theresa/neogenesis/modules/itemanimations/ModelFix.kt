package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IScrollable

class ModelFix : IScrollable
{
    override var name: String = "模型修复"
    override var minValue: Double = -100.0
    override var scrollValue: Double = 50.0
    override var maxValue: Double = 100.0
    override var canDecimal: Boolean = false

    companion object
    {
        lateinit var Instance: ModelFix
    }

    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}