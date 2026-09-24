package com.numbear.manjuan.core

/**
 * RAR4 headers are a chain from the start of the archive. Their packed sizes say
 * where the next header is, so the file list can be read without the image bytes.
 */
object RemoteRar {
    data class Item(
        val name: String,
        val headerOffset: Long,
        val headerSize: Int,
        val dataOffset: Long,
        val dataSize: Long,
        val directory: Boolean,
        val solid: Boolean,
    )

    fun items(fileSize: Long, read: (Long, Int) -> ByteArray): List<Item> {
        if (fileSize < 7) throw UnsupportedBookException("无法解压 CBR：文件过短")
        val signatureAt = findSignature(fileSize, read)
        var position = signatureAt + 7
        val found = ArrayList<Item>()
        while (position + 7 <= fileSize) {
            val base = readExact(read, position, 7)
            val type = base[2].toInt() and 0xFF
            val flags = u16(base, 3)
            val headerSize = u16(base, 5)
            if (headerSize < 7 || position + headerSize > fileSize) {
                throw UnsupportedBookException("无法解压 CBR：文件头损坏")
            }
            val header = if (headerSize == 7) base else readExact(read, position, headerSize)
            if (type == 0x74 && flags and 0x0004 != 0) {
                throw UnsupportedBookException("这本 CBR 有密码，暂不支持")
            }
            var dataSize = 0L
            if (flags and 0x8000 != 0 && headerSize >= 11) {
                dataSize = u32(header, 7)
                if (type == 0x74 && flags and 0x0100 != 0 && headerSize >= 36) {
                    dataSize = dataSize or (u32(header, 32) shl 32)
                }
            }
            if (type == 0x74) {
                found += fileItem(header, flags, position, headerSize, dataSize)
            }
            if (type == 0x7B) break
            val next = position + headerSize + dataSize
            if (next <= position) throw UnsupportedBookException("无法解压 CBR：文件头损坏")
            position = next
        }
        return found
    }

    private fun fileItem(header: ByteArray, flags: Int, position: Long, headerSize: Int, dataSize: Long): Item {
        val nameSize = if (header.size >= 28) u16(header, 26) else 0
        val nameAt = if (flags and 0x0100 != 0) 40 else 32
        val name = if (nameSize > 0 && nameAt + nameSize <= header.size) {
            val raw = header.copyOfRange(nameAt, nameAt + nameSize)
            val end = raw.indexOf(0).takeIf { it >= 0 } ?: raw.size
            val slice = raw.copyOf(end)
            val utf = slice.toString(Charsets.UTF_8)
            if (!utf.contains('\uFFFD') && utf.toByteArray(Charsets.UTF_8).contentEquals(slice)) utf
            else slice.toString(Charsets.ISO_8859_1)
        } else {
            ""
        }
        return Item(
            name = name,
            headerOffset = position,
            headerSize = headerSize,
            dataOffset = position + headerSize,
            dataSize = dataSize,
            directory = flags and 0x00E0 == 0x00E0,
            solid = flags and 0x0010 != 0,
        )
    }

    private fun findSignature(fileSize: Long, read: (Long, Int) -> ByteArray): Long {
        val head = if (fileSize >= 8 && isRar4(readExact(read, 0, 8), 0)) {
            return 0
        } else {
            readExact(read, 0, minOf(fileSize, 64L * 1024).toInt())
        }
        if (head.size >= 8 && head[0] == 0x52.toByte() && head[1] == 0x61.toByte() && head[2] == 0x72.toByte() &&
            head[3] == 0x21.toByte() && head[4] == 0x1A.toByte() && head[5] == 0x07.toByte() && head[6] == 0x01.toByte()
        ) {
            throw UnsupportedBookException("此 CBR 使用 RAR5，当前版本暂不支持")
        }
        for (index in 0..head.size - 7) {
            if (head[index] == 0x52.toByte() && head[index + 1] == 0x61.toByte() && head[index + 2] == 0x72.toByte() &&
                head[index + 3] == 0x21.toByte() && head[index + 4] == 0x1A.toByte() && head[index + 5] == 0x07.toByte() &&
                head[index + 6] == 0x00.toByte()
            ) {
                return index.toLong()
            }
        }
        throw UnsupportedBookException("无法解压 CBR：不是 RAR 文件")
    }

    private fun isRar4(bytes: ByteArray, index: Int): Boolean =
        index + 6 < bytes.size &&
            bytes[index] == 0x52.toByte() && bytes[index + 1] == 0x61.toByte() &&
            bytes[index + 2] == 0x72.toByte() && bytes[index + 3] == 0x21.toByte() &&
            bytes[index + 4] == 0x1A.toByte() && bytes[index + 5] == 0x07.toByte() &&
            bytes[index + 6] == 0x00.toByte()

    private fun readExact(read: (Long, Int) -> ByteArray, start: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val bytes = read(start, length)
        if (bytes.size < length) throw UnsupportedBookException("无法解压 CBR：文件过短")
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
}
