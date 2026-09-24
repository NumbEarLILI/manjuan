package com.numbear.manjuan.core

import java.util.zip.Inflater

/**
 * Finds the PDF trailer at the end of the file, then the objects one page needs.
 * The bytes [read] is asked for are the bytes a renderer has to have locally.
 */
class RemotePdf(
    private val fileSize: Long,
    private val read: (Long, Int) -> ByteArray,
) {
    val pageCount: Int
    private var trailerRoot: Int? = null
    private val objects = HashMap<Int, Obj>()
    private val xref = HashMap<Int, Loc>()
    private val pages = ArrayList<Int>()

    init {
        if (fileSize < 16) throw UnsupportedBookException("无法解析 PDF 目录")
        read(0, minOf(fileSize, 1024L).toInt())
        val start = startXref()
        loadXref(start, HashSet())
        val root = trailerRoot ?: throw UnsupportedBookException("无法解析 PDF 目录")
        val pagesId = singleRef(parse(root).dict, "Pages") ?: throw UnsupportedBookException("无法解析 PDF 目录")
        walkPages(pagesId, HashSet())
        pageCount = pages.size
        if (pageCount <= 0) throw UnsupportedBookException("没有可显示的页面")
    }

    fun ensure(page: Int) {
        if (page !in pages.indices) throw UnsupportedBookException("没有这一页")
        val seen = HashSet<Int>()
        fun visit(id: Int) {
            if (!seen.add(id)) return
            val obj = parse(id)
            for (ref in follow(obj)) visit(ref)
        }
        visit(pages[page])
    }

    private sealed class Loc {
        data class At(val offset: Long) : Loc()
        data class Packed(val stream: Int, val index: Int) : Loc()
    }

    private class Obj(
        val dict: String,
        val literal: String,
        val refs: List<Int>,
        val stream: ByteArray?,
    )

    private fun loadXref(offset: Long, seen: MutableSet<Long>) {
        if (offset < 0 || offset >= fileSize || !seen.add(offset)) return
        val probe = latin(offset, 16)
        val trimmed = probe.trimStart()
        if (trimmed.startsWith("xref")) parseClassic(offset, seen) else parseXrefStream(offset, seen)
    }

    private fun parseClassic(offset: Long, seen: MutableSet<Long>) {
        val cursor = Cursor(offset)
        cursor.skipWs()
        if (cursor.word() != "xref") throw UnsupportedBookException("无法解析 PDF 目录")
        while (true) {
            cursor.skipWs()
            if (cursor.startsWith("trailer")) break
            val startObj = cursor.word().toIntOrNull() ?: throw UnsupportedBookException("无法解析 PDF 目录")
            val count = cursor.word().toIntOrNull() ?: throw UnsupportedBookException("无法解析 PDF 目录")
            repeat(count) { slot ->
                cursor.skipWs()
                val entry = cursor.take(20)
                val kind = entry.getOrNull(17)
                if (kind == 'n') {
                    val at = entry.substring(0, 10).trim().toLongOrNull() ?: return@repeat
                    xref.putIfAbsent(startObj + slot, Loc.At(at))
                }
            }
        }
        cursor.word()
        val dict = cursor.dict()
        singleRef(dict, "Root")?.let { if (trailerRoot == null) trailerRoot = it }
        intKey(dict, "Prev")?.let { loadXref(it.toLong(), seen) }
    }

    private fun startXref(): Long {
        var window = minOf(fileSize, 4096L)
        while (true) {
            val start = fileSize - window
            val text = latin(start, window.toInt())
            val at = text.lastIndexOf("startxref")
            if (at >= 0) {
                val number = text.substring(at + "startxref".length).dropWhile { it.isWhitespace() }.takeWhile { it.isDigit() }
                if (number.isNotEmpty()) return number.toLong()
            }
            if (window >= fileSize || window >= 1024L * 1024) break
            window = minOf(fileSize, window * 4)
        }
        throw UnsupportedBookException("无法解析 PDF 目录")
    }

    private fun parseXrefStream(offset: Long, seen: MutableSet<Long>) {
        val obj = parseAt(offset, objectNumberAt(offset))
        val dict = obj.dict
        if (!dict.contains("/XRef") && !dict.contains("/Type")) {
            // Still a trailer-like stream when /Root is present.
        }
        singleRef(dict, "Root")?.let { if (trailerRoot == null) trailerRoot = it }
        val widths = intList(dict, "W").ifEmpty { listOf(1, 2, 1) }
        if (widths.size < 3) throw UnsupportedBookException("无法解析 PDF 目录")
        val size = intKey(dict, "Size") ?: 0
        val index = intList(dict, "Index").ifEmpty { listOf(0, size) }
        val data = obj.stream ?: throw UnsupportedBookException("无法解析 PDF 目录")
        var cursor = 0
        var pair = 0
        while (pair + 1 < index.size) {
            var objNum = index[pair]
            val count = index[pair + 1]
            repeat(count) {
                if (cursor + widths.sum() > data.size) return@repeat
                val fields = IntArray(3)
                for (field in 0 until 3) {
                    var value = 0
                    repeat(widths[field]) {
                        value = (value shl 8) or (data[cursor].toInt() and 0xFF)
                        cursor++
                    }
                    fields[field] = value
                }
                when (fields[0]) {
                    1 -> xref.putIfAbsent(objNum, Loc.At(fields[1].toLong()))
                    2 -> xref.putIfAbsent(objNum, Loc.Packed(fields[1], fields[2]))
                }
                objNum++
            }
            pair += 2
        }
        intKey(dict, "Prev")?.let { loadXref(it.toLong(), seen) }
    }

    private fun objectNumberAt(offset: Long): Int =
        latin(offset, 32).trimStart().takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    private fun walkPages(id: Int, seen: MutableSet<Int>) {
        if (!seen.add(id)) return
        val obj = parse(id)
        val type = nameKey(obj.dict, "Type")
        val kids = kidIds(obj)
        if (type == "Page" || (kids.isEmpty() && obj.dict.contains("/Contents"))) {
            pages += id
            return
        }
        for (kid in kids) walkPages(kid, seen)
    }

    private fun kidIds(obj: Obj): List<Int> {
        val inline = refsInArray(obj.dict, "Kids")
        if (inline.isNotEmpty()) return inline
        val indirect = singleRef(obj.dict, "Kids") ?: return emptyList()
        val target = parse(indirect)
        if (target.dict.isEmpty()) return target.refs
        return target.refs
    }

    private fun follow(obj: Obj): List<Int> {
        val skip = HashSet<Int>()
        singleRef(obj.dict, "Parent")?.let { skip += it }
        refsInArray(obj.dict, "Kids").forEach { skip += it }
        singleRef(obj.dict, "Kids")?.let { skip += it }
        return obj.refs.filter { it !in skip && it != 0 }
    }

    private fun parse(id: Int): Obj {
        objects[id]?.let { return it }
        val loc = xref[id] ?: throw UnsupportedBookException("无法解析 PDF 目录")
        val obj = when (loc) {
            is Loc.At -> parseAt(loc.offset, id)
            is Loc.Packed -> parsePacked(loc.stream, loc.index, id)
        }
        objects[id] = obj
        return obj
    }

    private fun parsePacked(streamId: Int, index: Int, id: Int): Obj {
        val holder = parse(streamId)
        val count = intKey(holder.dict, "N") ?: throw UnsupportedBookException("无法解析 PDF 目录")
        val first = intKey(holder.dict, "First") ?: 0
        val data = holder.stream ?: throw UnsupportedBookException("无法解析 PDF 目录")
        val header = data.copyOfRange(0, minOf(first, data.size)).toString(Charsets.ISO_8859_1)
        val numbers = header.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (index !in 0 until count || index * 2 + 1 >= numbers.size) {
            throw UnsupportedBookException("无法解析 PDF 目录")
        }
        val start = first + numbers[index * 2 + 1].toInt()
        val end = if (index + 1 < count && (index + 1) * 2 + 1 < numbers.size) {
            first + numbers[(index + 1) * 2 + 1].toInt()
        } else {
            data.size
        }
        val slice = data.copyOfRange(start.coerceIn(0, data.size), end.coerceIn(0, data.size))
        return scanValue(slice, 0).obj
    }

    private fun parseAt(offset: Long, id: Int): Obj = scanObject(objectBytes(offset), offset)

    private fun objectBytes(offset: Long): ByteArray {
        val buf = ArrayList<Byte>(128)
        fun pull(count: Int) {
            if (count <= 0 || offset + buf.size >= fileSize) return
            val n = minOf(count.toLong(), fileSize - offset - buf.size).toInt()
            read(offset + buf.size, n).forEach { buf += it }
        }
        pull(48)
        while (buf.size < 512 * 1024 && offset + buf.size < fileSize && !closedValue(buf)) pull(32)
        val text = latinOf(buf.toByteArray(), 0, buf.size)
        val streamAt = text.indexOf("stream")
        val dictEnd = text.indexOf(">>")
        if (streamAt >= 0 && dictEnd in 0 until streamAt) {
            val length = streamLength(text.substring(0, streamAt))
            var dataAt = streamAt + "stream".length
            if (dataAt < buf.size && buf[dataAt] == '\r'.code.toByte()) dataAt++
            if (dataAt < buf.size && buf[dataAt] == '\n'.code.toByte()) dataAt++
            val want = dataAt + length + 32
            if (buf.size < want) pull(want - buf.size)
        }
        while (buf.size < 512 * 1024 && offset + buf.size < fileSize &&
            !latinOf(buf.toByteArray(), 0, buf.size).contains("endobj")
        ) {
            pull(16)
        }
        return buf.toByteArray()
    }

    private fun closedValue(buf: ArrayList<Byte>): Boolean {
        val text = latinOf(buf.toByteArray(), 0, buf.size)
        val obj = text.indexOf(" obj")
        if (obj < 0) return false
        val body = text.substring(obj)
        if (body.contains(">>")) {
            val after = body.substring(body.indexOf(">>") + 2).trimStart()
            return after.startsWith("stream") || after.startsWith("endobj")
        }
        return body.contains("endobj")
    }

    private fun literalInt(obj: Obj): Int = obj.literal.trim().toIntOrNull()
        ?: Regex("(-?\\d+)").find(obj.literal)?.groupValues?.get(1)?.toIntOrNull()
        ?: 0

    private fun scanObject(bytes: ByteArray, fileOffset: Long): Obj {
        var index = 0
        index = skipWs(bytes, index)
        index = skipToken(bytes, index)
        index = skipWs(bytes, index)
        index = skipToken(bytes, index)
        index = skipWs(bytes, index)
        index = skipToken(bytes, index)
        index = skipWs(bytes, index)
        val value = scanValue(bytes, index)
        var cursor = value.next
        val dict = value.obj.dict
        var stream: ByteArray? = null
        cursor = skipWs(bytes, cursor)
        if (matches(bytes, cursor, "stream")) {
            cursor += "stream".length
            if (cursor < bytes.size && bytes[cursor] == '\r'.code.toByte()) cursor++
            if (cursor < bytes.size && bytes[cursor] == '\n'.code.toByte()) cursor++
            val length = streamLength(dict)
            val end = (cursor + length).coerceAtMost(bytes.size)
            val raw = bytes.copyOfRange(cursor, end)
            stream = decodeStream(raw, dict)
            cursor = end
        }
        return Obj(dict, value.obj.literal, value.obj.refs, stream)
    }

    private data class Scanned(val obj: Obj, val next: Int)

    private fun scanValue(bytes: ByteArray, start: Int): Scanned {
        var index = skipWs(bytes, start)
        if (index >= bytes.size) return Scanned(Obj("", "", emptyList(), null), index)
        val refs = ArrayList<Int>()
        when (bytes[index].toInt().toChar()) {
            '[' -> {
                val end = skipContainer(bytes, index, '[', ']', refs)
                return Scanned(Obj("", "", refs, null), end)
            }
            '<' -> {
                if (index + 1 < bytes.size && bytes[index + 1] == '<'.code.toByte()) {
                    val end = skipDict(bytes, index, refs)
                    val dict = latinOf(bytes, index, end)
                    return Scanned(Obj(dict, "", refs, null), end)
                }
                val end = skipHex(bytes, index)
                return Scanned(Obj("", "", refs, null), end)
            }
            '(' -> return Scanned(Obj("", "", refs, null), skipString(bytes, index))
            '/' -> return Scanned(Obj("", "", refs, null), skipName(bytes, index))
            else -> {
                val tokenEnd = skipToken(bytes, index)
                val token = latinOf(bytes, index, tokenEnd)
                val after = skipWs(bytes, tokenEnd)
                val secondEnd = skipToken(bytes, after)
                val second = latinOf(bytes, after, secondEnd)
                val afterSecond = skipWs(bytes, secondEnd)
                if (token.toIntOrNull() != null && second.toIntOrNull() != null && matches(bytes, afterSecond, "R")) {
                    refs += token.toInt()
                    return Scanned(Obj("", "", refs, null), afterSecond + 1)
                }
                return Scanned(Obj("", token, refs, null), tokenEnd)
            }
        }
    }

    private fun streamLength(dict: String): Int {
        val indirect = Regex("/Length\\s+(\\d+)\\s+\\d+\\s+R").find(dict)
        if (indirect != null) return literalInt(parse(indirect.groupValues[1].toInt()))
        return Regex("/Length\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun decodeStream(raw: ByteArray, dict: String): ByteArray {
        val filter = nameKey(dict, "Filter")
        val decoded = when (filter) {
            null, "" -> raw
            "FlateDecode" -> inflateZlib(raw)
            else -> throw UnsupportedBookException("这本 PDF 使用了暂不支持的压缩")
        }
        val predictor = predictorOf(dict)
        if (predictor < 10) return decoded
        val columns = columnsOf(dict)
        return unpredict(decoded, columns, predictor)
    }

    private fun predictorOf(dict: String): Int {
        Regex("/Predictor\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 1
    }

    private fun columnsOf(dict: String): Int {
        Regex("/Columns\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val widths = intList(dict, "W")
        return if (widths.isEmpty()) 1 else widths.sum()
    }

    private fun unpredict(data: ByteArray, columns: Int, predictor: Int): ByteArray {
        val row = columns + 1
        if (row <= 1 || data.size % row != 0) return data
        val rows = data.size / row
        val out = ByteArray(rows * columns)
        var previous = ByteArray(columns)
        for (rowIndex in 0 until rows) {
            val filter = data[rowIndex * row].toInt()
            val source = data.copyOfRange(rowIndex * row + 1, rowIndex * row + row)
            val current = ByteArray(columns)
            for (column in 0 until columns) {
                val left = if (column > 0) current[column - 1].toInt() and 0xFF else 0
                val up = previous[column].toInt() and 0xFF
                val value = source[column].toInt() and 0xFF
                current[column] = when (filter) {
                    0 -> value
                    1 -> value + left
                    2 -> value + up
                    3 -> value + ((left + up) / 2)
                    4 -> value + paeth(left, up, if (column > 0) previous[column - 1].toInt() and 0xFF else 0)
                    else -> value
                }.toByte()
            }
            current.copyInto(out, rowIndex * columns)
            previous = current
        }
        return out
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val estimate = left + up - upperLeft
        val a = kotlin.math.abs(estimate - left)
        val b = kotlin.math.abs(estimate - up)
        val c = kotlin.math.abs(estimate - upperLeft)
        return when {
            a <= b && a <= c -> left
            b <= c -> up
            else -> upperLeft
        }
    }

    private fun inflateZlib(raw: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(raw)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                if (count > 0) out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private inner class Cursor(var pos: Long) {
        private var buffer = ByteArray(0)
        private var bufferAt = 0L

        private fun at(offset: Long): Int {
            if (offset < bufferAt || offset >= bufferAt + buffer.size) {
                val count = minOf(4096L, fileSize - offset).toInt().coerceAtLeast(0)
                if (count == 0) return -1
                buffer = read(offset, count)
                bufferAt = offset
            }
            val index = (offset - bufferAt).toInt()
            if (index !in buffer.indices) return -1
            return buffer[index].toInt() and 0xFF
        }

        fun skipWs() {
            while (pos < fileSize) {
                val value = at(pos)
                if (value == '%'.code) {
                    while (pos < fileSize) {
                        val next = at(pos)
                        if (next == '\n'.code || next == '\r'.code) break
                        pos++
                    }
                    continue
                }
                if (value != ' '.code && value != '\n'.code && value != '\r'.code && value != '\t'.code && value != 0) break
                pos++
            }
        }

        fun word(): String {
            skipWs()
            val start = pos
            while (pos < fileSize) {
                val value = at(pos)
                if (value < 0 || value <= 32 || value == '['.code || value == ']'.code || value == '<'.code ||
                    value == '>'.code || value == '('.code || value == ')'.code || value == '/'.code || value == '%'.code
                ) {
                    break
                }
                pos++
            }
            return slice(start, (pos - start).toInt())
        }

        fun take(count: Int): String {
            val text = slice(pos, count)
            pos += text.length
            return text
        }

        fun startsWith(token: String): Boolean = slice(pos, token.length) == token

        fun dict(): String {
            skipWs()
            val start = pos
            val bytes = ArrayList<Byte>()
            while (pos + bytes.size < fileSize && bytes.size < 65536) {
                val value = at(pos + bytes.size)
                if (value < 0) break
                bytes += value.toByte()
                if (bytes.size >= 2 && bytes[bytes.lastIndex] == '>'.code.toByte() && bytes[bytes.lastIndex - 1] == '>'.code.toByte()) {
                    break
                }
            }
            val raw = bytes.toByteArray()
            val end = skipDict(raw, 0, ArrayList())
            pos += end
            return latinOf(raw, 0, end)
        }

        private fun slice(offset: Long, length: Int): String {
            if (length <= 0) return ""
            val out = ByteArray(length)
            for (index in 0 until length) {
                val value = at(offset + index)
                if (value < 0) return out.copyOf(index).toString(Charsets.ISO_8859_1)
                out[index] = value.toByte()
            }
            return out.toString(Charsets.ISO_8859_1)
        }
    }

    private fun latin(offset: Long, length: Int): String = latinOf(read(offset, length), 0, length)

    private fun latinOf(bytes: ByteArray, start: Int, end: Int): String {
        val from = start.coerceIn(0, bytes.size)
        val to = end.coerceIn(from, bytes.size)
        return bytes.copyOfRange(from, to).toString(Charsets.ISO_8859_1)
    }

    private fun skipWs(bytes: ByteArray, start: Int): Int {
        var index = start
        while (index < bytes.size) {
            val value = bytes[index].toInt().toChar()
            if (value == '%') {
                while (index < bytes.size && bytes[index] != '\n'.code.toByte() && bytes[index] != '\r'.code.toByte()) index++
                continue
            }
            if (value != ' ' && value != '\n' && value != '\r' && value != '\t' && value != '\u0000') break
            index++
        }
        return index
    }

    private fun skipToken(bytes: ByteArray, start: Int): Int {
        var index = start
        while (index < bytes.size) {
            val value = bytes[index].toInt()
            if (value <= 32 || value == '['.code || value == ']'.code || value == '<'.code || value == '>'.code ||
                value == '('.code || value == ')'.code || value == '/'.code || value == '%'.code
            ) {
                break
            }
            index++
        }
        return index
    }

    private fun skipName(bytes: ByteArray, start: Int): Int = skipToken(bytes, start + 1)

    private fun matches(bytes: ByteArray, index: Int, token: String): Boolean {
        if (index + token.length > bytes.size) return false
        return latinOf(bytes, index, index + token.length) == token
    }

    private fun skipString(bytes: ByteArray, start: Int): Int {
        var index = start + 1
        var depth = 1
        while (index < bytes.size && depth > 0) {
            when (bytes[index].toInt().toChar()) {
                '\\' -> index += 2
                '(' -> {
                    depth++
                    index++
                }
                ')' -> {
                    depth--
                    index++
                }
                else -> index++
            }
        }
        return index
    }

    private fun skipHex(bytes: ByteArray, start: Int): Int {
        var index = start + 1
        while (index < bytes.size && bytes[index] != '>'.code.toByte()) index++
        return (index + 1).coerceAtMost(bytes.size)
    }

    private fun skipContainer(bytes: ByteArray, start: Int, open: Char, close: Char, refs: MutableList<Int>): Int {
        var index = start + 1
        while (index < bytes.size) {
            index = skipWs(bytes, index)
            if (index >= bytes.size) break
            if (bytes[index] == close.code.toByte()) return index + 1
            val value = scanValue(bytes, index)
            refs += value.obj.refs
            index = value.next
        }
        return index
    }

    private fun skipDict(bytes: ByteArray, start: Int, refs: MutableList<Int>): Int {
        var index = start + 2
        while (index < bytes.size) {
            index = skipWs(bytes, index)
            if (index + 1 < bytes.size && bytes[index] == '>'.code.toByte() && bytes[index + 1] == '>'.code.toByte()) {
                return index + 2
            }
            if (index < bytes.size && bytes[index] == '/'.code.toByte()) {
                index = skipName(bytes, index)
                index = skipWs(bytes, index)
                if (index >= bytes.size) break
                val value = scanValue(bytes, index)
                refs += value.obj.refs
                index = value.next
                continue
            }
            index++
        }
        return index
    }

    private fun nameKey(dict: String, key: String): String? {
        Regex("/$key(?![A-Za-z0-9])\\s*/([A-Za-z0-9]+)").find(dict)?.groupValues?.get(1)?.let { return it }
        return Regex("/$key(?![A-Za-z0-9])\\s*\\[\\s*/([A-Za-z0-9]+)").find(dict)?.groupValues?.get(1)
    }

    private fun intKey(dict: String, key: String): Int? =
        Regex("/$key(?![A-Za-z0-9])\\s+(-?\\d+)(?!\\s+\\d+\\s+R)").find(dict)?.groupValues?.get(1)?.toIntOrNull()

    private fun intList(dict: String, key: String): List<Int> {
        val match = Regex("/$key(?![A-Za-z0-9])\\s*\\[([^]]*)]").find(dict) ?: return emptyList()
        return Regex("-?\\d+").findAll(match.groupValues[1]).map { it.value.toInt() }.toList()
    }

    private fun singleRef(dict: String, key: String): Int? =
        Regex("/$key(?![A-Za-z0-9])\\s+(\\d+)\\s+\\d+\\s+R").find(dict)?.groupValues?.get(1)?.toIntOrNull()

    private fun refsInArray(dict: String, key: String): List<Int> {
        val match = Regex("/$key(?![A-Za-z0-9])\\s*\\[([^]]*)]").find(dict) ?: return emptyList()
        return Regex("(\\d+)\\s+\\d+\\s+R").findAll(match.groupValues[1]).map { it.groupValues[1].toInt() }.toList()
    }
}
