// Compile-only stand-in for Guava's ListenableFuture, which Gmail ships under its own
// name. It only lets the extension compile and is never packaged into it.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
