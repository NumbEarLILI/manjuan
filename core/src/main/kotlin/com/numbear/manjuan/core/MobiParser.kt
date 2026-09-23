package com.numbear.manjuan.core

import java.io.File
import java.nio.charset.Charset

object MobiParser {
    fun parse(file: File): NovelContent {
        val bytes = file.readBytes()
        if (bytes.size < 80 || !FormatDetector.isMobi(bytes)) {
            throw UnsupportedBookException("这不是有效的 MOBI（缺少 BOOKMOBI 标识）")
        }
        val records = pdbRecords(bytes)
        if (records.isEmpty()) throw UnsupportedBookException("MOBI 记录表是空的")
        val header = records[0]
        if (header.size < 16) throw UnsupportedBookException("MOBI 文件头损坏")
        val compression = u16(header, 0)
        val textLength = u32(header, 4)
        val textRecordCount = u16(header, 8)
        val encryption = u16(header, 12)
        if (encryption != 0) throw UnsupportedBookException("此 MOBI 已加密，暂不支持")
        if (compression == 17480) throw UnsupportedBookException("此 MOBI 使用 Huff/CDIC 压缩，暂不支持")
        if (compression != 1 && compression != 2) {
            throw UnsupportedBookException("此 MOBI 使用了暂不支持的压缩方式（$compression）")
        }
        val encoding = if (header.size >= 32 && header.copyOfRange(16, 20).toString(Charsets.US_ASCII) == "MOBI") {
            u32(header, 28)
        } else {
            65001
        }
        val charset = when (encoding) {
            65001 -> Charsets.UTF_8
            1252 -> Charset.forName("windows-1252")
            else -> Charsets.UTF_8
        }
        val title = readFullName(header) ?: file.nameWithoutExtension
        val textBytes = ByteArray(0).let {
            val joined = java.io.ByteArrayOutputStream()
            val count = textRecordCount.coerceAtMost(records.size - 1)
            for (index in 1..count) {
                val raw = records[index]
                val decoded = if (compression == 2) PalmDoc.decompress(raw) else raw
                joined.write(decoded)
            }
            val all = joined.toByteArray()
            if (textLength in 1..all.size) all.copyOf(textLength) else all
        }
        val plain = HtmlText.toPlain(String(textBytes, charset).replace('\u0000', ' '))
        if (plain.isBlank()) throw UnsupportedBookException("没有从 MOBI 中提取到正文")
        val chapters = TxtChapters.split(plain).map { chapter ->
            NovelChapter(chapter.title, plain.substring(chapter.start, chapter.end).trim())
        }
        return NovelContent(title, "", chapters.ifEmpty { listOf(NovelChapter("正文", plain)) })
    }

    private fun readFullName(header: ByteArray): String? {
        if (header.size < 92 || header.copyOfRange(16, 20).toString(Charsets.US_ASCII) != "MOBI") return null
        val offset = u32(header, 16 + 0x44)
        val length = u32(header, 16 + 0x48)
        if (length <= 0 || offset < 0 || offset + length > header.size) return null
        val name = header.copyOfRange(offset, offset + length).toString(Charsets.UTF_8).trim('\u0000', ' ')
        return name.ifBlank { null }
    }

    private fun pdbRecords(bytes: ByteArray): List<ByteArray> {
        val count = u16(bytes, 76)
        if (count <= 0 || 78 + count * 8 > bytes.size) return emptyList()
        val offsets = IntArray(count) { index -> u32(bytes, 78 + index * 8) }
        return List(count) { index ->
            val start = offsets[index]
            val end = if (index + 1 < count) offsets[index + 1] else bytes.size
            if (start < 0 || end > bytes.size || start > end) ByteArray(0) else bytes.copyOfRange(start, end)
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
