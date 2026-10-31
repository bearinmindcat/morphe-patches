extension {
    name = "extensions/samsungcalendar.mpe"
}

android {
    namespace = "org.ungoogled.samsungcalendar"

    defaultConfig {
        // Samsung Calendar 12.8 itself requires Android 15 (API 35).
        minSdk = 35
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
