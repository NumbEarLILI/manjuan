package com.numbear.manjuan.core

object TxtChapters {
    private val heading = Regex(
        """(?m)^[ \t\u3000]*(第[0-9０-９零一二三四五六七八九十百千万两]+[章节回部卷集篇][^\n]{0,40}|Chapter\s+\d+[^\n]{0,40}|CHAPTER\s+\d+[^\n]{0,40}|序章[^\n]{0,40}|楔子[^\n]{0,40}|尾声[^\n]{0,40}|后记[^\n]{0,40})\s*$""",
    )

    private val markdownHeading = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]+\S[^\n]*$""")

    fun split(text: String, markdown: Boolean = false): List<TextChapter> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val matches = buildList {
            addAll(heading.findAll(normalized))
            if (markdown) addAll(markdownHeading.findAll(normalized))
        }.sortedBy { it.range.first }.distinctBy { it.range.first }
        if (matches.isEmpty()) {
            return listOf(TextChapter("正文", 0, normalized.length))
        }
        val chapters = ArrayList<TextChapter>(matches.size + 1)
        val first = matches.first().range.first
        if (normalized.substring(0, first).isNotBlank()) {
            chapters += TextChapter("前言", 0, first)
        }
        matches.forEachIndexed { index, match ->
            val start = match.range.first
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else normalized.length
            val title = headingTitle(match.value).ifBlank { "第 ${index + 1} 节" }
            chapters += TextChapter(title, start, end)
        }
        return chapters
    }

    private fun headingTitle(raw: String): String =
        raw.trim().replace(Regex("""^#{1,6}[ \t]+"""), "").trim()
}
