package net.theresa.neogenesis.modules.unlegit

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoHitDelay : IToggleable
{
    override var name: String = "[非法] 关闭原版Miss瞄准时打击间隔"
    override var toggled: Boolean = false

    companion object
    {
        lateinit var Instance: NoHitDelay
    }

    init {
        Instance = this
    }
}