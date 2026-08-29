package net.theresa.neogenesis.modules.particles

import net.theresa.neogenesis.interfaces.modules.IExtendable
import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.IToggleable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class ParticleMultiplier : IToggleable, IExtendable
{
    override var name: String = "粒子倍增器"
    override var toggled: Boolean = false
    override var childList: MutableList<String> = mutableListOf<String>()

    companion object
    {
        lateinit var Instance: ParticleMultiplier
        fun regSubClass(subclass: Any)
        {
            val subclassName = subclass::class.simpleName
            if (subclassName != null && subclassName !in Instance.childList) {
                Instance.childList.add(subclassName)
            }
        }
    }

    init {
        Instance = this
    }
}

class ParticleMultiplierValue : IScrollable
{
    override var name: String = "粒子倍数"
    
    override var minValue: Double = 0.0
    override var scrollValue: Double = 3.0
    override var maxValue: Double = 20.0
    override var canDecimal: Boolean = false

    companion object
    {
        lateinit var Instance: ParticleMultiplierValue
    }
    
    init {
        Instance = this
        ParticleMultiplier.regSubClass(this)
    }
}