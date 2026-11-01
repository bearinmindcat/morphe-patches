extension {
    name = "extensions/nothingrecorder.mpe"
}

android {
    namespace = "org.ungoogled.nothingrecorder"

    defaultConfig {
        // Nothing Recorder 4.0.0 requires Android 14 (API 34).
        minSdk = 34
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
