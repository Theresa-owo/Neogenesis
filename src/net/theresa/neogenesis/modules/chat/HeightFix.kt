package net.theresa.neogenesis.modules.chat

import net.theresa.neogenesis.interfaces.modules.IToggleable

class HeightFix : IToggleable
{
    override var name: String = "聊天栏高度修复 (使其显示在状态栏上)"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: HeightFix
    }

    init {
        Instance = this
    }
}