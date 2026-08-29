package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IToggleable

class ThirdPersonViewBlockhitting : IToggleable
{
    override var name: String = "第三人称视角防砍动画修复"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: ThirdPersonViewBlockhitting
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}