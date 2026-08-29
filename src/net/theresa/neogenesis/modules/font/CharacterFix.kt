package net.theresa.neogenesis.modules.font

import net.theresa.neogenesis.interfaces.modules.IToggleable

class CharacterFix : IToggleable
{
    override var name: String = "开启字符修复"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: CharacterFix
    }

    init {
        Instance = this
    }
}