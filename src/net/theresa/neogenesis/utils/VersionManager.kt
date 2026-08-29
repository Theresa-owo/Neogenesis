package net.theresa.neogenesis.utils

import kotlin.random.Random

class VersionManager
{
    companion object
    {
        const val NAME: String = "Neogenesis"
        const val BASE_VERSION: String = "D"
        var _clientVersion: Int = 0
        const val AUTHOR: String = "Theresa-owo"
    }

    var hints: List<String> = listOf(
        "Neogenesis~ ❤", "我爱特蕾西娅！ ❤", "by Theresa-owo ❤", "新世界即将开启~ ❤"
    )

    constructor(clientVersion: Int)
    {
        _clientVersion = clientVersion
    }

    fun formatVersionString() : String
    {
        return buildString {
            append("[")
            append(NAME)
            append("]")
            append(" ")
            append(BASE_VERSION)
            append(_clientVersion)
            append(" ")
            append(hints[Random.nextInt(0, hints.size)])
        }
    }
}