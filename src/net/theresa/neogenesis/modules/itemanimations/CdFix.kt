package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IToggleable

class CdFix : IToggleable
{
    override var name: String = "挥手间隔修复"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: CdFix
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}