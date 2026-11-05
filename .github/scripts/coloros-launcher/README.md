# OnePlus System Launcher (ColorOS / OxygenOS 16 home screen) generators

`com.android.launcher` 16.4.30 is ColorOS's own home screen: Launcher3/Quickstep plus OPPO's window shell, built
against the full Android framework as a system app. Off ColorOS it needs two kinds of help, both generated here so a
new version is a re-run, not hand edits:

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
J=extensions/coloroslauncher/src/main/java
# the phone's boot classpath (adb pull every $BOOTCLASSPATH entry, see ../xiaomi-standins) in boot/
java -Xmx6g -cp "$CP" $X/Missing.java boot <app.apk> work/
find $J -mindepth 1 -maxdepth 1 ! -name org -exec rm -rf {} +      # keep the runtime package (org/ungoogled/...)
python3 $L/gen.py work/ $J "OnePlus System Launcher" --apk <app.apk> --runtime org.ungoogled.coloroslauncher
python3 $L/hidden.py boot <app.apk> $J patches/src/main/kotlin/org/ungoogled/patches/coloroslauncher/ColorOsLauncherMembers.kt
```

Run the stand-ins first: `hidden.py` reads the extension to know which classes and members the stand-ins declare.
Always run the ART verifier sweep afterwards: 0 rejected classes.

Emulator notes: with SwiftShader (no host GPU) the launcher's window blurs make frames take seconds -- turn
"Allow window-level blurs" off (`settings put global disable_window_blurs 1`) and keep the animation scales at 1 when
testing; `adb shell input swipe` releases with no velocity, so open the drawer with a swipe longer than half the screen.
