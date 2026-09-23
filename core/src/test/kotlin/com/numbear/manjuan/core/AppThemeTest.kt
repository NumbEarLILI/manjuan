package com.numbear.manjuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun defaultsToInkNightPaperAndExposesThreeChineseThemes() {
        val settings = ReaderSettings()
        assertEquals(AppTheme.INK_NIGHT.storageKey, settings.appearance)
        assertEquals(AppTheme.PAPER_FOLLOW, settings.theme)
        assertEquals("墨夜纸感", AppTheme.Default.label)
        assertEquals(
            listOf("墨夜纸感", "青灰夜读", "赭褐书斋"),
            AppTheme.entries.map { it.label },
        )
        assertEquals(AppTheme.Default, AppTheme.fromStorage(null))
        assertEquals(AppTheme.Default, AppTheme.fromStorage("NO_SUCH_THEME"))
        assertEquals(AppTheme.SLATE_NIGHT, AppTheme.fromStorage("SLATE_NIGHT"))
        assertEquals(AppTheme.OCHRE_STUDY, AppTheme.fromStorage("OCHRE_STUDY"))
    }

    @Test
    fun novelPaperFollowsAppearanceUntilAPaperOverrideIsChosen() {
        val ink = AppTheme.INK_NIGHT.palette
        val slate = AppTheme.SLATE_NIGHT.palette
        val ochre = AppTheme.OCHRE_STUDY.palette

        assertEquals(PaperInk(ink.background, ink.onBackground), paperInk(AppTheme.INK_NIGHT.storageKey, AppTheme.PAPER_FOLLOW, 0, 0))
        assertEquals(PaperInk(slate.background, slate.onBackground), paperInk(AppTheme.SLATE_NIGHT.storageKey, AppTheme.PAPER_FOLLOW, 0, 0))
        assertEquals(PaperInk(ochre.background, ochre.onBackground), paperInk(AppTheme.OCHRE_STUDY.storageKey, AppTheme.PAPER_FOLLOW, 0, 0))

        assertEquals(PaperInk(0xFFF7F1E6, 0xFF1B1714), paperInk(AppTheme.INK_NIGHT.storageKey, "DAY", 0, 0))
        assertEquals(PaperInk(0xFF121417, 0xFFE7E1D6), paperInk(AppTheme.SLATE_NIGHT.storageKey, "NIGHT", 0, 0))
        assertEquals(PaperInk(0xFFF4E4C8, 0xFF3F2E22), paperInk(AppTheme.OCHRE_STUDY.storageKey, "SEPIA", 0, 0))
        assertEquals(PaperInk(0xFF10261C, 0xFFE7E1D6), paperInk(AppTheme.INK_NIGHT.storageKey, "CUSTOM", 0xFF10261C, 0xFFE7E1D6))
    }

    @Test
    fun readingTextAndAccentsStayLegibleOnEachTheme() {
        AppTheme.entries.forEach { theme ->
            val palette = theme.palette
            assertTrue(
                "${theme.label} body contrast",
                contrast(palette.onBackground, palette.background) >= 7.0,
            )
            assertTrue(
                "${theme.label} surface contrast",
                contrast(palette.onSurface, palette.surface) >= 7.0,
            )
            assertTrue(
                "${theme.label} accent contrast",
                contrast(palette.onPrimary, palette.primary) >= 4.5,
            )
            assertTrue(
                "${theme.label} accent stands off the page",
                contrast(palette.primary, palette.background) >= 3.0,
            )
        }
    }

    private fun contrast(foreground: Long, background: Long): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF).toInt() / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
