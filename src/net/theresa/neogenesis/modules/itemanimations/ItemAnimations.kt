package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IExtendable
import net.theresa.neogenesis.interfaces.modules.IToggleable

class ItemAnimations : IToggleable, IExtendable
{
    override var name: String = "1.7动画"
    override var toggled: Boolean = true
    override var childList: MutableList<String> = mutableListOf<String>()

    companion object
    {
        lateinit var Instance: ItemAnimations
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