package com.numbear.manjuan.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class WebDavBookRouteTest {
    private val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(60) { 'a'.code.toByte() }
    private val rarHeader = byteArrayOf(
        'R'.code.toByte(),
        'a'.code.toByte(),
        'r'.code.toByte(),
        '!'.code.toByte(),
        0x1A,
        0x07,
        0x00,
    )

    @Test
    fun webDavEntriesOfComicArchivesAreNeverNovels() {
        assertComic("第1话", "/dav/comics/第1话.cbz", zipHeader, BookFormat.CBZ)
        assertComic("第1话", "/dav/comics/第1话", zipHeader, BookFormat.ZIP_IMAGES)
        assertComic("story.cbr", "/dav/story.cbr", rarHeader, BookFormat.CBR)
        assertComic("pages.zip", "/dav/pages.zip", zipHeader, BookFormat.ZIP_IMAGES)
        assertComic("pages", "/dav/pages/", ByteArray(0), BookFormat.IMAGE_FOLDER, directory = true)

        val pdf = "%PDF-1.7".toByteArray()
        val pdfBook = WebDavBooks.classify("a.pdf", "/dav/a.pdf", directory = false, pdf)
        assertEquals(BookFormat.PDF, pdfBook.format)
        assertEquals(BookKind.PDF, pdfBook.format.kind())

        val text = "第一章 正文".toByteArray(Charsets.UTF_8)
        val novel = WebDavBooks.classify("notes.txt", "/dav/notes.txt", directory = false, text)
        assertEquals(BookFormat.TXT, novel.format)
        assertEquals(BookKind.NOVEL, novel.format.kind())
        val markdown = WebDavBooks.classify("笔记.md", "/dav/笔记.md", directory = false, "# 第一章\n甲".toByteArray())
        assertEquals(BookFormat.MARKDOWN, markdown.format)
        assertEquals(BookKind.NOVEL, markdown.format.kind())
    }

    @Test
    fun openRouteIgnoresWrongNovelKindForComics() {
        val zip = zipHeader
        assertEquals(
            BookKind.COMIC,
            WebDavBooks.resolve("CBZ", "NOVEL", "/dav/a.cbz", "/dav/a.cbz", "a", zip).format.kind(),
        )
        assertEquals(
            BookKind.COMIC,
            WebDavBooks.resolve("TXT", "NOVEL", "/dav/a.cbz", "/dav/a.cbz", "a", ByteArray(0)).format.kind(),
        )
        assertEquals(
            BookKind.COMIC,
            WebDavBooks.resolve("TXT", "NOVEL", "/dav/a.cbr", "/dav/a.cbr", "a", rarHeader).format.kind(),
        )
        assertEquals(
            BookKind.COMIC,
            WebDavBooks.resolve("TXT", "NOVEL", "/dav/pages.zip", "/dav/pages.zip", "pages", zip).format.kind(),
        )
        assertEquals(
            BookKind.COMIC,
            WebDavBooks.resolve("TXT", "NOVEL", "/dav/folder", "/dav/folder", "folder", ByteArray(0), cachedImageCount = 3).format.kind(),
        )
        assertEquals(
            BookFormat.IMAGE_FOLDER,
            WebDavBooks.resolve("TXT", "NOVEL", "saf:tree", "", "图片夹", ByteArray(0)).format,
        )
        assertEquals(
            BookKind.PDF,
            WebDavBooks.resolve("TXT", "NOVEL", "/dav/a.pdf", "/dav/a.pdf", "a", "%PDF-1.4".toByteArray()).format.kind(),
        )
        val prose = WebDavBooks.resolve("TXT", "NOVEL", "/dav/a.txt", "/dav/a.txt", "a", "你好".toByteArray())
        assertEquals(BookKind.NOVEL, prose.format.kind())
        assertEquals(BookFormat.TXT, prose.format)
        val epub = WebDavBooks.resolve("EPUB", "NOVEL", "/dav/book.epub", "/dav/book.epub", "book", zip)
        assertEquals(BookKind.NOVEL, epub.format.kind())
        assertEquals(BookFormat.EPUB, epub.format)
    }

    @Test
    fun mobiBytesStoredAsTxtOpenAsMobiNotPlainText() {
        val header = ByteArray(80)
        "BOOK".toByteArray(Charsets.US_ASCII).copyInto(header, 60)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(header, 64)
        val byPath = WebDavBooks.resolve("TXT", "NOVEL", "/dav/三体.mobi", "/dav/三体.mobi", "三体", header)
        assertEquals(BookFormat.MOBI, byPath.format)
        assertEquals(BookKind.NOVEL, byPath.format.kind())
        val byMagic = WebDavBooks.resolve("TXT", "NOVEL", "/dav/三体", "/dav/三体", "三体", header)
        assertEquals(BookFormat.MOBI, byMagic.format)
        val azw3 = WebDavBooks.resolve("TXT", "NOVEL", "/dav/三体.azw3", "/dav/三体.azw3", "三体", header)
        assertEquals(BookFormat.AZW3, azw3.format)
        val azw = WebDavBooks.resolve("TXT", "NOVEL", "/dav/三体.azw", "/dav/三体.azw", "三体", header)
        assertEquals(BookFormat.MOBI, azw.format)
    }

    @Test
    fun webDavRemotePathIsNotALocalFile() {
        val exists = { path: String -> path == "/dav/a.cbz" || path == "/data/book.txt" }
        assertNull(
            WebDavPaths.localReadablePath("WEBDAV", "/dav/a.cbz", "", "/dav/a.cbz", exists),
        )
        assertEquals(
            "/data/book.txt",
            WebDavPaths.localReadablePath("LOCAL", "/data/book.txt", "", "", exists),
        )
        assertEquals(
            "/cache/a.cbz",
            WebDavPaths.localReadablePath(
                "WEBDAV",
                "/dav/a.cbz",
                "/cache/a.cbz",
                "/dav/a.cbz",
            ) { it == "/cache/a.cbz" },
        )
    }

    @Test
    fun joinBaseDoesNotDoubleTheRoot() {
        assertEquals("http://h/dav/", WebDavPaths.joinBase("http://h/dav/", "/"))
        assertEquals("http://h/dav/", WebDavPaths.joinBase("http://h/dav", "dav"))
        assertEquals("http://h/dav/", WebDavPaths.joinBase("http://h/", "/dav"))
        assertEquals("http://h/dav/books/", WebDavPaths.joinBase("http://h/dav/", "/dav/books"))
        assertEquals("http://h/other/dav/", WebDavPaths.joinBase("http://h/other/", "dav"))
    }

    @Test
    fun relativePropfindHrefDownloadsBesideTheCollection() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/comics/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>第1话.cbz</d:href><d:propstat><d:prop><d:displayname>第1话</d:displayname><d:getcontentlength>4</d:getcontentlength><d:resourcetype/></d:prop></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            server.enqueue(MockResponse().setResponseCode(200).setBody("cbz!"))
            val client = OkHttpWebDavClient(server.url("/dav/").toString(), "user", "secret")
            val entry = client.list("comics").first { !it.directory }
            assertEquals("第1话", entry.name)
            val dest = File.createTempFile("comic", ".cbz")
            client.download(entry.path, dest)
            server.takeRequest()
            val get = server.takeRequest()
            assertEquals("/dav/comics/第1话.cbz", URLDecoder.decode(get.path, Charsets.UTF_8.name()))
            assertEquals("cbz!", dest.readText())
            dest.delete()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun namesWithSpacesAndColonsResolveToTheSamePath() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val client = OkHttpWebDavClient(server.url("/dav/").toString(), "user", "secret")
            val dest = File.createTempFile("comic", ".cbz")
            client.download("/dav/Vol.1: Title.cbz", dest)
            val path = URLDecoder.decode(server.takeRequest().path, Charsets.UTF_8.name())
            assertEquals("/dav/Vol.1: Title.cbz", path)
            assertEquals("ok", dest.readText())
            dest.delete()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stalledDownloadFailsWithTimeoutMessage() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = OkHttpClient.Builder()
                .connectTimeout(400, TimeUnit.MILLISECONDS)
                .readTimeout(400, TimeUnit.MILLISECONDS)
                .build()
            val client = OkHttpWebDavClient(server.url("/dav/").toString(), "user", "secret", http)
            val dest = File.createTempFile("comic", ".cbz")
            val error = runCatching { client.download("/dav/a.cbz", dest) }.exceptionOrNull()
            assertTrue(error is UnsupportedBookException)
            assertTrue(error!!.message!!.contains("网络超时"))
            dest.delete()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpFailureIsDownloadErrorNotSilent() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val client = OkHttpWebDavClient(server.url("/dav/").toString(), "user", "secret")
            val dest = File.createTempFile("comic", ".cbz")
            val error = runCatching { client.download("/dav/missing.cbz", dest) }.exceptionOrNull()
            assertTrue(error is UnsupportedBookException)
            assertTrue(error!!.message!!.contains("无法下载"))
            dest.delete()
        } finally {
            server.shutdown()
        }
    }

    private fun assertComic(name: String, path: String, header: ByteArray, format: BookFormat, directory: Boolean = false) {
        val detected = WebDavBooks.classify(name, path, directory, header)
        assertEquals(format, detected.format)
        assertEquals(BookKind.COMIC, detected.format.kind())
    }
}
