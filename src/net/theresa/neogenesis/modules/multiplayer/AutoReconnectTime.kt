package net.theresa.neogenesis.modules.multiplayer

import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class AutoReconnectTime : IScrollable
{
    override var name: String = "自动重连时间"
    
    override var minValue: Double = 0.0
    override var scrollValue: Double = 5.0
    override var maxValue: Double = 10.0
    override var canDecimal: Boolean = false

    companion object
    {
        lateinit var Instance: AutoReconnectTime
    }

    init {
        Instance = this
        AutoReconnect.regSubClass(this)
    }
}