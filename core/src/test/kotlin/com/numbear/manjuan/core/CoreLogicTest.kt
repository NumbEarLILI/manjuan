package com.numbear.manjuan.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreLogicTest {
    @Test
    fun detectsPdfEpubMobiCbzAndRejectsMismatch() {
        val pdf = FormatDetector.detectFile("book.pdf", "%PDF-1.7".toByteArray())
        assertEquals(BookFormat.PDF, pdf.format)

        val epub = FormatDetector.detectFile("book.epub", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        assertEquals(BookFormat.EPUB, epub.format)

        val badEpub = FormatDetector.detectFile("book.epub", "hello".toByteArray())
        assertEquals(BookFormat.UNSUPPORTED, badEpub.format)
        assertTrue(badEpub.error!!.contains("EPUB"))

        val header = ByteArray(68)
        "BOOKMOBI".toByteArray().copyInto(header, 60)
        assertEquals(BookFormat.MOBI, FormatDetector.detectFile("story.mobi", header).format)
        assertEquals(BookFormat.AZW3, FormatDetector.detectFile("story.azw3", header).format)

        val rar = byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(), 0x1A, 0x07, 0x00)
        assertEquals(BookFormat.CBR, FormatDetector.detectFile("comic.cbr", rar).format)

        val cbz = FormatDetector.detectFile(
            "comic.cbz",
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            zipPaths = listOf("001.jpg", "002.png"),
        )
        assertEquals(BookFormat.CBZ, cbz.format)

        val zip = FormatDetector.detectFile(
            "pages.zip",
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            zipPaths = listOf("a/01.webp"),
        )
        assertEquals(BookFormat.ZIP_IMAGES, zip.format)

        assertEquals(
            BookFormat.ZIP_IMAGES,
            FormatDetector.detectFile("pages.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04)).format,
        )
        val emptyZip = FormatDetector.detectFile("notes.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04), zipPaths = listOf("readme.txt"))
        assertEquals(BookFormat.UNSUPPORTED, emptyZip.format)

        val folder = FormatDetector.detectDirectory("卷一", listOf("01.jpg", "note.txt"))
        assertEquals(BookFormat.IMAGE_FOLDER, folder.format)
        assertEquals(BookFormat.UNSUPPORTED, FormatDetector.detectDirectory("空", listOf("a.txt")).format)
    }

    @Test
    fun decodesUtf8BomAndGb18030() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray()
        val utf = TextEncoding.decode(bom)
        assertEquals("你好", utf.text)
        assertEquals("UTF-8", utf.charset)

        val gb = "第一章 开始".toByteArray(Charset.forName("GB18030"))
        val decoded = TextEncoding.decode(gb)
        assertEquals("第一章 开始", decoded.text)
        assertEquals("GB18030", decoded.charset)
    }

    @Test
    fun splitsChineseChaptersAndPaginates() {
        val text = "开场白\n\n第一章 起\n甲乙丙丁\n第二章 承\n戊己庚辛"
        val chapters = TxtChapters.split(text)
        assertEquals(listOf("前言", "第一章 起", "第二章 承"), chapters.map { it.title })
        assertTrue(text.substring(chapters[1].start, chapters[1].end).contains("甲乙丙丁"))

        val pages = TextPaginator.pages("abcdefghij", charsPerLine = 4, linesPerPage = 2)
        assertEquals(listOf("abcdefgh", "ij"), pages.map { it.text })
        assertEquals(0, pages[0].start)
    }

    @Test
    fun stripsHtmlAndSortsNaturally() {
        val plain = HtmlText.toPlain("<h1>标题</h1><p>甲&amp;乙</p><script>no</script><br/>丙")
        assertTrue(plain.contains("标题"))
        assertTrue(plain.contains("甲&乙"))
        assertTrue(!plain.contains("no"))
        assertTrue(plain.contains("丙"))
        val soup = HtmlText.toPlain("<p>潮水</p><mbp:pagebreak/><img recindex=\"00001\" alt=\"彩页")
        assertTrue(soup.contains("潮水"))
        assertTrue(!soup.contains("mbp:"))
        assertTrue(!soup.contains("alt="))
        assertTrue(!soup.contains("<"))
        assertTrue(!soup.contains("recindex"))

        val names = listOf("page10.jpg", "page2.jpg", "page1.jpg").sortedWith(NaturalSort)
        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg"), names)
    }

    @Test
    fun parsesSyntheticEpub() {
        val file = File.createTempFile("manjuan", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""".toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/book.opf"))
            zip.write(
                """
                <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata><dc:title>测试书</dc:title><dc:creator>作者甲</dc:creator></metadata>
                  <manifest>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/c1.xhtml"))
            zip.write("<html><body><h1>第一章</h1><p>正文你好</p></body></html>".toByteArray())
            zip.closeEntry()
        }
        val book = EpubParser.parse(file)
        assertEquals("测试书", book.title)
        assertEquals("作者甲", book.author)
        assertEquals(1, book.chapters.size)
        assertTrue(book.chapters[0].text.contains("正文你好"))
        file.delete()
    }

    @Test
    fun epubColorPlateIsDecodedAndKeptBesideProse() {
        val png = tinyPng()
        val file = File.createTempFile("manjuan", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""".toByteArray(),
            )
            put(
                "OEBPS/book.opf",
                """
                <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata><dc:title>彩页书</dc:title><dc:creator>作者甲</dc:creator></metadata>
                  <manifest>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="plate" href="plate.xhtml" media-type="application/xhtml+xml"/>
                    <item id="img" href="images/plate.png" media-type="image/png"/>
                  </manifest>
                  <spine><itemref idref="plate"/><itemref idref="c1"/></spine>
                </package>
                """.trimIndent().toByteArray(),
            )
            put(
                "OEBPS/plate.xhtml",
                """<html><body><img src="images/plate.png" alt="彩页"/></body></html>""".toByteArray(),
            )
            put(
                "OEBPS/c1.xhtml",
                """<html><body><h1>第一章</h1><p>正文你好</p><img src="images/plate.png" alt="插图"/><p>潮水</p></body></html>""".toByteArray(),
            )
            put("OEBPS/images/plate.png", png)
        }
        val book = EpubParser.parse(file)
        assertEquals(2, book.chapters.size)
        val plate = book.chapters[0]
        assertEquals("彩页", plate.title)
        val plateBytes = plate.spans.filterIsInstance<NovelSpan.Plate>().single().bytes
        assertTrue(plateBytes.contentEquals(png))
        val decoded = javax.imageio.ImageIO.read(plateBytes.inputStream())
        assertTrue(decoded != null && decoded.width == 1 && decoded.height == 1)
        val prose = book.chapters[1]
        assertTrue(prose.text.contains("正文你好"))
        assertTrue(prose.text.contains("潮水"))
        assertTrue(!prose.text.contains("alt="))
        assertTrue(!prose.text.contains("<img"))
        val pages = NovelPages.pages(prose, charsPerLine = 40, linesPerPage = 20)
        assertTrue(pages.any { it is NovelPages.Page.Picture && (it as NovelPages.Page.Picture).bytes.contentEquals(png) })
        assertTrue(pages.filterIsInstance<NovelPages.Page.Words>().joinToString("") { it.text }.contains("潮水"))
        file.delete()
    }

    @Test
    fun decompressesPalmDocAndReadsMobi() {
        val compressed = byteArrayOf(
            'a'.code.toByte(),
            'b'.code.toByte(),
            'c'.code.toByte(),
            0x80.toByte(),
            24,
        )
        assertEquals("abcabc", PalmDoc.decompress(compressed).toString(Charsets.US_ASCII))

        val title = "测试"
        val body = "<p>第一章 海</p><p>潮水</p>"
        val file = File.createTempFile("manjuan", ".mobi")
        file.writeBytes(syntheticMobi(title, body))
        val novel = MobiParser.parse(file)
        assertEquals(title, novel.title)
        assertTrue(novel.chapters.joinToString { it.text }.contains("潮水"))
        file.delete()
    }

    @Test
    fun mergesProgressByTimestampThenPercent() {
        val older = ReadingProgress("c=0;o=1", 0.1f, 10)
        val newer = ReadingProgress("c=1;o=2", 0.4f, 20)
        assertEquals(newer, ProgressMerge.merge(older, newer))
        val high = ReadingProgress("c=2;o=3", 0.9f, 20)
        assertEquals(high, ProgressMerge.merge(newer, high))
        assertEquals(older, ProgressMerge.merge(older, null))
    }

    @Test
    fun parsesPropfindAndDownloadsFromMockServer() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/</d:href>
                <d:propstat><d:prop><d:displayname>dav</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/b.txt</d:href>
                <d:propstat><d:prop><d:displayname>b.txt</d:displayname><d:getcontentlength>4</d:getcontentlength><d:resourcetype/></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/dir/</d:href>
                <d:propstat><d:prop><d:displayname>dir</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = WebDavXml.parse(xml, "/dav/")
        assertEquals(listOf("dir", "b.txt"), entries.map { it.name })
        assertTrue(entries.first().directory)

        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            server.enqueue(MockResponse().setResponseCode(200).setBody("demo"))
            val client = OkHttpWebDavClient(server.url("/dav/").toString(), "user", "secret")
            assertTrue(client.test() is WebDavStatus.Ok)
            val listed = client.list("")
            assertEquals("b.txt", listed.first { !it.directory }.name)
            val recorded = server.takeRequest()
            assertEquals("PROPFIND", recorded.method)
            assertTrue(recorded.getHeader("Authorization")!!.startsWith("Basic "))
            val dest = File.createTempFile("dav", ".txt")
            client.download("/dav/b.txt", dest)
            assertEquals("demo", dest.readText())
            dest.delete()
        } finally {
            server.shutdown()
        }
    }
}

private fun syntheticMobi(title: String, html: String): ByteArray {
    val titleBytes = title.toByteArray(Charsets.UTF_8)
    val textBytes = html.toByteArray(Charsets.UTF_8)
    val header = ByteArray(144 + titleBytes.size)
    fun put16(offset: Int, value: Int) {
        header[offset] = (value shr 8).toByte()
        header[offset + 1] = value.toByte()
    }
    fun put32(offset: Int, value: Int) {
        header[offset] = (value shr 24).toByte()
        header[offset + 1] = (value shr 16).toByte()
        header[offset + 2] = (value shr 8).toByte()
        header[offset + 3] = value.toByte()
    }
    put16(0, 1)
    put32(4, textBytes.size)
    put16(8, 1)
    put16(10, 4096)
    "MOBI".toByteArray().copyInto(header, 16)
    put32(20, 128)
    put32(24, 2)
    put32(28, 65001)
    put32(16 + 0x44, 144)
    put32(16 + 0x48, titleBytes.size)
    titleBytes.copyInto(header, 144)

    val recordCount = 2
    val pdb = ByteArray(78 + recordCount * 8)
    "Book".toByteArray().copyInto(pdb, 0)
    "BOOK".toByteArray().copyInto(pdb, 60)
    "MOBI".toByteArray().copyInto(pdb, 64)
    pdb[76] = 0
    pdb[77] = recordCount.toByte()
    val record0 = 78 + recordCount * 8
    val record1 = record0 + header.size
    fun putOffset(index: Int, offset: Int) {
        val at = 78 + index * 8
        pdb[at] = (offset shr 24).toByte()
        pdb[at + 1] = (offset shr 16).toByte()
        pdb[at + 2] = (offset shr 8).toByte()
        pdb[at + 3] = offset.toByte()
    }
    putOffset(0, record0)
    putOffset(1, record1)
    return pdb + header + textBytes
}
