package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoFallingBlocks : IToggleable
{
    override var name: String = "不渲染掉落的方块"
    override var toggled: Boolean = false

    companion object
    {
        lateinit var Instance: NoFallingBlocks
    }

    init {
        Instance = this
    }
}