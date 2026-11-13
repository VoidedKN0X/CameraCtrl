# Changelog

What changed in each CameraCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/CameraCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.1.1 (2026-11-13)

A small security and housekeeping update. Nothing changes in how you take photos or videos.

### Security

- **Release builds drop debug logging** from CameraCtrl and its libraries, so nothing they would log ends up in the phone's log.
- **The build tool download is checked:** the Gradle version used to build CameraCtrl is verified against its published SHA-256 checksum.

### Privacy

- Checked again: photos (JPEG and DNG) and videos never get a location. CameraCtrl has no location permission and writes no GPS data, so there's nothing to remove.

### Fixed

- A temporary file used to prepare video recording is now always deleted, even when preparing fails.

## v0.1.0 (2026-11-12)

The first release of CameraCtrl: a private, offline camera that saves the sensor's RAW data.

### New

- **RAW (DNG) on every lens that has it**, alongside or instead of JPEG.
- **Every RAW profile detected on your phone:** RAW16, RAW12 and RAW10 are saved as DNG; RAW_PRIVATE is listed when a lens offers it.
- **Maximum resolution mode** on lenses where Android offers it, for the sensor's full pixel count.
- **All lenses:** the AUTO multi-camera with pinch zoom, and every physical lens the phone lets apps use.
- **Camera modes page** listing what each lens offers: sensor, RAW profiles and sizes, maximum resolution, JPEG and video sizes, 10-bit HDR, manual ranges and Camera2 level.
- **Pro controls:** manual ISO, shutter speed, focus, white balance and exposure compensation.
- **Tap to focus**, grid, self-timer and torch.
- **Video** in 4K or 1080p, HEVC or H.264.
- **Privacy:** no internet or location permission, app lock, Hide in screenshots and Hide in recent apps.