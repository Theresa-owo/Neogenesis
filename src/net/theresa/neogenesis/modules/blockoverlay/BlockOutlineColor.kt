package net.theresa.neogenesis.modules.blockoverlay

import net.theresa.neogenesis.interfaces.modules.IEditable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class BlockOutlineColor : IEditable
{
    override var name: String = "方块边框颜色"
    override var value: String = "0x99000000"

    companion object
    {
        lateinit var Instance: BlockOutlineColor
    }

    init {
        Instance = this
        BlockOverlay.regSubClass(this)
    }
}