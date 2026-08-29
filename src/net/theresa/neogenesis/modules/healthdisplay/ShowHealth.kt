package net.theresa.neogenesis.modules.healthdisplay

import net.theresa.neogenesis.interfaces.modules.IExtendable
import net.theresa.neogenesis.interfaces.modules.IToggleable

class ShowHealth : IToggleable, IExtendable
{
    override var name: String = "血量显示"
    override var toggled: Boolean = true
    override var childList: MutableList<String> = mutableListOf<String>()

    companion object
    {
        lateinit var Instance: ShowHealth
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