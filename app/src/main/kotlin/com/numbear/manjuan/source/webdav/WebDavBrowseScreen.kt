package com.numbear.manjuan.source.webdav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.cache.CacheProgress
import com.numbear.manjuan.core.FormatDetector
import com.numbear.manjuan.core.LibraryNames
import com.numbear.manjuan.core.WebDavEntry
import com.numbear.manjuan.data.repo.RemoteScanReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class Crumb(val label: String, val path: String)

private data class ScanUi(
    val running: Boolean,
    val found: Int = 0,
    val imported: Int = 0,
    val skipped: Int = 0,
    val errorCount: Int = 0,
    val current: String = "",
    val report: RemoteScanReport? = null,
)

private data class BrowseAction(val label: String, val icon: ImageVector, val run: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBrowseScreen(sourceId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val scope = rememberCoroutineScope()
    val sources by app.library.observeSources().collectAsState(initial = emptyList())
    val accountName = sources.firstOrNull { it.id == sourceId }?.displayName ?: "WebDAV"
    var path by remember { mutableStateOf("") }
    var crumbs by remember { mutableStateOf(listOf(Crumb("根目录", ""))) }
    var entries by remember { mutableStateOf<List<WebDavEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var caching by remember { mutableStateOf<CacheProgress?>(null) }
    var menuEntry by remember { mutableStateOf<WebDavEntry?>(null) }
    var scan by remember { mutableStateOf<ScanUi?>(null) }
    val scanToken = remember { intArrayOf(0) }
    val scanJob = remember { mutableStateOf<Job?>(null) }
    val loadToken = remember { intArrayOf(0) }
    val crumbScroll = rememberScrollState()

    fun reload(target: String) {
        val generation = ++loadToken[0]
        if (path != target) entries = emptyList()
        path = target
        loading = true
        error = null
        scope.launch {
            try {
                val listed = app.library.listWebDav(sourceId, target)
                if (generation != loadToken[0]) return@launch
                entries = listed
                error = null
            } catch (failure: Exception) {
                if (generation != loadToken[0]) return@launch
                error = failure.message ?: "无法列出目录"
                entries = emptyList()
            } finally {
                if (generation == loadToken[0]) loading = false
            }
        }
    }

    fun jumpTo(index: Int) {
        val next = crumbs.take(index + 1)
        crumbs = next
        reload(next.last().path)
    }

    fun openDirectory(entry: WebDavEntry) {
        crumbs = crumbs + Crumb(entry.name.trimEnd('/').ifBlank { entry.name }, entry.path)
        reload(entry.path)
    }

    fun goUp() {
        if (crumbs.size <= 1) onBack() else jumpTo(crumbs.lastIndex - 1)
    }

    fun addToShelf(entry: WebDavEntry) {
        scope.launch {
            try {
                app.library.addRemote(sourceId, entry)
                message = "已加入书架：${entry.name.trimEnd('/')}"
            } catch (failure: Exception) {
                message = failure.message ?: "无法加入"
            }
        }
    }

    fun cacheEntry(entry: WebDavEntry) {
        scope.launch {
            try {
                val id = app.library.addRemote(sourceId, entry)
                caching = CacheProgress(0, -1)
                app.library.cacheBook(id) { read, total -> caching = CacheProgress(read, total) }
                caching = null
                message = "已缓存：${entry.name.trimEnd('/')}"
            } catch (failure: Exception) {
                caching = null
                message = failure.message ?: "缓存失败"
            }
        }
    }

    fun startScan(target: String) {
        scanJob.value?.cancel()
        val token = ++scanToken[0]
        scan = ScanUi(running = true, current = "正在列出目录…")
        scanJob.value = scope.launch {
            try {
                val report = app.library.scanWebDav(sourceId, target) { progress ->
                    if (token != scanToken[0]) return@scanWebDav
                    scan = ScanUi(
                        running = true,
                        found = progress.found,
                        imported = progress.imported,
                        skipped = progress.skipped,
                        errorCount = progress.errorCount,
                        current = progress.current.ifBlank { "正在列出目录…" },
                    )
                }
                if (token != scanToken[0]) return@launch
                scan = ScanUi(
                    running = false,
                    found = report.found,
                    imported = report.imported,
                    skipped = report.skipped,
                    errorCount = report.errors.count { it != "其余错误已省略" },
                    report = report,
                )
            } catch (_: CancellationException) {
                if (token == scanToken[0]) scan = null
            } catch (failure: Exception) {
                if (token == scanToken[0]) {
                    scan = null
                    message = failure.message ?: "扫描失败"
                }
            }
        }
    }

    fun cancelScan() {
        scanToken[0]++
        scanJob.value?.cancel()
        scan = null
    }

    DisposableEffect(sourceId) {
        onDispose {
            scanToken[0]++
            scanJob.value?.cancel()
        }
    }

    LaunchedEffect(sourceId) { reload("") }
    LaunchedEffect(crumbs) { crumbScroll.scrollTo(crumbScroll.maxValue) }

    val visible = entries.filter { entry ->
        !LibraryNames.isJunk(entry.name) && !LibraryNames.isJunk(entry.path)
    }

    menuEntry?.let { entry ->
        val actions = buildList {
            if (entry.directory) {
                add(BrowseAction("进入目录", Icons.Filled.FolderOpen) {
                    menuEntry = null
                    openDirectory(entry)
                })
            }
            add(BrowseAction("加入书架", Icons.Filled.LibraryAdd) {
                menuEntry = null
                addToShelf(entry)
            })
            add(BrowseAction("缓存", Icons.Filled.Download) {
                menuEntry = null
                cacheEntry(entry)
            })
            if (entry.directory) {
                add(BrowseAction("扫描导入", Icons.AutoMirrored.Filled.ManageSearch) {
                    menuEntry = null
                    startScan(entry.path)
                })
            }
        }
        ModalBottomSheet(onDismissRequest = { menuEntry = null }) {
            Column(Modifier.padding(bottom = 28.dp)) {
                Text(
                    entry.name.trimEnd('/'),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                Text(
                    entryHint(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                )
                actions.forEach { action ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = action.run)
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(action.label, modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(accountName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { goUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reload(path) }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新加载")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("当前目录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            crumbs.lastOrNull()?.label ?: "根目录",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { startScan(path) },
                        enabled = scan?.running != true && caching == null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("扫描导入", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(crumbScroll),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    crumbs.forEachIndexed { index, crumb ->
                        val last = index == crumbs.lastIndex
                        if (index > 0) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            crumb.label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !last) { jumpTo(index) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = if (last) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    "${visible.size} 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { failure ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(failure, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload(path) }) { Text("重试") }
                }
            }
            PullToRefreshBox(
                isRefreshing = loading && visible.isNotEmpty(),
                onRefresh = { reload(path) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    loading && visible.isEmpty() -> {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "正在读取目录…",
                                modifier = Modifier.padding(top = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    visible.isEmpty() -> {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("这个目录是空的", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                            Text(
                                "没有可显示的文件或子文件夹",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            TextButton(onClick = { reload(path) }) { Text("重新加载") }
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(visible, key = { it.path + "\n" + it.directory }) { entry ->
                                WebDavEntryRow(
                                    entry = entry,
                                    onOpen = {
                                        if (entry.directory) openDirectory(entry) else menuEntry = entry
                                    },
                                    onMenu = { menuEntry = entry },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            }
                        }
                    }
                }
            }
        }
    }

    caching?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在缓存") },
            text = {
                Column {
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.read.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${progress.read / 1024} / ${progress.total / 1024} KB")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("已接收 ${progress.read / 1024} KB")
                    }
                }
            },
            confirmButton = {},
        )
    }

    scan?.let { state ->
        AlertDialog(
            onDismissRequest = { if (state.running) cancelScan() else scan = null },
            title = { Text(if (state.running) "正在扫描" else "扫描结果") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            state.current.ifBlank { "正在列出目录…" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("已发现 ${state.found} · 已导入 ${state.imported} · 跳过重复 ${state.skipped} · 错误 ${state.errorCount}")
                    state.report?.errors?.takeIf { it.isNotEmpty() }?.let { lines ->
                        Text(lines.joinToString("\n"), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                if (!state.running) {
                    TextButton(onClick = { scan = null }) { Text("好") }
                }
            },
            dismissButton = {
                if (state.running) {
                    TextButton(onClick = { cancelScan() }) { Text("取消") }
                }
            },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("漫卷") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
        )
    }
}

@Composable
private fun WebDavEntryRow(entry: WebDavEntry, onOpen: () -> Unit, onMenu: () -> Unit) {
    val directory = entry.directory
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (directory) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                fileIcon(entry),
                contentDescription = if (directory) "文件夹" else "文件",
                tint = if (directory) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                entry.name.trimEnd('/'),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entryHint(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "操作")
        }
    }
}

private fun fileIcon(entry: WebDavEntry): ImageVector {
    if (entry.directory) return Icons.Filled.Folder
    val ext = FormatDetector.extension(entry.name)
    return when {
        FormatDetector.isImageName(entry.name) -> Icons.Filled.Image
        ext == "pdf" -> Icons.Filled.PictureAsPdf
        else -> Icons.Filled.Description
    }
}

private fun entryHint(entry: WebDavEntry): String {
    if (entry.directory) return "文件夹"
    val ext = FormatDetector.extension(entry.name).uppercase().ifBlank { "文件" }
    val size = formatBytes(entry.size)
    return if (size.isEmpty()) ext else "$ext · $size"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    if (unit == 0) return "$bytes B"
    val scaled = (value * 10).toLong() / 10.0
    return "$scaled ${units[unit]}"
}
