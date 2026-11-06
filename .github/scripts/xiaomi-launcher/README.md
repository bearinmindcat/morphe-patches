# HyperOS launcher (Xiaomi's System launcher) generators

`com.miui.home` RELEASE-7.50.06.2372 (HyperOS 2) is Xiaomi's home screen, built against HyperOS's framework as a system
app. Off HyperOS it needs two kinds of help, both generated so a new version is a re-run:

- `gen.py` -- **stand-ins** for the HyperOS framework classes it calls (`miui.os.Build`, `miui.util.FeatureParser`,
  `android.provider.MiuiSettings`, ...): the shared Xiaomi generator (`../xiaomi-standins`), then `overrides/`, the
  stand-ins it cannot answer right: `MiuiSettings` keeps the launcher's home options (stored in Android's System table,
  which an installed app may not write) through the extension's `LocalSettings`; `miui.os.Build` adds `IS_DEV_VERSION`
  (read by reflection and unboxed unchecked); `ToggleManager.getAllToggles` is an empty list, not null; `NewHomeView` is
  a FrameLayout and `AdAction` an `Action`, as the launcher's code expects (the ART verifier rejected two classes).
- `../coloros-launcher/hidden.py` -- the **hidden Android API** it references (written to
  `patches/.../xiaomilauncher/XiaomiLauncherMembers.kt`), as for the other launcher ports.

## Use

```bash
X=.github/scripts/xiaomi-standins; L=.github/scripts/xiaomi-launcher
J=extensions/xiaomilauncher/src/main/java
# the phone's boot classpath (adb pull every $BOOTCLASSPATH entry, see ../xiaomi-standins) in boot/
java -Xmx6g -cp "$CP" $X/Missing.java boot <app.apk> work/
python3 $L/gen.py work/ $J "HyperOS launcher" --apk <app.apk> --runtime org.ungoogled.xiaomilauncher
python3 .github/scripts/coloros-launcher/hidden.py boot <app.apk> $J \
    patches/src/main/kotlin/org/ungoogled/patches/xiaomilauncher/XiaomiLauncherMembers.kt "HyperOS launcher"
```

Run the stand-ins first: `hidden.py` reads the extension to know which classes and members the stand-ins declare. The
extension's runtime (`org/ungoogled/xiaomilauncher`: Members, Fallbacks, LocalSettings, ... from the other launcher ports)
is kept by gen.py (it never overwrites Fallbacks). Always run the ART verifier sweep afterwards: 0 rejected classes.
