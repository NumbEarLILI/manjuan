package com.numbear.manjuan.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.cache.CacheProgress
import com.numbear.manjuan.data.db.BookEntity
import com.numbear.manjuan.ui.formatLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private data class BatchCache(
    val index: Int,
    val total: Int,
    val title: String,
    val progress: CacheProgress,
)

private enum class ShelfFilter { RECENT, ALL, SOURCE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(onAdd: () -> Unit, onSettings: () -> Unit, onOpen: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val books by app.library.observeBooks().collectAsState(initial = emptyList())
    val sources by app.library.observeSources().collectAsState(initial = emptyList())
    val progress by app.library.observeProgress().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(ShelfFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf<BatchCache?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val batchJob = remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val sourceName = sources.associate { it.id to it.displayName }
    val visible = books.filter { book ->
        val matches = query.isBlank() || book.title.contains(query, true) || book.author.contains(query, true)
        val recent = filter != ShelfFilter.RECENT || book.lastOpenedAt > 0
        matches && recent
    }.let { list ->
        if (filter == ShelfFilter.RECENT) list.sortedByDescending { it.lastOpenedAt } else list
    }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun startBatchCache() {
        val ids = visible.map { it.id }.filter { it in selected }
        if (ids.isEmpty() || batch != null) return
        val titles = books.associate { it.id to it.title }
        batchJob.value?.cancel()
        batchJob.value = scope.launch {
            var done = 0
            val errors = ArrayList<String>()
            ids.forEachIndexed { index, id ->
                val title = titles[id] ?: "未命名"
                batch = BatchCache(index, ids.size, title, CacheProgress(0, -1))
                try {
                    app.library.cacheBook(id) { read, total ->
                        batch = BatchCache(index, ids.size, title, CacheProgress(read, total))
                    }
                    done++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    errors += "$title：${failure.message ?: "缓存失败"}"
                }
            }
            batch = null
            notice = if (errors.isEmpty()) {
                "已缓存 $done 本"
            } else {
                "已缓存 $done 本，失败 ${errors.size} 本\n${errors.take(3).joinToString("\n")}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已选 ${selected.size}" else "漫卷") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "取消选择")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = {
                            selected = if (visible.isNotEmpty() && selected.containsAll(visible.map { it.id })) {
                                emptySet()
                            } else {
                                visible.map { it.id }.toSet()
                            }
                        }) { Text(if (visible.isNotEmpty() && selected.containsAll(visible.map { it.id })) "取消全选" else "全选") }
                        TextButton(enabled = selected.isNotEmpty() && batch == null, onClick = { startBatchCache() }) { Text("缓存") }
                        TextButton(enabled = selected.isNotEmpty() && batch == null, onClick = { confirmDelete = true }) { Text("删除") }
                    } else {
                        TextButton(onClick = { selecting = true }) { Text("选择") }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "添加") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索书名或作者") },
            )
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == ShelfFilter.RECENT, { filter = ShelfFilter.RECENT }, label = { Text("最近") })
                FilterChip(filter == ShelfFilter.ALL, { filter = ShelfFilter.ALL }, label = { Text("全部") })
                FilterChip(filter == ShelfFilter.SOURCE, { filter = ShelfFilter.SOURCE }, label = { Text("按来源") })
            }
            if (visible.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
                    Text("从本地文件夹或 WebDAV 加一本书", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onAdd) { Text("添加书籍") }
                }
            } else if (filter == ShelfFilter.SOURCE) {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    sources.forEach { source ->
                        val group = visible.filter { it.sourceId == source.id }
                        if (group.isNotEmpty()) {
                            item { Text(source.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp)) }
                            items(group, key = { it.id }) { book ->
                                BookRow(
                                    book,
                                    sourceName[book.sourceId].orEmpty(),
                                    progress.firstOrNull { it.bookId == book.id }?.percent ?: 0f,
                                    selecting = selecting,
                                    checked = book.id in selected,
                                    onOpen = {
                                        if (selecting) toggle(book.id) else onOpen(book.id)
                                    },
                                    onSelect = {
                                        selecting = true
                                        toggle(book.id)
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(visible, key = { it.id }) { book ->
                        BookRow(
                            book,
                            sourceName[book.sourceId].orEmpty(),
                            progress.firstOrNull { it.bookId == book.id }?.percent ?: 0f,
                            selecting = selecting,
                            checked = book.id in selected,
                            onOpen = {
                                if (selecting) toggle(book.id) else onOpen(book.id)
                            },
                            onSelect = {
                                selecting = true
                                toggle(book.id)
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移出书架") },
            text = { Text("将移除选中的 ${selected.size} 本书。本地副本和缓存也会删除，服务器上的原始文件不会动。") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toList()
                    confirmDelete = false
                    scope.launch {
                        ids.forEach { app.library.deleteBook(it) }
                        exitSelection()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    batch?.let { state ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在缓存 ${state.index + 1}/${state.total}") },
            text = {
                Column {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val progress = state.progress
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.read.toFloat() / progress.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                        Text("${progress.read / 1024} / ${progress.total / 1024} KB", modifier = Modifier.padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        Text("已接收 ${progress.read / 1024} KB", modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    batchJob.value?.cancel()
                    batch = null
                }) { Text("取消") }
            },
        )
    }
    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text("漫卷") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("好") } },
        )
    }
}

@Composable
private fun BookRow(
    book: BookEntity,
    source: String,
    percent: Float,
    selecting: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onSelect() })
        }
        val cover = coverColor(book.title)
        Box(
            Modifier
                .width(46.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(4.dp, 8.dp, 8.dp, 4.dp))
                .background(cover),
            contentAlignment = Alignment.Center,
        ) {
            Text(book.title.take(1), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                listOf(formatLabel(book.format), source, book.author).filter { it.isNotBlank() }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            LinearProgressIndicator(
                progress = { percent.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

private fun coverColor(title: String): Color {
    val palette = listOf(Color(0xFF8C3A32), Color(0xFF2F4A3C), Color(0xFF3E4C68), Color(0xFF8A5A2A), Color(0xFF5C3A4E))
    return palette[title.hashCode().absoluteValue % palette.size]
}
