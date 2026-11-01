extension {
    name = "extensions/honorclock.mpe"
}

android {
    namespace = "org.ungoogled.honorclock"

    defaultConfig {
        // Honor Clock 12.0 itself requires Android 10 (API 29).
        minSdk = 29
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
