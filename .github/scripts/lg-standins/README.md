# LG stand-in generator

Off LG's ROM, LG's apps call framework classes only LG's phones have -- `com.lge.*` system services and helpers
(`LGContext.getLGSystemService`, `RequestPermissionsHelper`, `WindowManagerHelper`, `Features2`, `LGMDMManager`, ...),
LG's optional shared libraries (`com.lge.sui` date/time/colour pickers, `com.lge.lgdynamicactionbar`, `blurengine2`),
classes LG added inside `android.*` (`android.widget.TopButtonLayout`, `RollableDisplayManager`) and members LG added
to Android classes (`NotificationChannel.setHideChannel`, `Configuration.fontTypeIndex`) -- and die with
NoClassDefFoundError / NoSuchMethodError. Their layouts and styles also name LG's framework resource package
(`com.lge`, id 0x02: `Theme.LGE.Default`, its theme attributes, dialog layouts), which does not exist elsewhere.

The generator is the Xiaomi one (`../xiaomi-standins/gen.py`, same `Missing.java`), run with this directory's
`known.py` (LG's answers) and `runtime/`. The stand-ins carry LG's names and signatures only, no LG code; on an LG
phone the framework's own classes load first, so they never run there. Samsung, Xiaomi and Honor output is unchanged.

`known.py` beyond the defaults:

- `SDK_BASES` and `INFLATED_ONLY` as in `../honor-standins`: widgets of LG's UI libraries that only layouts name get
  LayoutInflater's constructors; `SUPERS` gives the ones without an Android namesake a base (lgdynamicactionbar's
  `CoordinatorLayout` -> `FrameLayout`, `AppBarLayout` -> `LinearLayout`, SUI pickers -> `LinearLayout`/`SeekBar`).
- `getLGSystemService` hands out stand-in managers (pen panel, clip tray, OS manager) whose answers say "absent",
  never null; `RequestPermissionsHelper` is Android's own permission request (request code 10000, as LG's).
- `WindowManagerHelper.addView` turns LG's system overlay windows (QuickMemo+'s pen panel) into application panels
  on the calling activity's window, which an app may add without a system permission.
- `Features2.blur_level()` answers "0" (no blur), MDM microphone checks answer "allowed", badged-icon helpers return
  Android's icon.

Runtime classes (`runtime/`, generated into each extension under its own package):

- `Lg`: reflection call/get/set for LG members on Android classes (`redirectLgMembers`), answering through the
  extension's `Fallbacks` first, then common answers (display density, `DatabaseUtils.queryIsEmpty`, hidden on
  Android 15 for target-30 apps), else "absent".
- `LgRes`: `id("type/name")` for resources a port adds (Morphe's bytecode patches cannot see resources added in the
  same run) and `getIdentifier` for `com.lge` names (LG's dialog layouts / FAB drawables / dialog themes -> the
  port's `lg_*` copies or Android's).
- `LgSettings`: `Settings.System.putInt/getInt` without WRITE_SETTINGS (LG apps write their own system keys) -- kept
  in the app's preferences when the system refuses.
- `LgAlertTitle`: the title view of the port's copy of LG's alert dialog layout (carries Android's internal
  alertTitle id, which Morphe cannot encode).

## Use

```bash
X=.github/scripts/xiaomi-standins; L=.github/scripts/lg-standins
# 1. what the app uses that the phone lacks (boot classpath pulled once per emulator image, see ../xiaomi-standins)
java -Xmx4g -cp "$CP" $X/Missing.java boot <app.apk> work/
# 2. stand-ins + runtime (Lg, LgRes, LgSettings, ...) into the extension
python3 $L/gen.py work/ extensions/lg<app>/src/main/java "LG <App>" --apk <app.apk> --runtime org.ungoogled.lg<app>
# 3. LG framework resources the app names, with what Theme.LGE.Default answers (lge-res.apk from an LG firmware
#    dump: system/framework/lge-res/lge-res.apk of the LG V50 ThinQ Android 12 dump on GitHub,
#    leddaz-dump-stash/lge_flashlmdd_dump; signed by LG's platform key 4274243d...)
python3 $L/lgres.py <app.apk> lge-res.apk
```

Then in the patch (`patches/.../lg/`):

- `replaceLgFrameworkReferences(extra, attributes)` (`LgResources.kt`): every `com.lge` reference in the app's XML
  becomes a plain value (`LG_FRAMEWORK_VALUES`, day and night), an Android or port resource
  (`LG_FRAMEWORK_ANSWERS`), or an app attribute `lg_<name>` when the app's own styles set an LG theme attribute.
  LG's themes become the port's `LgDayNight` styles (DeviceDefault.DayNight with LG's colours; LG's
  `colorForeground` is a surface colour, unlike Android's). LG-only layout attributes are dropped unless `attributes`
  converts them (QuickMemo+: `layout_behavior` -> a tag the stand-in CoordinatorLayout reads). Unknown references stop
  the patch, listed.
- `addLgFrameworkLayouts(runtime)` / `addLgFloatingActionButton()`: copies of LG's dialog layouts and FAB drawables
  under `lg_*` names, for `LgRes.getIdentifier`.
- `replaceLgFrameworkIds(runtime, answers)` (`LgFrameworkIds.kt`, bytecode): `0x02xxxxxx` literals in code
  (`LG_FRAMEWORK_CODE_IDS` + the app's own); a `new:` answer is looked up at run time through `LgRes.id`. List them
  with `baksmali d classes.dex -o out; grep -rhE "const(/high16)? [vp][0-9]+, 0x2[0-9a-f]{6}$" out | sort | uniq -c`.
- `redirectLgMembers(lg, shims, extra)` (`LgMembers.kt`) over `samsung/OneUiMembers.kt`'s `redirectMembers()`; pass
  Android members the app may no longer call (hidden API blocklist for its target SDK) in `extra`.
- `makeLibrariesOptional()` (`samsung/OptionalLibraries.kt`) for a required LG `uses-library`.

Hand-written stand-ins (functional SUI pickers and dialogs, lgdynamicactionbar layouts, `TopButtonLayout`) replace
generated ones in the extension; keep a list of them when regenerating. Always run the ART verifier sweep
afterwards: 0 rejected classes.
