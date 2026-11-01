# Honor (MagicOS) stand-in generator

Off MagicOS, Honor's apps call framework classes only Honor's ROM has -- the `com.hihonor.android.*` "Ex" wrappers
(`com.hihonor.android.os.SystemPropertiesEx`, `ActivityManagerEx`, `WindowManagerEx$LayoutParamsEx`, ...), HiView
telemetry, MagicOS widgets, leftovers of Huawei's `com.huawei.android.*` -- and die with NoClassDefFoundError at
launch. Their layouts also name MagicOS's framework widgets (`<com.hihonor.android.widget.HwToolbar>`) and theme
attributes of MagicOS's framework resource package (`androidhnext`, id 0x02). Honor phones have Google Play services,
so these apps do not need HMS; what they need is the framework.

The generator is the Xiaomi one (`../xiaomi-standins/gen.py`, same `Missing.java`), run with this directory's
`known.py` (Honor's answers) and `runtime/` (`Magic`: the phone's system properties for `SystemPropertiesEx`). The
stand-ins carry Honor's names and signatures only, no Honor code; on an Honor phone the framework's own classes load
first, so they never run there. `known.py` switches on what MagicOS needs beyond the Xiaomi lessons (all off for
Xiaomi, whose output is unchanged):

- **Framework widgets extend Android's own.** `com.hihonor.android.widget.ListView` extends `android.widget.ListView`,
  `HwToolbar` extends `Toolbar` (`SUPER_RULES`: Hw/Hn prefix dropped, first Android class of that name). Such a
  stand-in keeps every member Android already has (generating "absent" `getAdapter()` would break the list) and calls
  Android's constructors.
- **Widgets named only in XML.** With `--apk` the app's binary XML (layouts, preferences, ...) is read for tags and
  `class=` attributes in the framework's widget packages (`INFLATED_ONLY`); those get stand-ins with LayoutInflater's
  constructors. A widget with no Android namesake is listed as a note -- give it a base in `SUPERS` or an `--extends`
  file (e.g. `TextArrowPreference`, `SubTabWidget`, `HwDialogTitle`, `HnIconVectorDrawable`, a drawable tag).
- `MAGIC_SDK_INT` is 0: no MagicOS API level, so version-gated MagicOS calls and Honor's HiView telemetry stay off.
- `SystemPropertiesEx` answers with the phone's real properties (`RUNTIME.Magic`), so MagicOS-only ones (`msc.*`,
  `ro.build.version.magic`) are empty and the app takes its own defaults.
- `ActionBarEx.setStartIcon/setEndIcon` (MagicOS's toolbar cancel/done icons, view ids android:id/icon1 and icon2 that
  the app looks up) are drawn by `runtime/MagicToolbar`: the toolbar's navigation button and an end button.
  Hiding the start icon leaves a navigation icon the app set itself (a back arrow) alone.
- A known body for a constructor (`Lowner;-><init>(...)V`) is used as is, e.g. the super call to a base class that has
  no constructor of the app's shape (`HwUniversalKeyStoreProvider` -> `java.security.Provider`).
- `HwExpandedAppbarController` (MagicOS's big collapsing title above a list) -> `runtime/MagicAppbar`: the layout the
  port adds (`magic_expanded_appbar`, ids `magic_appbar_layout` / `magic_toolbar` / `magic_collapsing_toolbar`), the
  app's content layout inflated under it and padded to start below it. It does not collapse.
- `HnIconVectorDrawableUtils.setLayerColor` (MagicOS's layered vector icon, which the app's bundled uikit
  `HnIconVectorDrawable` wraps): the first layer's colour tints the icon, whose paths carry a grey placeholder fill.
- `runtime/Magic` also answers what ports redirect to it: `setDecorFitsSystemWindows` (targetSdk 35+ apps are edge to
  edge on Android 15: pads android:id/content by the bars it overlaps), `getIdentifier` for `androidhnext` names
  (Theme.Magic styles -> the port's MagicDayNight ones, dimens -> the app's copy, ids -> the app's `magic_<name>`),
  `themeColor` (a colour attribute the port answered with a plain colour).

## Use

```bash
X=.github/scripts/xiaomi-standins; H=.github/scripts/honor-standins
# 1. what the app uses that the phone lacks (boot classpath pulled once per emulator image, see ../xiaomi-standins)
java -Xmx4g -cp "$CP" $X/Missing.java boot <app.apk> work/
# 2. stand-ins + runtime (Magic) into the extension
python3 $H/gen.py work/ extensions/honor<app>/src/main/java "Honor <App>" --apk <app.apk> --runtime org.ungoogled.honor<app>
# 3. MagicOS framework resources the app's XML names, with MagicOS's own answers (androidhnext APK: APKMirror,
#    "androidhnext" by Honor, signed by Honor's platform key 483013ce...)
python3 $H/magicres.py <app.apk> androidhnext.apk
```

Then in the patch (`patches/.../honor/`):
- `replaceMagicFrameworkReferences()` (`MagicResources.kt`): every `?0x0201...` theme attribute and `@0x02...`
  resource of `androidhnext` in the app's XML becomes the value MagicOS's Theme.Magic gives it. Without it the first
  layout using one fails to inflate ("Failed to resolve attribute"), and Morphe cannot re-encode a file that names
  it. The shared table holds the values that are the same on every phone configuration (sizes, margins, text sizes);
  `magicres.py` lists the rest (night-mode colours, framework drawables and styles), which a port answers in `extra`
  -- the helper stops the patch, listing them, until it does.
- `followMagicDayNight()` (`MagicThemes.kt`): MagicOS gives each activity whose `hnc-theme` meta-data names
  Theme.Magic its own day/night theme; off MagicOS the declared Android theme (light Material, even dark Holo) stays,
  so action bars, popups and bar icons don't follow dark mode. Re-bases those activities on Android's DayNight theme.
- `replaceMagicFrameworkIds(runtime, answers)` (`MagicFrameworkIds.kt`, bytecode): code can name androidhnext
  resources by id too (`getColor(0x02050177)`, `new ContextThemeWrapper(context, Theme.Magic)`, a theme attribute
  it resolves) and then dies on Resources$NotFoundException. Rewrites those literals to the app's own resources (the
  bundled Honor UI kit has most of MagicOS's names) or Android's; a theme attribute gets the resource MagicOS answers
  with, and `Theme.resolveAttribute` goes through the runtime `Magic.resolveAttribute`. List them with
  `baksmali d classes.dex -o out; grep -rhE "const(/high16)? [vp][0-9]+, 0x2[0-9a-f]{6}$" out | sort | uniq -c`
  (0x2000000 is a flag, not a resource).
  `redirectMagicFrameworkLookups(runtime)` does the same for framework interpolators looked up by name
  (`getIdentifier(name, "interpolator", "androidhnext")`); other framework lookups keep answering 0, because Honor's
  libraries (hnanimscene, the UI kit) use them to tell whether they run on MagicOS.
- `replaceMagicIconDrawables(sources)` (`MagicIcons.kt`): drawables of MagicOS's framework icon class
  (`com.hihonor.android.widget.HnIconVectorDrawable`, framework icon sources; notification icons above all) become
  Android `<vector>`s from the app's copies of the sources (the UI kit carries them under MagicOS's names), coloured
  as the drawable asked; a plain vector also inflates in SystemUI. A source the app's build shrank to `<x/>` stops the
  patch: draw that one yourself (into that placeholder).
- `replaceMagicInterpolators()` (`MagicInterpolators.kt`): `<cubicBezierInterpolator>` (MagicOS-only tag) becomes
  Android's `<pathInterpolator>`.
- Layouts that SystemUI or a launcher inflates (notification `RemoteViews`) may name only Android's widgets: swap
  `com.hihonor.android.widget.HwTextView`/`ImageView` for `TextView`/`ImageView` there. App widgets built from the
  app's own views cannot work on other launchers (Clock turns its providers off).
- Runtime `Magic` (generated into each extension) also has `toolbarButton` (MagicOS's ActionBarEx start/end buttons,
  ids android:id/icon1 / icon2), `setNavigationIcon` (tints the UI kit's grey placeholder icons) and `onMagicOs()`
  (MagicOS framework on the boot class path) for the few places a port swaps a MagicOS-only component.
- A stand-in whose members name a class the app itself bundles (Recorder ships a stub `HwToolbar`) is skipped by the
  generator; write it by hand, with a copy of that class in the extension (Morphe merges it into the app's).
- `replaceMagicFrameworkLiterals(answers, magic)` (`MagicLiterals.kt`): app code names androidhnext ids too
  (`getTheme().resolveAttribute(0x020100ca, ...)`, Theme.Magic for a ContextThemeWrapper, interpolators); each
  literal becomes the port's answer (the app's own copy of the resource, which Honor's bundled UI kit ships, or an
  Android id), and Theme.resolveAttribute calls go to the runtime `Magic.resolveAttribute`, which answers a plain
  resource put in place of an attribute with its value. Unknown literals stop the patch, listed.
- `convertMagicInterpolators()` (`MagicResources.kt`): MagicOS's `<cubicBezierInterpolator>` tag -> `<pathInterpolator>`.
- `useMagicWidgets(widgets)` (`MagicResources.kt`): MagicOS's LayoutInflater turns plain tags (`<ImageView>`,
  `<TextView>`, `<Toolbar>`, ...) into its Hw widgets, which the app then casts to; off MagicOS each such tag in the
  app's layouts is renamed to the stand-in the port names.
- `followMagicDayNight()` reads Huawei's older `hwc-theme` meta-data as well as `hnc-theme` (Calendar, Files, Notes).
- `makeLibrariesOptional()` (`samsung/OptionalLibraries.kt`) for a required Honor `uses-library`.
- Vendor members on Android classes (none in Honor's own apps so far; YOYO and Honor Health have a few): redirect them
  like `xiaomi/MiuiMembers.kt` does, on top of `samsung/OneUiMembers.kt`'s `redirectMembers()`.

Always run the ART verifier sweep afterwards: 0 rejected classes.

Merge note (2026-11-02): Compass/Clock/Recorder and Calendar/Files/Notes were ported in parallel and built
equivalent helpers under different names -- `MagicFrameworkIds` ~ `MagicLiterals`, `MagicIcons` ~ Notes' icon
handling, `replaceMagicInterpolators` ~ `convertMagicInterpolators`, `Magic.toolbarButton` ~ `MagicToolbar`. Each
app keeps the helper it was tested with (its extension carries its own generated `Magic`). The runtime `Magic`
template keeps the interpolator-only `getIdentifier` (answering other framework lookups breaks Honor libraries'
MagicOS detection -- Recorder). Unify them before the next Honor port: pick one of each pair, re-patch every Honor
app and check the patched output is unchanged (or re-test it).
