package net.theresa.neogenesis.modules.blockoverlay

import net.theresa.neogenesis.interfaces.modules.IExtendable
import net.theresa.neogenesis.interfaces.modules.IToggleable

class BlockOverlay : IToggleable, IExtendable {
    override var name: String = "选中方块边框更改"
    override var toggled: Boolean = true
    override var childList: MutableList<String> = mutableListOf<String>()

    companion object
    {
        lateinit var Instance: BlockOverlay
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