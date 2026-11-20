# Changelog

What changed in each CameraCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/CameraCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

Built and checked by CI only: nothing here has been run on a phone.

### Faster and smoother

- **The camera is opened once at start, not twice.** CameraCtrl's settings are read from storage a moment after the app starts; until then the app works with the defaults (RAW + JPEG, HEVC, quality 95, maximum resolution off). If your settings differ from those and the viewfinder was ready first, the camera was opened with the defaults, closed, and opened again with your settings. The viewfinder now waits for the saved settings before it opens the camera the first time, so it's opened once with the right setup. The wait is normally a few milliseconds. It can't keep the camera closed: if reading the settings fails the defaults are used as before, and if storage hasn't answered after 2 seconds the camera is opened with what there is (and, as before, once more if the saved settings then turn out to be different). Coming back to the viewfinder later, switching lens or mode and changing a setting work exactly as before.

### Security

- **The signing key is no longer available to the build.** Nothing in the app changed. Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and README.md and its contents are identical to what the build job made. Releases are signed with the same key and the same signature scheme (v2) as before, so updates install as usual. The release job itself has not run with the new workflow yet; the next version will be the first.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml`, SECURITY.md and README.md ever disagree about the signing certificate, the build fails.

### Project

- **Builds no longer use a saved Gradle cache.** Other Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause (found on DateCtrl): with a restored cache Gradle still fetches the plugin files from Maven Central on every build; when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the weekly code scan start from a clean Gradle home, and the plugin portal is no longer a plugin source at all (`settings.gradle.kts`): everything the build needs is on Maven Central and Google's Maven. Dependency verification itself is unchanged and as strict as before. Builds take a little longer.

### Changed

- **Newer build tools and libraries; nothing changes in how CameraCtrl looks or works.** CameraCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.11.1 → 9.5.1, Gradle 8.14.3 → 9.9.2, Kotlin 2.2.0 → 2.4.21 (with its Compose and serialization compiler plugins) and Compose BOM 2025.07.00 → 2026.09.00 (Compose 1.12.1, Material 3 1.4.0). The libraries move to the versions the newest Ctrl apps use: AndroidX Activity 1.10.1 → 1.13.0, Core 1.16.0 → 1.18.0, Lifecycle 2.9.1 → 2.10.0, Navigation 2.9.1 → 2.9.8, DataStore 1.1.7 → 1.3.0, kotlinx.serialization 1.9.0 → 1.13.0 and kotlinx.coroutines 1.10.2 → 1.11.0. No library was added, and the app is still Google-free and offline (CI checks both).
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 36 to 37. The Android versions CameraCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 36), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4; the release APK is about 5% larger (3.13 MB → 3.30 MB). CameraCtrl's own keep rules are unchanged. The new R8 shrinks differently from the old one (stricter keep rules, resources removed together with unused code, classes repackaged), so the release build needs a run on a phone before the next version is published.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

## v0.2.2 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Privacy

- **Unfinished night shots and timelapses no longer stay behind in the cache.** A night shot and a timelapse are put together in a temporary file inside CameraCtrl's private cache, which is deleted as soon as the finished photo or video is in your library. If the app was closed or stopped by Android in the middle of that, the half-finished file stayed there until Android happened to clear the cache; a timelapse can be large. CameraCtrl now deletes these leftovers each time it starts, in the background. Only files from before that start are removed, so a shot you begin right after opening the app is never touched, and nothing in your library changes.

## v0.2.1 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Faster and smoother

- **The app's first frame no longer waits for the lens list.** CameraCtrl asks Android about every camera and every physical lens when the viewfinder opens. That used to happen on the main thread before anything was drawn; it now runs in the background while the screen is black, and the viewfinder (or the "no camera" message) appears as soon as the list is in. The camera is still opened only once the list is known, exactly as before.
- **The first shutter sound isn't late any more.** The shutter click and the two video sounds are loaded when the camera starts instead of at the first photo or recording. Nothing plays unless the shutter sound is on.
- **Scan mode uses less memory and battery.** Reading QR codes and barcodes used to set aside a new buffer of up to 1.5 MB for every frame it checked; it now reuses one. What's scanned and how fast a code is found are unchanged.

## v0.2.0 (2026-11-13)

Three new modes in the row at the bottom of the viewfinder: Night, Interval and Scan.

### New

- **Night mode.** CameraCtrl takes a quick burst of photos at the same exposure, lines them up and merges them into one JPEG with much less noise. Choose **Handheld** (6 frames, short exposures) or **Tripod** (8 longer frames at a lower ISO). It's a multi-frame merge, not one long exposure: the viewfinder says so, shows each frame as it's taken, and the photo's EXIF "Software" field says how many frames were merged. Parts that moved between frames are taken from the first frame, so they don't leave ghosts. RAW isn't saved in night mode; it stays in Photo mode.
- **Interval shooting and timelapses.** Take a photo every 1 second to 10 minutes, for 50 to 600 shots, 10 minutes to 4 hours, or until you stop. Keep every shot as a JPEG photo, or let CameraCtrl make a timelapse video from them (30 frames per second, HEVC or H.264, 4K or 1080p as set for video), built on the phone as the run goes. The run goes on with the screen dimmed or off: tap **Dim screen** to turn the screen black, and the camera keeps working in a foreground service with a Stop button in its notification. Shots that can't keep up with a short interval are skipped rather than taken in a hurry.
- **QR code and barcode scanner.** Point the camera at a QR code, Data Matrix, Aztec, PDF417 or a common barcode (EAN, UPC, Code 128, Code 39 and others). CameraCtrl shows what it says, with **Copy** and **Share**. A link is shown in full and only opens in your browser when you tap **Open link**; other kinds of links (apps, files) are only shown as text. A Wi-Fi code shows the network and password, offers Android's own add-network screen (CameraCtrl needs no Wi-Fi permission for that) and **Copy password**.

### Privacy

- Still no internet and no location permission. Night merges, timelapse videos and code scanning all happen on the phone.
- New install-time permissions, only used while an interval run you started is going: run a foreground service of the camera type. Without a run, nothing runs in the background and the camera closes when you leave the app.

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