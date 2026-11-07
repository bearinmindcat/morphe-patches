extension {
    name = "extensions/coloroslauncher.mpe"
}

android {
    namespace = "org.ungoogled.coloroslauncher"

    defaultConfig {
        // OnePlus System Launcher 15.6 runs on Android 11 (API 30), 16.4 on Android 13. The dexer drops version checks
        // below this, so it is the lower one.
        minSdk = 30
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
