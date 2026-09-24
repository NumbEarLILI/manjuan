package com.numbear.manjuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelScrollTest {
    @Test
    fun longChapterSecondHalfStaysReachable() {
        val text = "甲".repeat(5_000)
        val blocks = NovelScroll.blocks(NovelChapter("正文", text), maxChars = 300)
        val words = blocks.filterIsInstance<NovelScroll.Block.Words>()

        assertTrue(blocks.isNotEmpty())
        assertTrue(words.all { it.text.length <= 300 })
        assertEquals(text, words.joinToString("") { it.text })

        val offset = 4_000
        val index = NovelScroll.indexAt(blocks, offset)
        assertTrue(index > 0)
        val block = words[index] as NovelScroll.Block.Words
        assertTrue(block.start <= offset)
        assertTrue(offset < block.start + block.text.length)
    }

    @Test
    fun splitsOnNewlinesWithoutDroppingCharacters() {
        val text = buildString {
            repeat(20) { append("段落$it\n") }
        }
        val blocks = NovelScroll.blocks(NovelChapter("正文", text), maxChars = 12)
        val words = blocks.filterIsInstance<NovelScroll.Block.Words>()

        assertEquals(text, words.joinToString("") { it.text })
        assertTrue(words.all { it.text.length <= 12 })
        assertTrue(words.all { it.text.endsWith('\n') || it == words.last() })
    }

    @Test
    fun plateSitsBetweenProseAndOffsetsFollowChapterText() {
        val png = byteArrayOf(1, 2, 3, 4)
        val chapter = NovelChapter(
            title = "彩页",
            text = "甲乙\n丙丁戊",
            spans = listOf(
                NovelSpan.Prose("甲乙"),
                NovelSpan.Plate(png),
                NovelSpan.Prose("丙丁戊"),
            ),
        )
        val blocks = NovelScroll.blocks(chapter, maxChars = 2)
        val words = blocks.filterIsInstance<NovelScroll.Block.Words>()

        assertEquals("甲乙丙丁戊", words.joinToString("") { it.text })
        assertTrue(words.all { it.text.length <= 2 })
        val picture = blocks.filterIsInstance<NovelScroll.Block.Picture>().single()
        assertTrue(picture.bytes.contentEquals(png))
        assertTrue(blocks.indexOf(picture) in 1 until blocks.lastIndex)

        val later = NovelScroll.indexAt(blocks, chapter.text.indexOf('戊'))
        val laterBlock = blocks[later]
        assertTrue(laterBlock is NovelScroll.Block.Words)
        assertTrue((laterBlock as NovelScroll.Block.Words).text.contains('戊'))
        assertTrue(later > blocks.indexOf(picture))
    }

    @Test
    fun documentContinuesIntoTheNextChapter() {
        val first = "第一章\n" + "甲".repeat(10)
        val second = "第二章\n" + "乙".repeat(10)
        val chapters = listOf(
            NovelChapter("第一章", first),
            NovelChapter("第二章", second),
        )
        val entries = NovelScroll.document(chapters, maxChars = 4)
        val chapterBodies = entries.filterIsInstance<NovelScroll.Entry.Body>().groupBy { it.chapter }

        assertEquals(first, chapterBodies.getValue(0).joinToString("") { (it.block as NovelScroll.Block.Words).text })
        assertEquals(second, chapterBodies.getValue(1).joinToString("") { (it.block as NovelScroll.Block.Words).text })
        assertTrue(entries.none { it is NovelScroll.Entry.Heading })

        val start = NovelScroll.indexAt(entries, chapter = 1, offset = 0)
        assertEquals(1, entries[start].chapter)
        assertEquals(0, (entries[start] as NovelScroll.Entry.Body).block.start)

        val later = NovelScroll.indexAt(entries, chapter = 1, offset = 8)
        val laterBody = entries[later] as NovelScroll.Entry.Body
        val laterWords = laterBody.block as NovelScroll.Block.Words
        assertEquals(1, laterBody.chapter)
        assertTrue(laterWords.start <= 8)
        assertTrue(8 < laterWords.start + laterWords.text.length)

        val endOfFirst = NovelScroll.indexAt(entries, chapter = 0, offset = first.length - 1)
        assertEquals(0, entries[endOfFirst].chapter)
    }

    @Test
    fun headingIsInsertedWhenTheBodyDoesNotAlreadyStartWithTheTitle() {
        val chapters = listOf(
            NovelChapter("序", "很久以前"),
            NovelChapter("正文", "甲乙丙丁"),
        )
        val entries = NovelScroll.document(chapters, maxChars = 100)

        assertTrue(entries.first() is NovelScroll.Entry.Heading)
        assertEquals("序", (entries.first() as NovelScroll.Entry.Heading).title)
        val second = entries.indexOfFirst { it.chapter == 1 }
        assertTrue(entries[second] is NovelScroll.Entry.Heading)
        assertEquals("正文", (entries[second] as NovelScroll.Entry.Heading).title)
        assertEquals(second, NovelScroll.indexAt(entries, chapter = 1, offset = 0))
    }

    @Test
    fun maxCharsKeepsBlockUnderPixelBudget() {
        assertEquals(760, NovelScroll.maxChars(charsPerLine = 20, lineHeightPx = 80f, maxBlockPx = 3072))
        val chars = NovelScroll.maxChars(charsPerLine = 8, lineHeightPx = 280f, maxBlockPx = 3072)
        val lines = (chars + 8 - 1) / 8
        assertTrue(lines * 280 <= 3072)
    }
}
