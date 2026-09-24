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
        val opening = MobiParser.opening(file)
        assertTrue(opening.pictureBook)
        val pages = opening.images
        assertEquals(51, pages.size)
        assertEquals(50, pages.count { ImageSniff.extension(it) == "jpg" })
        assertEquals(1, pages.count { ImageSniff.extension(it) == "gif" })
        assertEquals("jpg", ImageSniff.extension(pages.first()))
        assertEquals("gif", ImageSniff.extension(pages.last()))
        pages.forEachIndexed { index, bytes ->
            val decoded = javax.imageio.ImageIO.read(bytes.inputStream())
            assertTrue(
                "第${index + 1}页 ${decoded?.width}x${decoded?.height}",
                decoded != null && decoded.width >= 200 && decoded.height >= 200,
            )
        }
        try {
            MobiParser.parse(file)
            throw AssertionError("图片书不应进入小说正文")
        } catch (error: UnsupportedBookException) {
            assertTrue(error.message.orEmpty().contains("不能当小说打开"))
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
