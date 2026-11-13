extension {
    name = "extensions/xiaomilauncher.mpe"
}

android {
    namespace = "org.ungoogled.xiaomilauncher"

    defaultConfig {
        // The HyperOS launcher 5.39.40 runs on Android 13 (API 33), 7.50 on Android 14. Not higher: the dexer drops version
        // checks below minSdk.
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
