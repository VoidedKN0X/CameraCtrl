# CameraCtrl

**A private, offline camera that saves the sensor's own RAW data, on every lens your phone lets apps use.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/CameraCtrl?label=download)](https://github.com/VoidedKN0X/CameraCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) ![Offline](https://img.shields.io/badge/internet-none-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

CameraCtrl is a camera app for people who want the real sensor data. It asks your phone which camera modes it offers, lens by lens, and lets you shoot RAW (DNG) on every lens that has it, alongside or instead of JPEG. It has no internet permission and no location access, so nothing leaves your phone and no photo ever carries your position.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/CameraCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

## Features

- **RAW from the sensor:** saves DNG files with the sensor's own data, before the phone's processing. Open them in [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) or any RAW editor
- **Every RAW profile, detected on your phone:** RAW16, RAW12 and RAW10 are all turned into DNG; RAW_PRIVATE is listed when a lens has it. Nothing is hard-coded per phone model
- **Maximum resolution mode:** on lenses where Android offers it (its "ultra high resolution sensor" mode), CameraCtrl shoots the sensor's full pixel count, for example 50 or 200 MP, in RAW or JPEG
- **Every lens:** the phone's AUTO multi-camera with pinch zoom, plus each physical lens on its own (ultra-wide, main, telephoto, front) where the phone allows it
- **Camera modes page:** everything your phone reports for each lens: sensor size and pixel count, colour filter, RAW profiles and sizes, maximum resolution mode, JPEG and video sizes, 10-bit HDR, ISO and shutter ranges, closest focus and Camera2 level
- **Pro controls:** manual ISO and shutter speed (down to the lens's longest exposure), manual focus, white balance presets and exposure compensation
- **Tap to focus**, rule-of-thirds grid, self-timer (3 or 10 s), torch
- **Flash for photos:** off, automatic or on, on lenses with a flash. With automatic exposure the camera measures with a pre-flash first; with manual exposure the flash fires once in the photo
- **Volume keys as the shutter:** either volume key takes a photo, or starts and stops a video, while the viewfinder is open (can be turned off)
- **Exposure bracketing:** 3 photos ±1 or ±2 EV, or 5 photos ±1 EV, in one press (the **BKT** chip), on lenses with manual exposure. The shutter speed changes first; ISO takes over past a quarter second
- **Histogram, focus peaking and a level:** a live brightness histogram, violet edges on what's sharp while you focus by hand, and a horizon line that turns violet when the phone is straight
- **Zero shutter lag and Ultra HDR photos**, on lenses that offer them: the camera can save the moment you pressed, and JPEGs can carry an HDR gain map for brighter highlights on HDR screens
- **Automatic lens switching for close-ups:** on a single lens, when the subject is closer than that lens can focus, CameraCtrl switches to the lens that focuses closest and back again. It goes by each lens's closest focus as your phone reports it, nothing per phone model
- **Night mode:** takes a burst of 6 (handheld) or 8 (tripod) photos, lines them up and merges them into one JPEG with much less noise. It's a multi-frame merge, not one long exposure, and the app says so; RAW stays in Photo mode
- **Interval shooting and timelapses:** a photo every 1 s to 10 min, for a number of shots, a length of time or until you stop. Keep every photo, or let CameraCtrl build a timelapse video (30 fps, HEVC or H.264) on the phone. The run goes on with the screen dimmed or off
- **QR code and barcode scanner:** reads QR, Data Matrix, Aztec, PDF417 and the common barcodes on the phone. Copy or share what it says; links open only when you tap Open, with the full address shown; Wi-Fi codes open Android's own add-network screen (or copy the password)
- **Video** in 4K or 1080p, HEVC or H.264, with sound if you allow the microphone. **24, 30 or 60 fps**, **stabilisation** and **10-bit HDR (HLG)**, each where the lens offers it at that resolution; the line under the viewfinder shows what is used
- **A photo for other apps:** when a chat, form or other app asks for "take a photo", CameraCtrl takes it, lets you check it (Use photo or Retake) and hands it to that app only; it isn't saved in your library
- **From the lock screen without unlocking:** the lock-screen camera (for example a double press of the power key) opens CameraCtrl over the lock screen with only the viewfinder and the photos you take then. Settings, the camera modes page, QR scanning, interval shooting and your older photos need the phone unlocked
- **Settings export and import:** save your photo, viewfinder and video settings to a file and use it again later or on another phone. The app lock and privacy settings are never in it
- **Formats:** RAW + JPEG, RAW only or JPEG only, with JPEG quality 90 to 100 %
- **No location, ever:** CameraCtrl doesn't have the location permission
- **App lock** with its own PIN and optional fingerprint unlock, and **Hide in screenshots** / **Hide in recent apps** (all off until you turn them on)
- **English and Dutch:** CameraCtrl follows your phone's language (Dutch when your phone is set to Dutch, English otherwise); there's no language setting in the app
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, high contrast, one violet accent, Space Grotesk and JetBrains Mono

## What your phone allows

Android lets each phone maker decide which camera modes other apps may use. CameraCtrl uses every standard Android mode your phone offers, and only those. Phone makers often keep their own extra modes (such as a 200 MP mode, night mode or their own colour processing) for their own camera app. CameraCtrl doesn't use those private modes. **Camera modes** (the info button in the viewfinder) shows exactly what your phone offers.

## Requirements

- Android 15 or newer, on a 64-bit ARM phone (nearly every phone from the last several years)
- Works on any Android ROM as a normal app: stock Android, LineageOS, GrapheneOS, CalyxOS, /e/OS and the phone makers' own systems. It doesn't need root.
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google libraries, and the app never contacts Google. (The QR scanner uses ZXing, an independent open-source library that only carries a com.google package name.)

## Install

1. Open the [latest release](https://github.com/VoidedKN0X/CameraCtrl/releases/latest) on your phone, scroll down to **Assets** and tap the file that ends in `.apk` (for example `CameraCtrl-v0.1.0.apk`). The "Source code" zip and tar.gz files are not the app; you don't need them.
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings are kept.

**Automatic updates:** on your phone, tap the Obtainium button above. Install [Obtainium](https://github.com/ImranR98/Obtainium) first; the button adds CameraCtrl and installs every new release for you. If the button doesn't open the app, add `https://github.com/VoidedKN0X/CameraCtrl` in Obtainium by hand.

## Getting started

- **Shoot:** tap the shutter. The chip at the top left switches between RAW+JPEG, RAW and JPEG; **MAX RES** appears on lenses that have a maximum resolution mode.
- **Modes:** the row at the bottom: **PHOTO**, **NIGHT**, **VIDEO**, **INTERVAL** and **SCAN**. Night and Interval show their options above the shutter (handheld or tripod; interval, when to stop, photos or video). While an interval run is going, the screen shows the last shot and the progress instead of the preview; **Dim screen** turns it black until you tap it, and **Stop** ends the run (a timelapse video is then finished and saved).
- **Lenses:** the row above the shutter: **AUTO** is the phone's multi-camera (pinch to zoom), the numbers are the single lenses.
- **Pro:** tap **PRO** for ISO, shutter, focus, white balance and EV. **AUTO** gives exposure back to the camera.
- **Camera modes:** the info button at the top shows what each lens offers.
- **Flash:** the flash button at the top cycles off, automatic and on (Photo mode). **BKT** turns on exposure bracketing and picks how many photos.
- **Settings** (profile picture): format, RAW profile, maximum resolution, JPEG quality, timer, grid, shutter sound, volume keys, zero shutter lag, Ultra HDR, close-up switching, histogram, focus peaking, level, video resolution, frame rate, codec, stabilisation and HDR, the settings file, and privacy.

Photos are saved in **DCIM/CameraCtrl**, videos in **Movies/CameraCtrl**.

## Is it safe to install?

- **Open source.** All of CameraCtrl's code is in this repo, under the GPL-3.0 licence. Anyone can read it.
- **Built by GitHub, not on a personal computer.** Every release on the Releases page is built by [the build workflow](.github/workflows/build.yml) on GitHub Actions, straight from the code in this repo. Each build also checks that the app has no network permission and no Google libraries. The signing key is kept away from the build: one job builds the APK without any secrets, and a separate job that runs no code from this repo signs it and checks the signature against the fingerprint below.
- **Always signed with the same key.** Android refuses to install an update signed with a different key over CameraCtrl. The SHA-256 fingerprint of CameraCtrl's signing certificate is:

  ```
  EC:F6:89:5F:2D:0F:7B:C9:1C:12:11:14:DB:3B:22:18:D5:8C:B4:04:48:F7:79:7D:A0:4C:5E:C5:1F:04:1A:1D
  ```

  Advanced users can check it with [AppVerifier](https://github.com/soupslurpr/AppVerifier).

**Check a download.** Every release is built and published by GitHub Actions, never on a personal computer:

- The release notes end with the APK's **SHA-256** checksum. Compare it with `sha256sum CameraCtrl-vX.Y.Z.apk` (or `Get-FileHash` on Windows).
- Each release APK has a signed **build provenance attestation**. With the [GitHub CLI](https://cli.github.com/), `gh attestation verify CameraCtrl-vX.Y.Z.apk -R VoidedKN0X/CameraCtrl` proves it was built by this repo's workflow from the tagged commit.
- The signing-certificate fingerprint above stays the same for every release.

**Permissions.** CameraCtrl has **no internet permission** and **no location permission**.

| Permission | What CameraCtrl uses it for |
| --- | --- |
| Camera | Taking photos and videos. |
| Record audio (optional) | Sound in videos. Asked for when you switch to Video; without it, videos are silent. |
| Use biometric hardware | Optional fingerprint unlock for the app lock. |
| Hide other apps' floating windows over the PIN screen | While the lock or PIN screen is showing, other apps' floating windows are hidden over it. |
| Run foreground service, of the camera type | Only while an interval run or timelapse you started is going: it keeps the camera working with the screen dimmed or off, and shows a notification (if notifications are allowed) with a Stop button. Nothing runs in the background otherwise. |
| App-internal broadcasts (`com.vknxworks.cameractrl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) | Added by AndroidX so that only CameraCtrl itself can send to its own internal receivers. |

See [Privacy](#privacy) for what happens to your data.

## App lock

Turn the app lock on under **Settings > Privacy and security > App lock**. It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, checked by the phone's secure hardware. The PIN is still needed after the phone restarts and at least every 72 hours.
- **The PIN is never stored.** It's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **Forgot your PIN?** It can't be recovered. The lock screen offers to reset CameraCtrl's settings and turn the lock off. Your photos and videos aren't touched.

## Privacy

**Telemetry-free.** CameraCtrl has no analytics, crash reporting, usage stats or tracking of any kind. It has **no internet permission**, works fully offline and never connects to the internet.

- Photos and videos are written straight into your library on the phone. CameraCtrl keeps no copies of its own.
- **No location** is ever saved in your photos: CameraCtrl can't read your location at all.
- The camera is only open while the viewfinder is on screen; it closes when you leave the app.
- The lock-screen camera has no app lock of its own because it shows nothing you took before: only the viewfinder and the photos from that visit. A photo opens in your gallery only after you unlock, and the lock-screen camera closes when the screen turns off. Everything else in CameraCtrl still asks for the app lock.
- A photo taken for another app goes only to that app (to the place it asked for, or as a small picture in the reply) and isn't saved in your library.
- A settings file holds only camera settings (no app lock, no privacy settings) and is saved only where you choose.
- The level uses the phone's motion sensor, which needs no permission, only while the level is on and the viewfinder is open. The one exception is an interval run you start yourself: it keeps the camera until the run ends or you stop it, with Android's foreground-service notice showing that the camera is in use.
- Night mode and the timelapse video are made on the phone. The QR scanner decodes codes on the phone with ZXing; nothing is looked up online, and a link only opens in your browser when you tap Open.
- Settings are in CameraCtrl's private storage. Android backups don't include them, and CameraCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
- The Support, Report a bug, Source code and Licence links on the You page open in your browser, only when you tap them.

## How CameraCtrl is made

CameraCtrl is a one-person project by VoidedKN0X (VKNXWorks). Much of the code is written with the help of AI (Claude, by Anthropic). I decide what the app does, review the changes and use the app myself. Every change is listed in the [changelog](CHANGELOG.md), and you can read every line of code here.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 or newer and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/CameraCtrl.git
   cd CameraCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

**Dependency verification.** Every library and build plugin download is checked against the sha256 checksums in `gradle/verification-metadata.xml`, and the build fails on any mismatch. When you change a dependency, a plugin or the Gradle version, run the **Dependency verification metadata** workflow (Actions tab, *Run workflow*), check the diff of the file it prints and commit it in the same push.

## Support the project

CameraCtrl is free and open source. If it's useful to you, you can support development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). Please report bugs and ideas with the [bug report and feature request forms](https://github.com/VoidedKN0X/CameraCtrl/issues/new/choose). Starring the repo helps too.

Found a security problem? Please don't open a public issue; see [SECURITY.md](SECURITY.md).

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same design.

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CallCtrl](https://github.com/VoidedKN0X/CallCtrl) | Phone app: dialer, call history and in-call screen |
| **CameraCtrl** (this app) | Private camera with RAW (DNG) on every lens |
| [ChatCtrl](https://github.com/VoidedKN0X/ChatCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [ClockCtrl](https://github.com/VoidedKN0X/ClockCtrl) | Alarms, timers, stopwatch and world clock |
| [ContactCtrl](https://github.com/VoidedKN0X/ContactCtrl) | Contacts: browse, edit, import and export |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [DateCtrl](https://github.com/VoidedKN0X/DateCtrl) | Calendar for the calendars on your phone and your accounts |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [GalleryCtrl](https://github.com/VoidedKN0X/GalleryCtrl) | Private, offline gallery with an encrypted vault |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [LijnCtrl](https://github.com/VoidedKN0X/LijnCtrl) | Live De Lijn bus and tram departures |
| [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) | Private, offline photo and video editor |
| [MailCtrl](https://github.com/VoidedKN0X/MailCtrl) | Email for Gmail and Proton Mail |
| [MapsCtrl](https://github.com/VoidedKN0X/MapsCtrl) | Private, offline maps and navigation |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| [ProxCtrl](https://github.com/VoidedKN0X/ProxCtrl) | Manage your Proxmox VE server |
| [RecorderCtrl](https://github.com/VoidedKN0X/RecorderCtrl) | Private, offline voice recorder |
| [RideCtrl](https://github.com/VoidedKN0X/RideCtrl) | Offline maintenance, fuel and mod log for your vehicles |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| [ShutterCtrl](https://github.com/VoidedKN0X/ShutterCtrl) | Remote control and photo transfer for Canon cameras |
| [SoundCtrl](https://github.com/VoidedKN0X/SoundCtrl) | JBL headphones and speakers, without the account |
| [SumCtrl](https://github.com/VoidedKN0X/SumCtrl) | Offline calculator, unit converter and work tools |
| [SynapseCtrl](https://github.com/VoidedKN0X/SynapseCtrl) | Administer your Matrix Synapse server |
| [TalkCtrl](https://github.com/VoidedKN0X/TalkCtrl) | Cardo intercom settings, radio and updates |
| [TapoCtrl](https://github.com/VoidedKN0X/TapoCtrl) | Tapo plugs and bulbs on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| [WakeCtrl](https://github.com/VoidedKN0X/WakeCtrl) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

CameraCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

QR codes and barcodes are decoded with [ZXing](https://github.com/zxing/zxing) core, under the Apache License 2.0.

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).