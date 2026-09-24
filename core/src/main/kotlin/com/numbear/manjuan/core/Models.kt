package com.numbear.manjuan.core

enum class BookFormat {
    TXT,
    MARKDOWN,
    EPUB,
    MOBI,
    AZW3,
    PDF,
    CBZ,
    CBR,
    ZIP_IMAGES,
    IMAGE_FOLDER,
    UNSUPPORTED,
}

enum class BookKind {
    NOVEL,
    COMIC,
    PDF,
}

fun BookFormat.kind(): BookKind? = when (this) {
    BookFormat.TXT, BookFormat.MARKDOWN, BookFormat.EPUB, BookFormat.MOBI, BookFormat.AZW3 -> BookKind.NOVEL
    BookFormat.CBZ, BookFormat.CBR, BookFormat.ZIP_IMAGES, BookFormat.IMAGE_FOLDER -> BookKind.COMIC
    BookFormat.PDF -> BookKind.PDF
    BookFormat.UNSUPPORTED -> null
}

data class Detection(
    val format: BookFormat,
    val error: String? = null,
)

class UnsupportedBookException(message: String) : Exception(message)

data class DecodedText(val text: String, val charset: String)

data class TextChapter(val title: String, val start: Int, val end: Int)

data class PageSlice(val start: Int, val end: Int, val text: String)

sealed class NovelSpan {
    data class Prose(val text: String) : NovelSpan()
    data class Plate(val bytes: ByteArray) : NovelSpan() {
        override fun equals(other: Any?): Boolean = other is Plate && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

data class NovelChapter(
    val title: String,
    val text: String,
    val spans: List<NovelSpan> = emptyList(),
)

data class NovelContent(
    val title: String,
    val author: String,
    val chapters: List<NovelChapter>,
    /** Remote text that has not been downloaded through [totalBytes] yet. */
    val more: Boolean = false,
    val loadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

data class ReadingProgress(val locator: String, val percent: Float, val updatedAt: Long)

data class ReaderSettings(
    val pageMode: Boolean = true,
    val fontSizeSp: Float = 18f,
    val lineSpacing: Float = 1.6f,
    val marginDp: Float = 20f,
    val appearance: String = AppTheme.Default.storageKey,
    val theme: String = AppTheme.PAPER_FOLLOW,
    val customBackground: Long = 0xFFF3EDE2,
    val customForeground: Long = 0xFF1B1714,
    val volumeKeys: Boolean = false,
    val keepScreenOn: Boolean = true,
    val comicDirection: String = "VERTICAL",
    val dualPage: Boolean = true,
    val fitMode: String = "WIDTH",
    val orientation: String = "FOLLOW",
)

data class WebDavEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
)

sealed class WebDavStatus {
    data object Ok : WebDavStatus()
    data class Failed(val message: String) : WebDavStatus()
}
