package net.theresa.neogenesis.modules.blockoverlay

import net.theresa.neogenesis.interfaces.modules.IEditable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class BlockOverlayColor : IEditable
{
    override var name: String = "方块表面颜色"
    override var value: String = "0x99000000"

    companion object
    {
        lateinit var Instance: BlockOverlayColor
    }

    init {
        Instance = this
        BlockOverlay.regSubClass(this)
    }
}