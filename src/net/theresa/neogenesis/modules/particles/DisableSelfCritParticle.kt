package net.theresa.neogenesis.modules.particles

import net.theresa.neogenesis.interfaces.modules.IToggleable

class DisableSelfCritParticle : IToggleable
{
    override var name: String = "关闭自身暴击/锋利粒子"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: DisableSelfCritParticle
    }

    init {
        Instance = this
    }
}