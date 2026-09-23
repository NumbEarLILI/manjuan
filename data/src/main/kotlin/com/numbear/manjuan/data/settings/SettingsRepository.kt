package com.numbear.manjuan.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.numbear.manjuan.core.AppTheme
import com.numbear.manjuan.core.ReaderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("manjuan_settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<ReaderSettings> = context.settingsStore.data.map { prefs -> decode(prefs) }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        context.settingsStore.edit { prefs ->
            val next = transform(decode(prefs))
            prefs[Keys.pageMode] = next.pageMode
            prefs[Keys.fontSize] = next.fontSizeSp
            prefs[Keys.lineSpacing] = next.lineSpacing
            prefs[Keys.margin] = next.marginDp
            prefs[Keys.appearance] = next.appearance
            prefs[Keys.theme] = next.theme
            prefs[Keys.customBg] = next.customBackground
            prefs[Keys.customFg] = next.customForeground
            prefs[Keys.volumeKeys] = next.volumeKeys
            prefs[Keys.keepScreenOn] = next.keepScreenOn
            prefs[Keys.comicDirection] = next.comicDirection
            prefs[Keys.dualPage] = next.dualPage
            prefs[Keys.fitMode] = next.fitMode
            prefs[Keys.orientation] = next.orientation
        }
    }

    private fun decode(prefs: androidx.datastore.preferences.core.Preferences): ReaderSettings =
        ReaderSettings(
            pageMode = prefs[Keys.pageMode] ?: true,
            fontSizeSp = prefs[Keys.fontSize] ?: 18f,
            lineSpacing = prefs[Keys.lineSpacing] ?: 1.6f,
            marginDp = prefs[Keys.margin] ?: 20f,
            appearance = prefs[Keys.appearance] ?: AppTheme.Default.storageKey,
            theme = prefs[Keys.theme] ?: AppTheme.PAPER_FOLLOW,
            customBackground = prefs[Keys.customBg] ?: 0xFFF3EDE2,
            customForeground = prefs[Keys.customFg] ?: 0xFF1B1714,
            volumeKeys = prefs[Keys.volumeKeys] ?: false,
            keepScreenOn = prefs[Keys.keepScreenOn] ?: true,
            comicDirection = prefs[Keys.comicDirection] ?: "VERTICAL",
            dualPage = prefs[Keys.dualPage] ?: true,
            fitMode = prefs[Keys.fitMode] ?: "WIDTH",
            orientation = prefs[Keys.orientation] ?: "FOLLOW",
        )

    private object Keys {
        val pageMode = booleanPreferencesKey("page_mode")
        val fontSize = floatPreferencesKey("font_size")
        val lineSpacing = floatPreferencesKey("line_spacing")
        val margin = floatPreferencesKey("margin")
        val appearance = stringPreferencesKey("appearance")
        val theme = stringPreferencesKey("theme")
        val customBg = longPreferencesKey("custom_bg")
        val customFg = longPreferencesKey("custom_fg")
        val volumeKeys = booleanPreferencesKey("volume_keys")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val comicDirection = stringPreferencesKey("comic_direction")
        val dualPage = booleanPreferencesKey("dual_page")
        val fitMode = stringPreferencesKey("fit_mode")
        val orientation = stringPreferencesKey("orientation")
    }
}
