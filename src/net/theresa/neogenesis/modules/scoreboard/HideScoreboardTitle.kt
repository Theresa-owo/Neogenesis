package net.theresa.neogenesis.modules.scoreboard

import net.theresa.neogenesis.interfaces.modules.IToggleable

class HideScoreboardTitle : IToggleable
{
    override var name: String = "隐藏计分板标题"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: HideScoreboardTitle
    }

    init {
        Instance = this
    }
}