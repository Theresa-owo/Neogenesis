package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IToggleable

class InvLight : IToggleable
{
    override var name: String = "使物品栏中物品更亮"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: InvLight
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}