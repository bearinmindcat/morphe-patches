extension {
    name = "extensions/motolauncher.mpe"
}

android {
    namespace = "org.ungoogled.motolauncher"

    defaultConfig {
        // Moto App Launcher 13 runs on Android 13 (API 33), 15 on Android 15. Not higher: the dexer drops version checks
        // below minSdk.
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
