package com.numbear.manjuan.core

data class AppPalette(
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val outline: Long,
    val outlineVariant: Long,
)

enum class AppTheme(
    val storageKey: String,
    val label: String,
    val palette: AppPalette,
) {
    INK_NIGHT(
        storageKey = "INK_NIGHT",
        label = "墨夜纸感",
        palette = AppPalette(
            background = 0xFF12110F,
            onBackground = 0xFFE6DCCE,
            surface = 0xFF1C1A16,
            onSurface = 0xFFE6DCCE,
            surfaceVariant = 0xFF332E28,
            onSurfaceVariant = 0xFFB7AFA3,
            surfaceContainerLowest = 0xFF0E0D0B,
            surfaceContainerLow = 0xFF181612,
            surfaceContainer = 0xFF221F1B,
            surfaceContainerHigh = 0xFF2A2622,
            surfaceContainerHighest = 0xFF332E28,
            primary = 0xFFE2B15C,
            onPrimary = 0xFF1A140C,
            primaryContainer = 0xFF5C4318,
            onPrimaryContainer = 0xFFFFE6B8,
            secondary = 0xFFC4A882,
            onSecondary = 0xFF23180C,
            secondaryContainer = 0xFF3D3224,
            onSecondaryContainer = 0xFFF0E2CC,
            outline = 0xFF8A7D6E,
            outlineVariant = 0xFF4A433B,
        ),
    ),
    SLATE_NIGHT(
        storageKey = "SLATE_NIGHT",
        label = "青灰夜读",
        palette = AppPalette(
            background = 0xFF161B22,
            onBackground = 0xFFE7EDF4,
            surface = 0xFF1B212A,
            onSurface = 0xFFE7EDF4,
            surfaceVariant = 0xFF313A46,
            onSurfaceVariant = 0xFFB7C2CE,
            surfaceContainerLowest = 0xFF0E1218,
            surfaceContainerLow = 0xFF181E26,
            surfaceContainer = 0xFF1E252E,
            surfaceContainerHigh = 0xFF262E38,
            surfaceContainerHighest = 0xFF313A46,
            primary = 0xFFA9C4DC,
            onPrimary = 0xFF101820,
            primaryContainer = 0xFF2C455C,
            onPrimaryContainer = 0xFFD6E8F6,
            secondary = 0xFF8AA4BA,
            onSecondary = 0xFF0E1620,
            secondaryContainer = 0xFF243240,
            onSecondaryContainer = 0xFFD5E4F0,
            outline = 0xFF7E8C9C,
            outlineVariant = 0xFF3E4854,
        ),
    ),
    OCHRE_STUDY(
        storageKey = "OCHRE_STUDY",
        label = "赭褐书斋",
        palette = AppPalette(
            background = 0xFF1A120E,
            onBackground = 0xFFF4E7D6,
            surface = 0xFF241812,
            onSurface = 0xFFF4E7D6,
            surfaceVariant = 0xFF3E2E24,
            onSurfaceVariant = 0xFFCDBBA8,
            surfaceContainerLowest = 0xFF120C0A,
            surfaceContainerLow = 0xFF1E1410,
            surfaceContainer = 0xFF261A14,
            surfaceContainerHigh = 0xFF32241C,
            surfaceContainerHighest = 0xFF3E2E24,
            primary = 0xFFE08A6A,
            onPrimary = 0xFF1F0C08,
            primaryContainer = 0xFF6B3A2A,
            onPrimaryContainer = 0xFFFFD8CC,
            secondary = 0xFFC4A484,
            onSecondary = 0xFF24160E,
            secondaryContainer = 0xFF3E2C22,
            onSecondaryContainer = 0xFFF3E0D0,
            outline = 0xFF8C7566,
            outlineVariant = 0xFF5A4538,
        ),
    ),
    ;

    companion object {
        val Default = INK_NIGHT
        const val PAPER_FOLLOW = "FOLLOW"

        fun fromStorage(key: String?): AppTheme = entries.firstOrNull { it.storageKey == key } ?: Default
    }
}

data class PaperInk(val background: Long, val foreground: Long)

fun paperInk(
    appearance: String,
    paperTheme: String,
    customBackground: Long,
    customForeground: Long,
): PaperInk = when (paperTheme) {
    "DAY" -> PaperInk(0xFFF7F1E6, 0xFF1B1714)
    "NIGHT" -> PaperInk(0xFF121417, 0xFFE7E1D6)
    "SEPIA" -> PaperInk(0xFFF4E4C8, 0xFF3F2E22)
    "CUSTOM" -> PaperInk(customBackground, customForeground)
    else -> {
        val palette = AppTheme.fromStorage(appearance).palette
        PaperInk(palette.background, palette.onBackground)
    }
}
