package net.theresa.neogenesis.interfaces.modules

interface IChangeable : TypedModule {
    var valueList: MutableList<String>
    var selectedValue: Int
}