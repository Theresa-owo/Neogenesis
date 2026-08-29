package net.theresa.neogenesis.modules.font

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoRomanNumerals : IToggleable
{
    override var name: String = "将罗马数字转换为阿拉伯数字"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoRomanNumerals
    }

    init {
        Instance = this
    }
}