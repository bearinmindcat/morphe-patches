extension {
    name = "extensions/samsungscreenrecorder.mpe"
}

android {
    namespace = "org.ungoogled.samsungscreenrecorder"

    defaultConfig {
        // Samsung Screen Recorder 4.0 itself runs on Android 9 (API 28); app audio needs Android 10 and is checked.
        minSdk = 28
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
