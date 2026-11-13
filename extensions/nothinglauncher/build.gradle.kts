extension {
    name = "extensions/nothinglauncher.mpe"
}

android {
    namespace = "org.ungoogled.nothinglauncher"

    defaultConfig {
        // Nothing Launcher 2.2.8 requires Android 13 (API 33), 3.3.14 Android 15, 4.0.20 Android 16. Not higher: d8
        // drops SDK_INT checks below minSdk.
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
