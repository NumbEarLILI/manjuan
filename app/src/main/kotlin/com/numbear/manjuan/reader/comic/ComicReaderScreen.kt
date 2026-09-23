package com.numbear.manjuan.reader.comic

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.cache.CacheProgress
import com.numbear.manjuan.core.LocatorCodec
import com.numbear.manjuan.core.ReaderSettings
import com.numbear.manjuan.data.db.BookmarkEntity
import com.numbear.manjuan.data.open.BitmapIO
import com.numbear.manjuan.data.repo.PageRef
import com.numbear.manjuan.data.repo.PagedContent
import com.numbear.manjuan.reader.common.BindReadingChrome
import com.numbear.manjuan.reader.common.ReaderLoading
import com.numbear.manjuan.progress.BookmarkSheet
import com.numbear.manjuan.progress.PercentSlider
import com.numbear.manjuan.reader.common.ReaderSettingsSheet
import com.numbear.manjuan.reader.common.ReaderTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ComicReaderScreen(bookId: Long, onBack: () -> Unit) {
    PagedReaderScreen(bookId, onBack)
}

@Composable
fun PagedReaderScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ManjuanApp
    val settings by app.settings.settings.collectAsState(initial = ReaderSettings())
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf<PagedContent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var download by remember { mutableStateOf<CacheProgress?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var bookmarks by remember { mutableStateOf<List<BookmarkEntity>>(emptyList()) }
    var chrome by remember { mutableStateOf(true) }
    var showMarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(bookId) {
        loading = true
        download = null
        try {
            content = app.library.openPaged(bookId) { read, total -> download = CacheProgress(read, total) }
            page = LocatorCodec.pageIndex(app.library.progress(bookId)?.locator.orEmpty())
            bookmarks = app.library.bookmarks(bookId)
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法打开"
        } finally {
            loading = false
        }
    }

    val book = content
    val count = when {
        book == null -> 0
        book.pdfPageCount > 0 -> book.pdfPageCount
        else -> book.pages.size
    }

    fun persist(index: Int) {
        page = index
        scope.launch {
            val percent = if (count <= 1) 1f else index.toFloat() / (count - 1).coerceAtLeast(1)
            app.library.saveProgress(bookId, LocatorCodec.page(index), percent)
        }
    }

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        when {
            loading -> ReaderLoading(download, onBack, scheme.onBackground, Modifier.align(Alignment.Center).fillMaxWidth())
            error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(error!!, color = scheme.onBackground)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
            book != null && count == 0 -> Text("没有可显示的页面", Modifier.align(Alignment.Center), color = scheme.onBackground)
            book != null -> {
                val safePage = page.coerceIn(0, count - 1)
                if (settings.comicDirection == "VERTICAL") {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safePage)
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex }.collect { persist(it) }
                    }
                    BindReadingChrome(
                        settings,
                        onPrev = { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0)) } },
                        onNext = { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex + 1).coerceAtMost(count - 1)) } },
                    )
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(count) { index ->
                            PageBitmap(book, index) { bitmap ->
                                ZoomImage(bitmap, settings.fitMode, vertical = true)
                            }
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val dual = settings.dualPage && maxWidth > maxHeight
                        val slots = if (dual) (count + 1) / 2 else count
                        val initial = if (dual) safePage / 2 else safePage
                        val pager = rememberPagerState(initialPage = initial.coerceIn(0, (slots - 1).coerceAtLeast(0)), pageCount = { slots })
                        LaunchedEffect(pager.currentPage) {
                            persist(if (dual) pager.currentPage * 2 else pager.currentPage)
                        }
                        BindReadingChrome(
                            settings,
                            onPrev = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                            onNext = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(slots - 1)) } },
                        )
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = settings.comicDirection == "RTL",
                        ) { slot ->
                            if (!dual) {
                                PageBitmap(book, slot) { bitmap -> ZoomImage(bitmap, settings.fitMode, vertical = false) }
                            } else {
                                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                                    val left = slot * 2
                                    PageBitmap(book, left, Modifier.weight(1f).fillMaxHeight()) { bitmap ->
                                        ZoomImage(bitmap, "PAGE", vertical = false)
                                    }
                                    if (left + 1 < count) {
                                        PageBitmap(book, left + 1, Modifier.weight(1f).fillMaxHeight()) { bitmap ->
                                            ZoomImage(bitmap, "PAGE", vertical = false)
                                        }
                                    }
                                }
                            }
                        }
                        Box(
                            Modifier.fillMaxSize().pointerInput(pager, slots) {
                                detectTapGestures { tap ->
                                    val zone = tap.x / size.width
                                    val forward = zone > 0.72f
                                    val back = zone < 0.28f
                                    val rtl = settings.comicDirection == "RTL"
                                    val goNext = if (rtl) back else forward
                                    val goPrev = if (rtl) forward else back
                                    when {
                                        goPrev -> scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
                                        goNext -> scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(slots - 1)) }
                                        else -> chrome = !chrome
                                    }
                                }
                            },
                        )
                    }
                }
                if (chrome) {
                    ReaderTopBar(
                        title = "${book.title}  ${safePage + 1}/$count",
                        bookmarked = bookmarks.any { LocatorCodec.pageIndex(it.locator) == safePage },
                        onBack = onBack,
                        onBookmark = {
                            scope.launch {
                                val locator = LocatorCodec.page(safePage)
                                val existing = bookmarks.find { it.locator == locator }
                                if (existing != null) app.library.deleteBookmark(existing.id)
                                else app.library.addBookmark(bookId, locator, "第 ${safePage + 1} 页")
                                bookmarks = app.library.bookmarks(bookId)
                            }
                        },
                        onToc = { showMarks = true },
                        container = scheme.surface,
                        content = scheme.onSurface,
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).background(scheme.surface.copy(alpha = 0.94f)).padding(12.dp),
                    ) {
                        PercentSlider(
                            percent = if (count <= 1) 0f else safePage.toFloat() / (count - 1),
                            labelColor = scheme.onSurface,
                        ) { value -> persist((value * (count - 1)).toInt().coerceIn(0, count - 1)) }
                        TextButton(onClick = { showSettings = true }) { Text("阅读", color = scheme.onSurface) }
                    }
                }
            }
        }
    }

    if (showMarks) {
        BookmarkSheet(
            bookmarks = bookmarks,
            onDismiss = { showMarks = false },
            onOpen = {
                persist(LocatorCodec.pageIndex(it.locator))
                showMarks = false
            },
            onDelete = { mark ->
                scope.launch {
                    app.library.deleteBookmark(mark.id)
                    bookmarks = app.library.bookmarks(bookId)
                }
            },
        )
    }
    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            novel = false,
            onChange = { next -> scope.launch { app.settings.update { next } } },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun PageBitmap(
    content: PagedContent,
    index: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
    image: @Composable (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(content, index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(content, index) { mutableStateOf<String?>(null) }
    LaunchedEffect(content, index) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                val pdf = content.pdfFile
                if (pdf != null) {
                    BitmapIO.renderPdf(pdf, index, 1600)
                } else {
                    when (val page = content.pages[index]) {
                        is PageRef.FilePage -> BitmapIO.decodeFile(File(page.path), 1600)
                        is PageRef.UriPage -> BitmapIO.decodeUri(context, Uri.parse(page.uri), 1600)
                    }
                }
            }
        } catch (error: Exception) {
            failed = error.message
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> image(bitmap!!)
            failed != null -> Text(failed!!, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(16.dp))
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun ZoomImage(bitmap: Bitmap, fit: String, vertical: Boolean) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var panX by remember(bitmap) { mutableFloatStateOf(0f) }
    var panY by remember(bitmap) { mutableFloatStateOf(0f) }
    val transform = Modifier
        .pointerInput(bitmap) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                panX += pan.x
                panY += pan.y
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = panX
            translationY = panY
        }
    val image = bitmap.asImageBitmap()
    when {
        fit == "ORIGINAL" -> Image(
            image,
            contentDescription = null,
            modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).then(transform),
            contentScale = ContentScale.None,
        )
        vertical || fit == "WIDTH" -> Image(
            image,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().then(transform),
            contentScale = ContentScale.FillWidth,
        )
        fit == "HEIGHT" -> Image(
            image,
            contentDescription = null,
            modifier = Modifier.fillMaxHeight().then(transform),
            contentScale = ContentScale.FillHeight,
        )
        else -> Image(
            image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().then(transform),
            contentScale = ContentScale.Fit,
        )
    }
}
