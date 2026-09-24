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
    fun maxCharsKeepsBlockUnderPixelBudget() {
        assertEquals(760, NovelScroll.maxChars(charsPerLine = 20, lineHeightPx = 80f, maxBlockPx = 3072))
        val chars = NovelScroll.maxChars(charsPerLine = 8, lineHeightPx = 280f, maxBlockPx = 3072)
        val lines = (chars + 8 - 1) / 8
        assertTrue(lines * 280 <= 3072)
    }
}
