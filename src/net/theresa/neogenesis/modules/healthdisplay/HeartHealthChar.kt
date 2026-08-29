package net.theresa.neogenesis.modules.healthdisplay

import net.theresa.neogenesis.interfaces.modules.IEditable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class HeartHealthChar : IEditable
{
    override var name: String = "血量心字符"
    override var value: String = "\uE000\uE001\uE002\uE003\uE004"

    companion object
    {
        lateinit var Instance: HeartHealthChar
    }

    init {
        Instance = this
        ShowHealth.regSubClass(this)
    }
}