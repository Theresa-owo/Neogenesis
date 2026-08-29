package net.theresa.neogenesis.modules.itemanimations

import net.theresa.neogenesis.interfaces.modules.IChangeable
import net.theresa.neogenesis.interfaces.modules.IScrollable
import net.theresa.neogenesis.interfaces.modules.ModuleType

class Model : IChangeable
{
    override var name: String = "防砍动画模型"
    override var valueList: MutableList<String> = mutableListOf("第一种", "第二种", "第三种")
    override var selectedValue: Int = 1

    companion object
    {
        lateinit var Instance: Model
    }
    
    init {
        Instance = this
        ItemAnimations.regSubClass(this)
    }
}