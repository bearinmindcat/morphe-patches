# Sony (Xperia) stand-in generator

Off Xperia, Sony's apps are refused at install because they require one of Sony's system libraries (`uses-library`
`com.sony.device`, `com.sonyericsson.idd`, ...); once that is optional (`makeLibrariesOptional()`,
`samsung/OptionalLibraries.kt`), an app dies on the first Sony class it calls without checking for it
(NoClassDefFoundError). The generator is the Xiaomi one (`../xiaomi-standins/gen.py`, same `Missing.java`), run with
this directory's `known.py` (Sony's answers). The stand-ins carry Sony's names and signatures only, no Sony code; on an
Xperia the system's own classes load first, so they never run there.

**Check how the app finds a Sony library before generating anything.** Sony's apps usually probe their optional
libraries by class name (`Class.forName`, then `getMethod`) and keep the feature off when the probe fails:

- Album 9.9.A.0.20 needs **no stand-ins**: its `DependencyManager` checks every Sony library (DLNA, photo analyzer,
  Play Anywhere, illumination, DRM stream, display panel, Idd, ...) by class name, and the Walkman media database by
  package. A stand-in would make the probe answer "present" and turn on a feature whose service does not exist. It only
  required `com.sony.device`, which it names nowhere in its code.
- Xperia Home 15 calls `com.sonyericsson.idd.api.Idd.addEvent` directly in three places (grid changes, model errors),
  so it needs the stand-in: `Idd` is Sony's usage-data client, and the stand-in drops every event (a void method's
  default body). Its own probe (`IddManager.isIddSupported`) is answered "unsupported" by the patch, so the launcher
  builds no events at all.

Generate only the classes an app calls unguarded: pass `--only` with exactly those classes if the app also references
probed ones.

`known.py` beyond the defaults: nothing yet (`ONLY` = Sony's three package roots; Idd needs no special answer).

## Use

```bash
X=.github/scripts/xiaomi-standins; Y=.github/scripts/sony-standins
# 1. what the app uses that the phone lacks (boot classpath pulled once per emulator image, see ../xiaomi-standins)
java -Xmx4g -cp "$CP" $X/Missing.java boot <app.apk> work/
# 2. stand-ins into the extension (aapt2 from build-tools 36 on PATH: Kali's can't read Android 15 resource tables)
PATH=~/android-sdk/build-tools/36.0.0:$PATH python3 $Y/gen.py work/ extensions/sony<app>/src/main/java "Xperia <App>" --apk <app.apk>
```

Things to know:

- Xperia Home's resource table uses aapt2's 16-bit entry offsets for every type. Any resource change (even the
  manifest) makes Morphe rewrite the table, and its writer leaves the entries of a type with an odd entry count
  misaligned, which Android refuses at install ("RES_TABLE_TYPE_TYPE entries start at unaligned address").
  `evenResourceEntryCounts()` (`patches/.../sony/EvenEntryCounts.kt`) adds one unused entry to each such type first.
  Check other Sony apps for this with `aapt2 dump resources` / the type chunks' flags (0x02 = offset16).
- Sony's launcher is Launcher3 / Quickstep on Android 15 like the OnePlus System Launcher: its hidden-API lists come
  from `../coloros-launcher/hidden.py` (with the app name as fifth argument) and the port reuses that port's helpers.

Always run the ART verifier sweep afterwards: 0 rejected classes.
