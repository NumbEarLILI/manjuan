package com.numbear.manjuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Local sample books live outside the repo. The suite skips them when the
 * files are not on this machine, and checks the approved counts when they are.
 */
class SampleBookTest {
    @Test
    fun sideStoryMobiImagePagesAreFiftyOneWithOneGif() {
        val file = File("/workspace/manjuan-testdata/无职转生-番外01-03.mobi")
        assumeTrue("样书不在本环境：${file.path}", file.isFile)
        val pages = MobiParser.imagePages(file)
        assertEquals(51, pages.size)
        assertEquals(50, pages.count { ImageSniff.extension(it) == "jpg" })
        assertEquals(1, pages.count { ImageSniff.extension(it) == "gif" })
        pages.forEach { bytes ->
            val decoded = javax.imageio.ImageIO.read(bytes.inputStream())
            assertTrue(decoded != null && decoded.width > 0 && decoded.height > 0)
        }
    }

    @Test
    fun sampleEpubKeepsIllustrationsAndImageOnlyChapters() {
        val file = File("/workspace/manjuan-testdata/novel.epub")
        assumeTrue("样书不在本环境：${file.path}", file.isFile)
        val novel = EpubParser.parse(file)
        val plates = novel.chapters.flatMap { it.spans }.filterIsInstance<NovelSpan.Plate>()
        ZipFile(file).use { zip ->
            for (token in listOf("co1", "co2", "co3")) {
                val entry = zip.entries().asSequence().firstOrNull { item ->
                    !item.isDirectory && item.name.contains(token, ignoreCase = true) &&
                        item.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp")
                }
                assertTrue("EPUB 里没有 Illus $token", entry != null)
                val raw = zip.getInputStream(entry).use { it.readBytes() }
                val payload = ImageSniff.extract(raw) ?: raw
                assertTrue(
                    plates.any { it.bytes.contentEquals(payload) || it.bytes.contentEquals(raw) },
                )
            }
        }
        assertTrue(
            novel.chapters.any { chapter ->
                chapter.spans.isNotEmpty() && chapter.spans.all { it is NovelSpan.Plate }
            },
        )
    }
}
