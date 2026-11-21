package com.vknxworks.cameractrl

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.cameractrl.ui.camera.ShutterKeys
import com.vknxworks.cameractrl.ui.lock.AppLockGate
import com.vknxworks.cameractrl.ui.lock.GuardWindow
import com.vknxworks.cameractrl.ui.lock.lockCovering
import com.vknxworks.cameractrl.ui.nav.AppRoot
import com.vknxworks.cameractrl.ui.theme.CameraCtrlTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CtrlWindow(this) { AppRoot() } }
    }

    /** The volume keys take a photo while the viewfinder has claimed them (see [ShutterKeys]). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = ShutterKeys.handle(event) || super.dispatchKeyEvent(event)
}

/**
 * The Ctrl window: follows the phone's dark theme, applies "Hide in screenshots" / "Hide in recent apps",
 * and shows nothing of the app while the app lock is locked.
 */
@Composable
fun CtrlWindow(activity: ComponentActivity, lockGate: Boolean = true, content: @Composable () -> Unit) {
    val c = activity.container
    val dark = isSystemInDarkTheme()
    DisposableEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(DARK_BAR) else SystemBarStyle.light(LIGHT_BAR, DARK_BAR)
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        onDispose { }
    }
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val covering = lockGate && lockCovering(c.vault)
    val secure = settings.hideScreenshots || covering
    val blankRecents = settings.hideRecents || c.vault.enabled
    // No overlays over the lock and PIN screens, and their content hidden from accessibility misuse.
    GuardWindow(covering, sensitive = true)
    LaunchedEffect(secure, blankRecents) {
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.setRecentsScreenshotEnabled(!blankRecents)
    }
    CameraCtrlTheme(dark) {
        // The lock-screen camera has no app lock of its own: it shows only the viewfinder and this visit's photos.
        if (lockGate) AppLockGate { content() } else content()
    }
}

private val DARK_BAR = android.graphics.Color.rgb(8, 8, 10)
private val LIGHT_BAR = android.graphics.Color.rgb(245, 245, 241)
