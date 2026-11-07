# OnePlus System Launcher (ColorOS / OxygenOS home screen) generators

`com.android.launcher` 16.4.30 (Android 13 and newer) and 15.6.24 (Android 11 and newer) are ColorOS's own home
screen: Launcher3/Quickstep plus OPPO's window shell, built against the full Android framework as a system app. Off
ColorOS they need two kinds of help, both generated here so a new version is a re-run, not hand edits:

- `gen.py` -- **stand-ins** for the classes the phone does not have: ColorOS's framework (`com.oplus.*`,
  `com.oplus.wrapper.*`, ... -- the shared ColorOS generator with `../coloros-standins/known.py`) and Android classes
  Android 15 lacks (`android.window.DesktopModeFlags`, `com.android.internal.protolog.ProtoLog`, ...). `known.py` here
  adds what the launcher needs beyond "absent": ColorOS's work dispatcher (`com.oplus.dispatch`) runs on Android's
  threads (`Dispatch`), its render-thread animators on the UI thread (`RenderAnimators`), its wrappers of hidden API
  answer with Android's public calls, partition paths are paths that do not exist, empty Bundles/Rects/lists instead of
  null, constants that are objects of their own class are objects. `overrides/` holds the stand-ins gen.py cannot
  write (an enum, a nested class with a base); they replace the generated files.
- `hidden.py` -- the **hidden Android API** the launcher references, written to
  `patches/.../coloroslauncher/ColorOsLauncherMembers.kt`: members an installed app may not link to (the boot dex
  files' hidden-API flags), members and classes Android 15 lacks that no stand-in declares, calls on system services'
  binder interfaces (they need the system's permissions), constructors of the same kinds, the super-constructor calls
  that can take an open constructor instead, and the Android classes to rebase subclasses onto (binder stubs onto
  Binder, plain listeners onto Object). References through the app's own classes and its own copies of Android classes
  count. The patch sends all of them through the extension's `Members` (reflection where the phone allows, else
  `Fallbacks` or "absent").

## Use

```bash
X=.github/scripts/xiaomi-standins; L=.github/scripts/coloros-launcher
J=extensions/coloroslauncher/src/main/java; K=patches/src/main/kotlin/org/ungoogled/patches/coloroslauncher
# each phone's boot classpath (adb pull every $BOOTCLASSPATH entry, see ../xiaomi-standins): a11/, a15/, a16/, a17/,
# canary/ (the Android canary preview image; its boot jars are DEX 041 containers, which hidden.py reads)
# stand-ins: one set for both versions, each against the oldest Android it runs on (and 15.6.24 against 15 as well)
java -Xmx6g -cp "$CP" $X/Missing.java a15 16.4.30.apk w16a15/
java -Xmx6g -cp "$CP" $X/Missing.java a11 15.6.24.apk w15a11/
java -Xmx6g -cp "$CP" $X/Missing.java a15 15.6.24.apk w15a15/
python3 $L/merge.py work/ w16a15/ w15a11/ w15a15/
find $J -mindepth 1 -maxdepth 1 ! -name org -exec rm -rf {} +      # keep the runtime package (org/ungoogled/...)
python3 $L/gen.py work/ $J "OnePlus System Launcher" --apk 16.4.30.apk --runtime org.ungoogled.coloroslauncher
git checkout $J/org                                                 # gen.py's ColorOS.java template is the generic one
# hidden members: 16.4.30 against Android 15 (its Android 16/17 block was added by hand from the patched APK),
# 15.6.24 against every Android it was checked on (a comma list gives the union)
python3 $L/hidden.py a15 16.4.30.apk $J $K/ColorOsLauncherMembers.kt
python3 $L/hidden.py a11,a15,a16,a17,canary 15.6.24.apk $J $K/ColorOsLauncher15Members.kt "OnePlus System Launcher 15.6.24" \
    --suffix _15 --android "Android 11, 15, 16, 17 or the canary"
```

Run the stand-ins first: `hidden.py` reads the extension to know which classes and members the stand-ins declare.
A stand-in only ever runs where the phone lacks the class (the phone's own loads first), so stand-ins made for
Android 11 change nothing on newer phones. Keep 16.4.30's Android 15 list as committed unless you regenerate its
Android 16/17 block too: a member a stand-in now declares (ProtoLog's) drops out of the Android 15 list but is still
hidden on 16/17. Always run the ART verifier sweep afterwards: 0 rejected classes on every Android in the list.

15.6.24 declares Android 11 but was built for ColorOS 15, so the patch also gives it what Android 11 lacks beyond
hidden members: defaults for resources defined only for Android 12+ (`shared/OlderAndroidResources.kt`), Android
12-15 calls answered with their Android 11 equivalents (`Older.java`, reached through `Members` only where the phone
lacks the call), DeviceConfig reads, which need a system permission on Android 11 (`Calls.java`), and
`INACCESSIBLE_INTERFACES_15` (Android 11's private AnimationHandler.AnimationFrameCallback). The extension's minSdk is
30: the dexer drops version checks below it.

Emulator notes: with SwiftShader (no host GPU) the launcher's window blurs make frames take seconds -- turn
"Allow window-level blurs" off (`settings put global disable_window_blurs 1`) and keep the animation scales at 1 when
testing; `adb shell input swipe` releases with no velocity, so open the drawer with a swipe longer than half the screen.
