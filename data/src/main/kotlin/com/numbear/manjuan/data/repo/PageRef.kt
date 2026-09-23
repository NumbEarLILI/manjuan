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
)

data class ImportReport(val added: Int, val errors: List<String>)
