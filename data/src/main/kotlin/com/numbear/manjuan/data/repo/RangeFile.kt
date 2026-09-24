package com.numbear.manjuan.data.repo

import java.io.File
import java.io.RandomAccessFile

/**
 * A file that is logically [logicalSize] bytes, with only the requested spans stored.
 * The allocated-byte note keeps the cache display from counting the empty tail.
 */
internal class RangeFile(
    val file: File,
    private val logicalSize: Long,
) {
    private val spans = ArrayList<Span>()

    init {
        file.parentFile?.mkdirs()
        val note = File(file.parentFile, file.name + ".ranges")
        if (note.isFile) {
            note.readLines().forEach { line ->
                val parts = line.split(' ')
                if (parts.size == 2) {
                    val start = parts[0].toLongOrNull()
                    val end = parts[1].toLongOrNull()
                    if (start != null && end != null && end > start) spans += Span(start, end)
                }
            }
        }
    }

    fun covers(start: Long, length: Long): Boolean {
        if (length <= 0) return true
        var cursor = start
        val end = start + length
        for (span in spans.sortedBy { it.start }) {
            if (span.end <= cursor) continue
            if (span.start > cursor) return false
            cursor = span.end
            if (cursor >= end) return true
        }
        return cursor >= end
    }

    fun read(start: Long, length: Int, fetch: (Long, Int) -> ByteArray): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (!covers(start, length.toLong())) {
            var at = start
            var left = length
            while (left > 0) {
                val count = minOf(left, 256 * 1024)
                if (covers(at, count.toLong())) {
                    at += count
                    left -= count
                    continue
                }
                val bytes = fetch(at, count)
                if (bytes.isEmpty()) break
                write(at, bytes)
                at += bytes.size
                left -= bytes.size
                if (bytes.size < count) break
            }
        }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val out = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = raf.read(out, offset, length - offset)
                if (count < 0) break
                offset += count
            }
            return if (offset == length) out else out.copyOf(offset)
        }
    }

    fun write(start: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (logicalSize > raf.length()) raf.setLength(logicalSize)
            raf.seek(start)
            raf.write(bytes)
        }
        spans += Span(start, start + bytes.size)
        merge()
        val note = File(file.parentFile, file.name + ".ranges")
        note.writeText(spans.joinToString("\n") { "${it.start} ${it.end}" })
        File(file.parentFile, file.name + ".allocated").writeText(spans.sumOf { it.end - it.start }.toString())
    }

    private fun merge() {
        if (spans.size < 2) return
        val ordered = spans.sortedBy { it.start }
        val merged = ArrayList<Span>()
        var current = ordered.first()
        for (span in ordered.drop(1)) {
            current = if (span.start <= current.end) {
                Span(current.start, maxOf(current.end, span.end))
            } else {
                merged += current
                span
            }
        }
        merged += current
        spans.clear()
        spans += merged
    }

    private data class Span(val start: Long, val end: Long)
}
