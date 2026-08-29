package net.theresa.neogenesis.modules.misc

import net.theresa.neogenesis.interfaces.modules.IExtendable
import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.IToggleable

class FpsLimiter : IToggleable, IExtendable
{
    override var name: String = "后台帧数限制"
    override var toggled: Boolean = true
    override var childList: MutableList<String> = mutableListOf<String>()

    companion object
    {
        lateinit var Instance: FpsLimiter
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

class FpsLimiter_Value : IScrollable
{
    override var name: String = "后台时限制帧数"
    
    override var minValue: Double = 5.0
    override var scrollValue: Double = 5.0
    override var maxValue: Double = 10.0
    override var canDecimal: Boolean = false

    companion object
    {
        lateinit var Instance: FpsLimiter_Value
    }
    
    init {
        Instance = this
        FpsLimiter.regSubClass(this)
    }
}