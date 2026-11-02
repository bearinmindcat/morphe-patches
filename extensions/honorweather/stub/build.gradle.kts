// Compile-only stand-ins for the few classes of Honor Weather itself that the extension calls (its bundled RxJava and
// its supplier entity). The app ships the real ones; these only let the extension compile and are never packaged.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
