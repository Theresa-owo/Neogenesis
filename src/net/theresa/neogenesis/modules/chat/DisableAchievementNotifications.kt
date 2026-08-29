package net.theresa.neogenesis.modules.chat

import net.theresa.neogenesis.interfaces.modules.IToggleable

class DisableAchievementNotifications : IToggleable
{
    override var name: String = "禁用获得成就通知"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: DisableAchievementNotifications
    }

    init {
        Instance = this
    }
}