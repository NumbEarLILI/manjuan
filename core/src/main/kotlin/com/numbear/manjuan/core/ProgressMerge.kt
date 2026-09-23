package com.numbear.manjuan.core

object ProgressMerge {
    fun merge(primary: ReadingProgress?, incoming: ReadingProgress?): ReadingProgress? {
        if (primary == null) return incoming
        if (incoming == null) return primary
        return when {
            primary.updatedAt > incoming.updatedAt -> primary
            incoming.updatedAt > primary.updatedAt -> incoming
            primary.percent >= incoming.percent -> primary
            else -> incoming
        }
    }
}

object LocatorCodec {
    fun novel(chapter: Int, offset: Int): String = "c=$chapter;o=$offset"

    fun page(index: Int): String = "p=$index"

    fun chapter(locator: String): Int = field(locator, "c") ?: 0

    fun offset(locator: String): Int = field(locator, "o") ?: 0

    fun pageIndex(locator: String): Int = field(locator, "p") ?: 0

    private fun field(locator: String, key: String): Int? =
        locator.split(';').firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=')
            ?.toIntOrNull()
}
