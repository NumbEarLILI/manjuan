package com.numbear.manjuan.reader.pdf

import androidx.compose.runtime.Composable
import com.numbear.manjuan.reader.comic.PagedReaderScreen

@Composable
fun PdfReaderScreen(bookId: Long, onBack: () -> Unit) {
    PagedReaderScreen(bookId, onBack)
}
