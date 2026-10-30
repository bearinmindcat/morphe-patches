extension {
    name = "extensions/samsungmyfiles.mpe"
}

android {
    namespace = "org.ungoogled.samsungmyfiles"

    defaultConfig {
        // My Files 15.2 itself requires Android 14 (API 34).
        minSdk = 34
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
