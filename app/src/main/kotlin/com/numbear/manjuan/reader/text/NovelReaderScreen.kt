package com.numbear.manjuan.reader.text

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.cache.CacheProgress
import com.numbear.manjuan.core.LocatorCodec
import com.numbear.manjuan.core.NovelChapter
import com.numbear.manjuan.core.NovelContent
import com.numbear.manjuan.core.NovelPages
import com.numbear.manjuan.core.NovelScroll
import com.numbear.manjuan.core.ReaderSettings
import com.numbear.manjuan.core.RemoteText
import com.numbear.manjuan.data.db.BookmarkEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun NovelReaderScreen(bookId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val settings by app.settings.settings.collectAsState(initial = ReaderSettings())
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf<NovelContent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var download by remember { mutableStateOf<CacheProgress?>(null) }
    var chapter by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    var anchor by remember { mutableIntStateOf(0) }
    var bookmarks by remember { mutableStateOf<List<BookmarkEntity>>(emptyList()) }
    var chrome by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var pageCommand by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    var catchingUp by remember { mutableStateOf(false) }
    var catchChapter by remember { mutableIntStateOf(0) }
    var catchOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(bookId) {
        loading = true
        download = null
        try {
            content = app.library.openNovel(bookId) { read, total -> download = CacheProgress(read, total) }
            val saved = app.library.progress(bookId)
            val savedChapter = LocatorCodec.chapter(saved?.locator.orEmpty())
            val savedOffset = LocatorCodec.offset(saved?.locator.orEmpty())
            bookmarks = app.library.bookmarks(bookId)
            val opened = content
            val reached = opened == null || RemoteText.covered(opened.chapters, savedChapter, savedOffset, !opened.more)
            if (reached) {
                chapter = savedChapter
                offset = savedOffset
                catchingUp = false
            } else {
                chapter = 0
                offset = 0
                catchChapter = savedChapter
                catchOffset = savedOffset
                catchingUp = true
            }
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
        val targetChapter = catchChapter
        val targetOffset = catchOffset
        try {
            var latest = content ?: return@LaunchedEffect
            while (latest.more && !RemoteText.covered(latest.chapters, targetChapter, targetOffset, complete = false)) {
                loadingMore = true
                latest = app.library.extendNovel(bookId)
                content = latest
            }
            if (targetChapter <= latest.chapters.lastIndex) {
                chapter = targetChapter
                offset = targetOffset.coerceIn(0, latest.chapters[targetChapter].text.length)
                anchor += 1
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        } finally {
            loadingMore = false
            catchingUp = false
        }
    }

    val novel = content
    val colors = settings.inkColors()
    fun totalChars(): Int = novel?.chapters?.sumOf { it.text.length }?.coerceAtLeast(1) ?: 1
    fun globalOffset(): Int {
        val chapters = novel?.chapters ?: return 0
        var total = 0
        chapters.forEachIndexed { index, item ->
            if (index == chapter) return total + offset.coerceIn(0, item.text.length)
            total += item.text.length
        }
        return total
    }
    fun percent(): Float {
        val current = novel
        if (current != null && current.more && current.totalBytes > 0) {
            val loadedChars = totalChars().coerceAtLeast(1)
            val readBytes = current.loadedBytes * globalOffset().coerceAtMost(loadedChars) / loadedChars
            return (readBytes.toFloat() / current.totalBytes).coerceIn(0f, 0.99f)
        }
        return globalOffset().toFloat() / totalChars()
    }

    fun requestMore() {
        val current = content ?: return
        if (!current.more || loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                content = app.library.extendNovel(bookId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                loadingMore = false
            }
        }
    }

    fun persist() {
        if (catchingUp) return
        scope.launch {
            app.library.saveProgress(bookId, LocatorCodec.novel(chapter, offset), percent())
        }
    }

    fun place(targetPercent: Float, novelNow: com.numbear.manjuan.core.NovelContent) {
        val chaptersNow = novelNow.chapters
        if (chaptersNow.isEmpty()) return
        val total = chaptersNow.sumOf { it.text.length }.coerceAtLeast(1)
        val target = (targetPercent.coerceIn(0f, 1f) * total).toInt()
        var consumed = 0
        chaptersNow.forEachIndexed { index, item ->
            val next = consumed + item.text.length
            if (target < next || index == chaptersNow.lastIndex) {
                chapter = index
                offset = (target - consumed).coerceIn(0, item.text.length)
                anchor += 1
                persist()
                return
            }
            consumed = next
        }
    }

    fun seek(targetPercent: Float) {
        catchingUp = false
        val current = novel ?: return
        if (current.more && current.totalBytes > 0) {
            val targetBytes = (targetPercent.coerceIn(0f, 1f) * current.totalBytes).toLong()
            if (targetBytes > current.loadedBytes) {
                scope.launch {
                    loadingMore = true
                    try {
                        var latest = current
                        while (latest.more && latest.loadedBytes < targetBytes) {
                            latest = app.library.extendNovel(bookId)
                            content = latest
                        }
                        place(targetBytes.toFloat() / latest.loadedBytes.coerceAtLeast(1), latest)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } finally {
                        loadingMore = false
                    }
                }
                return
            }
            val fraction = targetBytes.toFloat() / current.loadedBytes.coerceAtLeast(1)
            place(fraction, current)
            return
        }
        place(targetPercent, current)
    }

    BindReadingChrome(
        settings = settings,
        onPrev = {
            if (settings.pageMode) {
                pageCommand -= 1
            } else if (chapter > 0) {
                chapter -= 1
                offset = 0
                anchor += 1
                persist()
            }
        },
        onNext = {
            val chapters = novel?.chapters ?: return@BindReadingChrome
            if (settings.pageMode) {
                pageCommand += 1
            } else if (chapter < chapters.lastIndex) {
                chapter += 1
                offset = 0
                anchor += 1
                persist()
            } else if (novel?.more == true) {
                requestMore()
            }
        },
    )

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when {
            // Catching up used to paint chapter 1, then jump once each new segment arrived.
            loading || catchingUp -> ReaderLoading(download, onBack, colors.foreground, Modifier.align(Alignment.Center).fillMaxWidth())
            error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(error!!, color = colors.foreground)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
            novel != null -> {
                val safeChapter = chapter.coerceIn(0, novel.chapters.lastIndex)
                val current = novel.chapters[safeChapter]
                Column(Modifier.fillMaxSize()) {
                if (chrome) {
                    ReaderTopBar(
                        title = novel.chapters[safeChapter].title,
                        bookmarked = bookmarks.any { LocatorCodec.chapter(it.locator) == safeChapter },
                        onBack = onBack,
                        onBookmark = {
                            scope.launch {
                                val locator = LocatorCodec.novel(safeChapter, offset)
                                val existing = bookmarks.find { it.locator == locator }
                                if (existing != null) {
                                    app.library.deleteBookmark(existing.id)
                                } else {
                                    app.library.addBookmark(bookId, locator, "${novel.chapters[safeChapter].title} ${(percent() * 100).toInt()}%")
                                }
                                bookmarks = app.library.bookmarks(bookId)
                            }
                        },
                        onToc = { showToc = true },
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
                if (settings.pageMode) {
                    PageTurn(
                        chapter = current,
                        chapterIndex = safeChapter,
                        offset = offset.coerceIn(0, current.text.length),
                        settings = settings,
                        pageCommand = pageCommand,
                        onOffset = {
                            if (catchingUp) return@PageTurn
                            offset = it
                            persist()
                            val current = content
                            if (current != null && !RemoteText.covered(current.chapters, chapter, it, !current.more)) {
                                requestMore()
                            }
                        },
                        onChapterDelta = { delta ->
                            val next = (safeChapter + delta).coerceIn(0, novel.chapters.lastIndex)
                            if (next != safeChapter) {
                                chapter = next
                                offset = if (delta < 0) novel.chapters[next].text.length else 0
                                persist()
                            } else if (delta > 0 && novel.more) {
                                requestMore()
                            }
                        },
                        onToggleChrome = { chrome = !chrome },
                    )
                } else {
                    ScrollChapter(
                        chapters = novel.chapters,
                        chapterIndex = safeChapter,
                        offset = offset.coerceIn(0, current.text.length),
                        anchor = anchor,
                        settings = settings,
                        showProgress = chrome,
                        onPlace = { nextChapter, nextOffset ->
                            if (catchingUp) return@ScrollChapter
                            val clamped = nextChapter.coerceIn(0, novel.chapters.lastIndex)
                            val textLength = novel.chapters[clamped].text.length
                            val clampedOffset = nextOffset.coerceIn(0, textLength)
                            if (clamped != chapter || clampedOffset != offset) {
                                chapter = clamped
                                offset = clampedOffset
                                persist()
                            }
                            val current = content
                            if (current != null && !RemoteText.covered(current.chapters, clamped, clampedOffset, !current.more)) {
                                requestMore()
                            }
                        },
                        onToggleChrome = { chrome = !chrome },
                    )
                }
                if (loadingMore || catchingUp) {
                    SegmentLoading(
                        message = if (catchingUp) "正在加载到上次阅读的位置…" else "正在加载后续内容…",
                        color = colors.foreground,
                        container = colors.background,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
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
                        PercentSlider(percent(), colors.foreground) { seek(it) }
                        TextButton(onClick = { showBookmarks = true }) { Text("书签") }
                        TextButton(onClick = { showSettings = true }) { Text("版式") }
                    }
                }
                }
            }
        }
    }

    if (showToc && novel != null) {
        BookmarkSheet(
            bookmarks = novel.chapters.mapIndexed { index, item ->
                BookmarkEntity(id = index.toLong(), bookId = bookId, locator = LocatorCodec.novel(index, 0), label = item.title, createdAt = 0)
            },
            onDismiss = { showToc = false },
            onOpen = {
                chapter = LocatorCodec.chapter(it.locator)
                offset = 0
                anchor += 1
                persist()
                showToc = false
            },
            onDelete = {},
        )
    }
    if (showBookmarks) {
        BookmarkSheet(
            bookmarks = bookmarks,
            onDismiss = { showBookmarks = false },
            onOpen = {
                chapter = LocatorCodec.chapter(it.locator)
                offset = LocatorCodec.offset(it.locator)
                anchor += 1
                showBookmarks = false
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
        ReaderSettingsSheet(settings, novel = true, onChange = { next -> scope.launch { app.settings.update { next } } }, onDismiss = { showSettings = false })
    }
}

@Composable
private fun PageTurn(
    chapter: NovelChapter,
    chapterIndex: Int,
    offset: Int,
    settings: ReaderSettings,
    pageCommand: Int,
    onOffset: (Int) -> Unit,
    onChapterDelta: (Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val colors = settings.inkColors()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val margin = settings.marginDp.dp
        val fontPx = with(density) { settings.fontSizeSp.sp.toPx() }
        val widthPx = with(density) { (maxWidth - margin * 2).toPx() }.coerceAtLeast(fontPx)
        val heightPx = with(density) { (maxHeight - margin * 2).toPx() }.coerceAtLeast(fontPx)
        // Glyphs are a little wider than the font size, and the last line needs
        // room for its descent. Overestimating either one clips a line off the page.
        val charsPerLine = (widthPx / (fontPx * 1.12f)).toInt().coerceAtLeast(6)
        val lines = ((heightPx / (fontPx * settings.lineSpacing)).toInt() - 1).coerceAtLeast(3)
        // The chapter index and the measured page size own the pager. Appending the unread
        // tail must not rebuild it, or the page that is already open blinks and jumps.
        key(chapterIndex, charsPerLine, lines) {
            val pages = remember(chapter.text, charsPerLine, lines) { NovelPages.pages(chapter, charsPerLine, lines) }
            if (pages.isEmpty()) {
                Text("这一章是空的", modifier = Modifier.padding(margin), color = colors.foreground)
            } else {
                val initial = pages.indexOfLast { it.start <= offset }.let { if (it < 0) 0 else it }
                val pager = rememberPagerState(initialPage = initial, pageCount = { pages.size })
                LaunchedEffect(pager.currentPage) {
                    onOffset(pages[pager.currentPage].start)
                }
                val scope = rememberCoroutineScope()
                var seenCommand by remember { mutableIntStateOf(pageCommand) }
                LaunchedEffect(pageCommand) {
                    val delta = pageCommand - seenCommand
                    seenCommand = pageCommand
                    if (delta == 0) return@LaunchedEffect
                    val target = (pager.currentPage + delta).coerceIn(0, pages.lastIndex)
                    if (target == pager.currentPage && delta < 0) onChapterDelta(-1)
                    else if (target == pager.currentPage && delta > 0) onChapterDelta(1)
                    else pager.animateScrollToPage(target)
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    when (val item = pages[page]) {
                        is NovelPages.Page.Words -> Text(
                            item.text,
                            modifier = Modifier.fillMaxSize().padding(margin),
                            style = TextStyle(
                                color = colors.foreground,
                                fontSize = settings.fontSizeSp.sp,
                                lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
                                fontFamily = FontFamily.Serif,
                            ),
                        )
                        is NovelPages.Page.Picture -> PlateImage(item.bytes, colors.foreground, Modifier.fillMaxSize().padding(margin))
                    }
                }
                Box(
                    Modifier.fillMaxSize().pointerInput(pager.currentPage, pages.size) {
                        detectTapGestures { tap ->
                            val zone = tap.x / size.width
                            when {
                                zone < 0.28f -> {
                                    if (pager.currentPage == 0) onChapterDelta(-1)
                                    else scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                                }
                                zone > 0.72f -> {
                                    if (pager.currentPage >= pages.lastIndex) onChapterDelta(1)
                                    else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                                }
                                else -> onToggleChrome()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ScrollChapter(
    chapters: List<NovelChapter>,
    chapterIndex: Int,
    offset: Int,
    anchor: Int,
    settings: ReaderSettings,
    showProgress: Boolean,
    onPlace: (chapter: Int, offset: Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val colors = settings.inkColors()
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(onToggleChrome) {
            detectReaderTap(onToggleChrome)
        },
    ) {
        val density = LocalDensity.current
        val margin = settings.marginDp.dp
        val fontPx = with(density) { settings.fontSizeSp.sp.toPx() }
        val widthPx = with(density) { (maxWidth - margin * 2).toPx() }.coerceAtLeast(fontPx)
        val charsPerLine = (widthPx / fontPx).toInt().coerceAtLeast(1)
        val lineHeightPx = fontPx * settings.lineSpacing
        val maxChars = remember(charsPerLine, lineHeightPx) {
            NovelScroll.maxChars(charsPerLine, lineHeightPx)
        }
        val entries = remember(chapters, maxChars) { NovelScroll.document(chapters, maxChars) }
        val openedAt = if (entries.isEmpty()) {
            0
        } else {
            NovelScroll.indexAt(entries, chapterIndex, offset).coerceIn(0, entries.lastIndex)
        }
        // Start on the saved block. Scrolling there after the first frame flashes the top of the book.
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = openedAt)
        var settling by remember { mutableStateOf(true) }
        LaunchedEffect(anchor) {
            settling = true
            try {
                if (entries.isNotEmpty()) {
                    val index = NovelScroll.indexAt(entries, chapterIndex, offset).coerceIn(0, entries.lastIndex)
                    if (listState.firstVisibleItemIndex != index || listState.firstVisibleItemScrollOffset != 0) {
                        listState.scrollToItem(index)
                    }
                    val body = entries[index] as? NovelScroll.Entry.Body
                    val words = body?.block as? NovelScroll.Block.Words
                    if (words != null && words.text.isNotEmpty() && offset > words.start) {
                        val size = withTimeoutOrNull(500) {
                            snapshotFlow {
                                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
                            }.first { it > 0 }
                        } ?: 0
                        if (size > 0) {
                            val fraction = (offset - words.start).coerceIn(0, words.text.length).toFloat() / words.text.length
                            listState.scrollToItem(index, (fraction * size).toInt().coerceAtLeast(0))
                        }
                    }
                }
            } finally {
                settling = false
            }
        }
        LaunchedEffect(listState, entries) {
            snapshotFlow {
                val index = listState.firstVisibleItemIndex
                val pixel = listState.firstVisibleItemScrollOffset
                val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
                Triple(index, pixel, size)
            }.collect { (index, pixel, size) ->
                if (settling || entries.isEmpty()) return@collect
                val entry = entries.getOrNull(index) ?: return@collect
                onPlace(entry.chapter, entryOffset(entry, pixel, size))
            }
        }
        if (entries.isEmpty()) {
            Text("这一章是空的", modifier = Modifier.padding(margin), color = colors.foreground)
        } else {
            val style = TextStyle(
                color = colors.foreground,
                fontSize = settings.fontSizeSp.sp,
                lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
                fontFamily = FontFamily.Serif,
            )
            val plateCap = with(density) { NovelScroll.MaxBlockPx.toDp() }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(margin),
            ) {
                items(count = entries.size, key = { it }) { index ->
                    val entry = entries[index]
                    val chapterBreak = index > 0 && entries[index - 1].chapter != entry.chapter
                    val gap = if (chapterBreak) Modifier.padding(top = 28.dp) else Modifier
                    when (entry) {
                        is NovelScroll.Entry.Heading -> Text(
                            entry.title,
                            style = style,
                            modifier = gap.fillMaxWidth().padding(bottom = 12.dp),
                        )
                        is NovelScroll.Entry.Body -> when (val block = entry.block) {
                            is NovelScroll.Block.Words -> Text(block.text, style = style, modifier = gap.fillMaxWidth())
                            is NovelScroll.Block.Picture -> PlateImage(
                                block.bytes,
                                colors.foreground,
                                gap.fillMaxWidth().heightIn(max = plateCap).padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
        if (showProgress) {
            val shown = scrollFraction(chapters, entries, listState)
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
        }
    }
}

private fun entryOffset(entry: NovelScroll.Entry, pixel: Int, size: Int): Int {
    val body = entry as? NovelScroll.Entry.Body ?: return 0
    val words = body.block as? NovelScroll.Block.Words ?: return body.block.start
    if (size <= 0 || words.text.isEmpty()) return words.start
    val within = (words.text.length * (pixel.toFloat() / size)).toInt().coerceIn(0, words.text.length)
    return words.start + within
}

private fun scrollFraction(chapters: List<NovelChapter>, entries: List<NovelScroll.Entry>, listState: LazyListState): Float {
    val total = chapters.sumOf { it.text.length }
    if (total <= 0 || entries.isEmpty()) return 0f
    val index = listState.firstVisibleItemIndex.coerceIn(0, entries.lastIndex)
    val entry = entries[index]
    val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
    val local = entryOffset(entry, listState.firstVisibleItemScrollOffset, size)
    val before = chapters.take(entry.chapter).sumOf { it.text.length }
    return ((before + local).toFloat() / total).coerceIn(0f, 1f)
}

@Composable
private fun PlateImage(bytes: ByteArray, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    if (bitmap == null) {
        Text("彩页无法显示", color = color, modifier = modifier)
    } else {
        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "彩页",
            modifier = modifier.aspectRatio(ratio),
            contentScale = ContentScale.Fit,
        )
    }
}
