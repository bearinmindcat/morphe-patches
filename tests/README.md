# Extension regression tests

Run from the repository root with Java 21 or newer:

```sh
./gradlew -p tests test
```

This isolated Gradle project compiles the actual extension Java sources and the
existing Cronet compile-time stubs, then runs JUnit 4 tests with Robolectric on
Android API 35. It needs neither an APK nor GitHub Packages credentials. Test
dependencies are not added to the production patch bundle.

The tests exercise extension behavior, not a patched Google Maps APK. In
particular, the Cronet stubs cannot establish successful networking with the
real Cronet version bundled into Maps; device testing remains separate.
