extension {
    name = "extensions/photos.mpe"
}

android {
    namespace = "org.ungoogled.photos"

    defaultConfig {
        // Google Photos 8.1 itself requires Android 12L (API 32).
        minSdk = 32
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
