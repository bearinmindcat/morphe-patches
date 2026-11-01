# ColorOS (OPPO / OnePlus / realme) stand-in generator

Off ColorOS (and OnePlus's OxygenOS and realme UI, which are ColorOS underneath), OPPO's apps call framework classes
only those ROMs have and die with NoClassDefFoundError at launch, most often on `com.oplus.content.OplusFeatureConfigManager`,
`com.oplus.os.OplusBuild` or `com.oplus.wrapper.os.SystemProperties`:

- `com.oplus.*` framework classes: `OplusBuild`, `OplusFeatureConfigManager`, zoom/flexible/split-screen window
  managers, `LinearmotorVibrator` + `WaveformEffect`, the "smooth round rect" graphics classes (`OplusCanvas`,
  `OplusPath`, `OplusOutline` and their adapters), blur (`ViewRootManager`), dynamic frame rate, ...
- `com.oplus.wrapper.*`: ColorOS 14+ wrappers of Android's hidden API (`SystemProperties`, `UserHandle`, `Trace`,
  `ServiceManager`, `IWindowManager`, `ViewTreeObserver` insets, `Settings`, `StatusBarManager`, ...).
- older names the bundled libraries still try: `com.color.*`, `com.oppo.*`, `com.oplus.inner.*`.
- ColorOS's additions inside `android.*` (`OplusBaseConfiguration`, `OplusWindowManager`, `OplusActivityManager`,
  `OplusSystemProperties`, `android.os.olc.OlcManager`, ...), and HeyTap SDK pieces an app references without bundling.

Unlike MagicOS (Honor) and LG's ROM, **ColorOS apps name no framework resources**: OPPO's UI library (COUI) is bundled
in every app with its resources, so there is nothing to rewrite in layouts or styles, and COUI's day/night themes work
off ColorOS. They also add **no members to Android's classes** (Missing.java's members.txt lists only `sun.misc` and
newer-Android reflection calls). A port is therefore mostly stand-ins.

The generator is the Xiaomi one (`../xiaomi-standins/gen.py`, same `Missing.java`), run with this directory's
`known.py` (ColorOS's answers) and `runtime/` (`ColorOS`). The stand-ins carry OPPO's names and signatures only, no OPPO
code; on an OPPO / OnePlus / realme phone the framework's own classes load first, so they never run there. Samsung,
Xiaomi, Honor, LG and RedMagic output is unchanged (the shared generator only gained opt-in `FACTORIES` and skips
members named like Java keywords, which no other vendor's apps have).

`known.py` beyond the defaults:

- **No ColorOS version**: `OplusBuild.getOplusOSVERSION()` / `ColorBuild.getColorOSVERSION()` and
  `OplusBuild$VERSION.SDK_VERSION` answer 0, so version-gated ColorOS paths (system animations, services, telemetry)
  stay off; `OplusFeatureConfigManager.hasFeature` answers false (gen.py's default). The device name is `Build.MODEL`.
- **System properties**: the `SystemProperties` wrappers (`com.oplus.wrapper.os`, `android.os.OplusSystemProperties`,
  `com.oplus.inner.os` / `com.color.inner.os` `SystemPropertiesWrapper`) answer with the phone's own values
  (`runtime/ColorOS.prop`), so ColorOS-only ones (`ro.build.version.oplusrom`, `persist.sys.oplus.region`,
  `ro.oplus.pipeline.region`, ...) are empty and the app takes its own default.
- **Smooth round rects**: `OplusCanvas.drawSmoothRoundRect`, `OplusPath[Adapter].addSmoothRoundRect` and
  `OplusOutline[Adapter].setSmoothRoundRect` draw Android's plain round rect on the Canvas / Path / Outline the stand-in
  was constructed around (`ColorOS.wrap` / `wrapped`), so COUI widgets that take this path still get their shapes.
- `IWindowManager$Stub.asInterface` gives `ColorOS.absent(...)`, a stand-in whose calls answer "absent" except the
  phone's stable density and "has a navigation bar"; zoom-window state is "no zoom window" (never null);
  `OplusBezierInterpolator` is Android's `PathInterpolator`; user ids, settings reads and slot subscription ids come from
  Android's public API; the USB environment helpers report Android's shared storage and no SD card.
- `FACTORIES`: OPPO's managers hand out instances through `getDefault`, `get(Class)`, `getOplusPackageManager`, ... as
  well as gen.py's default factory names; those return a stand-in object instead of null.

## Use

```bash
X=.github/scripts/xiaomi-standins; C=.github/scripts/coloros-standins
# 1. what the app uses that the phone lacks (boot classpath pulled once per emulator image, see ../xiaomi-standins)
java -Xmx4g -cp "$CP" $X/Missing.java boot <app.apk> work/
# 2. stand-ins + runtime (ColorOS) into the extension
python3 $C/gen.py work/ extensions/coloros<app>/src/main/java "OnePlus <App>" --apk <app.apk> --runtime org.ungoogled.coloros<app>
```

Then in the patch: `extendWith` the extension (`patches/.../coloroscalculator/` is the template), plus what the app
needs from the shared helpers:

- `exportDynamicReceivers(runtime)` (`patches/.../coloros/ColorOsReceivers.kt`): ColorOS's apps are system apps there
  and register broadcast receivers at run time without RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED (or with flags 0);
  an installed app targeting Android 14+ gets a SecurityException for that (OnePlus Photos dies on it at start; COUI's
  status-bar "tap to scroll up" receiver and the NearX Track SDK have such calls in every app). The calls go to the
  runtime `ColorOS.registerReceiver(Context, ...)`, which registers them as exported, as Android did before Android 14.
- `makeLibrariesOptional()` (`samsung/OptionalLibraries.kt`) for a required `uses-library` / `uses-native-library`
  (OnePlus Photos requires `libOpenCL.so`: most phones' vendor partitions have it, the emulator does not).

Measured with the union of the surveyed apps' stand-ins + `makeLibrariesOptional` + `exportDynamicReceivers` (no app
code changed): Calculator, Compass, Clock, Recorder, My Files, Notes and Photos open their main screens on a Google
Android 15 emulator; Screen recording opens its settings but asks to enable "AppPlatform" (its own check for
`com.oplus.appplatform`, a ColorOS system app); Weather dies in its native weather-effects engine (SIGSEGV on the
emulator); what each needs beyond that is app-specific.

Things to know:

- Off ColorOS the apps keep their own consent screens (Compass: "Agree and continue / Disagree" before any network);
  the ColorOS system "statement page" (`com.coloros.bootreg.activity.statementpage`) that some apps open for their
  privacy notice does not exist -- the app's call fails (Calculator catches it).
- Telemetry bundled in most apps: OPPO's DCS statistics (`com.oplus.statistics`, sends to ColorOS's system service,
  absent elsewhere) and the NearX Track SDK (`com.oplus.nearx.track`, uploads to OPPO's servers when its own gates and
  cloud config allow). Measure per uid (`dumpsys netstats`, mAppUidStatsMap) before and after using a port, and
  report it.
- OPPO's OpenID / StdID (DUID, GUID, OUID from `android.app.OplusNotificationManager.getStdid`,
  `com.android.id.impl.IdProviderImpl`) answer "" off ColorOS. Never invent them: a server that insists on them is a
  no-go (no faked device identity).

Always run the ART verifier sweep afterwards: 0 rejected classes.
