package com.numbear.manjuan.core

object TextPaginator {
    fun pages(text: String, charsPerLine: Int, linesPerPage: Int): List<PageSlice> {
        val perLine = charsPerLine.coerceAtLeast(1)
        val perPage = linesPerPage.coerceAtLeast(1)
        val source = text.replace("\r\n", "\n").replace('\r', '\n')
        if (source.isEmpty()) return emptyList()

        val lines = ArrayList<Pair<Int, Int>>()
        var lineStart = 0
        var column = 0
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            if (ch == '\n') {
                lines += lineStart to (index + 1)
                index++
                lineStart = index
                column = 0
                continue
            }
            column++
            index++
            if (column >= perLine) {
                lines += lineStart to index
                lineStart = index
                column = 0
            }
        }
        if (lineStart < source.length || lines.isEmpty()) {
            lines += lineStart to source.length
        }

        val pages = ArrayList<PageSlice>()
        var cursor = 0
        while (cursor < lines.size) {
            val slice = lines.subList(cursor, minOf(cursor + perPage, lines.size))
            val start = slice.first().first
            val end = slice.last().second
            val body = source.substring(start, end).trimEnd('\n')
            if (body.isNotEmpty() || pages.isEmpty()) {
                pages += PageSlice(start, end, body)
            }
            cursor += perPage
        }
        return pages.filter { it.text.isNotEmpty() }
    }
}
