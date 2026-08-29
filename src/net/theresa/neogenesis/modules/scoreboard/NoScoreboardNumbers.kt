package net.theresa.neogenesis.modules.scoreboard

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoScoreboardNumbers : IToggleable
{
    override var name: String = "关闭计分板红色数字"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoScoreboardNumbers
    }

    init {
        Instance = this
    }
}