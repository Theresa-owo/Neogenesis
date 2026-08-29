package net.theresa.neogenesis.modules.multiplayer

import net.theresa.neogenesis.interfaces.modules.IExtendable
import net.theresa.neogenesis.interfaces.modules.IToggleable

class AutoReconnect : IToggleable, IExtendable
{
    override var name: String = "自动重连"
    override var toggled: Boolean = true
    override var childList: MutableList<String> = mutableListOf<String>()

    companion object
    {
        lateinit var Instance: AutoReconnect
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