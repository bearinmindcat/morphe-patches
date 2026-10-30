extension {
    name = "extensions/samsungmusic.mpe"
}

android {
    namespace = "org.ungoogled.samsungmusic"

    defaultConfig {
        // Samsung Music 16.2 itself requires Android 9 (API 28).
        minSdk = 28
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
