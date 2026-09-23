package com.numbear.manjuan.source.webdav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.core.UnsupportedBookException
import com.numbear.manjuan.core.WebDavStatus
import com.numbear.manjuan.data.db.SourceEntity
import com.numbear.manjuan.data.repo.LibraryRepository
import kotlinx.coroutines.launch

private sealed interface AccountEditor {
    data object Closed : AccountEditor
    data object Create : AccountEditor
    data class Edit(val id: Long) : AccountEditor
}

@Composable
fun WebDavAccountSection(onBrowse: (Long) -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    val scope = rememberCoroutineScope()
    val sources by app.library.observeSources().collectAsState(initial = emptyList())
    val accounts = sources.filter { it.type == LibraryRepository.WEBDAV }
    var editor by remember { mutableStateOf<AccountEditor>(AccountEditor.Closed) }
    var name by remember { mutableStateOf("WebDAV") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("/") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SourceEntity?>(null) }

    fun beginCreate() {
        editor = AccountEditor.Create
        name = "WebDAV"
        url = ""
        username = ""
        password = ""
        root = "/"
        status = null
    }

    fun beginEdit(source: SourceEntity) {
        editor = AccountEditor.Edit(source.id)
        name = source.displayName
        url = source.baseUrl
        username = source.username
        password = ""
        root = source.rootPath.ifBlank { "/" }
        status = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("WebDAV 书库", style = MaterialTheme.typography.titleMedium)
        Text(
            if (accounts.isEmpty()) {
                "添加一次后会保存在本机。下次打开直接点账号浏览，不用再填地址和密码。"
            } else {
                "已保存的账号会一直留在本机。点一下即可浏览。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accounts.isEmpty() && editor == AccountEditor.Closed) {
            Text("还没有保存的账号", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        accounts.forEach { source ->
            WebDavAccountCard(
                source = source,
                onBrowse = { onBrowse(source.id) },
                onEdit = { beginEdit(source) },
                onDelete = { pendingDelete = source },
            )
        }
        if (editor == AccountEditor.Closed) {
            if (accounts.isEmpty()) {
                Button(onClick = { beginCreate() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加 WebDAV 账号", modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                OutlinedButton(onClick = { beginCreate() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加账号", modifier = Modifier.padding(start = 8.dp))
                }
            }
            status?.let { Text(it) }
        } else {
            val editing = editor is AccountEditor.Edit
            WebDavCredentialForm(
                title = if (editing) "编辑账号" else "新账号",
                name = name,
                onName = { name = it },
                url = url,
                onUrl = { url = it },
                username = username,
                onUsername = { username = it },
                password = password,
                onPassword = { password = it },
                passwordLabel = if (editing) "新密码（留空则保持不变）" else "密码",
                root = root,
                onRoot = { root = it },
                busy = busy,
                status = status,
                submitLabel = if (editing) "保存" else "保存并浏览",
                onCancel = {
                    editor = AccountEditor.Closed
                    status = null
                },
                onTest = {
                    busy = true
                    status = null
                    scope.launch {
                        val existingId = (editor as? AccountEditor.Edit)?.id
                        status = when (val result = app.library.testWebDavAccount(existingId, url, username, password, root)) {
                            is WebDavStatus.Ok -> "连接成功"
                            is WebDavStatus.Failed -> result.message
                        }
                        busy = false
                    }
                },
                onSubmit = {
                    busy = true
                    status = null
                    scope.launch {
                        try {
                            when (val current = editor) {
                                AccountEditor.Create -> {
                                    val id = app.library.saveWebDav(name, url, username, password, root)
                                    editor = AccountEditor.Closed
                                    onBrowse(id)
                                }
                                is AccountEditor.Edit -> {
                                    app.library.updateWebDav(current.id, name, url, username, password, root)
                                    editor = AccountEditor.Closed
                                    status = "已保存"
                                }
                                AccountEditor.Closed -> Unit
                            }
                        } catch (error: UnsupportedBookException) {
                            status = error.message
                        } catch (error: Exception) {
                            status = error.message ?: "保存失败"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除账号") },
            text = { Text("删除「${source.displayName}」后，书架上来自该账号的书会一并移除。服务器上的文件不会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = source.id
                    pendingDelete = null
                    if (editor is AccountEditor.Edit && (editor as AccountEditor.Edit).id == id) {
                        editor = AccountEditor.Closed
                    }
                    scope.launch { app.library.deleteSource(id) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun WebDavAccountCard(
    source: SourceEntity,
    onBrowse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onBrowse,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(source.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    source.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(source.username.ifBlank { "无用户名" }, source.rootPath.ifBlank { "/" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "浏览",
                tint = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun WebDavCredentialForm(
    title: String,
    name: String,
    onName: (String) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    passwordLabel: String,
    root: String,
    onRoot: (String) -> Unit,
    busy: Boolean,
    status: String?,
    submitLabel: String,
    onCancel: () -> Unit,
    onTest: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            name,
            onName,
            label = { Text("显示名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            url,
            onUrl,
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.2:5005/dav/") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            username,
            onUsername,
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            password,
            onPassword,
            label = { Text(passwordLabel) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            root,
            onRoot,
            label = { Text("根路径") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = onTest) { Text("测试连接") }
            TextButton(enabled = !busy, onClick = onSubmit) { Text(submitLabel) }
            TextButton(enabled = !busy, onClick = onCancel) { Text("取消") }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
    }
}
