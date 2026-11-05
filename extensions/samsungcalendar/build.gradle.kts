extension {
    name = "extensions/samsungcalendar.mpe"
}

android {
    namespace = "org.ungoogled.samsungcalendar"

    defaultConfig {
        // Samsung Calendar 12.8 requires Android 15 (API 35), 12.5 Android 13 (API 33).
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
