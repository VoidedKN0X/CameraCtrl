# CameraCtrl

Camera app in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. Camera2 directly (no CameraX: it hides RAW profiles, physical lenses and the maximum-resolution mode).

## Layout

- `camera/Lenses.kt`: `LensDetector` reads every camera ID and every physical lens of each logical multi-camera, and records what Android reports: RAW profiles (RAW16, RAW12, RAW10, RAW_PRIVATE) with sizes, the maximum-resolution stream map (ULTRA_HIGH_RESOLUTION_SENSOR), JPEG/preview/video sizes, manual ranges. Nothing is hard-coded per phone.
- `camera/CameraController.kt`: one HandlerThread; session per `Setup` (lens, mode, format, RAW profile, max-res); physical lenses through `OutputConfiguration.setPhysicalCameraId`; max-res outputs with `addSensorPixelModeUsed(MAXIMUM_RESOLUTION)` and still requests with `SENSOR_PIXEL_MODE`; video through MediaRecorder on a persistent input surface.
- `camera/Saver.kt`: MediaStore (DCIM/CameraCtrl, Movies/CameraCtrl, IS_PENDING); DNG via `DngCreator` (RAW10/RAW12 unpacked to 16-bit first).
- `ui/camera`: `CameraScreen` (viewfinder, lens row, pro panel, shutter), `ModesScreen` (everything detected, per lens).

## Hard rules

- **No `INTERNET` permission, ever**, and **no location permission** (no GPS in any file).
- **Only standard Android camera modes.** No vendor tags, vendor sessions or phone-maker SDKs.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No CameraX, ML Kit or Play services. No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- The camera is open only while the viewfinder is visible and resumed.
- arm64-v8a only. Every user-facing string goes in `strings.xml`. Settings open only from the profile picture.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "CameraCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.