package com.numbear.manjuan.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.core.AppTheme
import com.numbear.manjuan.core.ReaderSettings
import com.numbear.manjuan.source.webdav.WebDavAccountSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onBrowse: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val settings by app.settings.settings.collectAsState(initial = ReaderSettings())
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cacheBytes = app.library.cacheSize()
    }
    fun update(next: ReaderSettings) {
        scope.launch { app.settings.update { next } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("外观 / 主题", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = AppTheme.fromStorage(settings.appearance) == theme,
                        onClick = { update(settings.copy(appearance = theme.storageKey, theme = AppTheme.PAPER_FOLLOW)) },
                        label = { Text(theme.label) },
                    )
                }
            }

            Text("小说", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings.pageMode, { update(settings.copy(pageMode = true)) }, label = { Text("翻页") })
                FilterChip(!settings.pageMode, { update(settings.copy(pageMode = false)) }, label = { Text("滚动") })
            }
            Text("字号 ${settings.fontSizeSp.toInt()}")
            Slider(settings.fontSizeSp, { update(settings.copy(fontSizeSp = it)) }, valueRange = 14f..32f)
            Text("行距 ${"%.1f".format(settings.lineSpacing)}")
            Slider(settings.lineSpacing, { update(settings.copy(lineSpacing = it)) }, valueRange = 1.1f..2.2f)
            Text("边距 ${settings.marginDp.toInt()}")
            Slider(settings.marginDp, { update(settings.copy(marginDp = it)) }, valueRange = 8f..48f)

            Text("漫画 / PDF", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("VERTICAL" to "纵向", "RTL" to "从右向左", "LTR" to "从左向右").forEach { (value, label) ->
                    FilterChip(settings.comicDirection == value, { update(settings.copy(comicDirection = value)) }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("横屏双页", modifier = Modifier.weight(1f))
                Switch(settings.dualPage, { update(settings.copy(dualPage = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("音量键翻页", modifier = Modifier.weight(1f))
                Switch(settings.volumeKeys, { update(settings.copy(volumeKeys = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("阅读时保持屏幕常亮", modifier = Modifier.weight(1f))
                Switch(settings.keepScreenOn, { update(settings.copy(keepScreenOn = it)) })
            }
            Text("方向")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FOLLOW" to "跟随系统", "PORTRAIT" to "锁定竖屏", "LANDSCAPE" to "锁定横屏").forEach { (value, label) ->
                    FilterChip(settings.orientation == value, { update(settings.copy(orientation = value)) }, label = { Text(label) })
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
                    FilterChip(settings.theme == value, { update(settings.copy(theme = value)) }, label = { Text(label) })
                }
            }

            WebDavAccountSection(onBrowse)

            Text("缓存", style = MaterialTheme.typography.titleMedium)
            Text(
                when (val bytes = cacheBytes) {
                    null -> "正在计算占用…"
                    else -> "已用 ${formatBytes(bytes)}"
                },
            )
            if (cleared) Text("已清除，阅读进度和书签仍保留")
            Button(
                onClick = { confirmClear = true },
                enabled = !clearing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (clearing) "正在清除" else "清除缓存")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { if (!clearing) confirmClear = false },
            title = { Text("清除缓存") },
            text = {
                Text("只会删除网盘下载的文件，以及阅读时解压生成的临时文件。阅读进度、书签、书架和本机导入的书都会保留。下次打开未缓存的网盘书时会重新下载。")
            },
            confirmButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = {
                        clearing = true
                        scope.launch {
                            try {
                                app.library.clearCache()
                                cleared = true
                            } finally {
                                cacheBytes = runCatching { app.library.cacheSize() }.getOrDefault(cacheBytes)
                                clearing = false
                                confirmClear = false
                            }
                        }
                    },
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(enabled = !clearing, onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
