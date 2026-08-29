package net.theresa.neogenesis.utils

class Color(hexCode: String)
{
    var r: Double = 0.0
    var g: Double = 0.0
    var b: Double = 0.0
    var a: Double = 0.0

    init {
        val hexColor = if (hexCode.startsWith("0x")) hexCode.substring(2) else hexCode
        val alpha = hexColor.substring(0, 2).toInt(16) / 255.0
        val red = hexColor.substring(2, 4).toInt(16) / 255.0
        val green = hexColor.substring(4, 6).toInt(16) / 255.0
        val blue = hexColor.substring(6, 8).toInt(16) / 255.0
        this.r = red
        this.g = green
        this.b = blue
        this.a = alpha
    }
}