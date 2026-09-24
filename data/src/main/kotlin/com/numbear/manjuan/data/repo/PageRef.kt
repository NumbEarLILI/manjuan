package com.numbear.manjuan.data.repo

import com.numbear.manjuan.core.BookFormat
import java.io.File

sealed class PageRef {
    data class FilePage(val path: String) : PageRef()
    data class UriPage(val uri: String) : PageRef()
}

data class PagedContent(
    val title: String,
    val format: BookFormat,
    val pages: List<PageRef>,
    val pdfFile: File?,
    val pdfPageCount: Int,
    /** Remote image count when more pages exist than [pages]. Zero means the list is complete. */
    val remotePageCount: Int = 0,
)

data class ImportReport(val added: Int, val errors: List<String>)

data class RemoteScanProgress(
    val found: Int,
    val imported: Int,
    val skipped: Int,
    val errorCount: Int,
    val current: String,
)

data class RemoteScanReport(
    val found: Int,
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)
