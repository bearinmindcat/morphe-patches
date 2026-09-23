// Compile-only stand-ins for the few Cronet API classes (org.chromium.net) that
// the extension calls. Maps ships the real ones; these only let the extension
// compile and are never packaged into it.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
