extension {
    name = "extensions/xiaomicalendar.mpe"
}

android {
    namespace = "org.ungoogled.xiaomicalendar"

    defaultConfig {
        // Xiaomi Calendar 18.0 itself requires Android 8 (API 26).
        minSdk = 26
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
