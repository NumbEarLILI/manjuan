package com.numbear.manjuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTextTest {
    @Test
    fun firstChunkOpensAndTheNextChunkContinuesTheSameChapters() {
        val first = "第一章 江岸\n".toByteArray() + "甲".repeat(20).toByteArray()
        val opened = RemoteText.novel("远程", "", first, totalBytes = 10_000)
        assertTrue(opened.more)
        assertEquals(1, opened.chapters.size)
        assertTrue(opened.chapters[0].text.contains("甲"))

        val more = "甲".repeat(10).toByteArray() + "\n第二章 江心\n乙".toByteArray()
        val combined = RemoteText.novel("远程", "", first + more, totalBytes = (first + more).size.toLong())
        assertFalse(combined.more)
        assertEquals("第一章 江岸", combined.chapters[0].title)
        assertTrue(combined.chapters[0].text.contains("甲".repeat(30)))
        assertEquals("第二章 江心", combined.chapters[1].title)
        assertTrue(combined.chapters[1].text.contains("乙"))
    }

    @Test
    fun readingTheLastLoadedChapterAsksForAnotherChunk() {
        val chapters = listOf(
            NovelChapter("第一章", "甲".repeat(10_000)),
            NovelChapter("第二章", "乙".repeat(100)),
        )
        assertTrue(RemoteText.covered(chapters, chapter = 0, offset = 0, complete = false))
        assertFalse(RemoteText.covered(chapters, chapter = 1, offset = 0, complete = false))
        assertTrue(RemoteText.covered(chapters, chapter = 1, offset = 0, complete = true))
        assertFalse(RemoteText.covered(chapters, chapter = 3, offset = 0, complete = false))
    }

    @Test
    fun brokenUtf8TailIsNotDecodedAsACharacter() {
        val whole = "章节".toByteArray(Charsets.UTF_8)
        val cut = whole.copyOf(whole.size - 1)
        val opened = RemoteText.novel("远程", "", cut, totalBytes = 100)
        assertFalse(opened.chapters[0].text.contains("\uFFFD"))
        assertTrue(opened.more)
    }

    @Test
    fun imageTargetKeepsABatchAheadOfTheCurrentPage() {
        assertEquals(8, RemoteText.imageTarget(page = 0, total = 40))
        assertEquals(28, RemoteText.imageTarget(page = 20, total = 40))
        assertEquals(3, RemoteText.imageTarget(page = 0, total = 3))
        assertEquals(40, RemoteText.imageTarget(page = 39, total = 40))
    }
}
