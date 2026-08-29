package net.theresa.neogenesis.modules.blockoverlay

import net.theresa.neogenesis.interfaces.modules.IToggleable

class FullSelectionBox : IToggleable
{
    override var name: String = "渲染整个方块的边框"
    override var toggled: Boolean = false

    companion object
    {
        lateinit var Instance: FullSelectionBox
    }

    init {
        Instance = this
        BlockOverlay.regSubClass(this)
    }
}