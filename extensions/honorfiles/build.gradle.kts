extension {
    name = "extensions/honorfiles.mpe"
}

android {
    namespace = "org.ungoogled.honorfiles"

    defaultConfig {
        // Honor Files 10.0 itself requires Android 11 (API 30).
        minSdk = 30
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
