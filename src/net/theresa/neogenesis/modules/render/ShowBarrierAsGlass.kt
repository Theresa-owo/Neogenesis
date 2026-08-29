package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class ShowBarrierAsGlass : IToggleable
{
    override var name: String = "将屏障方块显示为玻璃"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: ShowBarrierAsGlass
    }

    init {
        Instance = this
    }
}