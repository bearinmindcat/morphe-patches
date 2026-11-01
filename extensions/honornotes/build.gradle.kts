extension {
    name = "extensions/honornotes.mpe"
}

android {
    namespace = "org.ungoogled.honornotes"

    defaultConfig {
        // Honor Notes 18.0 itself requires Android 12 (API 31).
        minSdk = 31
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
