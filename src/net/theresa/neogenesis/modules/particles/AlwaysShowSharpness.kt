package net.theresa.neogenesis.modules.particles

import net.theresa.neogenesis.interfaces.modules.IToggleable

class AlwaysShowSharpness : IToggleable
{
    override var name: String = "总是显示锋利粒子"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: AlwaysShowSharpness
    }

    init {
        Instance = this
    }
}