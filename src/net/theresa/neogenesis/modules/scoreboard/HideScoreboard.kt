package net.theresa.neogenesis.modules.scoreboard

import net.theresa.neogenesis.interfaces.modules.IToggleable

class HideScoreboard : IToggleable
{
    override var name: String = "隐藏计分板"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: HideScoreboard
    }

    init {
        Instance = this
    }
}