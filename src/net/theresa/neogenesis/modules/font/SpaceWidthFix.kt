package net.theresa.neogenesis.modules.font

import net.theresa.neogenesis.interfaces.modules.IToggleable

class SpaceWidthFix : IToggleable
{
    override var name: String = "修复空格宽度"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: SpaceWidthFix
    }

    init {
        Instance = this
    }
}