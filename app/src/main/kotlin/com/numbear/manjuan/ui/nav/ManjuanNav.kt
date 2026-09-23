package com.numbear.manjuan.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.numbear.manjuan.ManjuanApp
import com.numbear.manjuan.bookshelf.BookshelfScreen
import com.numbear.manjuan.reader.comic.ComicReaderScreen
import com.numbear.manjuan.reader.pdf.PdfReaderScreen
import com.numbear.manjuan.reader.text.NovelReaderScreen
import com.numbear.manjuan.settings.SettingsScreen
import com.numbear.manjuan.source.local.AddScreen
import com.numbear.manjuan.source.webdav.WebDavBrowseScreen
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext

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
    LaunchedEffect(bookId) {
        kind = app.library.book(bookId)?.kind
    }
    when (kind) {
        "NOVEL" -> NovelReaderScreen(bookId, onBack)
        "COMIC" -> ComicReaderScreen(bookId, onBack)
        "PDF" -> PdfReaderScreen(bookId, onBack)
        null -> Text("正在打开…")
        else -> Text("无法打开这种书")
    }
}
