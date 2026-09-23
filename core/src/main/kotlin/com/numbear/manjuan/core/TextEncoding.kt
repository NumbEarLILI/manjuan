package com.numbear.manjuan.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextEncoding {
    private val gb18030: Charset = Charset.forName("GB18030")

    fun decode(bytes: ByteArray): DecodedText {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return DecodedText(bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8), "UTF-8")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return DecodedText(bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE), "UTF-16BE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return DecodedText(bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE), "UTF-16LE")
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return DecodedText(it, "UTF-8") }
        strictDecode(bytes, gb18030)?.let { return DecodedText(it, "GB18030") }
        return DecodedText(bytes.toString(Charsets.UTF_8), "UTF-8")
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
}
