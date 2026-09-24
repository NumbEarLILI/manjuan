package com.numbear.manjuan.core

/**
 * A MOBI file's record table sits at the front. The text is a run of records after
 * the header, so a reader can fetch that table and then only the next slice of records.
 */
object RemoteMobi {
    const val BUDGET = 256 * 1024

    data class Table(val offsets: IntArray, val fileSize: Long) {
        fun endOf(index: Int): Long =
            if (index + 1 < offsets.size) offsets[index + 1].toLong() else fileSize

        override fun equals(other: Any?): Boolean = other is Table && fileSize == other.fileSize && offsets.contentEquals(other.offsets)
        override fun hashCode(): Int = 31 * offsets.contentHashCode() + fileSize.hashCode()
    }

    fun table(fileSize: Long, read: (Long, Int) -> ByteArray): Table {
        val head = readExact(read, 0, 78)
        if (!FormatDetector.isMobi(head)) throw UnsupportedBookException("这不是有效的 MOBI（缺少 BOOKMOBI 标识）")
        val count = u16(head, 76)
        if (count <= 0) throw UnsupportedBookException("MOBI 记录表是空的")
        val width = count * 8
        if (78L + width > fileSize) throw UnsupportedBookException("MOBI 文件不完整或已损坏")
        val raw = readExact(read, 78, width)
        return Table(IntArray(count) { index -> u32(raw, index * 8).toInt() }, fileSize)
    }

    /** Record index of the Palm header that holds the text to read. Joint KF8 files use the second header. */
    fun chooseHeader(table: Table, read: (Long, Int) -> ByteArray): Int {
        for (index in 1 until table.offsets.size) {
            if (table.endOf(index) - table.offsets[index] != 8L) continue
            val mark = readExact(read, table.offsets[index].toLong(), 8)
            if (mark.toString(Charsets.US_ASCII) != "BOUNDARY") continue
            val next = index + 1
            if (next >= table.offsets.size) continue
            val header = readRecord(table, next, read)
            if (isPalmHeader(header)) return next
        }
        return 0
    }

    fun readRecord(table: Table, index: Int, read: (Long, Int) -> ByteArray): ByteArray {
        if (index !in table.offsets.indices) throw UnsupportedBookException("MOBI 文件不完整或已损坏")
        val start = table.offsets[index].toLong()
        val end = table.endOf(index)
        val length = end - start
        if (length < 0 || length > Int.MAX_VALUE) throw UnsupportedBookException("MOBI 文件不完整或已损坏")
        return readExact(read, start, length.toInt())
    }

    fun requireText(header: ByteArray) {
        if (header.size < 16) throw UnsupportedBookException("MOBI 文件头损坏")
        val compression = u16(header, 0)
        val encryption = u16(header, 12)
        if (encryption != 0) throw UnsupportedBookException("此 MOBI 已加密，暂不支持")
        if (compression == 17480) throw UnsupportedBookException("此 MOBI 使用 Huff/CDIC 压缩，暂不支持")
        if (compression != 1 && compression != 2) {
            throw UnsupportedBookException("此 MOBI 使用了暂不支持的压缩方式（$compression）")
        }
    }

    fun textRecords(header: ByteArray, recordsAfterHeader: Int): Int {
        val declared = u16(header, 8)
        if (header.size < 0x54) return declared
        if (header.copyOfRange(16, 20).toString(Charsets.US_ASCII) != "MOBI") return declared
        val firstNonText = u32(header, 0x50).toInt()
        if (firstNonText <= 1) return declared
        val implied = firstNonText - 1
        if (implied > declared && implied <= recordsAfterHeader) return implied
        return declared
    }

    fun preferComic(header: ByteArray): Boolean {
        if (header.size < 16) return false
        val textLength = u32(header, 4)
        val records = u16(header, 8)
        val firstImage = if (header.size >= 0x70) u32(header, 0x6C) else 0L
        val bookType = exthBytes(header, 123)?.toString(Charsets.UTF_8)?.lowercase().orEmpty()
        if ("comic" in bookType) return true
        return (textLength == 0L || records == 0) && firstImage > 0
    }

    fun imageStart(header: ByteArray, headerIndex: Int, recordCount: Int, looksLikeImage: (Int) -> Boolean): Int {
        if (header.size < 0x70) return -1
        val first = u32(header, 0x6C).toInt()
        if (first <= 0) return -1
        return listOf(first, headerIndex + first).firstOrNull { index ->
            index in 0 until recordCount && looksLikeImage(index)
        } ?: -1
    }

    /** Text records to have locally after this step. Always advances by at least one when any remain. */
    fun nextCount(table: Table, headerIndex: Int, textRecords: Int, already: Int, budget: Int = BUDGET): Int {
        val last = headerIndex + textRecords
        var index = headerIndex + 1 + already
        if (index >= last) return already
        var included = already
        var bytes = 0L
        while (index <= last && index < table.offsets.size && (included == already || bytes < budget)) {
            if (index == last) break
            bytes += table.endOf(index) - table.offsets[index]
            included++
            index++
        }
        return included
    }

    fun synthesize(header: ByteArray, texts: List<ByteArray>, fullCount: Int): ByteArray {
        val patched = header.copyOf()
        if (patched.size >= 10) {
            put16(patched, 8, texts.size)
            if (texts.size < fullCount && patched.size >= 8) put32(patched, 4, 0)
        }
        val records = ArrayList<ByteArray>(texts.size + 1)
        records += patched
        records += texts
        val pdb = ByteArray(78 + records.size * 8)
        "BOOK".toByteArray(Charsets.US_ASCII).copyInto(pdb, 60)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(pdb, 64)
        put16(pdb, 76, records.size)
        var cursor = pdb.size
        for (index in records.indices) {
            put32(pdb, 78 + index * 8, cursor)
            cursor += records[index].size
        }
        val out = ByteArray(cursor)
        pdb.copyInto(out)
        var at = pdb.size
        for (record in records) {
            record.copyInto(out, at)
            at += record.size
        }
        return out
    }

    private fun isPalmHeader(record: ByteArray): Boolean {
        if (record.size < 16) return false
        val compression = u16(record, 0)
        return compression == 1 || compression == 2 || compression == 17480
    }

    private fun exthBytes(header: ByteArray, type: Int): ByteArray? {
        if (header.size < 24 || header.copyOfRange(16, 20).toString(Charsets.US_ASCII) != "MOBI") return null
        val headerLength = u32(header, 20).toInt()
        if (headerLength < 16) return null
        val start = 16 + headerLength
        if (start + 12 > header.size) return null
        if (header.copyOfRange(start, start + 4).toString(Charsets.US_ASCII) != "EXTH") return null
        val count = u32(header, start + 8).toInt()
        var cursor = start + 12
        repeat(count) {
            if (cursor + 8 > header.size) return null
            val kind = u32(header, cursor).toInt()
            val length = u32(header, cursor + 4).toInt()
            if (length < 8 || cursor + length > header.size) return null
            if (kind == type) return header.copyOfRange(cursor + 8, cursor + length)
            cursor += length
        }
        return null
    }

    private fun readExact(read: (Long, Int) -> ByteArray, start: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val bytes = read(start, length)
        if (bytes.size < length) throw UnsupportedBookException("MOBI 文件不完整或已损坏")
        return bytes
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
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
