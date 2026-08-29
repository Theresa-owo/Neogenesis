package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IToggleable

class BlockSwing : IToggleable
{
    override var name: String = "防砍动画"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: BlockSwing
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}