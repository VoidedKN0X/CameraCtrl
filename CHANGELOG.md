# Changelog

What changed in each CameraCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/CameraCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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