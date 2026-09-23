package com.numbear.manjuan.source.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.source.webdav.WebDavAccountSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(onBack: () -> Unit, onBrowse: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val report = app.library.importUris(uris)
            busy = false
            message = reportText(report.added, report.errors)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val report = app.library.importTree(uri)
            busy = false
            message = reportText(report.added, report.errors)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("本地", style = MaterialTheme.typography.titleMedium)
            Text("支持 TXT、EPUB、MOBI/AZW3、PDF、CBZ、CBR、图片 ZIP，以及图片文件夹。无法识别的文件会说明原因。")
            Button(onClick = { files.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("选择文件")
            }
            OutlinedButton(onClick = { folder.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("选择文件夹")
            }
            WebDavAccountSection(onBrowse)
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("导入结果") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
        )
    }
}

private fun reportText(added: Int, errors: List<String>): String {
    val head = "已加入 $added 本"
    if (errors.isEmpty()) return head
    return head + "\n" + errors.joinToString("\n")
}
