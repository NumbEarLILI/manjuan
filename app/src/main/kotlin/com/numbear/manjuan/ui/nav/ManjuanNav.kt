package com.numbear.manjuan.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.bookshelf.BookshelfScreen
import com.numbear.manjuan.cache.CacheProgress
import com.numbear.manjuan.reader.comic.ComicReaderScreen
import com.numbear.manjuan.reader.common.ReaderLoading
import com.numbear.manjuan.reader.pdf.PdfReaderScreen
import com.numbear.manjuan.reader.text.NovelReaderScreen
import com.numbear.manjuan.settings.SettingsScreen
import com.numbear.manjuan.source.local.AddScreen
import com.numbear.manjuan.source.webdav.WebDavBrowseScreen
import kotlinx.coroutines.CancellationException

@Composable
fun ManjuanNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "shelf") {
        composable("shelf") {
            BookshelfScreen(
                onAdd = { nav.navigate("add") },
                onSettings = { nav.navigate("settings") },
                onOpen = { id -> nav.navigate("read/$id") },
            )
        }
        composable("add") {
            AddScreen(onBack = { nav.popBackStack() }, onBrowse = { id -> nav.navigate("browse/$id") })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onBrowse = { id -> nav.navigate("browse/$id") },
            )
        }
        composable("browse/{sourceId}") { entry ->
            val sourceId = entry.arguments?.getString("sourceId")?.toLongOrNull() ?: 0L
            WebDavBrowseScreen(sourceId, onBack = { nav.popBackStack() })
        }
        composable("read/{bookId}") { entry ->
            val bookId = entry.arguments?.getString("bookId")?.toLongOrNull() ?: 0L
            ReaderRouter(bookId, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun ReaderRouter(bookId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ManjuanApp
    var kind by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var download by remember { mutableStateOf<CacheProgress?>(null) }
    LaunchedEffect(bookId) {
        try {
            kind = app.library.prepareReader(bookId) { read, total -> download = CacheProgress(read, total) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法打开"
        }
    }
    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.onBackground)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
        }
        kind == "NOVEL" -> NovelReaderScreen(bookId, onBack)
        kind == "COMIC" -> ComicReaderScreen(bookId, onBack)
        kind == "PDF" -> PdfReaderScreen(bookId, onBack)
        kind == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ReaderLoading(download, onBack, MaterialTheme.colorScheme.onBackground, Modifier.fillMaxWidth())
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp)) {
                Text("无法打开这种书", color = MaterialTheme.colorScheme.onBackground)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
        }
    }
}
