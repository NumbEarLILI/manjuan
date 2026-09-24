package com.numbear.manjuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class MobiParserTest {
    @Test
    fun palmDocRecordsWithTrailersDecodeAcrossTheBoundary() {
        val part1 = "hello"
        val part2 = "潮水"
        val trailer = byteArrayOf('Q'.code.toByte(), 0x01, 'Z'.code.toByte(), 0x82.toByte())
        val record1 = palmDocLiterals(part1.toByteArray(Charsets.UTF_8)) + trailer
        val record2 = palmDocLiterals(part2.toByteArray(Charsets.UTF_8)) + trailer
        val textLength = part1.toByteArray(Charsets.UTF_8).size + part2.toByteArray(Charsets.UTF_8).size
        val file = writeMobi(
            compression = 2,
            encoding = 65001,
            records = listOf(record1, record2),
            textLength = textLength,
            extraFlags = 0x0003,
        )
        val novel = MobiParser.parse(file)
        val text = novel.chapters.joinToString("") { it.text }
        assertEquals("hello潮水", text)
        file.delete()
    }

    @Test
    fun gbkBodyDeclaredAsWindows1252UsesHtmlCharset() {
        val gbk = Charset.forName("GBK")
        val html = "<html><head><meta charset=\"gbk\"></head><body><p>潮水</p></body></html>"
        val bytes = html.toByteArray(gbk)
        val file = writeMobi(
            compression = 1,
            encoding = 1252,
            records = listOf(bytes),
            textLength = bytes.size,
            extraFlags = null,
        )
        val novel = MobiParser.parse(file)
        assertTrue(novel.chapters.joinToString("") { it.text }.contains("潮水"))
        file.delete()
    }

    @Test
    fun utf8AndGbkMislabelledAsWindows1252StillDecode() {
        val utf8 = "<p>潮水</p>".toByteArray(Charsets.UTF_8)
        val utf8File = writeMobi(compression = 1, encoding = 1252, records = listOf(utf8), textLength = utf8.size, extraFlags = null)
        val gbk = "<p>潮水</p>".toByteArray(Charset.forName("GBK"))
        val gbkFile = writeMobi(compression = 1, encoding = 1252, records = listOf(gbk), textLength = gbk.size, extraFlags = null)
        val codePage = writeMobi(compression = 1, encoding = 936, records = listOf(gbk), textLength = gbk.size, extraFlags = null)
        val latin = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        val latinFile = writeMobi(compression = 1, encoding = 1252, records = listOf(latin), textLength = latin.size, extraFlags = null)
        assertTrue(MobiParser.parse(utf8File).chapters.joinToString("") { it.text }.contains("潮水"))
        assertTrue(MobiParser.parse(gbkFile).chapters.joinToString("") { it.text }.contains("潮水"))
        assertTrue(MobiParser.parse(codePage).chapters.joinToString("") { it.text }.contains("潮水"))
        assertTrue(MobiParser.parse(latinFile).chapters.joinToString("") { it.text }.contains("café"))
        utf8File.delete()
        gbkFile.delete()
        codePage.delete()
        latinFile.delete()
    }

    @Test
    fun truncatedTextIsNotReturnedAsAChapter() {
        val body = "hello".toByteArray(Charsets.UTF_8)
        val file = writeMobi(compression = 1, encoding = 65001, records = listOf(body), textLength = 5000, extraFlags = null)
        assertChinese(file, "不完整")
        file.delete()
    }

    @Test
    fun trailerThatConsumesTheRecordStillKeepsBodyText() {
        val html = "<p>潮水</p>".toByteArray(Charsets.UTF_8)
        val record = html + byteArrayOf((0x80 or (html.size + 1)).toByte())
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(record),
            textLength = html.size,
            extraFlags = 0x0002,
        )
        val text = MobiParser.parse(file).chapters.joinToString("") { it.text }
        assertTrue(text.contains("潮水"))
        file.delete()
    }

    @Test
    fun zeroTextRecordCountUsesFirstNonText() {
        val part1 = "hello"
        val part2 = "潮水"
        val trailer = byteArrayOf('Q'.code.toByte(), 0x01, 'Z'.code.toByte(), 0x82.toByte())
        val record1 = palmDocLiterals(part1.toByteArray(Charsets.UTF_8)) + trailer
        val record2 = palmDocLiterals(part2.toByteArray(Charsets.UTF_8)) + trailer
        val textLength = part1.toByteArray(Charsets.UTF_8).size + part2.toByteArray(Charsets.UTF_8).size
        val file = writeMobi(
            compression = 2,
            encoding = 65001,
            records = listOf(record1, record2),
            textLength = textLength,
            extraFlags = 0x0003,
            textRecordCount = 0,
            firstNonText = 3,
        )
        val text = MobiParser.parse(file).chapters.joinToString("") { it.text }
        assertEquals("hello潮水", text)
        file.delete()
    }

    @Test
    fun shortRecordCountThatStopsBeforeTheBodyUsesFirstNonText() {
        val shell = "<html><body></body></html>".toByteArray(Charsets.UTF_8)
        val body = "<p>潮水</p>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(shell, body),
            textLength = shell.size,
            extraFlags = null,
            textRecordCount = 1,
            firstNonText = 3,
        )
        val text = MobiParser.parse(file).chapters.joinToString("") { it.text }
        assertTrue(text.contains("潮水"))
        file.delete()
    }

    @Test
    fun mobi7StubDoesNotHideLongerKf8TextBehindBoundary() {
        val stub = "<p>目录</p>".toByteArray(Charsets.UTF_8)
        val body = "<p>第一章潮水很深，船还在江心。</p>".toByteArray(Charsets.UTF_8)
        val file = writeHybrid(
            shell = stub,
            bodyCompression = 1,
            bodyEncoding = 65001,
            bodyRecords = listOf(body),
            bodyTextLength = body.size,
            bodyFlags = null,
            boundary = true,
        )
        val text = MobiParser.parse(file).chapters.joinToString("") { it.text }
        assertTrue(text.contains("潮水"))
        assertTrue(text.contains("江心"))
        file.delete()
    }

    @Test
    fun longerMobi7TextBeatsAShorterLaterHeader() {
        val book = "<p>第一章潮水很深，船还在江心。</p>".toByteArray(Charsets.UTF_8)
        val stub = "<p>目录</p>".toByteArray(Charsets.UTF_8)
        val file = writeHybrid(
            shell = book,
            bodyCompression = 1,
            bodyEncoding = 65001,
            bodyRecords = listOf(stub),
            bodyTextLength = stub.size,
            bodyFlags = null,
            boundary = true,
        )
        val text = MobiParser.parse(file).chapters.joinToString("") { it.text }
        assertTrue(text.contains("江心"))
        file.delete()
    }

    @Test
    fun readableMobi7IsKeptWhenTheKf8HalfIsHuff() {
        val book = "<p>潮水</p>".toByteArray(Charsets.UTF_8)
        val file = writeHybrid(
            shell = book,
            bodyCompression = 17480,
            bodyEncoding = 65001,
            bodyRecords = listOf(byteArrayOf(1)),
            bodyTextLength = 1,
            bodyFlags = null,
            boundary = true,
        )
        val text = MobiParser.parse(file).chapters.joinToString("") { it.text }
        assertTrue(text.contains("潮水"))
        file.delete()
    }

    @Test
    fun kf8BodyAfterEmptyShellIsNotReportedAsEmpty() {
        val shell = "<html><body></body></html>".toByteArray(Charsets.UTF_8)
        val part1 = "hello"
        val part2 = "潮水"
        val trailer = byteArrayOf('Q'.code.toByte(), 0x01, 'Z'.code.toByte(), 0x82.toByte())
        val record1 = palmDocLiterals(part1.toByteArray(Charsets.UTF_8)) + trailer
        val record2 = palmDocLiterals(part2.toByteArray(Charsets.UTF_8)) + trailer
        val textLength = part1.toByteArray(Charsets.UTF_8).size + part2.toByteArray(Charsets.UTF_8).size
        val file = writeHybrid(
            shell = shell,
            bodyCompression = 2,
            bodyEncoding = 65001,
            bodyRecords = listOf(record1, record2),
            bodyTextLength = textLength,
            bodyFlags = 0x0003,
        )
        val text = MobiParser.parse(file).chapters.joinToString("") { it.text }
        assertEquals("hello潮水", text)
        file.delete()
    }

    @Test
    fun emptyShellWithHuffBodyReportsHuffNotEmptyText() {
        val shell = "<html><body></body></html>".toByteArray(Charsets.UTF_8)
        val file = writeHybrid(
            shell = shell,
            bodyCompression = 17480,
            bodyEncoding = 65001,
            bodyRecords = listOf(byteArrayOf(1)),
            bodyTextLength = 1,
            bodyFlags = null,
        )
        assertChinese(file, "Huff")
        file.delete()
    }

    @Test
    fun huffAndEncryptedMobiFailInChinese() {
        val huff = writeMobi(compression = 17480, encoding = 65001, records = listOf(byteArrayOf(1)), textLength = 1, extraFlags = null)
        val encrypted = writeMobi(compression = 2, encoding = 65001, records = listOf(palmDocLiterals("hello".toByteArray())), textLength = 5, extraFlags = null, encryption = 1)
        assertChinese(huff, "Huff")
        assertChinese(encrypted, "加密")
        huff.delete()
        encrypted.delete()
    }

    @Test
    fun novelMobiDropsMarkupSoupButKeepsProse() {
        val html = "<p>第一章潮水很深，船还在江心。</p><mbp:pagebreak/><img recindex=\"00001\" alt=\"彩页".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
        )
        val opening = MobiParser.opening(file)
        assertTrue(!opening.pictureBook)
        val text = opening.novel!!.chapters.joinToString("") { it.text }
        assertTrue(text.contains("潮水"))
        assertTrue(text.contains("江心"))
        assertTrue(!text.contains("mbp:"))
        assertTrue(!text.contains("alt="))
        assertTrue(!text.contains("<"))
        assertTrue(!text.contains("recindex"))
        file.delete()
    }

    @Test
    fun imageOnlyMobiIsAPictureBookWithDecodablePages() {
        val png = tinyPng()
        val html = "<img recindex=\"00001\"/><mbp:pagebreak/>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png, png, png),
            firstImage = 2,
        )
        val opening = MobiParser.opening(file)
        assertTrue(opening.pictureBook)
        assertEquals(3, opening.images.size)
        opening.images.forEach { bytes ->
            assertTrue(bytes.contentEquals(png))
            val decoded = javax.imageio.ImageIO.read(bytes.inputStream())
            assertTrue(decoded != null && decoded.width == 1 && decoded.height == 1)
        }
        assertChinese(file, "图片页")
        assertChinese(file, "不能当小说打开")
        file.delete()
    }

    @Test
    fun oneImageDoesNotOpenTheBookAsAComic() {
        val png = tinyPng()
        val html = "<img recindex=\"00001\"/><mbp:pagebreak/>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png),
            firstImage = 2,
        )
        assertCleanRefusal(file, "没有从 MOBI 中提取到正文")
        file.delete()
    }

    @Test
    fun imageMarkupWithoutBytesIsAChineseErrorNotSoup() {
        val html = "<img recindex=\"00001\" alt=\"彩页\"/><mbp:pagebreak/>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
        )
        assertCleanRefusal(file, "没有从 MOBI 中提取到正文")
        try {
            MobiParser.imagePages(file)
            fail("expected UnsupportedBookException")
        } catch (error: UnsupportedBookException) {
            assertTrue(error.message.orEmpty().contains("没有可显示的图片"))
            assertNoTagSoup(error.message.orEmpty())
        }
        file.delete()
    }

    @Test
    fun screenshotSoupWithThreeImagesOpensAsPicturePages() {
        val png = tinyPng()
        val gif = tinyGif()
        val html = "\uFFFD\"00139\" alt=\"第 138 頁\"/>\n\n<mbp:pa".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png, png, gif),
            firstImage = 2,
        )
        val opening = MobiParser.opening(file)
        assertTrue(opening.pictureBook)
        assertEquals(3, opening.images.size)
        assertEquals(listOf("png", "png", "gif"), opening.images.map { ImageSniff.extension(it) })
        assertCleanRefusal(file, "不能当小说打开")
        file.delete()
    }

    @Test
    fun twoImagesDoNotOpenBrokenMarkupAsAComic() {
        val png = tinyPng()
        val html = "\uFFFD\"00139\" alt=\"第 138 頁\"/>\n\n<mbp:pa".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png, png),
            firstImage = 2,
        )
        assertCleanRefusal(file, "没有从 MOBI 中提取到正文")
        file.delete()
    }

    @Test
    fun screenshotSoupWithoutImagesIsChineseRefusalNotMarkup() {
        val html = "\uFFFD\"00139\" alt=\"第 138 頁\"/>\n\n<mbp:pa".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
        )
        assertCleanRefusal(file, "没有从 MOBI 中提取到正文")
        file.delete()
    }

    @Test
    fun pagebreakOnlyMobiWithThreeImagesIsAPictureBook() {
        val png = tinyPng()
        val html = "<mbp:pagebreak/>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png, png, png),
            firstImage = 2,
        )
        val opening = MobiParser.opening(file)
        assertTrue(opening.pictureBook)
        assertEquals(3, opening.images.size)
        assertCleanRefusal(file, "不能当小说打开")
        file.delete()
    }

    @Test
    fun pagebreakOnlyMobiWithoutImagesRefusesInChinese() {
        val html = "<mbp:pagebreak/><mbp:pa".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
        )
        assertCleanRefusal(file, "没有从 MOBI 中提取到正文")
        file.delete()
    }

    @Test
    fun brokenAltDoesNotBecomeAChapterAndProseSurvives() {
        val html = (
            "第一章 潮水\n" +
                "正文甲很深，船还在江边。\n" +
                "\uFFFD\"00139\" alt=\"第138章 假标题\"/>\n" +
                "<mbp:pa\n" +
                "第二章 江心\n" +
                "后文还在。\n"
            ).toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
        )
        val novel = MobiParser.parse(file)
        val titles = novel.chapters.map { it.title }
        assertEquals(listOf("第一章 潮水", "第二章 江心"), titles)
        val text = novel.chapters.joinToString("\n") { it.text }
        assertTrue(text.contains("正文甲很深"))
        assertTrue(text.contains("后文还在"))
        assertTrue(!text.contains("假标题"))
        assertTrue(!text.contains("00139"))
        assertTrue(!text.contains("頁"))
        assertTrue(!text.contains("\uFFFD"))
        assertNoTagSoup(text)
        novel.chapters.forEach { chapter ->
            assertNoTagSoup(chapter.title)
            assertNoTagSoup(chapter.text)
        }
        file.delete()
    }

    @Test
    fun tagSplitAcrossTextRecordsIsStrippedBeforeChapters() {
        val part1 = "<p>第一章 潮水很深</p><mbp:page".toByteArray(Charsets.UTF_8)
        val part2 = "break/><p>船还在江心。</p>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(part1, part2),
            textLength = part1.size + part2.size,
            extraFlags = null,
        )
        val novel = MobiParser.parse(file)
        val text = novel.chapters.joinToString("\n") { it.text }
        assertTrue(text.contains("潮水很深"))
        assertTrue(text.contains("江心"))
        assertTrue(!text.contains("break"))
        assertTrue(!text.contains("page"))
        assertNoTagSoup(text)
        file.delete()
    }

    @Test
    fun pageNumberCaptionsWithImagesOpenAsPagesNotProse() {
        val png = tinyPng()
        val gif = tinyGif()
        val html = (1..4).joinToString("\n") { index ->
            "<p>第 $index 頁</p><img recindex=\"${index.toString().padStart(5, '0')}\"/><mbp:pagebreak/>"
        }.toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png, png, gif),
            firstImage = 2,
        )
        val opening = MobiParser.opening(file)
        assertTrue(opening.pictureBook)
        assertEquals(3, opening.images.size)
        assertEquals(listOf("png", "png", "gif"), opening.images.map { ImageSniff.extension(it) })
        assertCleanRefusal(file, "不能当小说打开")
        file.delete()
    }

    @Test
    fun barePageNumbersWithImagesOpenAsPagesNotProse() {
        val png = tinyPng()
        val gif = tinyGif()
        val html = (1..4).joinToString("\n") { index ->
            "<p>${index}页</p><img recindex=\"${index.toString().padStart(5, '0')}\"/><mbp:pagebreak/>"
        }.toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png, png, gif),
            firstImage = 2,
        )
        val opening = MobiParser.opening(file)
        assertTrue(opening.pictureBook)
        assertEquals(3, opening.images.size)
        assertCleanRefusal(file, "不能当小说打开")
        file.delete()
    }

    @Test
    fun novelMobiShowsRecindexImageInsteadOfOnlyThePageCaption() {
        val png = tinyPng()
        val html = "<p>第一章潮水很深，船还在江心。</p><p>第 2 页</p><img recindex=\"00001\"/>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png),
            firstImage = 2,
        )
        val chapter = MobiParser.parse(file).chapters.single()
        val plates = chapter.spans.filterIsInstance<NovelSpan.Plate>()
        assertEquals(1, plates.size)
        assertTrue(plates.single().bytes.contentEquals(png))
        val prose = chapter.spans.filterIsInstance<NovelSpan.Prose>().joinToString("") { it.text }
        assertTrue(prose.contains("潮水"))
        assertTrue(prose.contains("江心"))
        assertTrue(!prose.contains("第 2 页"))
        file.delete()
    }

    @Test
    fun coverLabelAndKindleEmbedsOpenAsPicturePages() {
        val png = tinyPng()
        val gif = tinyGif()
        val html = buildString {
            append("<p>封面</p>")
            listOf("0001", "0002", "0003").forEachIndexed { index, id ->
                append("<p>第 ${index + 1} 頁</p><img src=\"kindle:embed:$id?mime=image/jpg\"/>")
            }
            append("<p>THE END</p>")
            append("/*\n * Copyright (c) 2014-2024 VOLUME.HK\n */\n")
            append("html{color:#000;background:#FFF;}\n")
            append("div.fs {\nheight: 1680px;\nwidth: 1264px;\n}\n")
        }.toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png, png, gif),
            firstImage = 2,
        )
        val opening = MobiParser.opening(file)
        assertTrue(opening.pictureBook)
        assertEquals(listOf("png", "png", "gif"), opening.images.map { ImageSniff.extension(it) })
        assertCleanRefusal(file, "不能当小说打开")
        file.delete()
    }

    @Test
    fun novelMobiShowsKindleEmbedImage() {
        val png = tinyPng()
        val html = "<p>第一章潮水很深，船还在江心。</p><img src=\"kindle:embed:0001?mime=image/jpg\"/>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png),
            firstImage = 2,
        )
        val chapter = MobiParser.parse(file).chapters.single()
        val plates = chapter.spans.filterIsInstance<NovelSpan.Plate>()
        assertEquals(1, plates.size)
        assertTrue(plates.single().bytes.contentEquals(png))
        val prose = chapter.spans.filterIsInstance<NovelSpan.Prose>().joinToString("") { it.text }
        assertTrue(prose.contains("潮水"))
        assertTrue(prose.contains("江心"))
        file.delete()
    }

    @Test
    fun proseThatMentionsAPageNumberStaysANovel() {
        val png = tinyPng()
        val html = "<p>第一章潮水很深，船还在第 3 页的江心。</p><img recindex=\"00001\"/>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png, png, png),
            firstImage = 2,
        )
        val opening = MobiParser.opening(file)
        assertTrue(!opening.pictureBook)
        val text = opening.novel!!.chapters.joinToString("") { it.text }
        assertTrue(text.contains("潮水"))
        assertTrue(text.contains("江心"))
        file.delete()
    }

    @Test
    fun proseWithACoverImageStaysANovel() {
        val png = tinyPng()
        val html = "<p>第一章潮水很深，船还在江心。</p><mbp:pagebreak/><img recindex=\"00001\" alt=\"彩页\"/>".toByteArray(Charsets.UTF_8)
        val file = writeMobi(
            compression = 1,
            encoding = 65001,
            records = listOf(html),
            textLength = html.size,
            extraFlags = null,
            extraRecords = listOf(png),
            firstImage = 2,
        )
        val opening = MobiParser.opening(file)
        assertTrue(!opening.pictureBook)
        val text = opening.novel!!.chapters.joinToString("") { it.text }
        assertTrue(text.contains("潮水"))
        assertTrue(text.contains("江心"))
        assertNoTagSoup(text)
        file.delete()
    }

    @Test
    fun jointMobiKeepsTheLongerImageRunWhenKf8TextWins() {
        val png = tinyPng()
        val gif = tinyGif()
        val shortText = "<p>目录</p>".toByteArray(Charsets.UTF_8)
        val longText = "<p>第一章潮水很深，船还在江心。后文还在这里。</p>".toByteArray(Charsets.UTF_8)
        val shell = mobiHeader(
            compression = 1,
            encoding = 65001,
            textRecords = 1,
            textLength = shortText.size,
            extraFlags = null,
            firstImage = 2,
        )
        val body = mobiHeader(
            compression = 1,
            encoding = 65001,
            textRecords = 1,
            textLength = longText.size,
            extraFlags = null,
            firstImage = 9,
        )
        val file = File.createTempFile("manjuan", ".mobi")
        file.writeBytes(
            pdb(
                listOf(
                    shell,
                    shortText,
                    png,
                    png,
                    png,
                    gif,
                    "BOUNDARY".toByteArray(Charsets.US_ASCII),
                    body,
                    longText,
                    png,
                    png,
                    gif,
                ),
            ),
        )
        val pages = MobiParser.imagePages(file)
        assertEquals(4, pages.size)
        assertEquals(listOf("png", "png", "png", "gif"), pages.map { ImageSniff.extension(it) })
        val text = MobiParser.parse(file).chapters.joinToString("") { it.text }
        assertTrue(text.contains("潮水"))
        assertTrue(text.contains("江心"))
        file.delete()
    }

    @Test
    fun huffAfterPagebreakShellStillReportsHuff() {
        val shell = "<html><body><mbp:pagebreak/></body></html>".toByteArray(Charsets.UTF_8)
        val file = writeHybrid(
            shell = shell,
            bodyCompression = 17480,
            bodyEncoding = 65001,
            bodyRecords = listOf(byteArrayOf(1)),
            bodyTextLength = 1,
            bodyFlags = null,
        )
        assertChinese(file, "Huff")
        file.delete()
    }

    @Test
    fun undecodableBytesAreNotShownAsChapters() {
        val bytes = ByteArray(64) { 0xFF.toByte() }
        val file = writeMobi(compression = 1, encoding = 65001, records = listOf(bytes), textLength = bytes.size, extraFlags = null)
        assertChinese(file, "解码")
        file.delete()
    }

    private fun assertChinese(file: File, snippet: String) {
        try {
            MobiParser.parse(file)
            fail("expected UnsupportedBookException")
        } catch (error: UnsupportedBookException) {
            assertTrue(error.message.orEmpty().contains(snippet))
        }
    }

    private fun assertCleanRefusal(file: File, snippet: String) {
        try {
            MobiParser.parse(file)
            fail("expected UnsupportedBookException")
        } catch (error: UnsupportedBookException) {
            val message = error.message.orEmpty()
            assertTrue(message, message.contains(snippet))
            assertNoTagSoup(message)
        }
    }

    private fun assertNoTagSoup(text: String) {
        assertTrue(text, !text.contains("alt="))
        assertTrue(text, !text.contains("mbp:"))
        assertTrue(text, !text.contains("recindex"))
        assertTrue(text, !text.contains("<"))
        assertTrue(text, !text.contains("/>"))
        assertTrue(text, !text.contains("\uFFFD"))
    }

    private fun palmDocLiterals(raw: ByteArray): ByteArray {
        val out = ArrayList<Byte>(raw.size + 8)
        var index = 0
        while (index < raw.size) {
            val value = raw[index].toInt() and 0xFF
            if (value < 0x80) {
                out += raw[index]
                index++
                continue
            }
            var run = 0
            while (run < 8 && index + run < raw.size && (raw[index + run].toInt() and 0xFF) >= 0x80) run++
            out += run.toByte()
            for (step in 0 until run) out += raw[index + step]
            index += run
        }
        return out.toByteArray()
    }

    private fun writeMobi(
        compression: Int,
        encoding: Int,
        records: List<ByteArray>,
        textLength: Int,
        extraFlags: Int?,
        encryption: Int = 0,
        title: String = "测试",
        textRecordCount: Int? = null,
        firstNonText: Int = 0,
        extraRecords: List<ByteArray> = emptyList(),
        firstImage: Int = 0,
    ): File {
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val headerSize = if (extraFlags == null) 144 else 0xF4
        val header = ByteArray(headerSize + titleBytes.size)
        put16(header, 0, compression)
        put32(header, 4, textLength)
        put16(header, 8, textRecordCount ?: records.size)
        put16(header, 10, 4096)
        put16(header, 12, encryption)
        put32(header, 0x50, firstNonText)
        put32(header, 0x6C, firstImage)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(header, 16)
        put32(header, 20, if (extraFlags == null) 128 else 0xE8)
        put32(header, 24, 2)
        put32(header, 28, encoding)
        put32(header, 16 + 0x44, headerSize)
        put32(header, 16 + 0x48, titleBytes.size)
        if (extraFlags != null) {
            put32(header, 0x68, 6)
            put16(header, 0xF2, extraFlags)
        }
        titleBytes.copyInto(header, headerSize)

        val recordCount = records.size + extraRecords.size + 1
        val pdb = ByteArray(78 + recordCount * 8)
        "Book".toByteArray(Charsets.US_ASCII).copyInto(pdb, 0)
        "BOOK".toByteArray(Charsets.US_ASCII).copyInto(pdb, 60)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(pdb, 64)
        put16(pdb, 76, recordCount)
        var cursor = pdb.size + header.size
        val blobs = ArrayList<ByteArray>(recordCount)
        blobs += header
        put32(pdb, 78, pdb.size)
        records.forEachIndexed { index, record ->
            put32(pdb, 78 + (index + 1) * 8, cursor)
            blobs += record
            cursor += record.size
        }
        extraRecords.forEachIndexed { index, record ->
            put32(pdb, 78 + (records.size + 1 + index) * 8, cursor)
            blobs += record
            cursor += record.size
        }
        val file = File.createTempFile("manjuan", ".mobi")
        file.writeBytes(pdb + blobs.reduce { left, right -> left + right })
        return file
    }

    private fun writeHybrid(
        shell: ByteArray,
        bodyCompression: Int,
        bodyEncoding: Int,
        bodyRecords: List<ByteArray>,
        bodyTextLength: Int,
        bodyFlags: Int?,
        boundary: Boolean = false,
    ): File {
        val shellHeader = mobiHeader(
            compression = 1,
            encoding = 65001,
            textRecords = 1,
            textLength = shell.size,
            extraFlags = null,
        )
        val bodyHeader = mobiHeader(
            compression = bodyCompression,
            encoding = bodyEncoding,
            textRecords = bodyRecords.size,
            textLength = bodyTextLength,
            extraFlags = bodyFlags,
        )
        val blobs = ArrayList<ByteArray>()
        blobs += shellHeader
        blobs += shell
        if (boundary) blobs += "BOUNDARY".toByteArray(Charsets.US_ASCII)
        blobs += bodyHeader
        blobs += bodyRecords
        val file = File.createTempFile("manjuan", ".mobi")
        file.writeBytes(pdb(blobs))
        return file
    }

    private fun mobiHeader(
        compression: Int,
        encoding: Int,
        textRecords: Int,
        textLength: Int,
        extraFlags: Int?,
        title: String = "测试",
        firstImage: Int = 0,
    ): ByteArray {
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val headerSize = if (extraFlags == null) 144 else 0xF4
        val header = ByteArray(headerSize + titleBytes.size)
        put16(header, 0, compression)
        put32(header, 4, textLength)
        put16(header, 8, textRecords)
        put16(header, 10, 4096)
        put32(header, 0x6C, firstImage)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(header, 16)
        put32(header, 20, if (extraFlags == null) 128 else 0xE8)
        put32(header, 24, 2)
        put32(header, 28, encoding)
        put32(header, 16 + 0x44, headerSize)
        put32(header, 16 + 0x48, titleBytes.size)
        if (extraFlags != null) {
            put32(header, 0x68, 6)
            put16(header, 0xF2, extraFlags)
        }
        titleBytes.copyInto(header, headerSize)
        return header
    }

    private fun pdb(records: List<ByteArray>): ByteArray {
        val pdb = ByteArray(78 + records.size * 8)
        "Book".toByteArray(Charsets.US_ASCII).copyInto(pdb, 0)
        "BOOK".toByteArray(Charsets.US_ASCII).copyInto(pdb, 60)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(pdb, 64)
        put16(pdb, 76, records.size)
        var cursor = pdb.size
        val blobs = ArrayList<ByteArray>()
        records.forEachIndexed { index, record ->
            put32(pdb, 78 + index * 8, cursor)
            blobs += record
            cursor += record.size
        }
        return pdb + blobs.reduce { left, right -> left + right }
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
