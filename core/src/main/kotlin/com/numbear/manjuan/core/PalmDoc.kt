package com.numbear.manjuan.core

object PalmDoc {
    fun decompress(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size * 2)
        var index = 0
        while (index < data.size) {
            val c = data[index].toInt() and 0xFF
            index++
            when {
                c in 1..8 -> {
                    val take = minOf(c, data.size - index)
                    for (offset in 0 until take) {
                        out += data[index + offset]
                    }
                    index += take
                }
                c < 0x80 -> out += c.toByte()
                c >= 0xC0 -> {
                    out += ' '.code.toByte()
                    out += (c xor 0x80).toByte()
                }
                else -> {
                    if (index >= data.size) break
                    val combined = (c shl 8) or (data[index].toInt() and 0xFF)
                    index++
                    val distance = (combined shr 3) and 0x07FF
                    val length = (combined and 7) + 3
                    if (distance <= 0 || distance > out.size) continue
                    val start = out.size - distance
                    for (step in 0 until length) {
                        out += out[start + step]
                    }
                }
            }
        }
        return ByteArray(out.size) { out[it] }
    }
}
