package net.theresa.neogenesis.modules.freelook

import net.theresa.neogenesis.interfaces.modules.IToggleable

class IsHoldFreeLook : IToggleable
{
    override var name: String = "按住自由视角 (开启后按住设定的键进行FreeLook 关闭则按一下就切换FreeLook模式)"
    override var toggled: Boolean = true

    companion object
    {
        lateinit var Instance: IsHoldFreeLook
    }

    init {
        Instance = this
        FreeLook.regSubClass(this)
    }
}