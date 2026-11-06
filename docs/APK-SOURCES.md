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
- **Patch the downloaded file:** in Morphe Manager, choose the APK from your phone's storage instead of letting
  Manager download one.
- **APKMirror:** on the release page, pick the variant for your phone (arm64-v8a, or universal / noarch) and download it.
- **Bundle** means the version is published as a split APK set (APKMirror `.apkm`). Give Manager the whole
  `.apkm`; it merges the parts into one APK before patching. Don't pull a single APK out of it.
- **Firmware only** means the app was never published as an APK. You take it out of the vendor's official firmware
  with [`tools/firmware`](../tools/firmware/README.md), which downloads only the part of the firmware you need.
- **From a phone that has it:** `adb shell pm path <package>` lists the app's APK files; `adb pull <path>` copies
  each one off the phone. Keep all of them if there is more than one (a bundle).
- **Check what you downloaded:** `aapt dump badging <file.apk> | head -1` shows the package and version, and
  `apksigner verify --print-certs <file.apk>` shows who signed it. It should be the phone maker (or Google for
  Google's apps), never an unknown key.

## Launchers

| App | Package | Version | Where to get it |
|---|---|---|---|
| ASUS Launcher | `com.asus.launcher` | 11.0.0.76_240920 | [APKMirror](https://www.apkmirror.com/apk/zenui-asus-computer-inc/asus-launcher/asus-launcher-11-0-0-76_240920-release/) |
| Honor Home | `com.hihonor.android.launcher` | 14.0.0.210 | [APKMirror](https://www.apkmirror.com/apk/honor/honor-home/honor-home-14-0-0-210-release/) |
| HyperOS Launcher | `com.miui.home` | RELEASE-7.50.06.2372-06261924 | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-launcher/xiaomi-system-launcher-release-7-50-06-2372-06261924-release/) (listed as "Xiaomi System Launcher"; the newer 8.x builds have no app code and can't be patched) |
| Moto App Launcher | `com.motorola.launcher3` | 15 | [APKMirror](https://www.apkmirror.com/apk/motorola-mobility-llc/moto-app-launcher/moto-app-launcher-15-release/) (the release has two variants; take the first, "moto-app-launcher-15") |
| Nothing Launcher | `com.nothing.launcher` | 4.0.20 | [APKMirror](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-launcher/nothing-launcher-4-0-20-release/). Bundle. Needs Android 16 or newer. |
| One UI Home | `com.sec.android.app.launcher` | 18.0.07.59 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-experience-home-touchwiz-home/samsung-one-ui-home-18-0-07-59-release/) |
| OnePlus Launcher (OxygenOS 11) | `net.oneplus.launcher` | 5.2.66 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-launcher-2/oneplus-launcher-2-5-2-66-release/) |
| OnePlus System Launcher | `com.android.launcher` | 16.4.30 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/system-launcher-5/oneplus-system-launcher-16-4-30-release/) |
| Xperia Home | `com.sonymobile.launcher` | 15 | [APKMirror](https://www.apkmirror.com/apk/sony-mobile-communications/xperia-home-3/xperia-home-15-release/) |

## Samsung

| App | Package | Version | Where to get it |
|---|---|---|---|
| Samsung Calculator | `com.sec.android.app.popupcalculator` | 12.4.00.13 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/calculator-samsung/samsung-calculator-12-4-00-13-release/) |
| Samsung Calendar | `com.samsung.android.calendar` | 12.8.01.0 (Android 15+), 12.5.06.5 (Android 13 and 14) | [APKMirror 12.8.01.0](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/calendar-samsung-2/samsung-calendar-12-8-01-0-release/), [APKMirror 12.5.06.5](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/calendar-samsung-2/samsung-calendar-12-5-06-5-release/) |
| Samsung Clock | `com.sec.android.app.clockpackage` | 12.4.02.38 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-clock/samsung-clock-12-4-02-38-release/) |
| Samsung Contacts | `com.samsung.android.app.contacts` | 16.3.00.87 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-contacts-3/samsung-contacts-16-3-00-87-release/) |
| Samsung Email | `com.samsung.android.email.provider` | 6.2.13.1 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-email/samsung-email-6-2-13-1-release/) |
| Samsung Gallery | `com.sec.android.gallery3d` | 15.9.01.7 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-gallery/samsung-gallery-15-9-01-7-release/) |
| Good Lock | `com.samsung.android.goodlock` | 3.0.16.6 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/good-lock-2018/samsung-good-lock-3-0-16-6-release/) (not the older "Good Lock" app from 2016, a different listing) |
| Samsung Health | `com.sec.android.app.shealth` | 7.1.3.001, 7.00.6.011 | [APKMirror 7.1.3.001](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/s-health/samsung-health-7-1-3-001-release/) (APKMirror labels it beta), [APKMirror 7.00.6.011](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/s-health/samsung-health-7-00-6-011-release/) |
| Samsung Health Monitor | `com.samsung.android.shealthmonitor` | 1.5.3.011 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-health-monitor-2/samsung-health-monitor-1-5-3-011-release/) (the phone app, not the Wear OS one) |
| Home Up | `com.samsung.android.app.homestar` | 18.0.00.24 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/home-up/home-up-18-0-00-24-release/) (runs together with the One UI Home from this bundle) |
| Samsung Keyboard | `com.samsung.android.honeyboard` | 5.9.40.75, 5.9.40.48, 5.9.40.12, 5.9.40.11, 5.9.40.6, 5.9.30.97, 5.9.30.95, 5.9.30.90 | APKMirror: [5.9.40.48](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-40-48-release/), [5.9.40.12](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-40-12-release/), [5.9.40.11](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-40-11-release/), [5.9.40.6](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-40-6-release/), [5.9.30.97](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-30-97-release/), [5.9.30.95](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-30-95-release/), [5.9.30.90](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-honey-board/samsung-keyboard-5-9-30-90-release/). 5.9.40.75 is not on APKMirror: copy it off a Samsung phone that has it. |
| Samsung Messages | `com.samsung.android.messaging` | 16.3.21.1 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-messages/samsung-messages-16-3-21-1-release/) |
| Samsung Modes and Routines | `com.samsung.android.app.routines` | 5.1.03.1 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/bixby-routines/samsung-modes-and-routines-5-1-03-1-release/) |
| Samsung Music | `com.sec.android.app.music` | 16.2.45.1 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/music-samsung-electronics-co-ltd/samsung-music-16-2-45-1-release/) |
| Samsung My Files | `com.sec.android.app.myfiles` | 15.2.03.2 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-my-files/samsung-my-files-15-2-03-2-release/) |
| Samsung Notes | `com.samsung.android.app.notes` | 4.4.48.15 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-notes/samsung-notes-4-4-48-15-release/) |
| Samsung Phone | `com.samsung.android.dialer` | 16.3.52.1 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-phone-3/samsung-phone-16-3-52-1-release/) |
| Samsung Photo Editor | `com.sec.android.mimage.photoretouching` | 3.9.04.6 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-photo-editor/samsung-photo-editor-3-9-04-6-release/) |
| Samsung Reminder | `com.samsung.android.app.reminder` | 12.8.00.62 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-reminder/samsung-reminder-12-8-00-62-release/) |
| Samsung Screen Recorder | `com.sec.app.screenrecorder` | 4.0.23 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/record-screen/samsung-record-screen-4-0-23-release/) |
| Sound Assistant | `com.samsung.android.soundassistant` | 8.1.00.6 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/soundassistant/sound-assistant-8-1-00-6-release/) |
| Samsung Video Player | `com.samsung.android.video` | 7.3.47.1 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-video-player/samsung-video-player-7-3-47-1-release/) |
| Samsung Voice Recorder | `com.sec.android.app.voicenote` | 21.5.91.33 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-voice-recorder/samsung-voice-recorder-21-5-91-33-release/) |
| Samsung Weather | `com.sec.android.daemonapp` | 1.7.30.50 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-weather/samsung-weather-widget-1-7-30-50-release/) |
| Wonderland | `com.samsung.android.wonderland.wallpaper` | 1.6.22 | [APKMirror](https://www.apkmirror.com/apk/samsung-electronics-co-ltd/wonderland/samsung-wonderland-1-6-22-release/) |

## Xiaomi

| App | Package | Version | Where to get it |
|---|---|---|---|
| Xiaomi Calendar | `com.android.calendar` | 18.0.2.0 | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/mi-calendar/xiaomi-calendar-18-0-2-0-release/) |
| Xiaomi Compass | `com.miui.compass` | 17.1.4.1 | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-compass/xiaomi-compass-17-1-4-1-release/) |
| Xiaomi Gallery | `com.miui.gallery` | 4.3.1.15-global | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-gallery/xiaomi-gallery-4-3-1-15-global-release/) |
| Xiaomi Gallery Editor | `com.miui.mediaeditor` | 2.10.40.4.8-global | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/gallery-editor/xiaomi-gallery-editor-2-10-40-4-8-global-release/) |
| Xiaomi Notes | `com.miui.notes` | 3.2.7.9 | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-notes/xiaomi-notes-3-2-7-9-release/) |
| Xiaomi Recorder | `com.android.soundrecorder` | 7.7.9 | Not on APKMirror. [APKPure, 7.7.9](https://d.apkpure.com/b/APK/com.android.soundrecorder?versionCode=707090) (direct download of that version) |
| Xiaomi Scanner | `com.xiaomi.scanner` | 15.9.78 | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-qr-scanner-plus/xiaomi-scanner-15-9-78-release/) |
| Xiaomi Screen Recorder | `com.miui.screenrecorder` | 4.16.2.9.1 | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-screen-recorder/xiaomi-screen-recorder-4-16-2-9-1-release/) |
| Xiaomi Weather | `com.miui.weather2` | G-17.0.3.26 | [APKMirror](https://www.apkmirror.com/apk/xiaomi-inc/miui-weather/weather-by-xiaomi-g-17-0-3-26-release/) (the Global "G-" build; the newer 17.1.x builds only run on HyperOS) |

## Nothing

| App | Package | Version | Where to get it |
|---|---|---|---|
| Nothing Gallery | `com.nothing.gallery` | 3.1.3.1019 | [APKMirror](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-gallery/nothing-gallery-3-1-3-1019-release/). Bundle. |
| Nothing Recorder | `com.nothing.soundrecorder` | 4.0.0 | [APKMirror](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-recorder/nothing-recorder-4-0-0-release/) |
| Nothing Screenshot | `com.nothing.ntscreenshot` | 14.0.0 | [APKMirror](https://www.apkmirror.com/apk/nothing-technology-limited/nothing-screenshot/nothing-screenshot-14-0-0-release/) |

Nothing Launcher is listed under [Launchers](#launchers).

## Honor

| App | Package | Version | Where to get it |
|---|---|---|---|
| Honor Calculator | `com.hihonor.calculator` | 12.0.1.217 | [APKMirror](https://www.apkmirror.com/apk/honor/calculator-40/calculator-40-12-0-1-217-release/) |
| Honor Calendar | `com.hihonor.calendar` | 13.3.0.345 | [APKMirror](https://www.apkmirror.com/apk/honor/agenda-4/agenda-4-13-3-0-345-release/) (listed as "Agenda") |
| Honor Clock | `com.hihonor.deskclock` | 12.0.1.196 | [APKMirror](https://www.apkmirror.com/apk/honor/clock-33/clock-33-12-0-1-196-release/) |
| Honor Compass | `com.hihonor.compass` | 14.0.1.174 | [APKMirror](https://www.apkmirror.com/apk/honor/compass-15/compass-14-0-1-174-release/) |
| Honor Files | `com.hihonor.filemanager` | 10.0.0.641 | [APKMirror](https://www.apkmirror.com/apk/honor/files-35/files-10-0-0-641-release/) |
| Honor Notes | `com.hihonor.notepad` | 18.0.4.817 | [APKMirror](https://www.apkmirror.com/apk/honor/notes-18/notes-18-0-4-817-release/) |
| Honor Recorder | `com.hihonor.soundrecorder` | 14.0.1.197 | [APKMirror](https://www.apkmirror.com/apk/honor/recorder-15/recorder-14-0-1-197-release/) |
| Honor Screen recording | `com.hihonor.screenrecorder` | 14.0.1.181 | [APKMirror](https://www.apkmirror.com/apk/honor/screen-recording-4/screen-recording-4-14-0-1-181-release/) |
| Honor Video Player | `com.hihonor.hnvideoplayer` | 8.0.0.032 | [APKMirror](https://www.apkmirror.com/apk/honor/video-player-11/video-player-8-0-0-032-release/) |
| Honor Weather | `com.hihonor.android.totemweather` | 18.1.2.311 | [APKMirror](https://www.apkmirror.com/apk/honor-information-technology-co-limited/honor-weather/honor-weather-18-1-2-311-release/) |

Honor Home is listed under [Launchers](#launchers).

## RedMagic

| App | Package | Version | Where to get it |
|---|---|---|---|
| RedMagic Game Space | `cn.nubia.gamelauncher` | 15.0.000.001.2512060958 | **Firmware only** (steps below). Not on APKMirror; APKPure only has a 2019 build (1.2.07), which the patch doesn't support. |

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

| App | Package | Version | Where to get it |
|---|---|---|---|
| LG Health | `com.lge.lifetracker` | 5.60.16 | [APKMirror](https://www.apkmirror.com/apk/lgelectronics/lg-health/lg-health-5-60-16-release/) |
| LG QuickMemo+ | `com.lge.qmemoplus` | 10.0.30 | [APKMirror](https://www.apkmirror.com/apk/lgelectronics/quickmemo/quickmemo-10-0-30-release/) |

## ColorOS (OnePlus / OPPO)

| App | Package | Version | Where to get it |
|---|---|---|---|
| OnePlus Calculator | `com.oneplus.calculator` | 17.0.0 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-calculator/oneplus-calculator-17-0-0-release/) |
| OnePlus Clock | `com.oneplus.deskclock` | 16.19.8 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-clock/oneplusclock-16-19-8-release/) |
| OnePlus Compass | `com.coloros.compass2` | 17.0.0 | [APKMirror](https://www.apkmirror.com/apk/oppo/compass-5/coloros-compass-17-0-0-release/) |
| OnePlus Contacts | `com.android.contacts` | 16.80.15 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-contacts-4/) (pick 16.80.15; not the Xiaomi app of the same package name) |
| OnePlus My Files | `com.oneplus.filemanager` | 16.12.4 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-file-manager/oneplus-my-files-16-12-4-release/) |
| OnePlus Notes | `com.oneplus.note` | 16.6.14 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-notes/oneplus-notes-16-6-14-release/) |
| OnePlus Photos | `com.oneplus.gallery` | 16.45.2 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-gallery/oneplus-photos-16-45-2-release/) |
| OnePlus Recorder | `com.oneplus.soundrecorder` | 16.17.6 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-recorder/oneplus-recorder-16-17-6-release/) |
| OnePlus Screen Recording | `com.oplus.screenrecorder` | 16.5.12 | [APKMirror](https://www.apkmirror.com/apk/coloros/screen-recording-5/coloros-screen-recording-16-5-12-release/) |
| OnePlus Weather | `net.oneplus.weather` | 17.6.30 | [APKMirror](https://www.apkmirror.com/apk/oneplus-ltd/oneplus-weather/oneplus-weather-17-6-30-release/) |

Both OnePlus launchers are listed under [Launchers](#launchers).

## Sony

| App | Package | Version | Where to get it |
|---|---|---|---|
| Sony Album | `com.sonyericsson.album` | 9.9.A.0.20 | [APKMirror](https://www.apkmirror.com/apk/sony-mobile-communications/album/album-9-9-a-0-20-release/) |

Xperia Home is listed under [Launchers](#launchers).

## Other apps

| App | Package | Version | Where to get it |
|---|---|---|---|
| Google Maps | `com.google.android.apps.maps` | 26.36.04.973607363 | [APKMirror](https://www.apkmirror.com/apk/google-inc/maps/google-maps-26-36-04-973607363-release/). Bundle. |
| Waze | `com.waze` | 5.27.0.1, 5.26.90.901, 5.26.5.0, 5.24.5.0 | APKMirror: [5.27.0.1](https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-27-0-1-release/), [5.26.90.901](https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-26-90-901-release/), [5.26.5.0](https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-26-5-0-release/), [5.24.5.0](https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-24-5-0-release/). 5.27.0.1, 5.26.90.901 and 5.26.5.0 are bundles. |
| AllTrails | `com.alltrails.alltrails` | 26.3.20 | Source not recorded (the patches were made with the split APKs of a phone that had this version). If your phone has 26.3.20, copy it off with `adb pull` (see above). |
| Gmail | `com.google.android.gm` | 2026.11.02.1012472355.Release | [APKMirror](https://www.apkmirror.com/apk/google-inc/gmail/) (pick 2026.11.02.1012472355). Bundle. |
| PdfCraft | `io.github.artcraftandroid.pdfcraft` | any | [GitHub releases](https://github.com/artcraft-android/pdfcraft-android/releases) or [F-Droid](https://f-droid.org/en/packages/io.github.artcraftandroid.pdfcraft/) |
