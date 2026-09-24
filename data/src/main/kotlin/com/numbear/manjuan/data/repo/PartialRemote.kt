package com.numbear.manjuan.data.repo

import com.numbear.manjuan.core.BookFormat
import com.numbear.manjuan.core.CbrExtractor
import com.numbear.manjuan.core.EpubParser
import com.numbear.manjuan.core.FormatDetector
import com.numbear.manjuan.core.ImageSniff
import com.numbear.manjuan.core.MobiParser
import com.numbear.manjuan.core.NaturalSort
import com.numbear.manjuan.core.NovelContent
import com.numbear.manjuan.core.RemoteMobi
import com.numbear.manjuan.core.RemotePdf
import com.numbear.manjuan.core.RemoteRar
import com.numbear.manjuan.core.RemoteText
import com.numbear.manjuan.core.RemoteZip
import com.numbear.manjuan.core.UnsupportedBookException
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Opens a remote container by its directory (ZIP central directory, PDF trailer,
 * MOBI record table, RAR headers) and then the pages or chapters being read.
 */
internal class PartialRemote(
    private val directory: File,
    private val fetch: (Long, Int) -> ByteArray,
) {
    fun epub(fileSize: Long, title: String, maxSpineHtml: Int): NovelContent {
        val entries = zipEntries(fileSize)
        val parsed = EpubParser.parse(
            { path ->
                val entry = RemoteZip.find(entries, path) ?: return@parse null
                readZipEntry(entry)
            },
            title,
            maxSpineHtml,
        )
        val loaded = File(directory, "entries").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return parsed.copy(loadedBytes = loaded, totalBytes = fileSize)
    }

    fun cbz(fileSize: Long, title: String, format: BookFormat, page: Int): PagedContent {
        val images = zipEntries(fileSize)
            .filter { FormatDetector.isImageName(it.name) }
            .sortedWith(compareBy(NaturalSort) { it.name.substringAfterLast('/') })
        if (images.isEmpty()) throw UnsupportedBookException("压缩包里没有可显示的图片")
        return writeImages(title, format, images.size, page) { index ->
            readZipEntry(images[index])
        }
    }

    fun cbr(fileSize: Long, title: String, page: Int): PagedContent {
        val store = RangeFile(File(directory, "book.cbr"), fileSize)
        val items = RemoteRar.items(fileSize) { start, length -> store.read(start, length, fetch) }
        val images = items.filter { !it.directory && FormatDetector.isImageName(it.name) }
            .sortedWith(compareBy(NaturalSort) { it.name.substringAfterLast('/').substringAfterLast('\\') })
        if (images.isEmpty()) throw UnsupportedBookException("CBR 里没有可显示的图片")
        val target = RemoteText.imageTarget(page, images.size)
        val wanted = images.take(target)
        val solid = items.any { it.solid }
        val through = if (!solid) {
            wanted
        } else {
            val last = items.indexOfLast { item -> wanted.any { it.dataOffset == item.dataOffset } }
            items.subList(0, last + 1).filter { !it.directory }
        }
        for (item in through) {
            var at = item.dataOffset
            var left = item.dataSize
            while (left > 0) {
                val count = minOf(left, 256L * 1024).toInt()
                if (!store.covers(at, count.toLong())) store.read(at, count, fetch)
                at += count
                left -= count
            }
        }
        val out = File(directory, "images").apply { mkdirs() }
        val extracted = CbrExtractor.extractImages(
            store.file,
            out,
            wanted.map { it.name }.toSet(),
            solidPrefix = solid,
        ).associateBy { it.name }
        val pages = wanted.map { item ->
            val base = item.name.substringAfterLast('/').substringAfterLast('\\')
            val file = extracted[base]
                ?: extracted.values.firstOrNull { it.name.substringAfterLast('-') == base }
                ?: throw UnsupportedBookException("CBR 里没有可显示的图片")
            PageRef.FilePage(file.absolutePath)
        }
        val complete = target >= images.size
        val marker = File(out, ".partial")
        if (complete) marker.delete() else marker.writeText("partial")
        return PagedContent(title, BookFormat.CBR, pages, null, 0, remotePageCount = if (complete) 0 else images.size)
    }

    fun pdf(fileSize: Long, title: String, page: Int): PagedContent {
        val store = RangeFile(File(directory, "remote.pdf"), fileSize)
        val remote = RemotePdf(fileSize) { start, length -> store.read(start, length, fetch) }
        remote.ensure(page.coerceIn(0, remote.pageCount - 1))
        return PagedContent(title, BookFormat.PDF, emptyList(), store.file, remote.pageCount, remotePageCount = 0)
    }

    fun ensurePdf(fileSize: Long, page: Int) {
        val store = RangeFile(File(directory, "remote.pdf"), fileSize)
        val remote = RemotePdf(fileSize) { start, length -> store.read(start, length, fetch) }
        if (remote.pageCount == 0) return
        remote.ensure(page.coerceIn(0, remote.pageCount - 1))
    }

    fun mobiIsComic(fileSize: Long): Boolean {
        val table = RemoteMobi.table(fileSize, fetch)
        val headerIndex = RemoteMobi.chooseHeader(table, fetch)
        val header = RemoteMobi.readRecord(table, headerIndex, fetch)
        return RemoteMobi.preferComic(header)
    }

    fun mobiNovel(fileSize: Long, title: String, already: Int, advance: Boolean): NovelContent {
        val table = RemoteMobi.table(fileSize, fetch)
        val headerIndex = RemoteMobi.chooseHeader(table, fetch)
        val header = RemoteMobi.readRecord(table, headerIndex, fetch)
        RemoteMobi.requireText(header)
        val full = RemoteMobi.textRecords(header, table.offsets.size - headerIndex - 1)
        val included = if (advance) {
            RemoteMobi.nextCount(table, headerIndex, full, already)
        } else {
            already.coerceIn(1, full.coerceAtLeast(1))
        }
        File(directory, "mobi-text.txt").also { it.parentFile?.mkdirs() }.writeText(included.toString())
        val texts = (0 until included).map { index -> record(table, headerIndex + 1 + index) }
        val synthetic = File(directory, "partial.mobi")
        synthetic.parentFile?.mkdirs()
        synthetic.writeBytes(RemoteMobi.synthesize(header, texts, full))
        val parsed = MobiParser.parse(synthetic)
        val loaded = (0 until included).sumOf { index ->
            table.endOfRecord(headerIndex + 1 + index)
        }
        return parsed.copy(
            title = parsed.title.ifBlank { title },
            more = included < full,
            loadedBytes = loaded,
            totalBytes = fileSize,
        )
    }

    fun mobiComic(fileSize: Long, title: String, format: BookFormat, page: Int): PagedContent {
        val table = RemoteMobi.table(fileSize, fetch)
        val headerIndex = RemoteMobi.chooseHeader(table, fetch)
        val header = RemoteMobi.readRecord(table, headerIndex, fetch)
        val start = RemoteMobi.imageStart(header, headerIndex, table.offsets.size) { index ->
            looksLikeImage(peek(table, index, 32))
        }
        if (start < 0) throw UnsupportedBookException("这本 MOBI 是图片书，但没有可显示的图片")
        val records = (start until table.offsets.size).toList()
        var end = records.size
        val known = File(directory, "mobi-image-count.txt")
        if (known.isFile) end = known.readText().trim().toIntOrNull()?.coerceIn(0, records.size) ?: records.size
        return writeImages(title, format, end, page) { index ->
            val bytes = record(table, records[index])
            val image = ImageSniff.extract(bytes)
            if (image == null) {
                known.writeText(index.toString())
                throw StopImages()
            }
            image
        }.let { content ->
            if (known.isFile) content.copy(remotePageCount = 0) else content
        }
    }

    private class StopImages : RuntimeException()

    private fun writeImages(
        title: String,
        format: BookFormat,
        total: Int,
        page: Int,
        bytes: (Int) -> ByteArray,
    ): PagedContent {
        val target = RemoteText.imageTarget(page, total)
        val dir = File(directory, "images").apply { mkdirs() }
        var have = 0
        while (have < target) {
            val prefix = "%05d-".format(have + 1)
            val existing = dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.startsWith(prefix) && it.length() > 0L }
            if (existing == null) {
                val payload = try {
                    bytes(have)
                } catch (_: StopImages) {
                    break
                }
                val ext = ImageSniff.extension(payload).let { if (it == "img") "jpg" else it }
                File(dir, "$prefix" + "page.$ext").writeBytes(payload)
            }
            have++
        }
        val complete = have >= total
        val marker = File(dir, ".partial")
        if (complete) marker.delete() else marker.writeText("partial")
        val pages = (0 until have).map { index ->
            val file = dir.listFiles().orEmpty().first { it.isFile && it.name.startsWith("%05d-".format(index + 1)) }
            PageRef.FilePage(file.absolutePath)
        }
        return PagedContent(
            title,
            format,
            pages,
            null,
            0,
            remotePageCount = if (complete) 0 else total,
        )
    }

    private fun zipEntries(fileSize: Long): List<RemoteZip.Entry> {
        val cache = File(directory, "zip-index.txt")
        if (cache.isFile && cache.readLines().firstOrNull() == fileSize.toString()) {
            return cache.readLines().drop(1).mapNotNull(::decodeEntry)
        }
        val entries = RemoteZip.index(fileSize, fetch)
        cache.parentFile?.mkdirs()
        cache.writeText(buildString {
            append(fileSize)
            append('\n')
            entries.forEach { entry ->
                append(URLEncoder.encode(entry.name, StandardCharsets.UTF_8))
                append('\t')
                append(entry.method)
                append('\t')
                append(entry.flag)
                append('\t')
                append(entry.compressedSize)
                append('\t')
                append(entry.uncompressedSize)
                append('\t')
                append(entry.localHeaderOffset)
                append('\n')
            }
        })
        return entries
    }

    private fun decodeEntry(line: String): RemoteZip.Entry? {
        val parts = line.split('\t')
        if (parts.size != 6) return null
        return RemoteZip.Entry(
            name = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
            method = parts[1].toInt(),
            flag = parts[2].toInt(),
            compressedSize = parts[3].toLong(),
            uncompressedSize = parts[4].toLong(),
            localHeaderOffset = parts[5].toLong(),
        )
    }

    private fun readZipEntry(entry: RemoteZip.Entry): ByteArray {
        val cache = File(directory, "entries/" + URLEncoder.encode(entry.name, StandardCharsets.UTF_8).take(180))
        if (cache.isFile && cache.length() > 0) return cache.readBytes()
        val bytes = RemoteZip.readEntry(entry, fetch)
        cache.parentFile?.mkdirs()
        cache.writeBytes(bytes)
        return bytes
    }

    private fun record(table: RemoteMobi.Table, index: Int): ByteArray {
        val cache = File(directory, "mobi-rec-$index")
        if (cache.isFile && cache.length() > 0) return cache.readBytes()
        val bytes = RemoteMobi.readRecord(table, index, fetch)
        cache.parentFile?.mkdirs()
        cache.writeBytes(bytes)
        return bytes
    }

    private fun peek(table: RemoteMobi.Table, index: Int, count: Int): ByteArray {
        val cache = File(directory, "mobi-rec-$index")
        if (cache.isFile && cache.length() > 0) return cache.readBytes().copyOf(minOf(count, cache.length().toInt()))
        val start = table.offsets[index].toLong()
        val available = (table.endOf(index) - start).toInt().coerceAtLeast(0)
        return fetch(start, minOf(count, available))
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val gif = bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "GIF8"
        val webp = bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF"
        return jpeg || png || gif || webp
    }
}

private fun RemoteMobi.Table.endOfRecord(index: Int): Long = endOf(index) - offsets[index]
