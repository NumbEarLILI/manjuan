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
    fun huffAndEncryptedMobiFailInChinese() {
        val huff = writeMobi(compression = 17480, encoding = 65001, records = listOf(byteArrayOf(1)), textLength = 1, extraFlags = null)
        val encrypted = writeMobi(compression = 2, encoding = 65001, records = listOf(palmDocLiterals("hello".toByteArray())), textLength = 5, extraFlags = null, encryption = 1)
        assertChinese(huff, "Huff")
        assertChinese(encrypted, "加密")
        huff.delete()
        encrypted.delete()
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
    ): File {
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val headerSize = if (extraFlags == null) 144 else 0xF4
        val header = ByteArray(headerSize + titleBytes.size)
        put16(header, 0, compression)
        put32(header, 4, textLength)
        put16(header, 8, records.size)
        put16(header, 10, 4096)
        put16(header, 12, encryption)
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

        val recordCount = records.size + 1
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
        val file = File.createTempFile("manjuan", ".mobi")
        file.writeBytes(pdb + blobs.reduce { left, right -> left + right })
        return file
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
