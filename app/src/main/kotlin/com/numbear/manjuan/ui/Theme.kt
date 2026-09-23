package com.numbear.manjuan.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.numbear.manjuan.core.AppPalette
import com.numbear.manjuan.core.AppTheme
import com.numbear.manjuan.core.ReaderSettings
import com.numbear.manjuan.core.paperInk

private fun Long.argb(): Color = Color(toInt())

private fun Color.looksLight(): Boolean {
    val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    return luminance > 0.5
}

private fun AppPalette.toColorScheme() = darkColorScheme(
    primary = primary.argb(),
    onPrimary = onPrimary.argb(),
    primaryContainer = primaryContainer.argb(),
    onPrimaryContainer = onPrimaryContainer.argb(),
    secondary = secondary.argb(),
    onSecondary = onSecondary.argb(),
    secondaryContainer = secondaryContainer.argb(),
    onSecondaryContainer = onSecondaryContainer.argb(),
    tertiary = secondary.argb(),
    onTertiary = onSecondary.argb(),
    tertiaryContainer = secondaryContainer.argb(),
    onTertiaryContainer = onSecondaryContainer.argb(),
    background = background.argb(),
    onBackground = onBackground.argb(),
    surface = surface.argb(),
    onSurface = onSurface.argb(),
    surfaceVariant = surfaceVariant.argb(),
    onSurfaceVariant = onSurfaceVariant.argb(),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = surfaceContainerLowest.argb(),
    surfaceContainerLow = surfaceContainerLow.argb(),
    surfaceContainer = surfaceContainer.argb(),
    surfaceContainerHigh = surfaceContainerHigh.argb(),
    surfaceContainerHighest = surfaceContainerHighest.argb(),
    outline = outline.argb(),
    outlineVariant = outlineVariant.argb(),
)

@Composable
fun ManjuanTheme(appearance: String = AppTheme.Default.storageKey, content: @Composable () -> Unit) {
    val scheme = AppTheme.fromStorage(appearance).palette.toColorScheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val background = scheme.background.toArgb()
            window.statusBarColor = background
            window.navigationBarColor = background
            val lightBars = scheme.background.looksLight()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

data class InkColors(val background: Color, val foreground: Color)

fun ReaderSettings.inkColors(): InkColors {
    val ink = paperInk(appearance, theme, customBackground, customForeground)
    return InkColors(ink.background.argb(), ink.foreground.argb())
}

fun formatLabel(format: String): String = when (format) {
    "ZIP_IMAGES" -> "ZIP"
    "IMAGE_FOLDER" -> "图片夹"
    else -> format
}
