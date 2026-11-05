extension {
    name = "extensions/onepluslauncher.mpe"
}

android {
    namespace = "org.ungoogled.onepluslauncher"

    defaultConfig {
        // OnePlus Launcher 5.2.66 (OxygenOS 11) requires Android 10 (API 29).
        minSdk = 29
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
