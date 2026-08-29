package net.theresa.neogenesis.modules.particles

import net.theresa.neogenesis.interfaces.modules.IToggleable

class DisableBlockHarvestingParticle : IToggleable
{
    override var name: String = "关闭方块挖掘粒子"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: DisableBlockHarvestingParticle
    }

    init {
        Instance = this
    }
}