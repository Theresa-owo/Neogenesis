package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoSwingingItem : IToggleable
{
    override var name: String = "手持物品不挥动"
    override var toggled: Boolean = false

    companion object
    {
        lateinit var Instance: NoSwingingItem
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}