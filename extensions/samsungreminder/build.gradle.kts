extension {
    name = "extensions/samsungreminder.mpe"
}

android {
    namespace = "org.ungoogled.samsungreminder"

    defaultConfig {
        // Samsung Reminder 12.8 itself requires Android 15 (API 35).
        minSdk = 35
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
