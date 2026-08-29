package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoBlockhitting : IToggleable
{
    override var name: String = "关闭防砍动画"
    override var toggled: Boolean = false

    companion object
    {
        lateinit var Instance: NoBlockhitting
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}