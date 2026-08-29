package net.theresa.neogenesis.modules.chat

import net.theresa.neogenesis.interfaces.modules.IEditable
import net.theresa.neogenesis.interfaces.modules.ModuleType
import net.theresa.neogenesis.modules.blockoverlay.BlockOverlayColor

class ChatBackgroundColor : IEditable
{
    override var name: String = "聊天背景颜色"
    override var value: String = "0x7f000000"

    companion object
    {
        lateinit var Instance: ChatBackgroundColor
    }

    init {
        Instance = this
    }
}