extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "org.ungoogled.extension"

    defaultConfig {
        // The Maps build we patch from requires Android 12L, but people also patch a
        // 26.36.04 build that runs on Android 10 (issue #8: an Android 11 insets call
        // crashed every screen of ours there), so lint has to hold us to API 29.
        minSdk = 29
    }
}

// The extension is plain Java. Without this the Kotlin Gradle plugin's default
// stdlib dependency gets dexed into the extension -- ~1,100 kotlin.* classes
// merged into Maps for nothing.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    // Cronet's proxy API, which Maps bundles; see stub/.
    compileOnly(project(":extensions:extension:stub"))
}
