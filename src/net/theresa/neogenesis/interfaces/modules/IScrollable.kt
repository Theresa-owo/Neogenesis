package net.theresa.neogenesis.interfaces.modules

interface IScrollable : TypedModule {
    var minValue: Double
    var scrollValue: Double
    var maxValue: Double
    var canDecimal: Boolean
}