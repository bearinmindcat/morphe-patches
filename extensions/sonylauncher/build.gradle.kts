extension {
    name = "extensions/sonylauncher.mpe"
}

android {
    namespace = "org.ungoogled.sonylauncher"

    defaultConfig {
        // Xperia Home 15 requires Android 15 (API 35).
        minSdk = 35
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
