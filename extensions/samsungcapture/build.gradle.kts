extension {
    name = "extensions/samsungcapture.mpe"
}

android {
    namespace = "org.ungoogled.samsungcapture"

    defaultConfig {
        // Samsung capture 6.2 itself requires Android 15 (API 35).
        minSdk = 35
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
