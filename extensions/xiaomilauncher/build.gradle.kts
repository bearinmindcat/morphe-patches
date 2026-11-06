extension {
    name = "extensions/xiaomilauncher.mpe"
}

android {
    namespace = "org.ungoogled.xiaomilauncher"

    defaultConfig {
        // The HyperOS launcher 7.50 requires Android 14 (API 34).
        minSdk = 34
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
