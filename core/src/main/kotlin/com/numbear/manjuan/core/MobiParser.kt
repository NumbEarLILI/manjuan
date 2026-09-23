package com.numbear.manjuan.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object MobiParser {
    private val gb18030: Charset = Charset.forName("GB18030")
    private val windows1252: Charset = Charset.forName("windows-1252")
    private val charsetMeta = Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9._+-]+)""", RegexOption.IGNORE_CASE)

    fun parse(file: File): NovelContent {
        val bytes = file.readBytes()
        if (bytes.size < 80 || !FormatDetector.isMobi(bytes)) {
            throw UnsupportedBookException("这不是有效的 MOBI（缺少 BOOKMOBI 标识）")
        }
        val offsets = pdbOffsets(bytes)
        if (offsets.isEmpty()) throw UnsupportedBookException("MOBI 记录表是空的")
        val header = recordBytes(bytes, offsets, 0)
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
        val flags = extraFlags(header)
        val count = textRecordCount.coerceAtMost(offsets.size - 1)
        val joined = ByteArrayOutputStream()
        for (index in 1..count) {
            val raw = recordBytes(bytes, offsets, index)
            val payload = stripTrailers(raw, flags)
            val decoded = if (compression == 2) PalmDoc.decompress(payload) else payload
            joined.write(decoded)
        }
        val all = joined.toByteArray()
        if (textLength > all.size && textLength - all.size > 4096) {
            throw UnsupportedBookException("MOBI 文件不完整或已损坏")
        }
        val textBytes = if (textLength in 1..all.size) all.copyOf(textLength) else all
        val decoded = decodeBody(textBytes, encoding)
        val plain = HtmlText.toPlain(stripControls(decoded))
        if (plain.isBlank()) throw UnsupportedBookException("没有从 MOBI 中提取到正文")
        val title = readFullName(header, encoding) ?: file.nameWithoutExtension
        val chapters = TxtChapters.split(plain).map { chapter ->
            NovelChapter(chapter.title, plain.substring(chapter.start, chapter.end).trim())
        }
        return NovelContent(title, "", chapters.ifEmpty { listOf(NovelChapter("正文", plain)) })
    }

    /**
     * Mobipocket version 5+ appends trailing entries to every text record.
     * They are not part of the PalmDoc stream; decompressing them shifts the
     * following records and turns UTF-8 or GBK into 乱码.
     */
    private fun extraFlags(header: ByteArray): Int {
        if (header.size < 0xF4) return 0
        if (header.copyOfRange(16, 20).toString(Charsets.US_ASCII) != "MOBI") return 0
        val headerLength = u32(header, 20)
        val version = if (header.size >= 0x6C) u32(header, 0x68) else 0
        if (headerLength < 0xE4 || version < 5) return 0
        return u16(header, 0xF2)
    }

    private fun stripTrailers(data: ByteArray, flags: Int): ByteArray {
        val trail = trailingSize(data, flags)
        if (trail <= 0 || trail > data.size) return data
        return data.copyOf(data.size - trail)
    }

    private fun trailingSize(data: ByteArray, flags: Int): Int {
        if (flags == 0 || data.isEmpty()) return 0
        var num = 0
        var bits = flags ushr 1
        while (bits != 0) {
            if (bits and 1 != 0) {
                val entry = trailingEntrySize(data, data.size - num)
                if (entry <= 0 || entry > data.size - num) return 0
                num += entry
            }
            bits = bits ushr 1
        }
        if (flags and 1 != 0) {
            val off = data.size - num - 1
            if (off < 0) return 0
            val extra = (data[off].toInt() and 0x3) + 1
            if (num + extra > data.size) return 0
            num += extra
        }
        return num
    }

    private fun trailingEntrySize(data: ByteArray, end: Int): Int {
        var bitpos = 0
        var result = 0
        var pos = end
        while (true) {
            if (pos <= 0) return 0
            val value = data[pos - 1].toInt() and 0xFF
            result = result or ((value and 0x7F) shl bitpos)
            bitpos += 7
            pos -= 1
            if (value and 0x80 != 0 || bitpos >= 28) return result
        }
    }

    private fun decodeBody(bytes: ByteArray, encoding: Int): String {
        val meta = htmlCharset(bytes)
        val headerCharset = charsetForCode(encoding)
        val primary = meta ?: headerCharset ?: Charsets.UTF_8
        strictDecode(bytes, primary)?.let { text ->
            return upgradeLegacyChinese(bytes, primary, text)
        }
        if (looksLikeGbk(bytes)) {
            strictDecode(bytes, gb18030)?.let { return it }
        }
        val replaced = String(bytes, if (primary == windows1252) windows1252 else Charsets.UTF_8)
        if (!replacementHeavy(replaced)) return replaced
        throw UnsupportedBookException("无法解码这本 MOBI 的正文")
    }

    private fun upgradeLegacyChinese(bytes: ByteArray, charset: Charset, text: String): String {
        if (!isLatin(charset) || cjkCount(text) > 0) return text
        strictDecode(bytes, Charsets.UTF_8)?.takeIf { cjkCount(it) > 0 }?.let { return it }
        if (looksLikeGbk(bytes)) {
            strictDecode(bytes, gb18030)?.takeIf { cjkCount(it) > 0 }?.let { return it }
        }
        return text
    }

    private fun htmlCharset(bytes: ByteArray): Charset? {
        if (bytes.isEmpty()) return null
        val sample = bytes.copyOf(minOf(bytes.size, 4096)).toString(Charsets.ISO_8859_1)
        val name = charsetMeta.find(sample)?.groupValues?.get(1) ?: return null
        return charsetByName(name)
    }

    private fun charsetForCode(code: Int): Charset? = when (code) {
        65001 -> Charsets.UTF_8
        1252 -> windows1252
        936, 54936, 20936 -> gb18030
        950 -> Charset.forName("Big5")
        1200 -> Charsets.UTF_16LE
        1201 -> Charsets.UTF_16BE
        932 -> Charset.forName("Shift_JIS")
        else -> null
    }

    private fun charsetByName(name: String): Charset? = when (name.lowercase().replace('_', '-')) {
        "utf-8", "utf8" -> Charsets.UTF_8
        "gb2312", "gbk", "gb18030", "gb-18030", "x-gbk", "gb2312-80" -> gb18030
        "big5", "big-5", "cn-big5" -> Charset.forName("Big5")
        "windows-1252", "cp1252", "iso-8859-1", "latin1", "latin-1", "iso-8859-15" -> windows1252
        "utf-16le" -> Charsets.UTF_16LE
        "utf-16be" -> Charsets.UTF_16BE
        "shift-jis", "shift_jis", "sjis" -> Charset.forName("Shift_JIS")
        else -> runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun isLatin(charset: Charset): Boolean {
        val name = charset.name().lowercase()
        return name.contains("1252") || name.contains("8859") || name.contains("latin")
    }

    private fun looksLikeGbk(bytes: ByteArray): Boolean {
        var index = 0
        var pairs = 0
        while (index < bytes.size) {
            val lead = bytes[index].toInt() and 0xFF
            if (lead < 0x80) {
                index++
                continue
            }
            if (lead !in 0x81..0xFE || index + 1 >= bytes.size) return false
            val trail = bytes[index + 1].toInt() and 0xFF
            if (trail !in 0x40..0xFE || trail == 0x7F) return false
            pairs++
            index += 2
        }
        return pairs >= 2
    }

    private fun cjkCount(text: String): Int = text.count { ch ->
        val code = ch.code
        code in 0x3400..0x9FFF || code in 0xF900..0xFAFF
    }

    private fun replacementHeavy(text: String): Boolean {
        if (text.isEmpty()) return false
        val bad = text.count { it == '\uFFFD' }
        return bad >= 8 && bad * 5 >= text.length
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun stripControls(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            val drop = code == 0 || code in 1..8 || code == 0x0B || code == 0x0C || code in 0x0E..0x1F
            out.append(if (drop) ' ' else ch)
        }
        return out.toString()
    }

    private fun readFullName(header: ByteArray, encoding: Int): String? {
        if (header.size < 92 || header.copyOfRange(16, 20).toString(Charsets.US_ASCII) != "MOBI") return null
        val offset = u32(header, 16 + 0x44)
        val length = u32(header, 16 + 0x48)
        if (length <= 0 || offset < 0 || offset + length > header.size) return null
        val raw = header.copyOfRange(offset, offset + length)
        val charset = charsetForCode(encoding) ?: Charsets.UTF_8
        val name = (strictDecode(raw, Charsets.UTF_8) ?: strictDecode(raw, charset) ?: return null)
            .trim('\u0000', ' ')
        return name.ifBlank { null }
    }

    private fun pdbOffsets(bytes: ByteArray): IntArray {
        val count = u16(bytes, 76)
        if (count <= 0 || 78 + count * 8 > bytes.size) return IntArray(0)
        return IntArray(count) { index -> u32(bytes, 78 + index * 8) }
    }

    private fun recordBytes(bytes: ByteArray, offsets: IntArray, index: Int): ByteArray {
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else bytes.size
        if (start < 0 || end > bytes.size || start > end) {
            throw UnsupportedBookException("MOBI 文件不完整或已损坏")
        }
        return bytes.copyOfRange(start, end)
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
