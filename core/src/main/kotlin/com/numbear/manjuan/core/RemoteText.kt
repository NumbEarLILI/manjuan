package com.numbear.manjuan.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A remote novel is opened from the bytes downloaded so far. The next read continues
 * at [loadedBytes] instead of fetching the whole file again.
 */
object RemoteText {
    const val CHUNK_BYTES = 256 * 1024
    const val RUNWAY_CHARS = 4_096
    const val IMAGE_BATCH = 8

    fun covered(chapters: List<NovelChapter>, chapter: Int, offset: Int, complete: Boolean): Boolean {
        if (complete || chapters.isEmpty()) return complete
        if (chapter < chapters.lastIndex) return true
        if (chapter > chapters.lastIndex) return false
        return offset + RUNWAY_CHARS < chapters[chapter].text.length
    }

    /** How many remote images to have locally so [page] can be shown, plus a following batch. */
    fun imageTarget(page: Int, total: Int, batch: Int = IMAGE_BATCH): Int {
        if (total <= 0) return 0
        return (page.coerceAtLeast(0) + batch).coerceIn(batch.coerceAtMost(total), total)
    }

    fun novel(title: String, author: String, bytes: ByteArray, totalBytes: Long): NovelContent {
        val complete = totalBytes >= 0 && bytes.size.toLong() >= totalBytes
        val decoded = decodePrefix(bytes, complete)
        val chapters = TxtChapters.split(decoded.text).map { chapter ->
            NovelChapter(chapter.title, decoded.text.substring(chapter.start, chapter.end).trim())
        }.ifEmpty { listOf(NovelChapter("正文", "")) }
        val knownTotal = when {
            totalBytes >= 0 -> totalBytes
            complete -> bytes.size.toLong()
            else -> -1L
        }
        return NovelContent(
            title = title,
            author = author,
            chapters = chapters,
            more = !complete && (knownTotal < 0 || bytes.size.toLong() < knownTotal),
            loadedBytes = bytes.size.toLong(),
            totalBytes = knownTotal.coerceAtLeast(0),
        )
    }

    private fun decodePrefix(bytes: ByteArray, complete: Boolean): DecodedText {
        if (bytes.isEmpty() || complete) return TextEncoding.decode(bytes)
        var end = bytes.size
        val floor = (bytes.size - 16).coerceAtLeast(0)
        while (end > floor) {
            val slice = if (end == bytes.size) bytes else bytes.copyOf(end)
            if (decodesStrict(slice)) return TextEncoding.decode(slice)
            end--
        }
        return TextEncoding.decode(bytes)
    }

    private fun decodesStrict(bytes: ByteArray): Boolean {
        if (strict(bytes, Charsets.UTF_8)) return true
        return strict(bytes, Charset.forName("GB18030"))
    }

    private fun strict(bytes: ByteArray, charset: Charset): Boolean = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    } catch (_: Exception) {
        false
    }
}
