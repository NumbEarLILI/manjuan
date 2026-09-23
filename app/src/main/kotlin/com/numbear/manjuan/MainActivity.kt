package com.numbear.manjuan

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.numbear.manjuan.core.ReaderSettings
import com.numbear.manjuan.ui.ManjuanTheme
import com.numbear.manjuan.ui.nav.ManjuanNav

val LocalRegisterVolumeKey = staticCompositionLocalOf<(handler: (Int) -> Boolean) -> Unit> { { } }

class MainActivity : ComponentActivity() {
    private var volumeHandler: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ManjuanApp
        setContent {
            val settings by app.settings.settings.collectAsState(initial = ReaderSettings())
            requestedOrientation = when (settings.orientation) {
                "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            ManjuanTheme(appearance = settings.appearance) {
                CompositionLocalProvider(
                    LocalRegisterVolumeKey provides { handler -> volumeHandler = handler },
                ) {
                    ManjuanNav()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeHandler?.invoke(keyCode) == true) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
