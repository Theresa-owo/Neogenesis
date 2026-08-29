package net.theresa.neogenesis.modules.chat.autotext

import net.theresa.neogenesis.interfaces.modules.IEditable
import net.theresa.neogenesis.interfaces.modules.ModuleType
import net.theresa.neogenesis.modules.chat.AutoText

class AutoText_4 : IEditable
{
    override var name: String = "自动文本4"
    override var value: String = "喵喵喵"

    companion object
    {
        lateinit var Instance: AutoText_4
    }
    
    init {
        Instance = this
        AutoText.regSubClass(this)
    }
}