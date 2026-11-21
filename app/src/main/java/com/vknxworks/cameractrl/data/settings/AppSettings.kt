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
import com.vknxworks.cameractrl.camera.Bracket
import com.vknxworks.cameractrl.camera.FlashMode
import com.vknxworks.cameractrl.camera.PhotoFormat
import com.vknxworks.cameractrl.camera.VIDEO_FPS
import com.vknxworks.cameractrl.camera.RawProfile
import com.vknxworks.cameractrl.interval.StopRule
import com.vknxworks.cameractrl.night.NightPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    /** Night mode: tripod or handheld. */
    val nightPreset: NightPreset = NightPreset.HANDHELD,
    /** Interval mode: seconds between shots. */
    val intervalSeconds: Int = 5,
    val intervalStop: StopRule = StopRule.AfterShots(300),
    /** Interval mode: a timelapse video (true) or every shot as a photo (false). */
    val intervalVideo: Boolean = true,
    /** Flash for photos (Photo mode; the torch is separate). */
    val flash: FlashMode = FlashMode.OFF,
    /** The volume keys take a photo (or start and stop a video) while the viewfinder is open. */
    val volumeShutter: Boolean = true,
    /** Video frame rate, where the lens allows it (24, 30 or 60). */
    val videoFps: Int = 30,
    /** Video stabilisation (the lens's optical and the camera's electronic stabilisation, where offered). */
    val stabilization: Boolean = true,
    /** 10-bit HDR video (HLG, HEVC), where the lens offers it. */
    val hdrVideo: Boolean = false,
    /** Live histogram over the viewfinder. */
    val histogram: Boolean = false,
    /** Focus peaking while focusing by hand. */
    val peaking: Boolean = false,
    /** Level (horizon line) over the viewfinder. */
    val level: Boolean = false,
    /** Exposure bracketing in Photo mode. */
    val bracket: Bracket = Bracket.OFF,
    /** Let the camera use zero-shutter-lag for photos where it offers it. */
    val zsl: Boolean = true,
    /** Ultra HDR photos (JPEG with an HDR gain map) where the lens offers them. */
    val ultraHdr: Boolean = false,
    /** Switch to the lens that focuses closest when the subject is too close for the chosen lens. */
    val closeUpSwitch: Boolean = true,
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
        val nightPreset = stringPreferencesKey("night_preset")
        val intervalSeconds = intPreferencesKey("interval_seconds")
        val intervalStop = stringPreferencesKey("interval_stop")
        val intervalVideo = booleanPreferencesKey("interval_video")
        val flash = stringPreferencesKey("flash")
        val volumeShutter = booleanPreferencesKey("volume_shutter")
        val videoFps = intPreferencesKey("video_fps")
        val stabilization = booleanPreferencesKey("stabilization")
        val hdrVideo = booleanPreferencesKey("hdr_video")
        val histogram = booleanPreferencesKey("histogram")
        val peaking = booleanPreferencesKey("peaking")
        val level = booleanPreferencesKey("level")
        val bracket = stringPreferencesKey("bracket")
        val zsl = booleanPreferencesKey("zsl")
        val ultraHdr = booleanPreferencesKey("ultra_hdr")
        val closeUpSwitch = booleanPreferencesKey("close_up_switch")
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private val current = MutableStateFlow(AppSettings())
    private val read = MutableStateFlow(false)

    /** The settings: the defaults until the saved ones have been read (see [loaded]). */
    val settings: StateFlow<AppSettings> = current

    /**
     * True once the saved settings have been read from storage, so whoever sees `true` here also sees the saved
     * settings in [settings] (that one is always set first). The viewfinder waits for this before it opens the
     * camera, so the camera isn't opened with the defaults and then again with the saved settings.
     *
     * It always becomes true: when reading ends for any reason (the defaults stay in use then, as before), and
     * at the latest [LOAD_WAIT_MS] after the start even if storage hasn't answered by then.
     */
    val loaded: StateFlow<Boolean> = read

    init {
        scope.launch {
            try {
                prefs.map { it.toSettings() }.collect { s ->
                    current.value = s
                    read.value = true
                }
            } finally {
                read.value = true
            }
        }
        scope.launch {
            delay(LOAD_WAIT_MS)
            read.value = true
        }
    }

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
        nightPreset = enumOr(this[K.nightPreset], NightPreset.HANDHELD),
        intervalSeconds = (this[K.intervalSeconds] ?: 5).takeIf { it in INTERVAL_CHOICES } ?: 5,
        intervalStop = StopRule.decode(this[K.intervalStop]) ?: StopRule.AfterShots(300),
        intervalVideo = this[K.intervalVideo] ?: true,
        flash = enumOr(this[K.flash], FlashMode.OFF),
        volumeShutter = this[K.volumeShutter] ?: true,
        videoFps = (this[K.videoFps] ?: 30).takeIf { it in VIDEO_FPS } ?: 30,
        stabilization = this[K.stabilization] ?: true,
        hdrVideo = this[K.hdrVideo] ?: false,
        histogram = this[K.histogram] ?: false,
        peaking = this[K.peaking] ?: false,
        level = this[K.level] ?: false,
        bracket = enumOr(this[K.bracket], Bracket.OFF),
        zsl = this[K.zsl] ?: true,
        ultraHdr = this[K.ultraHdr] ?: false,
        closeUpSwitch = this[K.closeUpSwitch] ?: true,
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
                p[K.nightPreset] = s.nightPreset.name
                p[K.intervalSeconds] = s.intervalSeconds
                p[K.intervalStop] = s.intervalStop.encode()
                p[K.intervalVideo] = s.intervalVideo
                p[K.flash] = s.flash.name
                p[K.volumeShutter] = s.volumeShutter
                p[K.videoFps] = s.videoFps
                p[K.stabilization] = s.stabilization
                p[K.hdrVideo] = s.hdrVideo
                p[K.histogram] = s.histogram
                p[K.peaking] = s.peaking
                p[K.level] = s.level
                p[K.bracket] = s.bracket.name
                p[K.zsl] = s.zsl
                p[K.ultraHdr] = s.ultraHdr
                p[K.closeUpSwitch] = s.closeUpSwitch
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
        }
    }
}

/** The longest the viewfinder waits for the saved settings before it opens the camera with what it has. */
private const val LOAD_WAIT_MS = 2_000L

/** Seconds between interval shots that can be chosen. */
val INTERVAL_CHOICES = listOf(1, 2, 3, 5, 10, 15, 30, 60, 120, 300, 600)

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
