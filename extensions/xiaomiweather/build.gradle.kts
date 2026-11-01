extension {
    name = "extensions/xiaomiweather.mpe"
}

android {
    namespace = "org.ungoogled.xiaomiweather"

    defaultConfig {
        // Xiaomi Weather G-17.0 itself requires Android 7 (API 24).
        minSdk = 24
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
