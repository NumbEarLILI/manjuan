package com.numbear.manjuan.reader.comic

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.numbear.manjuan.reader.common.SegmentLoading
import com.numbear.manjuan.reader.common.detectReaderTap
import com.numbear.manjuan.progress.BookmarkSheet
import com.numbear.manjuan.progress.PercentSlider
import com.numbear.manjuan.reader.common.ReaderSettingsSheet
import com.numbear.manjuan.reader.common.ReaderTopBar
import com.numbear.manjuan.ui.inkColors
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
    var catchingUp by remember { mutableStateOf(false) }
    var catchPage by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    // Kept for the whole visit so a later segment does not throw away a page that is already on screen.
    val pageBitmaps = remember(bookId) { mutableStateMapOf<String, Bitmap>() }
    val pageRatios = remember(bookId) { mutableStateMapOf<String, Float>() }
    var picturesReady by remember(bookId) { mutableStateOf(false) }
    val colors = settings.inkColors()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    LaunchedEffect(bookId) {
        loading = true
        download = null
        try {
            content = app.library.openPaged(bookId) { read, total -> download = CacheProgress(read, total) }
            val saved = LocatorCodec.pageIndex(app.library.progress(bookId)?.locator.orEmpty())
            val opened = content
            val loaded = opened?.pages?.size ?: 0
            if (opened != null && opened.remotePageCount > loaded && saved >= loaded) {
                catchPage = saved
                page = 0
                catchingUp = true
            } else {
                page = saved
                catchingUp = false
            }
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

    LaunchedEffect(bookId, catchingUp) {
        if (!catchingUp) return@LaunchedEffect
        val target = catchPage
        try {
            var latest = content ?: return@LaunchedEffect
            while (latest.remotePageCount > latest.pages.size && target >= latest.pages.size) {
                latest = app.library.extendPaged(bookId, (latest.pages.size - 1).coerceAtLeast(0))
                content = latest
            }
            page = target.coerceAtMost(((content?.pages?.size ?: 1) - 1).coerceAtLeast(0))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        } finally {
            catchingUp = false
        }
    }

    // Stay on the opening spinner until the saved page (and enough of the next ones to fill
    // the screen) has a real height. Showing full-screen placeholders first makes the list
    // jump each time a picture arrives, and again when the next segment replaces the book.
    LaunchedEffect(bookId) {
        val widthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.coerceAtLeast(1f)
        var toppedUp = false
        snapshotFlow {
            OpeningFrame(
                content = content,
                page = page,
                catchingUp = catchingUp,
                loading = loading,
                vertical = settings.comicDirection == "VERTICAL",
                dual = settings.dualPage && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            )
        }.collect { frame ->
            if (picturesReady || frame.loading || frame.catchingUp) return@collect
            var latest = frame.content ?: return@collect
            var total = pagedCount(latest)
            // The saved page can sit on the last pages of this segment. Pull the next
            // segment in before the list is shown, so it does not resize once it appears.
            if (!toppedUp && latest.remotePageCount > total && total > 0 && frame.page >= total - 2) {
                toppedUp = true
                try {
                    latest = app.library.extendPaged(bookId, frame.page)
                    content = latest
                    total = pagedCount(latest)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
            }
            if (total <= 0) {
                picturesReady = true
                return@collect
            }
            try {
                warmOpening(app, context, bookId, latest, frame.page, frame.vertical, frame.dual, widthPx, heightPx, pageBitmaps, pageRatios)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
            picturesReady = true
        }
    }

    val book = content
    val count = when {
        book == null -> 0
        book.pdfPageCount > 0 -> book.pdfPageCount
        else -> book.pages.size
    }

    fun persist(index: Int) {
        if (catchingUp) return
        page = index
        scope.launch {
            val remote = content?.remotePageCount ?: 0
            val total = if (remote > count) remote else count
            val percent = if (total <= 1) 1f else index.toFloat() / (total - 1).coerceAtLeast(1)
            app.library.saveProgress(bookId, LocatorCodec.page(index), percent)
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when {
            loading || catchingUp || (content != null && !picturesReady) ->
                ReaderLoading(download, onBack, colors.foreground, Modifier.align(Alignment.Center).fillMaxWidth())
            error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(error!!, color = colors.foreground)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
            book != null && count == 0 -> Text("没有可显示的页面", Modifier.align(Alignment.Center), color = colors.foreground)
            book != null -> {
                val safePage = page.coerceIn(0, count - 1)
                val pageTotal = if (book.remotePageCount > count) book.remotePageCount else count
                // Keyed only by the book so a page turn does not cancel the fetch already running.
                LaunchedEffect(bookId) {
                    snapshotFlow {
                        val latest = content
                        val loaded = latest?.pages?.size ?: 0
                        val remote = latest?.remotePageCount ?: 0
                        remote > loaded && page >= loaded - 2
                    }.collect { need ->
                        if (!need) return@collect
                        val index = page
                        loadingMore = true
                        try {
                            content = app.library.extendPaged(bookId, index)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                        } finally {
                            loadingMore = false
                        }
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    if (chrome) {
                        ReaderTopBar(
                            title = "${book.title}  ${safePage + 1}/$pageTotal",
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
                            container = colors.background,
                            content = colors.foreground,
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(if (chrome) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()),
                    ) {
                        if (settings.comicDirection == "VERTICAL") {
                            val listState = rememberLazyListState(initialFirstVisibleItemIndex = safePage)
                            LaunchedEffect(listState) {
                                snapshotFlow {
                                    listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.isNotEmpty()
                                }.collect { (index, visible) ->
                                    if (visible) persist(index)
                                }
                            }
                            BindReadingChrome(
                                settings,
                                onPrev = { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0)) } },
                                onNext = { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex + 1).coerceAtMost(count - 1)) } },
                            )
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().pointerInput(chrome) {
                                    detectReaderTap { chrome = !chrome }
                                },
                            ) {
                                items(count = count, key = { pageKey(book, it) }) { index ->
                                    // A zero-height placeholder makes the list compose every page and
                                    // download them. An unloaded page stays one screen tall. A page whose
                                    // picture is already decoded uses that picture's ratio, so the list
                                    // does not resize when the bitmap is attached.
                                    val ratio = pageRatios[pageKey(book, index)]
                                    PageBitmap(
                                        app,
                                        bookId,
                                        book,
                                        index,
                                        pageBitmaps,
                                        pageRatios,
                                        modifier = if (ratio == null) {
                                            Modifier.fillParentMaxWidth().fillParentMaxHeight()
                                        } else {
                                            Modifier.fillParentMaxWidth().aspectRatio(ratio)
                                        },
                                    ) { bitmap ->
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
                                        PageBitmap(app, bookId, book, slot, pageBitmaps, pageRatios) { bitmap ->
                                            ZoomImage(bitmap, settings.fitMode, vertical = false)
                                        }
                                    } else {
                                        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                                            val left = slot * 2
                                            PageBitmap(app, bookId, book, left, pageBitmaps, pageRatios, Modifier.weight(1f).fillMaxHeight()) { bitmap ->
                                                ZoomImage(bitmap, "PAGE", vertical = false)
                                            }
                                            if (left + 1 < count) {
                                                PageBitmap(app, bookId, book, left + 1, pageBitmaps, pageRatios, Modifier.weight(1f).fillMaxHeight()) { bitmap ->
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
                        if (loadingMore || catchingUp) {
                            SegmentLoading(
                                message = if (catchingUp) "正在加载到上次阅读的位置…" else "正在加载后续内容…",
                                color = colors.foreground,
                                container = colors.background,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    if (chrome) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(colors.background.copy(alpha = 0.94f))
                                .navigationBarsPadding()
                                .padding(12.dp),
                        ) {
                            PercentSlider(
                                percent = if (pageTotal <= 1) 0f else safePage.toFloat() / (pageTotal - 1),
                                labelColor = colors.foreground,
                            ) { value ->
                                val target = (value * (pageTotal - 1)).toInt().coerceIn(0, pageTotal - 1)
                                if (target >= count && book.remotePageCount > count) {
                                    scope.launch {
                                        loadingMore = true
                                        try {
                                            runCatching { content = app.library.extendPaged(bookId, target) }
                                            persist(target.coerceAtMost((content?.pages?.size ?: count) - 1))
                                        } finally {
                                            loadingMore = false
                                        }
                                    }
                                } else {
                                    persist(target.coerceAtMost(count - 1))
                                }
                            }
                            TextButton(onClick = { showSettings = true }) { Text("阅读", color = colors.foreground) }
                        }
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
    app: ManjuanApp,
    bookId: Long,
    content: PagedContent,
    index: Int,
    decoded: MutableMap<String, Bitmap>,
    ratios: MutableMap<String, Float>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    image: @Composable (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val key = pageKey(content, index)
    // Keyed by the file, not the book object. Extending the download replaces the book
    // but the pages already on screen keep their bitmap and their height.
    var bitmap by remember(key) { mutableStateOf(decoded[key]) }
    var failed by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        val existing = bitmap
        if (existing != null) {
            ratios[key] = bitmapRatio(existing)
        } else {
            try {
                val loaded = decodePage(app, context, bookId, content, index)
                ratios[key] = bitmapRatio(loaded)
                bitmap = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = error.message
            }
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

private data class OpeningFrame(
    val content: PagedContent?,
    val page: Int,
    val catchingUp: Boolean,
    val loading: Boolean,
    val vertical: Boolean,
    val dual: Boolean,
)

private fun pagedCount(content: PagedContent): Int =
    if (content.pdfPageCount > 0) content.pdfPageCount else content.pages.size

private fun pageKey(content: PagedContent, index: Int): String {
    val pdf = content.pdfFile
    if (pdf != null) return "pdf:${pdf.absolutePath}:$index"
    return when (val page = content.pages.getOrNull(index)) {
        is PageRef.FilePage -> page.path
        is PageRef.UriPage -> page.uri
        null -> "missing:$index"
    }
}

private fun bitmapRatio(bitmap: Bitmap): Float =
    bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()

private suspend fun decodePage(
    app: ManjuanApp,
    context: android.content.Context,
    bookId: Long,
    content: PagedContent,
    index: Int,
): Bitmap = withContext(Dispatchers.IO) {
    val pdf = content.pdfFile
    if (pdf != null) {
        app.library.ensureRemotePage(bookId, index)
        BitmapIO.renderPdf(pdf, index, 1600)
    } else {
        when (val page = content.pages[index]) {
            is PageRef.FilePage -> BitmapIO.decodeFile(File(page.path), 1600)
            is PageRef.UriPage -> BitmapIO.decodeUri(context, Uri.parse(page.uri), 1600)
        }
    }
}

/**
 * Decode the page being opened, then enough following pages that the first screen is
 * already the right height. One extra page keeps the next placeholder below the fold.
 */
private suspend fun warmOpening(
    app: ManjuanApp,
    context: android.content.Context,
    bookId: Long,
    content: PagedContent,
    page: Int,
    vertical: Boolean,
    dual: Boolean,
    widthPx: Float,
    heightPx: Float,
    decoded: MutableMap<String, Bitmap>,
    ratios: MutableMap<String, Float>,
) {
    val total = pagedCount(content)
    if (total <= 0) return
    val first = page.coerceIn(0, total - 1)
    suspend fun load(index: Int) {
        val key = pageKey(content, index)
        val existing = decoded[key]
        if (existing != null) {
            ratios[key] = bitmapRatio(existing)
            return
        }
        val bitmap = decodePage(app, context, bookId, content, index)
        decoded[key] = bitmap
        ratios[key] = bitmapRatio(bitmap)
    }
    if (!vertical) {
        load(first)
        if (dual && first + 1 < total) load(first + 1)
        return
    }
    var filled = 0f
    var index = first
    var extra = false
    var guard = 0
    while (index < total && guard < 6) {
        load(index)
        val ratio = ratios[pageKey(content, index)] ?: 1f
        filled += widthPx / ratio.coerceAtLeast(0.05f)
        index++
        guard++
        if (filled >= heightPx) {
            if (extra) break
            extra = true
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
            // One finger turns the page. Pinch, or a drag after zooming, moves the picture.
            awaitEachGesture {
                while (true) {
                    val event = awaitPointerEvent()
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    val pointers = event.changes.count { it.pressed }
                    val zooming = pointers > 1 || scale > 1.01f
                    if (zooming) {
                        val next = (scale * zoomChange).coerceIn(1f, 6f)
                        if (next <= 1f) {
                            scale = 1f
                            panX = 0f
                            panY = 0f
                        } else {
                            scale = next
                            panX += panChange.x
                            panY += panChange.y
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (event.changes.none { it.pressed }) break
                }
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
        vertical || fit == "WIDTH" -> {
            val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
            Image(
                image,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio).then(transform),
                contentScale = ContentScale.FillWidth,
            )
        }
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
