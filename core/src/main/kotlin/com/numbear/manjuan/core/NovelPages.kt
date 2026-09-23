package com.numbear.manjuan.core

object NovelPages {
    sealed class Page {
        abstract val start: Int

        data class Words(val text: String, override val start: Int) : Page()
        data class Picture(val bytes: ByteArray, override val start: Int) : Page() {
            override fun equals(other: Any?): Boolean =
                other is Picture && start == other.start && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = 31 * start + bytes.contentHashCode()
        }
    }

    fun pages(chapter: NovelChapter, charsPerLine: Int, linesPerPage: Int): List<Page> {
        val spans = chapter.spans.ifEmpty { listOf(NovelSpan.Prose(chapter.text)) }
        val pages = ArrayList<Page>()
        var proseAt = 0
        for (span in spans) {
            when (span) {
                is NovelSpan.Prose -> {
                    val parts = TextPaginator.pages(span.text, charsPerLine, linesPerPage)
                    for (part in parts) {
                        pages += Page.Words(part.text, proseAt + part.start)
                    }
                    proseAt += span.text.length
                }
                is NovelSpan.Plate -> pages += Page.Picture(span.bytes, proseAt)
            }
        }
        return pages
    }
}
