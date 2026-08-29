package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoDeadAnimations : IToggleable
{
    override var name: String = "关闭生物死亡动画"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoDeadAnimations
    }

    init {
        Instance = this
    }
}