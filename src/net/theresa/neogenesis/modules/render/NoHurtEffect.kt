package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoHurtEffect : IToggleable
{
    override var name: String = "禁用受伤视角抖动"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoHurtEffect
    }

    init {
        Instance = this
    }
}