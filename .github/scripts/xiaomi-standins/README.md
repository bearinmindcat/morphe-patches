# Xiaomi stand-in generator

Off HyperOS/MIUI, Xiaomi apps (and the miuix library they all bundle) call framework classes only Xiaomi's ROM has
(`miui.os.Build`, `miui.util.FeatureParser`, `miui.util.HapticFeedbackUtil`, `com.xiaomi.mimotion.MimotionUtils`,
...) and die with NoClassDefFoundError. These scripts write Java stand-ins for exactly the classes, methods and fields
an app references, answering "absent" (false / 0 / "" / null, a getter's own default argument, a fresh object for a
builder or factory) unless `known.py` has a shared answer (e.g. `miui.os.Build.IS_INTERNATIONAL_BUILD = true`: off
HyperOS the apps behave like Xiaomi's Global build). They carry Xiaomi's names and signatures only, no Xiaomi code;
on a Xiaomi phone the framework's own classes load first, so the stand-ins never run there.

The Samsung generator next door (`samsung-standins/`) is unchanged; this one adds what the Samsung ports learnt:

- `Missing.java` compares the app with the phone's real boot classpath, so it also finds vendor classes inside
  `android.*` (`android.provider.MiuiSettings`, `android.content.res.MiuiConfiguration`) and vendor *members* added to
  Android classes (`ActivityOptions.makeMiuiClipAnimation`, ...) -> `members.txt`, for `redirectMiuiMembers`.
- Builders and factories (`build`, `create*`, `newInstance`, `getInstance`, `obtain`) return an object, not null.
- Superclasses: a stand-in that an app component extends (manifest activity/service/receiver/provider/application/
  backupAgent) extends the matching Android class and implements its abstract methods; `known.SUPERS` and an
  `--extends` file ("Lchild; Lparent;" per line) cover the rest, e.g. a vendor subclass of another vendor class.
- Types named in a catch clause, thrown, or named `...Exception`/`...Error` extend `java.lang.Exception`.
- A vendor class nested in an Android class (`Landroid/x/Foo$Bar;`) becomes a top-level class named `Foo$Bar`.
- Members whose signature names a hidden framework class or one of the app's own classes are skipped and listed
  (they can't be named in the extension's source); hand-write them if the app needs them.

## Use

```bash
# 1. the phone's boot classpath, once per emulator image (Android 15, API 35)
mkdir boot && for p in $(adb shell 'echo $BOOTCLASSPATH' | tr ':' ' ') /system/framework/org.apache.http.legacy.jar; do adb pull $p boot/; done
# 2. what the app uses that the phone lacks
G=~/.gradle/caches/modules-2/files-2.1
CP=$(find $G/com.github.MorpheApp.smali/smali-dexlib2 $G/com.github.MorpheApp.smali/smali-util $G/com.google.guava/guava -name '*.jar' ! -name '*sources*' | tr '\n' ':')
java -Xmx4g -cp "$CP" Missing.java boot <app.apk> work/        # -> types.txt, members.txt, usage.json, abstract.json
# 3. stand-ins + the runtime classes (Miui, MiuiShims, Fallbacks) into the extension
python3 gen.py work/ extensions/xiaomi<app>/src/main/java "Xiaomi <App>" --apk <app.apk> --runtime org.ungoogled.xiaomi<app>
```

Then in the patch: `redirectMiuiMembers("Lorg/ungoogled/xiaomi<app>/Miui;", "Lorg/ungoogled/xiaomi<app>/MiuiShims;")`
(`patches/.../xiaomi/MiuiMembers.kt`), `makeLibrariesOptional()` (`samsung/OptionalLibraries.kt`) for a required
`uses-library` such as `micloud-sdk`, and app-specific answers in the extension's `Fallbacks.java` (never overwritten
by gen.py). Always run the ART verifier sweep afterwards: 0 rejected classes.

Apps that bundle miuix and read Xiaomi's region properties (Gallery: `ro.product.mod_device`, `ro.miui.region`,
`ro.miui.build.region`) behave like China's build off HyperOS -- miuix's `IS_INTERNATIONAL_BUILD` is
`ro.product.mod_device` ending in `_global` -- so a Global APK asks for China's consent and keeps features off. For
those, `redirectMiuiProperties("Lorg/ungoogled/xiaomi<app>/MiuiProps;")` (`patches/.../xiaomi/MiuiProperties.kt`)
sends `android.os.SystemProperties` and MiCloud's `miui.cloud.os.SystemProperties` reads to the runtime `MiuiProps`,
which answers those three as a Global build where the phone has no value.
Apps (and SDKs) that look the class up by reflection (`Class.forName("android.os.SystemProperties")`, the Gallery
Editor) also need `redirectReflectedMiuiProperties(...)` (`patches/.../xiaomi/ReflectedProperties.kt`): the looked-up
name becomes MiuiProps, which has the same static getters.

Other vendors reuse this generator with their own answers: `--known <their known.py>` (its `HEADER`, `ONLY`,
`RUNTIME_DIR`, and opt-in features such as `SDK_BASES` for stand-ins that extend Android's own classes, or `FACTORIES`
for a vendor's own factory method names). See `../honor-standins/`. Without `--known` the output is exactly as before.
Members named like a Java keyword (Kotlin's `DefValueUtilKt.default`) cannot be written in Java source and are skipped
and listed.

Things to know:
- miuix probes the platform with `Class.forName("miui.util.HapticFeedbackUtil")` and reflection; with a stand-in
  present the class is found, so every stand-in must keep answering "not supported" (members it lacks throw
  NoSuchMethodException, which miuix catches).
- `known.REFLECTED` lists fields Xiaomi's libraries only read by reflection (miuix's
  `miui.os.Build.IS_MIUI_LITE_VERSION`, looked up on every layout pass and cached only when found); gen.py adds them to
  a stand-in it writes anyway.
- `--only` (default: `miui/`, `com/miui/`, `com/xiaomi/`, `Miui*` classes under `android/`, HyperOS's `android/os/spc/`)
  limits generation to the Xiaomi framework; other missing classes (unused optional libraries such as ad SDKs,
  `java.awt`) are left alone.
