package com.vknxworks.cameractrl

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.vknxworks.cameractrl.ui.camera.CameraLaunch
import com.vknxworks.cameractrl.ui.camera.CameraScreen
import com.vknxworks.cameractrl.ui.camera.ShutterKeys
import com.vknxworks.cameractrl.ui.camera.decodeUpright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The camera for two cases where it isn't the full app:
 *
 * - **Another app asks for a photo** (`IMAGE_CAPTURE`): one JPEG, shown for "Use photo" or "Retake", handed to
 *   that app and not saved in your library. The app lock applies as usual.
 * - **The lock screen** (`STILL_IMAGE_CAMERA_SECURE`, `IMAGE_CAPTURE_SECURE`): shown over the lock screen
 *   without unlocking. Only the viewfinder and the photos taken during this visit are there: no You page,
 *   Settings or camera modes page, and a photo opens in your gallery only after you unlock. It closes when
 *   the screen turns off.
 */
class CaptureActivity : ComponentActivity() {
    private var screenOff: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val secure = action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE || action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE
        val forResult = action == MediaStore.ACTION_IMAGE_CAPTURE || action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE
        if (secure) {
            setShowWhenLocked(true)
            // Like the phone's own camera: leaving the lock-screen camera by turning the screen off ends it.
            screenOff = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = finish()
            }.also { registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED) }
        }
        val output = if (forResult) intent?.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java) else null
        val launch = CameraLaunch(secure = secure, onPhoto = if (forResult) { jpeg -> deliver(jpeg, output) } else null)
        enableEdgeToEdge()
        setContent { CtrlWindow(this, lockGate = !secure) { CameraScreen(launch) } }
    }

    override fun onDestroy() {
        screenOff?.let { runCatching { unregisterReceiver(it) } }
        screenOff = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = ShutterKeys.handle(event) || super.dispatchKeyEvent(event)

    /**
     * Hands the photo to the app that asked: written to the address it gave (EXTRA_OUTPUT), or, without one, as
     * a small picture in the reply (Android's rule for this request). Nothing is saved in your library.
     */
    private fun deliver(jpeg: ByteArray, output: Uri?) {
        lifecycleScope.launch {
            val result: Intent? = withContext(Dispatchers.IO) {
                runCatching {
                    if (output != null) {
                        // Only a content address the other app granted us; never a plain file path, and never
                        // one of CameraCtrl's own (an app can't make CameraCtrl overwrite its own files).
                        if (output.scheme != "content" || output.authority?.startsWith(packageName) == true) error("refused")
                        val stream = runCatching { contentResolver.openOutputStream(output, "wt") }.getOrNull()
                            ?: contentResolver.openOutputStream(output, "w")
                        (stream ?: error("no stream")).use { it.write(jpeg) }
                        Intent()
                    } else {
                        val small: Bitmap = decodeUpright(jpeg, THUMBNAIL_SIDE) ?: error("decode")
                        Intent("inline-data").putExtra("data", small)
                    }
                }.getOrNull()
            }
            if (result != null) {
                setResult(Activity.RESULT_OK, result)
            } else {
                Toast.makeText(this@CaptureActivity, R.string.review_failed, Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
            }
            finish()
        }
    }

    private companion object {
        /** Without an address the photo goes back in the reply itself, which must stay small. */
        const val THUMBNAIL_SIDE = 512
    }
}
