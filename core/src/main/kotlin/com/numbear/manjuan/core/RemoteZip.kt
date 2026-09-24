package com.numbear.manjuan.core

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * Reads one ZIP entry after fetching the central directory from the end of the file.
 * EPUB and CBZ keep that directory in the tail, so the rest of the archive can stay remote.
 */
object RemoteZip {
    data class Entry(
        val name: String,
        val method: Int,
        val flag: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    fun index(fileSize: Long, read: (Long, Int) -> ByteArray): List<Entry> {
        if (fileSize < 22) throw UnsupportedBookException("压缩包不完整")
        val located = locate(fileSize, read)
        if (located.size > Int.MAX_VALUE) throw UnsupportedBookException("压缩包目录太大")
        val directory = readExact(read, located.offset, located.size.toInt())
        return entries(directory)
    }

    fun readEntry(entry: Entry, read: (Long, Int) -> ByteArray): ByteArray {
        if (entry.flag and 1 != 0) throw UnsupportedBookException("这本压缩包有密码，暂不支持")
        val localHead = readExact(read, entry.localHeaderOffset, 30)
        if (localHead.size < 30 || u32(localHead, 0) != 0x04034b50L) {
            throw UnsupportedBookException("压缩包本地文件头损坏")
        }
        val nameLen = u16(localHead, 26)
        val extraLen = u16(localHead, 28)
        val dataStart = entry.localHeaderOffset + 30 + nameLen + extraLen
        if (entry.compressedSize > Int.MAX_VALUE) throw UnsupportedBookException("这一段太大，无法加载")
        val compressed = if (entry.compressedSize == 0L) {
            ByteArray(0)
        } else {
            readExact(read, dataStart, entry.compressedSize.toInt())
        }
        return when (entry.method) {
            0 -> compressed
            8 -> inflate(compressed, entry.uncompressedSize)
            else -> throw UnsupportedBookException("暂不支持的压缩方式（${entry.method}）")
        }
    }

    fun find(entries: List<Entry>, path: String): Entry? {
        val want = path.trim().trimStart('/')
        if (want.isEmpty()) return null
        return entries.firstOrNull { it.name == want }
            ?: entries.firstOrNull { it.name.equals(want, ignoreCase = true) }
            ?: entries.firstOrNull { it.name.endsWith("/$want") }
    }

    private data class Located(val offset: Long, val size: Long)

    private fun locate(fileSize: Long, read: (Long, Int) -> ByteArray): Located {
        var window = minOf(fileSize, 64L * 1024 + 22)
        while (true) {
            val start = fileSize - window
            val tail = readExact(read, start, window.toInt())
            val eocd = findEocd(tail, start, fileSize)
            if (eocd != null) return resolve(eocd, read)
            if (window >= fileSize || window >= 1024L * 1024) break
            window = minOf(fileSize, window * 4)
        }
        throw UnsupportedBookException("压缩包缺少目录，无法分段打开")
    }

    private data class Eocd(
        val entryCount: Long,
        val directorySize: Long,
        val directoryOffset: Long,
        val zip64: Boolean,
        val locatorOffset: Long,
    )

    private fun findEocd(tail: ByteArray, tailStart: Long, fileSize: Long): Eocd? {
        var index = tail.size - 22
        while (index >= 0) {
            if (u32(tail, index) == 0x06054b50L) {
                val comment = u16(tail, index + 20)
                val eocdEnd = tailStart + index + 22 + comment
                if (eocdEnd == fileSize) {
                    val count = u16(tail, index + 10).toLong()
                    val size = u32(tail, index + 12)
                    val offset = u32(tail, index + 16)
                    val zip64 = count == 0xFFFFL || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL
                    return Eocd(count, size, offset, zip64, tailStart + index - 20)
                }
            }
            index--
        }
        return null
    }

    private fun resolve(eocd: Eocd, read: (Long, Int) -> ByteArray): Located {
        if (!eocd.zip64) return Located(eocd.directoryOffset, eocd.directorySize)
        if (eocd.locatorOffset < 0) throw UnsupportedBookException("压缩包缺少目录，无法分段打开")
        val locator = readExact(read, eocd.locatorOffset, 20)
        if (locator.size < 20 || u32(locator, 0) != 0x07064b50L) {
            throw UnsupportedBookException("压缩包缺少目录，无法分段打开")
        }
        val zip64Offset = u64(locator, 8)
        val zip64 = readExact(read, zip64Offset, 56)
        if (zip64.size < 56 || u32(zip64, 0) != 0x06064b50L) {
            throw UnsupportedBookException("压缩包缺少目录，无法分段打开")
        }
        return Located(u64(zip64, 48), u64(zip64, 40))
    }

    private fun entries(directory: ByteArray): List<Entry> {
        val found = ArrayList<Entry>()
        var cursor = 0
        while (cursor + 46 <= directory.size) {
            if (u32(directory, cursor) != 0x02014b50L) break
            val flag = u16(directory, cursor + 8)
            val method = u16(directory, cursor + 10)
            var compressed = u32(directory, cursor + 20)
            var uncompressed = u32(directory, cursor + 24)
            val nameLen = u16(directory, cursor + 28)
            val extraLen = u16(directory, cursor + 30)
            val commentLen = u16(directory, cursor + 32)
            var localOffset = u32(directory, cursor + 42)
            val nameStart = cursor + 46
            val nameEnd = nameStart + nameLen
            val extraEnd = nameEnd + extraLen
            if (extraEnd > directory.size) break
            val nameBytes = directory.copyOfRange(nameStart, nameEnd)
            if (uncompressed == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                val extra = directory.copyOfRange(nameEnd, extraEnd)
                val zip64 = zip64Extra(extra, uncompressed, compressed, localOffset)
                uncompressed = zip64.uncompressed
                compressed = zip64.compressed
                localOffset = zip64.offset
            }
            val name = decodeName(nameBytes, flag)
            if (!name.endsWith("/")) {
                found += Entry(name, method, flag, compressed, uncompressed, localOffset)
            }
            cursor = extraEnd + commentLen
        }
        if (found.isEmpty() && directory.isNotEmpty()) throw UnsupportedBookException("压缩包目录是空的")
        return found
    }

    private data class Zip64Sizes(val uncompressed: Long, val compressed: Long, val offset: Long)

    private fun zip64Extra(extra: ByteArray, uncompressed: Long, compressed: Long, offset: Long): Zip64Sizes {
        var cursor = 0
        var outUncompressed = uncompressed
        var outCompressed = compressed
        var outOffset = offset
        while (cursor + 4 <= extra.size) {
            val id = u16(extra, cursor)
            val size = u16(extra, cursor + 2)
            val start = cursor + 4
            val end = start + size
            if (end > extra.size) break
            if (id == 1) {
                var at = start
                if (outUncompressed == 0xFFFFFFFFL && at + 8 <= end) {
                    outUncompressed = u64(extra, at)
                    at += 8
                }
                if (outCompressed == 0xFFFFFFFFL && at + 8 <= end) {
                    outCompressed = u64(extra, at)
                    at += 8
                }
                if (outOffset == 0xFFFFFFFFL && at + 8 <= end) outOffset = u64(extra, at)
            }
            cursor = end
        }
        return Zip64Sizes(outUncompressed, outCompressed, outOffset)
    }

    private fun decodeName(bytes: ByteArray, flag: Int): String {
        if (flag and 0x800 != 0) return bytes.toString(Charsets.UTF_8)
        val utf = bytes.toString(Charsets.UTF_8)
        if (!utf.contains('\uFFFD') && utf.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return utf
        return bytes.toString(Charset.forName("GB18030"))
    }

    private fun inflate(compressed: ByteArray, uncompressedSize: Long): ByteArray {
        if (compressed.isEmpty()) return ByteArray(0)
        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressed)
            if (uncompressedSize in 1..Int.MAX_VALUE.toLong()) {
                val out = ByteArray(uncompressedSize.toInt())
                val written = inflater.inflate(out)
                if (written < 0) throw UnsupportedBookException("无法解压这一段")
                if (written == out.size) out else out.copyOf(written)
            } else {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && inflater.needsInput()) break
                    if (count > 0) out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally {
            inflater.end()
        }
    }

    private fun readExact(read: (Long, Int) -> ByteArray, start: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val bytes = read(start, length)
        if (bytes.size < length) throw UnsupportedBookException("压缩包不完整")
        return bytes
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        if (offset + 7 >= bytes.size) return 0
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
    }
}
