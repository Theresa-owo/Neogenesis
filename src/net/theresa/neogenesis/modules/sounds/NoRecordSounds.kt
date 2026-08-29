package net.theresa.neogenesis.modules.sounds

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoRecordSounds : IToggleable
{
    override var name: String = "不播放唱片音乐"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoRecordSounds
    }

    init {
        Instance = this
    }
}