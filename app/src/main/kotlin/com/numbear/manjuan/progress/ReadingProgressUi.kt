package com.numbear.manjuan.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.numbear.manjuan.data.db.BookmarkEntity

@Composable
fun PercentSlider(percent: Float, labelColor: Color, onChange: (Float) -> Unit) {
    Text("${(percent.coerceIn(0f, 1f) * 100).toInt()}%", color = labelColor)
    Slider(value = percent.coerceIn(0f, 1f), onValueChange = onChange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(
    bookmarks: List<BookmarkEntity>,
    onDismiss: () -> Unit,
    onOpen: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("书签", style = MaterialTheme.typography.titleMedium)
            if (bookmarks.isEmpty()) {
                Text("还没有书签", modifier = Modifier.padding(vertical = 12.dp))
            }
            bookmarks.forEach { bookmark ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(bookmark.label, modifier = Modifier.weight(1f).clickable { onOpen(bookmark) })
                    if (bookmark.createdAt > 0) {
                        TextButton(onClick = { onDelete(bookmark) }) { Text("删除") }
                    }
                }
            }
        }
    }
}
