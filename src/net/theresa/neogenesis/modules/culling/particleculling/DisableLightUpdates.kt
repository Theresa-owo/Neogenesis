package net.theresa.neogenesis.modules.culling.particleculling

import net.theresa.neogenesis.interfaces.modules.IToggleable

class DisableLightUpdates : IToggleable
{
    override var name: String = "禁用光线更新"
    override var toggled: Boolean = true

    companion object {
        lateinit var Instance: DisableLightUpdates
    }

    init {
        Instance = this
    }
}