package net.theresa.neogenesis.modules.chat

import net.theresa.neogenesis.interfaces.modules.IToggleable

class DisableChatHistoryLimit : IToggleable
{
    override var name: String = "禁用聊天历史长度限制"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: DisableChatHistoryLimit
    }

    init {
        Instance = this
    }
}