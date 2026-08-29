package net.theresa.neogenesis.interfaces.modules

import net.theresa.neogenesis.interfaces.IModule

sealed interface TypedModule : IModule {
    override val type: ModuleType
        get() = when (this) {
            is IToggleable -> ModuleType.Toggleable
            is IScrollable -> ModuleType.Scrollable
            is IEditable -> ModuleType.Editable
            is IChangeable -> ModuleType.Changeable
        }
}