package net.theresa.neogenesis.modules.chat

import net.theresa.neogenesis.interfaces.modules.IExtendable
import net.theresa.neogenesis.interfaces.modules.IToggleable

class SmoothChat : IToggleable, IExtendable
{
    override var name: String = "平滑聊天"
    override var toggled: Boolean = true
    override var childList: MutableList<String> = mutableListOf<String>()

    companion object
    {
        lateinit var Instance: SmoothChat
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