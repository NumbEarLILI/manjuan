package com.numbear.manjuan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.numbear.manjuan.core.ReaderSettings

val Paper = Color(0xFFF3EDE2)
val Ink = Color(0xFF1B1714)
val Cinnabar = Color(0xFFB6402C)
val Moss = Color(0xFF2F4A3C)

private val Colors = lightColorScheme(
    primary = Cinnabar,
    onPrimary = Color(0xFFFFF8F4),
    secondary = Moss,
    onSecondary = Color(0xFFF4EFE6),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFBF6),
    onSurface = Ink,
)

@Composable
fun ManjuanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

data class InkColors(val background: Color, val foreground: Color)

fun ReaderSettings.inkColors(): InkColors = when (theme) {
    "NIGHT" -> InkColors(Color(0xFF121417), Color(0xFFE7E1D6))
    "SEPIA" -> InkColors(Color(0xFFF4E4C8), Color(0xFF3F2E22))
    "CUSTOM" -> InkColors(Color(customBackground.toInt()), Color(customForeground.toInt()))
    else -> InkColors(Color(0xFFF7F1E6), Color(0xFF1B1714))
}

fun formatLabel(format: String): String = when (format) {
    "ZIP_IMAGES" -> "ZIP"
    "IMAGE_FOLDER" -> "图片夹"
    else -> format
}
