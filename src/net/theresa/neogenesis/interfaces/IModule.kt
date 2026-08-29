package net.theresa.neogenesis.interfaces

import net.theresa.neogenesis.interfaces.modules.ModuleType

interface IModule {
    val name: String
    val type: ModuleType
        get() = ModuleType.None
}