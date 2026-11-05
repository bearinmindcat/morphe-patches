extension {
    name = "extensions/nothinglauncher.mpe"
}

android {
    namespace = "org.ungoogled.nothinglauncher"

    defaultConfig {
        // Nothing Launcher 4.0.20 requires Android 16 (API 36).
        minSdk = 36
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
