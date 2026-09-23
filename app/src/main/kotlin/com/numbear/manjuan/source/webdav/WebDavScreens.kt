package com.numbear.manjuan.source.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.cache.CacheProgress
import com.numbear.manjuan.core.UnsupportedBookException
import com.numbear.manjuan.core.WebDavEntry
import com.numbear.manjuan.core.WebDavStatus
import kotlinx.coroutines.launch

@Composable
fun WebDavAccountSection(onBrowse: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("WebDAV") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("/") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("WebDAV 书库", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, placeholder = { Text("http://192.168.1.2:5005/dav/") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(username, { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(root, { root = it }, label = { Text("根路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    status = when (val result = app.library.testWebDav(url, username, password, root)) {
                        is WebDavStatus.Ok -> "连接成功"
                        is WebDavStatus.Failed -> result.message
                    }
                    busy = false
                }
            }) { Text("测试连接") }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val id = app.library.saveWebDav(name, url, username, password, root)
                        onBrowse(id)
                    } catch (error: UnsupportedBookException) {
                        status = error.message
                    } catch (error: Exception) {
                        status = error.message ?: "保存失败"
                    } finally {
                        busy = false
                    }
                }
            }) { Text("保存并浏览") }
        }
        if (busy) CircularProgressIndicator()
        status?.let { Text(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBrowseScreen(sourceId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    val stack = remember { mutableListOf<String>() }
    var entries by remember { mutableStateOf<List<WebDavEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var caching by remember { mutableStateOf<CacheProgress?>(null) }

    fun reload(target: String) {
        path = target
        loading = true
        scope.launch {
            try {
                entries = app.library.listWebDav(sourceId, target)
                error = null
            } catch (failure: Exception) {
                error = failure.message ?: "无法列出目录"
                entries = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(sourceId) { reload("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.ifBlank { "WebDAV" }) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (stack.isEmpty()) onBack() else reload(stack.removeAt(stack.lastIndex))
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.directory) {
                                    stack.add(path)
                                    reload(entry.path)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(if (entry.directory) "${entry.name}/" else entry.name, style = MaterialTheme.typography.titleMedium)
                        if (!entry.directory || entry.directory) {
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            app.library.addRemote(sourceId, entry)
                                            message = "已加入书架：${entry.name}"
                                        } catch (failure: Exception) {
                                            message = failure.message ?: "无法加入"
                                        }
                                    }
                                }) { Text("加入书架") }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            val id = app.library.addRemote(sourceId, entry)
                                            caching = CacheProgress(0, -1)
                                            app.library.cacheBook(id) { read, total -> caching = CacheProgress(read, total) }
                                            caching = null
                                            message = "已缓存：${entry.name}"
                                        } catch (failure: Exception) {
                                            caching = null
                                            message = failure.message ?: "缓存失败"
                                        }
                                    }
                                }) { Text("缓存整本") }
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
                        LinearProgressIndicator(progress = { progress.read.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
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
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("漫卷") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
        )
    }
}
