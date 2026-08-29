package net.theresa.neogenesis.modules.healthdisplay

import net.theresa.neogenesis.interfaces.modules.IToggleable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class ClearHealth : IToggleable
{
    override var name: String = "透明血量显示"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: ClearHealth
    }
    
    init {
        Instance = this
        ShowHealth.regSubClass(this)
    }
}