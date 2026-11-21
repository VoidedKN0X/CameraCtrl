package com.vknxworks.cameractrl.data.settings

import androidx.annotation.StringRes
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.camera.Bracket
import com.vknxworks.cameractrl.camera.FlashMode
import com.vknxworks.cameractrl.camera.PhotoFormat
import com.vknxworks.cameractrl.camera.RawProfile
import com.vknxworks.cameractrl.camera.VIDEO_FPS
import com.vknxworks.cameractrl.interval.StopRule
import com.vknxworks.cameractrl.night.NightPreset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CameraCtrl's settings as a JSON file you keep: photo, viewfinder, video, night and interval settings. The app
 * lock and the privacy switches (Hide in screenshots, Hide in recent apps, Lock again) are never in the file, so
 * a restore can't turn any of them off. CameraCtrl has no passwords, keys or accounts to leave out.
 */
@Serializable
data class SettingsBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** CameraCtrl version that wrote the file, for information. */
    val appVersion: String? = null,
    /** ISO time the file was written. */
    val createdAt: String? = null,
    val settings: BackupSettings = BackupSettings(),
) {
    companion object {
        const val FORMAT = "cameractrl-settings"
        const val VERSION = 1
    }
}

/** Every field is optional, so files from older or newer versions restore what they can. */
@Serializable
data class BackupSettings(
    val format: String? = null,
    /** A RAW profile name, or "AUTO" for the best each lens offers. */
    val rawProfile: String? = null,
    val maxRes: Boolean? = null,
    val jpegQuality: Int? = null,
    val shutterSound: Boolean? = null,
    val grid: Boolean? = null,
    val timer: Int? = null,
    val volumeShutter: Boolean? = null,
    val flash: String? = null,
    val bracket: String? = null,
    val zsl: Boolean? = null,
    val ultraHdr: Boolean? = null,
    val closeUpSwitch: Boolean? = null,
    val histogram: Boolean? = null,
    val peaking: Boolean? = null,
    val level: Boolean? = null,
    val videoUhd: Boolean? = null,
    val videoFps: Int? = null,
    val hevc: Boolean? = null,
    val stabilization: Boolean? = null,
    val hdrVideo: Boolean? = null,
    val nightPreset: String? = null,
    val intervalSeconds: Int? = null,
    val intervalStop: String? = null,
    val intervalVideo: Boolean? = null,
)

/** Thrown for a file that isn't a CameraCtrl settings file, or one from a newer, incompatible version. */
class BackupFormatException(val newer: Boolean) : Exception()

object SettingsBackups {
    private const val AUTO_RAW = "AUTO"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun export(s: AppSettings, appVersion: String, now: String): String =
        json.encodeToString(
            SettingsBackup.serializer(),
            SettingsBackup(
                appVersion = appVersion,
                createdAt = now,
                settings = BackupSettings(
                    format = s.format.name,
                    rawProfile = s.rawProfile?.name ?: AUTO_RAW,
                    maxRes = s.maxRes,
                    jpegQuality = s.jpegQuality,
                    shutterSound = s.shutterSound,
                    grid = s.grid,
                    timer = s.timer,
                    volumeShutter = s.volumeShutter,
                    flash = s.flash.name,
                    bracket = s.bracket.name,
                    zsl = s.zsl,
                    ultraHdr = s.ultraHdr,
                    closeUpSwitch = s.closeUpSwitch,
                    histogram = s.histogram,
                    peaking = s.peaking,
                    level = s.level,
                    videoUhd = s.videoUhd,
                    videoFps = s.videoFps,
                    hevc = s.hevc,
                    stabilization = s.stabilization,
                    hdrVideo = s.hdrVideo,
                    nightPreset = s.nightPreset.name,
                    intervalSeconds = s.intervalSeconds,
                    intervalStop = s.intervalStop.encode(),
                    intervalVideo = s.intervalVideo,
                ),
            ),
        )

    /** Reads and checks a settings file. */
    fun parse(text: String): SettingsBackup {
        val b = runCatching { json.decodeFromString(SettingsBackup.serializer(), text) }.getOrNull()
            ?: throw BackupFormatException(newer = false)
        if (b.format != SettingsBackup.FORMAT) throw BackupFormatException(newer = false)
        if (b.version > SettingsBackup.VERSION) throw BackupFormatException(newer = true)
        return b
    }

    /**
     * [cur] with the settings from [b] applied. A value that is missing or not valid keeps the current one;
     * the app lock and privacy settings always stay as they are.
     */
    fun applied(cur: AppSettings, b: BackupSettings): AppSettings = cur.copy(
        format = b.format?.let { n -> PhotoFormat.entries.firstOrNull { it.name == n } } ?: cur.format,
        rawProfile = when (val r = b.rawProfile) {
            null -> cur.rawProfile
            AUTO_RAW -> null
            else -> RawProfile.entries.firstOrNull { it.name == r && it.saveable } ?: cur.rawProfile
        },
        maxRes = b.maxRes ?: cur.maxRes,
        jpegQuality = b.jpegQuality?.takeIf { it in 80..100 } ?: cur.jpegQuality,
        shutterSound = b.shutterSound ?: cur.shutterSound,
        grid = b.grid ?: cur.grid,
        timer = b.timer?.takeIf { it in listOf(0, 3, 10) } ?: cur.timer,
        volumeShutter = b.volumeShutter ?: cur.volumeShutter,
        flash = b.flash?.let { n -> FlashMode.entries.firstOrNull { it.name == n } } ?: cur.flash,
        bracket = b.bracket?.let { n -> Bracket.entries.firstOrNull { it.name == n } } ?: cur.bracket,
        zsl = b.zsl ?: cur.zsl,
        ultraHdr = b.ultraHdr ?: cur.ultraHdr,
        closeUpSwitch = b.closeUpSwitch ?: cur.closeUpSwitch,
        histogram = b.histogram ?: cur.histogram,
        peaking = b.peaking ?: cur.peaking,
        level = b.level ?: cur.level,
        videoUhd = b.videoUhd ?: cur.videoUhd,
        videoFps = b.videoFps?.takeIf { it in VIDEO_FPS } ?: cur.videoFps,
        hevc = b.hevc ?: cur.hevc,
        stabilization = b.stabilization ?: cur.stabilization,
        hdrVideo = b.hdrVideo ?: cur.hdrVideo,
        nightPreset = b.nightPreset?.let { n -> NightPreset.entries.firstOrNull { it.name == n } } ?: cur.nightPreset,
        intervalSeconds = b.intervalSeconds?.takeIf { it in INTERVAL_CHOICES } ?: cur.intervalSeconds,
        intervalStop = StopRule.decode(b.intervalStop) ?: cur.intervalStop,
        intervalVideo = b.intervalVideo ?: cur.intervalVideo,
    )

    /** The names (string resources) of the settings that differ between [a] and [b], to show before a restore. */
    fun changed(a: AppSettings, b: AppSettings): List<Int> = NAMES.filter { (get, _) -> get(a) != get(b) }.map { it.second }

    private val NAMES: List<Pair<(AppSettings) -> Any?, Int>> = listOf(
        name(R.string.photo_format) { it.format },
        name(R.string.raw_profile) { it.rawProfile },
        name(R.string.max_res_setting) { it.maxRes },
        name(R.string.jpeg_quality) { it.jpegQuality },
        name(R.string.shutter_sound) { it.shutterSound },
        name(R.string.grid) { it.grid },
        name(R.string.timer) { it.timer },
        name(R.string.volume_shutter) { it.volumeShutter },
        name(R.string.flash) { it.flash },
        name(R.string.bracketing) { it.bracket },
        name(R.string.zsl) { it.zsl },
        name(R.string.ultra_hdr) { it.ultraHdr },
        name(R.string.closeup_switch) { it.closeUpSwitch },
        name(R.string.histogram) { it.histogram },
        name(R.string.peaking) { it.peaking },
        name(R.string.level) { it.level },
        name(R.string.video_quality) { it.videoUhd },
        name(R.string.video_fps) { it.videoFps },
        name(R.string.video_codec) { it.hevc },
        name(R.string.stabilization) { it.stabilization },
        name(R.string.hdr_video) { it.hdrVideo },
        name(R.string.backup_item_night) { it.nightPreset },
        name(R.string.backup_item_interval) { Triple(it.intervalSeconds, it.intervalStop, it.intervalVideo) },
    )

    private fun name(@StringRes res: Int, get: (AppSettings) -> Any?): Pair<(AppSettings) -> Any?, Int> = get to res
}
