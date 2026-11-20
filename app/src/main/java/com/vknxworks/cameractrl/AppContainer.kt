package com.vknxworks.cameractrl

import android.content.Context
import com.vknxworks.cameractrl.data.lock.LockVault
import com.vknxworks.cameractrl.data.settings.AppSettings
import com.vknxworks.cameractrl.data.settings.SettingsRepository
import com.vknxworks.cameractrl.interval.IntervalRunner
import com.vknxworks.cameractrl.ui.lock.AppLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "cameractrl")

    /** Interval shooting and timelapses, which go on in a foreground service while the screen is dimmed or off. */
    val interval by lazy { IntervalRunner(context, appScope) }

    init {
        AppLock.onLocked = { onLocked() }
        val startedAt = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) { clearLeftoverTempFiles(startedAt) }
    }

    /**
     * Night shots and timelapses are put together in a temporary file in the cache and deleted once the result
     * is in the library. If the app was closed in the middle of that, the unfinished file was left behind (it
     * can be large, and it is a picture or video). Nothing can resume it, so it's deleted at the next start.
     * Only files last written before this start are touched, so a shot begun right after opening is never hit.
     */
    private fun clearLeftoverTempFiles(startedAt: Long) {
        runCatching {
            context.cacheDir.listFiles()?.forEach { file ->
                val name = file.name
                val leftover = name == "prepare.mp4" ||
                    (name.startsWith("night-") && name.endsWith(".jpg")) ||
                    (name.startsWith("CTRL_") && name.endsWith("_TIMELAPSE.mp4"))
                if (leftover && file.isFile && file.lastModified() < startedAt) file.delete()
            }
        }
    }

    suspend fun enableLock(pin: String) {
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
    }

    fun disableLock() {
        vault.disable()
    }

    /**
     * "Forgot PIN?": the PIN can't be recovered, so CameraCtrl's settings are reset and the lock turned off.
     * Photos and videos you took are in your library and aren't touched.
     */
    suspend fun forgetEverything() {
        settings.update { AppSettings() }
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        vault.close()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }
}

val Context.container: AppContainer
    get() = (applicationContext as CameraCtrlApp).container