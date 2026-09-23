package com.numbear.manjuan.reader.text

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.numbear.manjuan.core.NovelSpan
import com.numbear.manjuan.core.ReaderSettings
import com.numbear.manjuan.data.db.BookmarkEntity
import com.numbear.manjuan.reader.common.BindReadingChrome
import com.numbear.manjuan.reader.common.ReaderLoading
import com.numbear.manjuan.progress.BookmarkSheet
import com.numbear.manjuan.progress.PercentSlider
import com.numbear.manjuan.reader.common.ReaderSettingsSheet
import com.numbear.manjuan.reader.common.ReaderTopBar
import com.numbear.manjuan.ui.inkColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    var bookmarks by remember { mutableStateOf<List<BookmarkEntity>>(emptyList()) }
    var chrome by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var pageCommand by remember { mutableIntStateOf(0) }

    LaunchedEffect(bookId) {
        loading = true
        download = null
        try {
            content = app.library.openNovel(bookId) { read, total -> download = CacheProgress(read, total) }
            val saved = app.library.progress(bookId)
            chapter = LocatorCodec.chapter(saved?.locator.orEmpty())
            offset = LocatorCodec.offset(saved?.locator.orEmpty())
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
    fun percent(): Float = globalOffset().toFloat() / totalChars()

    fun persist() {
        scope.launch {
            app.library.saveProgress(bookId, LocatorCodec.novel(chapter, offset), percent())
        }
    }

    fun seek(targetPercent: Float) {
        val chapters = novel?.chapters ?: return
        val target = (targetPercent.coerceIn(0f, 1f) * totalChars()).toInt()
        var consumed = 0
        chapters.forEachIndexed { index, item ->
            val next = consumed + item.text.length
            if (target < next || index == chapters.lastIndex) {
                chapter = index
                offset = (target - consumed).coerceAtLeast(0)
                persist()
                return
            }
            consumed = next
        }
    }

    BindReadingChrome(
        settings = settings,
        onPrev = {
            if (settings.pageMode) {
                pageCommand -= 1
            } else if (chapter > 0) {
                chapter -= 1
                offset = 0
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
                persist()
            }
        },
    )

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when {
            loading -> ReaderLoading(download, onBack, colors.foreground, Modifier.align(Alignment.Center).fillMaxWidth())
            error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(error!!, color = colors.foreground)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
            novel != null -> {
                val safeChapter = chapter.coerceIn(0, novel.chapters.lastIndex)
                val current = novel.chapters[safeChapter]
                if (settings.pageMode) {
                    PageTurn(
                        chapter = current,
                        offset = offset.coerceIn(0, current.text.length),
                        settings = settings,
                        pageCommand = pageCommand,
                        onOffset = {
                            offset = it
                            persist()
                        },
                        onChapterDelta = { delta ->
                            val next = (safeChapter + delta).coerceIn(0, novel.chapters.lastIndex)
                            if (next != safeChapter) {
                                chapter = next
                                offset = if (delta < 0) novel.chapters[next].text.length else 0
                                persist()
                            }
                        },
                        onToggleChrome = { chrome = !chrome },
                    )
                } else {
                    ScrollChapter(
                        chapter = current,
                        offset = offset,
                        settings = settings,
                        onOffset = {
                            offset = it
                            persist()
                        },
                        onToggleChrome = { chrome = !chrome },
                    )
                }
                if (chrome) {
                    Column(Modifier.align(Alignment.TopCenter)) {
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
                    Column(Modifier.align(Alignment.BottomCenter).background(colors.background.copy(alpha = 0.94f)).padding(12.dp)) {
                        PercentSlider(percent(), colors.foreground) { seek(it) }
                        TextButton(onClick = { showBookmarks = true }) { Text("书签") }
                        TextButton(onClick = { showSettings = true }) { Text("版式") }
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
        val charsPerLine = (widthPx / fontPx).toInt().coerceAtLeast(6)
        val lines = (heightPx / (fontPx * settings.lineSpacing)).toInt().coerceAtLeast(3)
        val pages = remember(chapter, charsPerLine, lines) { NovelPages.pages(chapter, charsPerLine, lines) }
        if (pages.isEmpty()) {
            Text("这一章是空的", modifier = Modifier.padding(margin), color = colors.foreground)
            return@BoxWithConstraints
        }
        val initial = pages.indexOfLast { it.start <= offset }.let { if (it < 0) 0 else it }
        key(chapter, charsPerLine, lines) {
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

@Composable
private fun ScrollChapter(
    chapter: NovelChapter,
    offset: Int,
    settings: ReaderSettings,
    onOffset: (Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val colors = settings.inkColors()
    val scroll = rememberScrollState()
    val spans = chapter.spans.ifEmpty { listOf(NovelSpan.Prose(chapter.text)) }
    LaunchedEffect(chapter) {
        val fraction = if (chapter.text.isEmpty()) 0f else offset.toFloat() / chapter.text.length
        scroll.scrollTo((scroll.maxValue * fraction).toInt())
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .pointerInput(Unit) { detectTapGestures { onToggleChrome() } }
            .padding(settings.marginDp.dp),
    ) {
        spans.forEach { span ->
            when (span) {
                is NovelSpan.Prose -> Text(
                    span.text,
                    style = TextStyle(
                        color = colors.foreground,
                        fontSize = settings.fontSizeSp.sp,
                        lineHeight = (settings.fontSizeSp * settings.lineSpacing).sp,
                        fontFamily = FontFamily.Serif,
                    ),
                )
                is NovelSpan.Plate -> PlateImage(span.bytes, colors.foreground, Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
        }
    }
    LaunchedEffect(scroll.value, scroll.maxValue, chapter) {
        if (scroll.maxValue > 0 && chapter.text.isNotEmpty()) {
            onOffset((scroll.value.toFloat() / scroll.maxValue * chapter.text.length).toInt())
        }
    }
    LinearProgressIndicator(
        progress = { if (scroll.maxValue == 0) 0f else scroll.value.toFloat() / scroll.maxValue },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlateImage(bytes: ByteArray, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    if (bitmap == null) {
        Text("彩页无法显示", color = color, modifier = modifier)
    } else {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "彩页",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}
