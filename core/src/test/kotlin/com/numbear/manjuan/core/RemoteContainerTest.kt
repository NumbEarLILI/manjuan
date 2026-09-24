package com.numbear.manjuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RemoteContainerTest {
    @Test
    fun zipReadsOneEntryFromTheCentralDirectoryWithoutTheOtherPayload() {
        val first = "第一章 只要这一段".toByteArray()
        val second = ByteArray(50_000) { 'B'.code.toByte() }
        val zip = zipOf(
            "META-INF/container.xml" to container("OEBPS/book.opf"),
            "OEBPS/book.opf" to opf(listOf("c1.xhtml", "c2.xhtml")),
            "OEBPS/c1.xhtml" to chapter("第一章", "只要这一段"),
            "OEBPS/c2.xhtml" to second,
        )
        val touched = HashSet<Long>()
        fun read(start: Long, length: Int): ByteArray {
            touched += start
            val from = start.toInt()
            return zip.copyOfRange(from, minOf(zip.size, from + length))
        }
        val entries = RemoteZip.index(zip.size.toLong(), ::read)
        val chapter = RemoteZip.find(entries, "OEBPS/c1.xhtml") ?: error("missing chapter")
        val text = RemoteZip.readEntry(chapter, ::read).toString(Charsets.UTF_8)
        assertTrue(text.contains("只要这一段"))
        val other = RemoteZip.find(entries, "OEBPS/c2.xhtml") ?: error("missing second")
        assertTrue(touched.none { it >= other.localHeaderOffset && it < other.localHeaderOffset + other.compressedSize })
        val parsed = EpubParser.parse(
            { path ->
                val entry = RemoteZip.find(entries, path) ?: return@parse null
                if (entry.name.endsWith("c2.xhtml")) return@parse null
                RemoteZip.readEntry(entry, ::read)
            },
            "书",
            maxSpineHtml = 1,
        )
        assertEquals("测试书", parsed.title)
        assertEquals(1, parsed.chapters.size)
        assertTrue(parsed.more)
        assertTrue(parsed.chapters[0].text.contains("只要这一段"))
        assertFalse(parsed.chapters[0].text.contains("BBBBB"))
    }

    @Test
    fun mobiPrefixKeepsTheOpeningAndLeavesTheTailRemote() {
        val opening = "第一章 开头能看".toByteArray(Charsets.UTF_8)
        val ending = "很远的结尾不要先下载".toByteArray(Charsets.UTF_8)
        val file = mobi(listOf(opening, ending))
        val touched = ArrayList<Long>()
        fun read(start: Long, length: Int): ByteArray {
            touched += start
            return file.copyOfRange(start.toInt(), start.toInt() + length)
        }
        val table = RemoteMobi.table(file.size.toLong(), ::read)
        val headerIndex = RemoteMobi.chooseHeader(table, ::read)
        val header = RemoteMobi.readRecord(table, headerIndex, ::read)
        RemoteMobi.requireText(header)
        val full = RemoteMobi.textRecords(header, table.offsets.size - headerIndex - 1)
        assertEquals(2, full)
        val included = RemoteMobi.nextCount(table, headerIndex, full, already = 0, budget = 8)
        assertEquals(1, included)
        val text = RemoteMobi.readRecord(table, headerIndex + 1, ::read)
        val synthetic = RemoteMobi.synthesize(header, listOf(text), full)
        val parsed = MobiParser.parse(temp(synthetic))
        assertTrue(parsed.chapters.joinToString { it.text }.contains("开头能看"))
        assertFalse(parsed.chapters.joinToString { it.text }.contains("很远的结尾"))
        val tail = table.offsets.last().toLong()
        assertTrue(touched.none { it >= tail })
    }

    @Test
    fun rarListsImagesWithoutReadingTheirBytes() {
        val first = ByteArray(3000) { 'A'.code.toByte() }
        val second = ByteArray(3000) { 'B'.code.toByte() }
        val rar = archiveOf(listOf("page1.jpg" to first, "page2.jpg" to second))
        val touched = ArrayList<LongRange>()
        fun read(start: Long, length: Int): ByteArray {
            touched += start until start + length
            return rar.copyOfRange(start.toInt(), minOf(rar.size, start.toInt() + length))
        }
        val items = RemoteRar.items(rar.size.toLong(), ::read)
        val images = items.filter { it.name.endsWith(".jpg") }
        assertEquals(listOf("page1.jpg", "page2.jpg"), images.map { it.name })
        assertTrue(touched.none { range -> images.any { image -> range.first >= image.dataOffset && range.first < image.dataOffset + image.dataSize } })
        val file = File.createTempFile("manjuan", ".cbr")
        file.writeBytes(rar)
        // Zero the second image payload.
        val zeroed = rar.copyOf()
        val image = images[1]
        for (index in image.dataOffset.toInt() until (image.dataOffset + image.dataSize).toInt()) zeroed[index] = 0
        file.writeBytes(zeroed)
        val extracted = CbrExtractor.extractImages(file, File(file.parentFile, "out-${file.name}").apply { mkdirs() }, setOf("page1.jpg"))
        assertEquals(1, extracted.size)
        assertEquals(first.size, extracted[0].readBytes().size)
        file.delete()
    }

    @Test
    fun pdfPageRangesLeaveTheOtherPageStreamUnread() {
        val pageOne = ByteArray(400) { 'A'.code.toByte() }
        val pageTwo = ByteArray(400) { 'B'.code.toByte() }
        val pdf = twoPagePdf(pageOne, pageTwo)
        val touched = ArrayList<LongRange>()
        fun read(start: Long, length: Int): ByteArray {
            touched += start until start + length
            val from = start.toInt().coerceIn(0, pdf.size)
            val to = (start + length).toInt().coerceIn(from, pdf.size)
            return pdf.copyOfRange(from, to)
        }
        val remote = RemotePdf(pdf.size.toLong(), ::read)
        assertEquals(2, remote.pageCount)
        touched.clear()
        remote.ensure(0)
        val twoAt = pdf.indexOfSlice(pageTwo)
        assertTrue(twoAt > 0)
        assertTrue(
            touched.none { range -> range.first < twoAt + pageTwo.size && range.last > twoAt },
        )
    }

    @Test
    fun allocatedSidecarReplacesSparseFileLength() {
        val root = File(System.getProperty("java.io.tmpdir"), "book-cache-${System.nanoTime()}").apply { mkdirs() }
        try {
            val book = File(root, "remote.pdf").apply { writeBytes(ByteArray(5000)) }
            File(root, "remote.pdf.allocated").writeText("120")
            assertEquals(120L, BookCache.size(root))
            book.writeBytes(ByteArray(10))
            File(root, "remote.pdf.allocated").delete()
            assertEquals(10L, BookCache.size(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (index in 0..size - needle.size) {
            if (copyOfRange(index, index + needle.size).contentEquals(needle)) return index
        }
        return -1
    }

    private fun temp(bytes: ByteArray): File =
        File.createTempFile("manjuan", ".mobi").apply { writeBytes(bytes); deleteOnExit() }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun container(opf: String) =
        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="$opf"/></rootfiles></container>"""
            .toByteArray()

    private fun opf(chapters: List<String>): ByteArray {
        val items = chapters.mapIndexed { index, name ->
            """<item id="c$index" href="$name" media-type="application/xhtml+xml"/>"""
        }.joinToString("")
        val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        return """
            <package xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata><dc:title>测试书</dc:title><dc:creator>作者甲</dc:creator></metadata>
              <manifest>$items</manifest>
              <spine>$spine</spine>
            </package>
        """.trimIndent().toByteArray()
    }

    private fun chapter(title: String, body: String) =
        "<html><body><h1>$title</h1><p>$body</p></body></html>".toByteArray()

    private fun mobi(records: List<ByteArray>): ByteArray {
        val header = ByteArray(144 + "测试".toByteArray().size)
        put16(header, 0, 1)
        put32(header, 4, records.sumOf { it.size })
        put16(header, 8, records.size)
        "MOBI".toByteArray().copyInto(header, 16)
        put32(header, 20, 128)
        put32(header, 28, 65001)
        val title = "测试".toByteArray()
        put32(header, 16 + 0x44, 144)
        put32(header, 16 + 0x48, title.size)
        title.copyInto(header, 144)
        val blobs = listOf(header) + records
        val pdb = ByteArray(78 + blobs.size * 8)
        "BOOK".toByteArray().copyInto(pdb, 60)
        "MOBI".toByteArray().copyInto(pdb, 64)
        put16(pdb, 76, blobs.size)
        var cursor = pdb.size
        for (index in blobs.indices) {
            put32(pdb, 78 + index * 8, cursor)
            cursor += blobs[index].size
        }
        val out = ByteArray(cursor)
        pdb.copyInto(out)
        var at = pdb.size
        for (blob in blobs) {
            blob.copyInto(out, at)
            at += blob.size
        }
        return out
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

    private fun archiveOf(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        out.write(rarBlock(0x73, 0, ByteArray(6)))
        for ((name, data) in files) out.write(storedFileBlock(name, data))
        out.write(byteArrayOf(0xC4.toByte(), 0x3D, 0x7B, 0x00, 0x40, 0x07, 0x00))
        return out.toByteArray()
    }

    private fun storedRar(name: String, data: ByteArray): ByteArray = archiveOf(listOf(name to data))

    private fun storedFile(name: String, data: ByteArray, continueFrom: Int): ByteArray = storedFileBlock(name, data)

    private fun storedFileBlock(name: String, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val size = 32 + nameBytes.size
        val body = ByteArray(size)
        body[2] = 0x74
        body[3] = 0x00
        body[4] = 0x80.toByte()
        put16le(body, 5, size)
        put32le(body, 7, data.size)
        put32le(body, 11, data.size)
        val crc = CRC32().apply { update(data) }.value
        put32le(body, 16, crc.toInt())
        body[24] = 20
        body[25] = 0x30
        put16le(body, 26, nameBytes.size)
        nameBytes.copyInto(body, 32)
        val headCrc = CRC32().apply { update(body, 2, body.size - 2) }.value.toInt() and 0xFFFF
        put16le(body, 0, headCrc)
        return body + data
    }

    private fun rarBlock(type: Int, flags: Int, rest: ByteArray): ByteArray {
        val size = 7 + rest.size
        val body = ByteArray(size)
        body[2] = type.toByte()
        put16le(body, 3, flags)
        put16le(body, 5, size)
        rest.copyInto(body, 7)
        val headCrc = CRC32().apply { update(body, 2, body.size - 2) }.value.toInt() and 0xFFFF
        put16le(body, 0, headCrc)
        return body
    }

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    private fun twoPagePdf(pageOne: ByteArray, pageTwo: ByteArray): ByteArray {
        val objects = arrayOfNulls<ByteArray>(7)
        objects[1] = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".toByteArray()
        objects[2] = "2 0 obj\n<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >>\nendobj\n".toByteArray()
        objects[3] = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 5 0 R >>\nendobj\n".toByteArray()
        objects[4] = "4 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 6 0 R >>\nendobj\n".toByteArray()
        objects[5] = streamObject(5, pageOne)
        objects[6] = streamObject(6, pageTwo)
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = IntArray(7)
        for (index in 1..6) {
            offsets[index] = out.size()
            out.write(objects[index])
        }
        val xrefAt = out.size()
        val xref = StringBuilder("xref\n0 7\n")
        xref.append("0000000000 65535 f \n")
        for (index in 1..6) xref.append("%010d 00000 n \n".format(offsets[index]))
        xref.append("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        out.write(xref.toString().toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun streamObject(id: Int, data: ByteArray): ByteArray {
        val head = "$id 0 obj\n<< /Length ${data.size} >>\nstream\n".toByteArray(Charsets.US_ASCII)
        val tail = "\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII)
        return head + data + tail
    }
}
