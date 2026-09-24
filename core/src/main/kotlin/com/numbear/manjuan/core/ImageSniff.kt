package com.numbear.manjuan.core

object ImageSniff {
    fun extract(record: ByteArray): ByteArray? {
        if (record.isEmpty()) return null
        val start = magicOffset(record)
        if (start < 0 || start > 128) return null
        val slice = if (start == 0) record else record.copyOfRange(start, record.size)
        return slice.takeIf { recognizable(it) }
    }

    fun extension(bytes: ByteArray): String = when {
        isPng(bytes, 0) -> "png"
        isGif(bytes, 0) -> "gif"
        isWebp(bytes, 0) -> "webp"
        isJpeg(bytes, 0) -> "jpg"
        else -> "img"
    }

    private fun magicOffset(record: ByteArray): Int {
        val limit = minOf(record.size, 129)
        for (index in 0 until limit) {
            if (isPng(record, index) || isJpeg(record, index) || isGif(record, index) || isWebp(record, index)) {
                return index
            }
        }
        return -1
    }

    private fun recognizable(bytes: ByteArray): Boolean = when {
        isPng(bytes, 0) -> containsAscii(bytes, "IEND")
        isGif(bytes, 0) -> bytes.size >= 10
        isWebp(bytes, 0) -> bytes.size >= 16
        isJpeg(bytes, 0) -> hasJpegEnd(bytes)
        else -> false
    }

    private fun isPng(bytes: ByteArray, offset: Int): Boolean =
        offset + 8 <= bytes.size &&
            bytes[offset] == 0x89.toByte() &&
            bytes[offset + 1] == 'P'.code.toByte() &&
            bytes[offset + 2] == 'N'.code.toByte() &&
            bytes[offset + 3] == 'G'.code.toByte()

    private fun isJpeg(bytes: ByteArray, offset: Int): Boolean =
        offset + 3 <= bytes.size &&
            bytes[offset] == 0xFF.toByte() &&
            bytes[offset + 1] == 0xD8.toByte() &&
            bytes[offset + 2] == 0xFF.toByte()

    private fun isGif(bytes: ByteArray, offset: Int): Boolean =
        offset + 6 <= bytes.size &&
            bytes[offset] == 'G'.code.toByte() &&
            bytes[offset + 1] == 'I'.code.toByte() &&
            bytes[offset + 2] == 'F'.code.toByte() &&
            bytes[offset + 3] == '8'.code.toByte()

    private fun isWebp(bytes: ByteArray, offset: Int): Boolean =
        offset + 12 <= bytes.size &&
            bytes[offset] == 'R'.code.toByte() &&
            bytes[offset + 1] == 'I'.code.toByte() &&
            bytes[offset + 2] == 'F'.code.toByte() &&
            bytes[offset + 3] == 'F'.code.toByte() &&
            bytes[offset + 8] == 'W'.code.toByte() &&
            bytes[offset + 9] == 'E'.code.toByte() &&
            bytes[offset + 10] == 'B'.code.toByte() &&
            bytes[offset + 11] == 'P'.code.toByte()

    private fun hasJpegEnd(bytes: ByteArray): Boolean {
        var index = 2
        while (index + 1 < bytes.size) {
            if (bytes[index] == 0xFF.toByte() && bytes[index + 1] == 0xD9.toByte()) return true
            index++
        }
        return false
    }

    private fun containsAscii(bytes: ByteArray, token: String): Boolean {
        val needle = token.toByteArray(Charsets.US_ASCII)
        if (needle.size > bytes.size) return false
        for (index in 0..bytes.size - needle.size) {
            var match = true
            for (step in needle.indices) {
                if (bytes[index + step] != needle[step]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}
