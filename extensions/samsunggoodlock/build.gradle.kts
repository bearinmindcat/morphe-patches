extension {
    name = "extensions/samsunggoodlock.mpe"
}

android {
    namespace = "org.ungoogled.samsunggoodlock"

    defaultConfig {
        // Good Lock 3.0 itself requires Android 12 (API 31).
        minSdk = 31
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
