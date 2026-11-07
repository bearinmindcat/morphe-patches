extension {
    name = "extensions/samsunglauncher.mpe"
}

android {
    namespace = "org.ungoogled.samsunglauncher"

    defaultConfig {
        // One UI Home 16.0 runs on Android 13 (API 33), 18.0 on Android 15. Not higher: the dexer drops version checks
        // below minSdk.
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
