package net.theresa.neogenesis.modules.healthdisplay

import net.theresa.neogenesis.interfaces.modules.IToggleable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class ShowHealthAsHearts : IToggleable
{
    override var name: String = "将血量显示为心"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: ShowHealthAsHearts
    }
    
    init {
        Instance = this
        ShowHealth.regSubClass(this)
    }
}