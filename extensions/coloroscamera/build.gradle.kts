extension {
    name = "extensions/coloroscamera.mpe"
}

android {
    namespace = "org.ungoogled.coloroscamera"

    defaultConfig {
        // The oldest camera line checked (5.020.905) requires Android 11 (API 30).
        minSdk = 30
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
