package com.vknxworks.cameractrl.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.camera.PhotoFormat
import com.vknxworks.cameractrl.camera.RawProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "cameractrl")

/** How long CameraCtrl can be in the background before the app lock asks again (-1: only after a restart). */
enum class LockTimeout(@StringRes val label: Int, val millis: Long) {
    IMMEDIATELY(R.string.lock_again_immediately, 0L),
    MINUTE_1(R.string.lock_again_1m, 60_000L),
    MINUTES_5(R.string.lock_again_5m, 5 * 60_000L),
    MINUTES_15(R.string.lock_again_15m, 15 * 60_000L),
    HOUR_1(R.string.lock_again_1h, 60 * 60_000L),
    RESTART(R.string.lock_again_restart, -1L),
}

data class AppSettings(
    val format: PhotoFormat = PhotoFormat.RAW_JPEG,
    /** Preferred RAW profile; null picks the best one each lens offers. */
    val rawProfile: RawProfile? = null,
    /** Use the lens's maximum-resolution mode where Android offers it. */
    val maxRes: Boolean = false,
    val jpegQuality: Int = 95,
    val shutterSound: Boolean = true,
    val grid: Boolean = false,
    /** Self-timer in seconds (0, 3 or 10). */
    val timer: Int = 0,
    val videoUhd: Boolean = true,
    val hevc: Boolean = true,
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    val hideScreenshots: Boolean = false,
    val hideRecents: Boolean = false,
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val format = stringPreferencesKey("format")
        val rawProfile = stringPreferencesKey("raw_profile")
        val maxRes = booleanPreferencesKey("max_res")
        val jpegQuality = intPreferencesKey("jpeg_quality")
        val shutterSound = booleanPreferencesKey("shutter_sound")
        val grid = booleanPreferencesKey("grid")
        val timer = intPreferencesKey("timer")
        val videoUhd = booleanPreferencesKey("video_uhd")
        val hevc = booleanPreferencesKey("hevc")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: StateFlow<AppSettings> = prefs
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings() = AppSettings(
        format = enumOr(this[K.format], PhotoFormat.RAW_JPEG),
        rawProfile = this[K.rawProfile]?.let { n -> RawProfile.entries.firstOrNull { it.name == n && it.saveable } },
        maxRes = this[K.maxRes] ?: false,
        jpegQuality = (this[K.jpegQuality] ?: 95).coerceIn(80, 100),
        shutterSound = this[K.shutterSound] ?: true,
        grid = this[K.grid] ?: false,
        timer = (this[K.timer] ?: 0).takeIf { it in listOf(0, 3, 10) } ?: 0,
        videoUhd = this[K.videoUhd] ?: true,
        hevc = this[K.hevc] ?: true,
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
    )

    /** Saves a change. If the phone's storage fails, the change is dropped instead of closing the app. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            context.appDataStore.edit { p ->
                val s = transform(p.toSettings())
                p[K.format] = s.format.name
                if (s.rawProfile != null) p[K.rawProfile] = s.rawProfile.name else p.remove(K.rawProfile)
                p[K.maxRes] = s.maxRes
                p[K.jpegQuality] = s.jpegQuality
                p[K.shutterSound] = s.shutterSound
                p[K.grid] = s.grid
                p[K.timer] = s.timer
                p[K.videoUhd] = s.videoUhd
                p[K.hevc] = s.hevc
                p[K.lockTimeout] = s.lockTimeout.name
                p[K.hideScreenshots] = s.hideScreenshots
                p[K.hideRecents] = s.hideRecents
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
        }
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
