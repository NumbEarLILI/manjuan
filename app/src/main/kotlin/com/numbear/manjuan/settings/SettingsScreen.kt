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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.core.AppTheme
import com.numbear.manjuan.core.ReaderSettings
import com.numbear.manjuan.data.repo.LibraryRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val settings by app.settings.settings.collectAsState(initial = ReaderSettings())
    val sources by app.library.observeSources().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
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

            Text("WebDAV 账号", style = MaterialTheme.typography.titleMedium)
            val accounts = sources.filter { it.type == LibraryRepository.WEBDAV }
            if (accounts.isEmpty()) Text("还没有保存的账号")
            accounts.forEach { source ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(source.displayName)
                        Text(source.baseUrl, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { scope.launch { app.library.deleteSource(source.id) } }) { Text("删除") }
                }
            }
        }
    }
}
