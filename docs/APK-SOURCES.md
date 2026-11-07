# Where to get each APK

Morphe Manager downloads APKs through its download providers (APKMirror and others). Many apps in this bundle
are phone makers' own apps that those providers don't carry, or don't carry in the version the patches are made
for. This page lists, for every app in the bundle, the exact version each patch supports and where that version
is published.

**This repository does not host or share any vendor APK.** Every link goes to the vendor's own download or to a
public APK mirror.

## How to use this page

- **Get the exact version listed.** The patches find their way around each app by its code; Manager marks other
  versions as unsupported, and they usually fail to patch.
- **Check the Android column first.** It is the oldest Android the original APK accepts. On an older phone Morphe
  Manager refuses the file with **"The selected file is not a valid APK"**: Manager reads the file with Android's
  own package parser, which rejects an APK made for a newer Android. The file is fine; the phone is too old for it.
  APKMirror shows the same requirement as "Android 15+" etc. on the release page.
- **Patch the downloaded file:** in Morphe Manager, choose the APK from your phone's storage instead of letting
  Manager download one.
- **APKMirror:** on the release page, under "Download", pick the variant marked **APK** for your phone (arm64-v8a, or
  universal / noarch; any dpi). When a release lists several APK variants of the version, any of them works unless
  the row says otherwise. When a release only has **BUNDLE** variants, download the `.apkm` the row names.
- **Bundle** in a row means APKMirror publishes that version only as a split APK set (`.apkm`). Manager takes `.apkm`,
  `.apks` and `.xapk` files as they are and merges the parts itself; no conversion is needed. Don't unzip it or pick
  a single APK out of it. Rows without "Bundle" are a single `.apk`.
- **Firmware only** means the app was never published as an APK. You take it out of the vendor's official firmware
  with [`tools/firmware`](../tools/firmware/README.md), which downloads only the part of the firmware you need.
- **From a phone that has it:** `adb shell pm path <package>` lists the app's APK files; `adb pull <path>` copies
  each one off the phone. Keep all of them if there is more than one (a bundle).
- **Check what you downloaded:** `aapt dump badging <file.apk> | head -1` shows the package and version, and
  `apksigner verify --print-certs <file.apk>` shows who signed it. It should be the phone maker (or Google for
  Google's apps), never an unknown key.

## Launchers

The launcher ports are tested on Android 15 and newer; ASUS Launcher, Honor Home, both OnePlus launchers, One UI Home 16.0.09.35 and Moto App Launcher 13 also on Android 13, OnePlus System Launcher 15.6.24 also on Android 11.

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| ASUS Launcher | `com.asus.launcher` | 11.0.0.76_240920 | 13+ | [APKMirror](https://www.apkmirror.com/apk/zenui-asus-computer-inc/asus-launcher/asus-launcher-11-0-0-76_240920-release/) |
| Honor Home | `com.hihonor.android.launcher` | 14.0.0.210 | 10+ | [APKMirror](https://www.apkmirror.com/apk/honor/honor-home/honor-home-14-0-0-210-release/) |
| HyperOS Launcher | `com.miui.home` | RELEASE-7.50.06.2372-06261924 | 14+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-launcher/xiaomi-system-launcher-release-7-50-06-2372-06261924-release/) (listed as "Xiaomi System Launcher"; the newer 8.x builds have no app code and can't be patched) |
| Moto App Launcher | `com.motorola.launcher3` | 15, 13 | 15+ (15), 13+ (13) | [APKMirror 15](https://www.apkmirror.com/apk/motorola-mobility-llc/moto-app-launcher/moto-app-launcher-15-release/) (the release has two variants; take the first, "moto-app-launcher-15"), [APKMirror 13](https://www.apkmirror.com/apk/motorola-mobility-llc/moto-app-launcher/moto-app-launcher-13-release/) (for Android 13 and 14; on Android 15 and newer take 15. Two variants with the same version; take the first, "moto-app-launcher-13" from April 2023) |
| Nothing Launcher | `com.nothing.launcher` | 4.0.20, 3.3.14 | 16+ (4.0.20), 15+ (3.3.14) | [APKMirror 4.0.20](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-launcher/nothing-launcher-4-0-20-release/), [APKMirror 3.3.14](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-launcher/nothing-launcher-3-3-14-release/) (for Android 15; on Android 16 and newer take 4.0.20). Bundle. |
| One UI Home | `com.sec.android.app.launcher` | 18.0.07.59, 16.0.09.35 | 15+ (18.0.07.59), 13+ (16.0.09.35) | [APKMirror 18.0.07.59](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-experience-home-touchwiz-home/samsung-one-ui-home-18-0-07-59-release/), [APKMirror 16.0.09.35](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-experience-home-touchwiz-home/samsung-one-ui-home-16-0-09-35-release/) (for Android 13 and 14; on Android 15 and newer take 18.0.07.59) |
| OnePlus Launcher (OxygenOS 11) | `net.oneplus.launcher` | 5.2.66 | 10+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-launcher-2/oneplus-launcher-2-5-2-66-release/) |
| OnePlus System Launcher | `com.android.launcher` | 16.4.30, 15.6.24 | 13+ (16.4.30), 11+ (15.6.24) | [APKMirror 16.4.30](https://www.apkmirror.com/apk/oneplus-ltd/system-launcher-5/oneplus-system-launcher-16-4-30-release/), [APKMirror 15.6.24](https://www.apkmirror.com/apk/oneplus-ltd/system-launcher-5/oneplus-system-launcher-15-6-24-release/) (for Android 11 and 12; on Android 13 and newer take 16.4.30) |
| Xperia Home | `com.sonymobile.launcher` | 15 | 15+ | [APKMirror](https://www.apkmirror.com/apk/sony-mobile-communications/xperia-home-3/xperia-home-15-release/) |

## Samsung

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| Samsung Calculator | `com.sec.android.app.popupcalculator` | 12.4.00.13 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/calculator-samsung/samsung-calculator-12-4-00-13-release/) |
| Samsung Calendar | `com.samsung.android.calendar` | 12.8.01.0, 12.5.06.5 | 15+ (12.8.01.0), 13+ (12.5.06.5) | [APKMirror 12.8.01.0](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/calendar-samsung-2/samsung-calendar-12-8-01-0-release/), [APKMirror 12.5.06.5](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/calendar-samsung-2/samsung-calendar-12-5-06-5-release/) |
| Samsung Capture | `com.samsung.android.app.smartcapture` | 6.2.12 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-capture/samsung-capture-6-2-12-release/) (screen recorder and screenshot editor; on Android 9-14 use Samsung Screen Recorder; on a Galaxy phone also turn on "Change package name") |
| Samsung Clock | `com.sec.android.app.clockpackage` | 12.4.02.38 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-clock/samsung-clock-12-4-02-38-release/) |
| Samsung Contacts | `com.samsung.android.app.contacts` | 16.3.00.87 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-contacts-3/samsung-contacts-16-3-00-87-release/) |
| Samsung Email | `com.samsung.android.email.provider` | 6.2.13.1 | 10+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-email/samsung-email-6-2-13-1-release/) |
| Samsung Gallery | `com.sec.android.gallery3d` | 15.9.01.7 | 11+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-gallery/samsung-gallery-15-9-01-7-release/) |
| Good Lock | `com.samsung.android.goodlock` | 3.0.16.6 | 12+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/good-lock-2018/samsung-good-lock-3-0-16-6-release/) (not the older "Good Lock" app from 2016, a different listing) |
| Samsung Health | `com.sec.android.app.shealth` | 7.1.3.001, 7.00.6.011 | 10+ | [APKMirror 7.1.3.001](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/s-health/samsung-health-7-1-3-001-release/) (APKMirror labels it beta), [APKMirror 7.00.6.011](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/s-health/samsung-health-7-00-6-011-release/) |
| Samsung Health Monitor | `com.samsung.android.shealthmonitor` | 1.5.3.011 | 12+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-health-monitor-2/samsung-health-monitor-1-5-3-011-release/) (the phone app, not the Wear OS one) |
| Home Up | `com.samsung.android.app.homestar` | 18.0.00.24 | 16+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/home-up/home-up-18-0-00-24-release/) (runs together with the One UI Home from this bundle). The original needs Android 16, so Manager on Android 15 refuses it; the patch makes it run on Android 15, so there patch it on a computer with [Morphe Desktop](https://morphe.software) and install the result |
| Samsung Internet | `com.sec.android.app.sbrowser` | 31.0.0.79 | 11+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-internet-for-android/samsung-browser-31-0-0-79-release/) (listed as Samsung Browser; runs on any phone; on a Galaxy phone the built-in copy has to be uninstalled first, or use a root mount install) |
| Samsung Keyboard | `com.samsung.android.honeyboard` | 5.9.40.75, 5.9.40.48, 5.9.40.12, 5.9.40.11, 5.9.40.6, 5.9.30.97, 5.9.30.95, 5.9.30.90 | 13+ | APKMirror: [5.9.40.48](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-40-48-release/), [5.9.40.12](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-40-12-release/), [5.9.40.11](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-40-11-release/), [5.9.40.6](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-40-6-release/), [5.9.30.97](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-30-97-release/), [5.9.30.95](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-30-95-release/), [5.9.30.90](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-30-90-release/). 5.9.40.75 is not on APKMirror: copy it off a Samsung phone that has it. |
| Samsung Messages | `com.samsung.android.messaging` | 16.3.21.1 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-messages/samsung-messages-16-3-21-1-release/) |
| Samsung Modes and Routines | `com.samsung.android.app.routines` | 5.1.03.1 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/bixby-routines/samsung-modes-and-routines-5-1-03-1-release/) |
| Samsung Music | `com.sec.android.app.music` | 16.2.45.1 | 9+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/music-samsung-electronics-co-ltd/samsung-music-16-2-45-1-release/) |
| Samsung My Files | `com.sec.android.app.myfiles` | 15.2.03.2 | 14+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-my-files/samsung-my-files-15-2-03-2-release/) |
| Samsung Notes | `com.samsung.android.app.notes` | 4.4.48.15 | 10+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-notes/samsung-notes-4-4-48-15-release/) |
| One Hand Operation + | `com.samsung.android.sidegesturepad` | 9.3.13.0 | 14+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/one-hand-operation/samsung-one-hand-operation-9-3-13-0-release/) |
| Samsung Phone | `com.samsung.android.dialer` | 16.3.52.1 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-phone-3/samsung-phone-16-3-52-1-release/) |
| Samsung Photo Editor | `com.sec.android.mimage.photoretouching` | 3.9.04.6 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-photo-editor/samsung-photo-editor-3-9-04-6-release/) |
| Samsung Reminder | `com.samsung.android.app.reminder` | 12.8.00.62 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-reminder/samsung-reminder-12-8-00-62-release/) |
| Samsung Screen Recorder | `com.sec.app.screenrecorder` | 4.0.23 | 9+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/record-screen/samsung-record-screen-4-0-23-release/) |
| Sound Assistant | `com.samsung.android.soundassistant` | 8.1.00.6 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/soundassistant/sound-assistant-8-1-00-6-release/) |
| Samsung Video Player | `com.samsung.android.video` | 7.3.47.1 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-video-player/samsung-video-player-7-3-47-1-release/) |
| Samsung Voice Recorder | `com.sec.android.app.voicenote` | 21.5.91.33 | 15+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-voice-recorder/samsung-voice-recorder-21-5-91-33-release/) |
| Samsung Weather | `com.sec.android.daemonapp` | 1.7.30.50 | 11+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-weather/samsung-weather-widget-1-7-30-50-release/) |
| Wonderland | `com.samsung.android.wonderland.wallpaper` | 1.6.22 | 11+ | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/wonderland/samsung-wonderland-1-6-22-release/) |

### On an older Samsung Galaxy

The patches are for phones that aren't Samsung. On a Galaxy, install Samsung's original APK instead: it updates the
built-in app and keeps Samsung account sign-in and the links between Samsung apps. A patched copy is signed with a
different key, so it can't replace the built-in app and installs next to it as a separate app.

Newest original versions whose minimum Android lets them install (from the apps' version histories; not tested on a
real One UI 4.1 / 5.1 phone, so if one misbehaves, go back to the version Galaxy Store offers):

| App | Android 12 (One UI 4.x) | Android 13 (One UI 5.x) |
|---|---|---|
| Samsung Gallery | 15.9.01.7 | 15.9.01.7 |
| Samsung Notes | 4.4.48.15 | 4.4.48.15 |
| Samsung Music | 16.2.45.1 | 16.2.45.1 |
| Samsung Weather | 1.7.30.50 | 1.7.30.50 |
| Good Lock | 3.0.16.6 | 3.0.16.6 |
| Samsung Health Monitor | 1.5.3.011 | 1.5.3.011 |
| Samsung Keyboard | 5.6.10.48 | 5.9.40.75 |
| Samsung Clock | 12.2.00.55 | 12.2.16.1 |
| Samsung Calendar | 12.5.00.28 | 12.6.00.37 |
| Samsung Reminder | 12.5.00.11 | 12.5.04.3 |
| Samsung Voice Recorder | 21.5.43.02 | 21.5.43.02 |
| Samsung My Files | 14.2.00.2 | 15.0.04.6 |
| Samsung Contacts / Phone | 15.7.55 | 15.9.70 |
| Samsung Messages | 15.7.04.3 | 16.0.11.27 |

Each Samsung app carries its own copy of Samsung's UI library, so a newer app brings that app's newer design; the rest
of the phone stays on its One UI version.

## Xiaomi

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| Xiaomi Calendar | `com.android.calendar` | 18.0.2.0 | 8+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/mi-calendar/xiaomi-calendar-18-0-2-0-release/) |
| Xiaomi Compass | `com.miui.compass` | 17.1.4.1 | 8+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-compass/xiaomi-compass-17-1-4-1-release/) |
| Xiaomi Gallery | `com.miui.gallery` | 4.3.1.15-global | 12+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-gallery/xiaomi-gallery-4-3-1-15-global-release/) |
| Xiaomi Gallery Editor | `com.miui.mediaeditor` | 2.10.40.4.8-global | 13+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/gallery-editor/xiaomi-gallery-editor-2-10-40-4-8-global-release/) |
| Xiaomi Notes | `com.miui.notes` | 3.2.7.9 | 10+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-notes/xiaomi-notes-3-2-7-9-release/) |
| Xiaomi Recorder | `com.android.soundrecorder` | 7.7.9 | 13+ | Not on APKMirror. [APKPure, 7.7.9](https://d.apkpure.com/b/APK/com.android.soundrecorder?versionCode=707090) (direct download of that version) |
| Xiaomi Scanner | `com.xiaomi.scanner` | 15.9.78 | 10+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-qr-scanner-plus/xiaomi-scanner-15-9-78-release/) |
| Xiaomi Screen Recorder | `com.miui.screenrecorder` | 4.16.2.9.1 | 10+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-screen-recorder/xiaomi-screen-recorder-4-16-2-9-1-release/) |
| Xiaomi Weather | `com.miui.weather2` | G-17.0.3.26 | 7+ | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-weather/weather-by-xiaomi-g-17-0-3-26-release/) (the Global "G-" build; the newer 17.1.x builds only run on HyperOS) |

## Nothing

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| Nothing Gallery | `com.nothing.gallery` | 3.1.4.1030, 3.1.3.1019 | 14+ | [APKMirror 3.1.4.1030](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-gallery/nothing-gallery-3-1-4-1030-release/), [APKMirror 3.1.3.1019](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-gallery/nothing-gallery-3-1-3-1019-release/). Bundle. |
| Nothing Recorder | `com.nothing.soundrecorder` | 4.0.0 | 14+ | [APKMirror](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-recorder/nothing-recorder-4-0-0-release/) |
| Nothing Screenshot | `com.nothing.ntscreenshot` | 14.0.0 | 14+ | [APKMirror](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-screenshot/nothing-screenshot-14-0-0-release/) |

Nothing Launcher is listed under [Launchers](#launchers).

## Honor

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| Honor Calculator | `com.hihonor.calculator` | 12.0.1.217 | 10+ | [APKMirror](https://www.apkmirror.com/apk/honor/calculator-40/calculator-40-12-0-1-217-release/) |
| Honor Calendar | `com.hihonor.calendar` | 13.3.0.345 | 12+ | [APKMirror](https://www.apkmirror.com/apk/honor/agenda-4/agenda-4-13-3-0-345-release/) (listed as "Agenda") |
| Honor Clock | `com.hihonor.deskclock` | 12.0.1.196 | 10+ | [APKMirror](https://www.apkmirror.com/apk/honor/clock-33/clock-33-12-0-1-196-release/) |
| Honor Compass | `com.hihonor.compass` | 14.0.1.174 | 10+ | [APKMirror](https://www.apkmirror.com/apk/honor/compass-15/compass-14-0-1-174-release/) |
| Honor Files | `com.hihonor.filemanager` | 10.0.0.641 | 11+ | [APKMirror](https://www.apkmirror.com/apk/honor/files-35/files-10-0-0-641-release/) |
| Honor Notes | `com.hihonor.notepad` | 18.0.4.817 | 12+ | [APKMirror](https://www.apkmirror.com/apk/honor/notes-18/notes-18-0-4-817-release/) |
| Honor Recorder | `com.hihonor.soundrecorder` | 14.0.1.197 | 10+ | [APKMirror](https://www.apkmirror.com/apk/honor/recorder-15/recorder-14-0-1-197-release/) |
| Honor Screen recording | `com.hihonor.screenrecorder` | 14.0.1.181 | 10+ | [APKMirror](https://www.apkmirror.com/apk/honor/screen-recording-4/screen-recording-4-14-0-1-181-release/) |
| Honor Video Player | `com.hihonor.hnvideoplayer` | 8.0.0.032 | 12+ | [APKMirror](https://www.apkmirror.com/apk/honor/video-player-11/video-player-8-0-0-032-release/) |
| Honor Weather | `com.hihonor.android.totemweather` | 18.1.2.311 | 10+ | [APKMirror](https://www.apkmirror.com/apk/honor-information-technology-co-limited/honor-weather/honor-weather-18-1-2-311-release/) |

Honor Home is listed under [Launchers](#launchers).

## RedMagic

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| RedMagic Game Space | `cn.nubia.gamelauncher` | 15.0.000.001.2512060958 | 13+ | **Firmware only** (steps below). Not on APKMirror; APKPure only has a 2019 build (1.2.07), which the patch doesn't support. |

Game Space is only published inside RedMagic's phone firmware. The supported build comes from RedMagic's own
firmware for the **RedMagic 9 Pro (NX769S)**, version **REDMAGICOS10.0.7MR1_GB** (Android 15, "North America, Asia
and other regions"), linked from RedMagic's support page
[REDMAGIC 9 Pro](https://help.redmagic.gg/hc/en-us/articles/29517159560857-REDMAGIC-9-Pro):

`https://rom.download.nubia.com/Europe%26Asia/NX769S/REDMAGICOS10.0.7MR1_GB/update.zip` (6.3 GB full OTA)

You don't need to download all of it. With [`tools/firmware/payload.py`](../tools/firmware/payload.py) (about
3.6 GB is downloaded):

```sh
URL="https://rom.download.nubia.com/Europe%26Asia/NX769S/REDMAGICOS10.0.7MR1_GB/update.zip"
python3 payload.py "$URL" list                         # optional: shows the partitions
python3 payload.py "$URL" extract system system.img    # Game Space is in the system partition (ext4)
debugfs -R "ls -l /system/priv-app/GameSpace" system.img
debugfs -R "dump /system/priv-app/GameSpace/GameSpace.apk GameSpace.apk" system.img
```

Then check it before patching:

```sh
aapt dump badging GameSpace.apk | head -1     # cn.nubia.gamelauncher, versionName 15.0.000.001.2512060958
apksigner verify --print-certs GameSpace.apk  # CN=ZTE, OU=Smartphone Software Dept., SHA-256 81cd58da1f975ed863319c8e42b7dd22d9bf3ac31f9caedb68e679b75ba80215
sha256sum GameSpace.apk                       # 46b6e8c1257e6b7c231ad5cede4f4488912fe6840895b65a66a00b08785ba847
```

More detail (EROFS images, other partitions): [`tools/firmware/README.md`](../tools/firmware/README.md).

## LG

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| LG Gallery | `com.android.gallery3d` | 9.60.32 | 11+ | [APKMirror](https://www.apkmirror.com/apk/lgelectronics/lg-gallery/lg-gallery-9-60-32-release/) |
| LG Health | `com.lge.lifetracker` | 5.60.16 | 7+ | [APKMirror](https://www.apkmirror.com/apk/lgelectronics/lg-health/lg-health-5-60-16-release/) |
| LG QuickMemo+ | `com.lge.qmemoplus` | 10.0.30 | 10+ | [APKMirror](https://www.apkmirror.com/apk/lgelectronics/quickmemo/quickmemo-10-0-30-release/) |

## ColorOS (OnePlus / OPPO)

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| OnePlus Camera | `com.oplus.camera` | any (the one on your phone) | 11+ | Your own phone: patch the installed camera in Morphe Manager (root mount install; a mount needs the exact version the phone has). Checked: 6.070.215 and 6.020.951 (Android 15+), 5.020.905 (Android 11+), also on [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-camera-2/) |
| OnePlus Calculator | `com.oneplus.calculator` | 17.0.0 | 11+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-calculator/oneplus-calculator-17-0-0-release/) |
| OnePlus Clock | `com.oneplus.deskclock` | 16.19.8 | 9+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-clock/oneplusclock-16-19-8-release/) |
| OnePlus Compass | `com.coloros.compass2` | 17.0.0 | 10+ | [APKMirror](https://www.apkmirror.com/apk/oppo/compass-5/coloros-compass-17-0-0-release/) |
| OnePlus Contacts | `com.android.contacts` | 16.80.15 | 15+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-contacts-4/oneplus-contacts-16-80-15-release/) (not the Xiaomi app with the same package name) |
| OnePlus Documents | `andes.oplus.documentsreader` | 17.1.26 | 15+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/quick-preview/documents-17-1-26-release/documents-17-1-26-android-apk-download/) (the international build, uploaded October 24, 2026; the release's other 17.1.26 variant is the China build, which works too) |
| OnePlus My Files | `com.oneplus.filemanager` | 16.12.4 | 10+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-file-manager/oneplus-my-files-16-12-4-release/) |
| OnePlus Notes | `com.oneplus.note` | 16.6.14 | 12+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-notes/oneplus-notes-16-6-14-release/) |
| OnePlus Photos | `com.oneplus.gallery` | 16.45.2, 15.33.40 | 13+ (16.45.2), 11+ (15.33.40) | [APKMirror 16.45.2](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-gallery/oneplus-photos-16-45-2-release/), [APKMirror 15.33.40](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-gallery/oneplus-photos-15-33-40-release/) (for Android 11 and 12; on Android 13 and newer take 16.45.2) |
| OnePlus Recorder | `com.oneplus.soundrecorder` | 16.17.6 | 10+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-recorder/oneplus-recorder-16-17-6-release/) |
| OnePlus Screen Recording | `com.oplus.screenrecorder` | 16.5.12 | 12+ | [APKMirror](https://www.apkmirror.com/apk/coloros/screen-recording-5/coloros-screen-recording-16-5-12-release/) |
| OnePlus Weather | `net.oneplus.weather` | 17.6.30 | 13+ | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-weather/oneplus-weather-17-6-30-release/) |

Both OnePlus launchers are listed under [Launchers](#launchers).

## Sony

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| Sony Album | `com.sonyericsson.album` | 9.9.A.0.20 | 4.4+ | [APKMirror](https://www.apkmirror.com/apk/sony-mobile-communications/album/album-9-9-a-0-20-release/) |
| Sony Music | `com.sonyericsson.music` | 9.4.16.A.1.17 | 11+ | [APKMirror](https://www.apkmirror.com/apk/sony-corporation/walkman-music/sony-music-9-4-16-a-1-17-release/) |
| Xperia Keyboard | `com.sonyericsson.textinput.uxp` | 8.1.A.0.12 | 4.4+ | [APKMirror](https://www.apkmirror.com/apk/sony-mobile-communications-inc/xperia-keyboard/xperia-keyboard-8-1-a-0-12-release/) |

Xperia Home is listed under [Launchers](#launchers).

## Other apps

| App | Package | Version | Android | Where to get it |
|---|---|---|---|---|
| Google Maps | `com.google.android.apps.maps` | 26.36.04.973607363 | 12L+ | [APKMirror](https://www.apkmirror.com/apk/google-inc/maps/google-maps-26-36-04-973607363-release/). Bundle: take the "universal, Android 12L+" one, the build the patches are made for (the page also has other Android 9/10 builds). |
| Waze | `com.waze` | 5.27.0.1, 5.26.90.901, 5.26.5.0, 5.24.5.0 | 12L+ | APKMirror: [5.27.0.1](https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-27-0-1-release/), [5.26.90.901](https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-26-90-901-release/), [5.26.5.0](https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-26-5-0-release/), [5.24.5.0](https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-24-5-0-release/). All are bundles; take the one marked Android 12L+. |
| AllTrails | `com.alltrails.alltrails` | 26.3.20 | 9+ | Source not recorded (the patches were made with the split APKs of a phone that had this version). If your phone has 26.3.20, copy it off with `adb pull` (see above). |
| Gmail | `com.google.android.gm` | 2026.11.02.1012472355.Release | 12L+ | [APKMirror](https://www.apkmirror.com/apk/google-inc/gmail/gmail-2026-11-02-1012472355-release-release/). Bundle: take the "universal, Android 12L+" one, the build the patches are made for (the page also has Android 7/10 builds). |
| PdfCraft | `io.github.artcraftandroid.pdfcraft` | any | 10+ | [GitHub releases](https://github.com/artcraft-android/pdfcraft-android/releases) or [F-Droid](https://f-droid.org/en/packages/io.github.artcraftandroid.pdfcraft/) |
