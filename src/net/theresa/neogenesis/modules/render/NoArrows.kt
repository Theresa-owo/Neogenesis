package net.theresa.neogenesis.modules.render

import net.theresa.neogenesis.interfaces.modules.IToggleable

class NoArrows : IToggleable
{
    override var name: String = "不渲染箭实体"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: NoArrows
    }

    init {
        Instance = this
    }
}