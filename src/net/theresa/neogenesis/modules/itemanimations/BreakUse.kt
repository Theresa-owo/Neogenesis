package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IToggleable

class BreakUse : IToggleable
{
    override var name: String = "右键交互时允许破坏方块"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: BreakUse
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}