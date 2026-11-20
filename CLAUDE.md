# CameraCtrl

Camera app in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. Camera2 directly (no CameraX: it hides RAW profiles, physical lenses and the maximum-resolution mode).

## Layout

- `camera/Lenses.kt`: `LensDetector` reads every camera ID and every physical lens of each logical multi-camera, and records what Android reports: RAW profiles (RAW16, RAW12, RAW10, RAW_PRIVATE) with sizes, the maximum-resolution stream map (ULTRA_HIGH_RESOLUTION_SENSOR), JPEG/preview/video sizes, manual ranges. Nothing is hard-coded per phone.
- `camera/CameraController.kt`: one HandlerThread; session per `Setup` (lens, mode, format, RAW profile, max-res); physical lenses through `OutputConfiguration.setPhysicalCameraId`; max-res outputs with `addSensorPixelModeUsed(MAXIMUM_RESOLUTION)` and still requests with `SENSOR_PIXEL_MODE`; video through MediaRecorder on a persistent input surface.
- `camera/Saver.kt`: MediaStore (DCIM/CameraCtrl, Movies/CameraCtrl, IS_PENDING); DNG via `DngCreator` (RAW10/RAW12 unpacked to 16-bit first).
- `ui/camera`: `CameraScreen` (viewfinder, lens row, pro panel, shutter, mode row), `CaptureModes` (night and interval panels, the interval run screen, the scan result sheet), `ModesScreen` (everything detected, per lens).
- `night/`: `NightMerge` (pure Kotlin, unit-tested: luma pyramid, global translation alignment, reference-guided average, exposure plan for the presets), `NightJob` (YUV burst frames in, one JPEG out, on its own thread).
- `interval/`: `IntervalPlan` (pure scheduling, unit-tested), `IntervalRunner` (the run, with its own preview-less `CameraController`), `IntervalService` (camera-type foreground service, not exported), `TimelapseEncoder` (JPEG shots to an MP4 with MediaCodec, explicit timestamps), `Yuv`.
- `scan/`: `Decoder` (ZXing core on the luma plane), `ScanContent` (link / Wi-Fi / text parsing, unit-tested).

## Hard rules

- **No `INTERNET` permission, ever**, and **no location permission** (no GPS in any file).
- **Only standard Android camera modes.** No vendor tags, vendor sessions or phone-maker SDKs.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No CameraX, ML Kit or Play services. No WebView. Fonts are bundled. The one `com.google` group allowed is `com.google.zxing:core` (independent Apache-2.0 decoder, no dependencies); CI filters exactly that module.
- **Telemetry-free:** no analytics or crash reporting.
- The camera is open only while the viewfinder is visible and resumed. The only exception is an interval run the user starts: `IntervalService` (foreground, camera type) holds it until the run ends or is stopped.
- Scanned codes: links open only on a tap, with the full URL shown, and only http/https; nothing else is ever opened from a code.
- arm64-v8a only. Every user-facing string goes in `strings.xml`. Settings open only from the profile picture.

## Build and signing (CI)

`.github/workflows/build.yml` has four jobs: `version` (is this a release?), `build` (Gradle, **no secrets**, `UNSIGNED_RELEASE=true` gives `app-release-unsigned.apk`; runs every check), `sign` (no checkout, no Gradle: `zipalign` + `apksigner` with the release key, then checks the signer against `EXPECTED_CERT_SHA256` and that the APK's contents didn't change) and `release` (attestation, release notes, publish).

- Never give the `build` job a secret, and never run Gradle or check out the repo in `sign`. New build checks (tests, lint, APK checks) go in `build`.
- `EXPECTED_CERT_SHA256` in `build.yml` must match the fingerprint in `SECURITY.md` and `README.md`; the build fails if it doesn't.
- No Gradle cache in any workflow (`cache-disabled: true`): a cache saved by a failed run used to break every later build.
- `settings.gradle.kts` lists only `google()` and `mavenCentral()` for plugins, not `gradlePluginPortal()`: the portal serves different files for the Kotlin plugin markers, which made dependency verification fail whenever Maven Central had a hiccup. Don't add it back unfiltered (see the comment in that file).

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "CameraCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.