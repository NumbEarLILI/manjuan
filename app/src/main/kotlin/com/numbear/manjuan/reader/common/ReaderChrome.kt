package com.numbear.manjuan.reader.common

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.LocalRegisterVolumeKey
import com.numbear.manjuan.cache.CacheProgress
import com.numbear.manjuan.core.AppTheme
import com.numbear.manjuan.core.ReaderSettings

@Composable
fun BindReadingChrome(settings: ReaderSettings, onPrev: () -> Unit, onNext: () -> Unit) {
    val register = LocalRegisterVolumeKey.current
    val prev by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    val enabled by rememberUpdatedState(settings.volumeKeys)
    DisposableEffect(Unit) {
        register { code ->
            if (!enabled) return@register false
            when (code) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    prev()
                    true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    next()
                    true
                }
                else -> false
            }
        }
        onDispose { register { false } }
    }
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun ReaderLoading(download: CacheProgress?, onCancel: () -> Unit, color: Color, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(24.dp)) {
        CircularProgressIndicator(color = color)
        Text(
            when {
                download == null -> "正在打开…"
                download.total > 0 -> "正在下载…"
                else -> "正在加载一部分…"
            },
            color = color,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (download != null && download.total > 0) {
            LinearProgressIndicator(
                progress = { (download.read.toFloat() / download.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text("${download.read / 1024} / ${download.total / 1024} KB", color = color, modifier = Modifier.padding(top = 8.dp))
        } else if (download != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text("已接收 ${download.read / 1024} KB", color = color, modifier = Modifier.padding(top = 8.dp))
        }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

/** Spinner shown while the next slice of a book is fetched, without covering the page already open. */
@Composable
fun SegmentLoading(message: String, color: Color, container: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(container.copy(alpha = 0.94f), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = color, strokeWidth = 2.dp)
        Text(message, color = color)
    }
}

/**
 * Tap toggles chrome. A drag is left for the scroller. The listener stays on the reader
 * container: a row's pointer coroutine is cancelled when that row scrolls away.
 */
suspend fun PointerInputScope.detectReaderTap(onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: return@awaitEachGesture
            if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) {
                if (!moved) onTap()
                return@awaitEachGesture
            }
        }
    }
}

@Composable
fun ReaderTopBar(
    title: String,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onToc: (() -> Unit)?,
    container: Color,
    content: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container.copy(alpha = 0.94f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = content)
        }
        Text(title, modifier = Modifier.weight(1f), color = content, maxLines = 1)
        if (onToc != null) {
            TextButton(onClick = onToc) { Text("目录", color = content) }
        }
        IconButton(onClick = onBookmark) {
            Icon(
                if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = "书签",
                tint = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    novel: Boolean,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleMedium)
            if (novel) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = settings.pageMode, onClick = { onChange(settings.copy(pageMode = true)) }, label = { Text("翻页") })
                    FilterChip(selected = !settings.pageMode, onClick = { onChange(settings.copy(pageMode = false)) }, label = { Text("滚动") })
                }
                Text("字号 ${settings.fontSizeSp.toInt()}")
                Slider(settings.fontSizeSp, { onChange(settings.copy(fontSizeSp = it)) }, valueRange = 14f..32f)
                Text("行距 ${"%.1f".format(settings.lineSpacing)}")
                Slider(settings.lineSpacing, { onChange(settings.copy(lineSpacing = it)) }, valueRange = 1.1f..2.2f)
                Text("边距 ${settings.marginDp.toInt()}")
                Slider(settings.marginDp, { onChange(settings.copy(marginDp = it)) }, valueRange = 8f..48f)
            } else {
                Text("方向")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("VERTICAL" to "纵向", "RTL" to "从右向左", "LTR" to "从左向右").forEach { (value, label) ->
                        FilterChip(
                            selected = settings.comicDirection == value,
                            onClick = { onChange(settings.copy(comicDirection = value)) },
                            label = { Text(label) },
                        )
                    }
                }
                Text("适应")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("WIDTH" to "宽", "HEIGHT" to "高", "PAGE" to "整页", "ORIGINAL" to "原始").forEach { (value, label) ->
                        FilterChip(
                            selected = settings.fitMode == value,
                            onClick = { onChange(settings.copy(fitMode = value)) },
                            label = { Text(label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("横屏双页", modifier = Modifier.weight(1f))
                    Switch(settings.dualPage, { onChange(settings.copy(dualPage = it)) })
                }
            }
            Text("纸色")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AppTheme.PAPER_FOLLOW to "跟随",
                    "DAY" to "日间",
                    "NIGHT" to "夜间",
                    "SEPIA" to "羊皮纸",
                    "CUSTOM" to "自定义",
                ).forEach { (value, label) ->
                    FilterChip(selected = settings.theme == value, onClick = { onChange(settings.copy(theme = value)) }, label = { Text(label) })
                }
            }
            if (settings.theme == "CUSTOM") {
                Text("背景")
                Swatches(settings.customBackground) { onChange(settings.copy(customBackground = it)) }
                Text("文字")
                Swatches(settings.customForeground) { onChange(settings.copy(customForeground = it)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量键翻页", modifier = Modifier.weight(1f))
                Switch(settings.volumeKeys, { onChange(settings.copy(volumeKeys = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("阅读时保持屏幕常亮", modifier = Modifier.weight(1f))
                Switch(settings.keepScreenOn, { onChange(settings.copy(keepScreenOn = it)) })
            }
            Text("屏幕方向")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FOLLOW" to "跟随系统", "PORTRAIT" to "锁定竖屏", "LANDSCAPE" to "锁定横屏").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.orientation == value,
                        onClick = { onChange(settings.copy(orientation = value)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Swatches(selected: Long, onPick: (Long) -> Unit) {
    val colors = listOf(
        0xFFF7F1E6, 0xFFF4E4C8, 0xFFE7E1D6, 0xFF1B1714,
        0xFF121417, 0xFF3F2E22, 0xFF10261C, 0xFF8C3A32,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { color ->
            val value = color.toLong() and 0xFFFFFFFFL
            TextButton(onClick = { onPick(value) }) {
                Text(if (value == (selected and 0xFFFFFFFFL)) "●" else "○", color = Color(color))
            }
        }
    }
}
