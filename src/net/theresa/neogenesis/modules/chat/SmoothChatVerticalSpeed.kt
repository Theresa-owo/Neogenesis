package net.theresa.neogenesis.modules.chat

import net.theresa.neogenesis.interfaces.modules.IScrollable

class SmoothChatVerticalSpeed : IScrollable
{
    override var name: String = "平滑聊天垂直速度"
    override var minValue: Double = 0.0
    override var scrollValue: Double = 2.0
    override var maxValue: Double = 10.0
    override var canDecimal: Boolean = true

    companion object
    {
        lateinit var Instance: SmoothChatVerticalSpeed
    }

    init {
        Instance = this
        SmoothChat.regSubClass(this)
    }
}