extension {
    name = "extensions/xiaomicompass.mpe"
}

android {
    namespace = "org.ungoogled.xiaomicompass"

    defaultConfig {
        // Xiaomi Compass 17.1 itself requires Android 8 (API 26).
        minSdk = 26
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
