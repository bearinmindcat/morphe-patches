# RedMagic (Nubia / ZTE) stand-in generator

Off RedMagic OS, RedMagic's apps call ZTE's framework classes (`com.zte.feature.Feature`, `com.zte.gameassist.*`,
`com.zte.performance.mindsync.*`, `nubia.os.*`, Nubia's `android.util.NubiaConfig` / `android.view.NubiaInput`) and,
as system apps built against Android's own framework, hidden Android members an ordinary app may not use. The generator
is the Xiaomi one (`../xiaomi-standins/gen.py`, same `Missing.java`), run with this directory's `known.py` (RedMagic's
answers) and `runtime/`. The stand-ins carry ZTE's names and signatures only, no ZTE code; on a RedMagic phone the
framework's own classes load first, so they never run there.

- `com.zte.feature.Feature` (ZTE's feature switches, which the apps also look up by reflection) answers through
  `runtime/RedMagic.feature`: the app's `Fallbacks.feature(key)` where the port has an answer (Game Space: which of its
  parts exist off RedMagic OS, e.g. `ZTE_FEATURE_GAMESPACE_CONFIG`), else the caller's default -- no ZTE feature on.
- `runtime/RedMagic` + `RedMagicShims`: the target of `redirectMembers(..., keys)` (`patches/.../samsung/OneUiMembers.kt`)
  for hidden Android members, by reflection under Android's rules for apps; refused or missing members answer "absent"
  (0 / false / null / empty list / the receiver for a builder), with public equivalents for the common ones
  (`Context.getUserId`, `startActivityAsUser`, `getPackageInfoAsUser`, ...).
- `runtime/LocalSettings`: every `Settings.Global/Secure/System` read and write. RedMagic's apps keep their state in
  Android's settings tables; a write an ordinary app may not make is kept in the app's own preferences, announced to
  the app's observers of that setting, and read back from there.

## Use

```bash
X=.github/scripts/xiaomi-standins; R=.github/scripts/redmagic-standins
# 1. what the app uses that the phone lacks (boot classpath pulled once per emulator image, see ../xiaomi-standins)
java -Xmx4g -cp "$CP" $X/Missing.java boot <app.apk> work/
# 2. stand-ins + runtime (RedMagic, RedMagicShims, LocalSettings, Fallbacks) into the extension
python3 $R/gen.py work/ extensions/redmagic<app>/src/main/java "RedMagic <App>" --apk <app.apk> --runtime org.ungoogled.redmagic<app>
```

```bash
# 3. hidden Android members the app references (MEMBER / CLASS lines), against the newest android.jar
python3 $R/nonsdk.py <app.apk> > work/nonsdk.txt
```

The hidden members to redirect are those (`MEMBER` lines, and members of hidden `CLASS`es) plus `members.txt` (members
the phone's Android lacks), as exact keys for `redirectMembers`, never `<init>`. Always run the ART verifier sweep
afterwards: 0 rejected classes.
