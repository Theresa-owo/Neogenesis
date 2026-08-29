package net.theresa.neogenesis.modules.chat

import net.theresa.neogenesis.interfaces.modules.IToggleable

class DisableChatLimit : IToggleable
{
    override var name: String = "禁用聊天长度限制 (用于发送客户端指令)"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: DisableChatLimit
    }

    init {
        Instance = this
    }
}