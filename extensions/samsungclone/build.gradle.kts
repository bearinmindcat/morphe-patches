extension {
    name = "extensions/samsungclone.mpe"
}

android {
    namespace = "org.ungoogled.samsungclone"

    defaultConfig {
        // Shared by every Samsung port's "Change package name"; the oldest of them runs on Android 13 (API 33).
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
