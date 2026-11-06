extension {
    name = "extensions/samsungonehand.mpe"
}

android {
    namespace = "org.ungoogled.samsungonehand"

    defaultConfig {
        // One Hand Operation + 9.3 itself requires Android 14 (API 34).
        minSdk = 34
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
