extension {
    name = "extensions/samsungnotes.mpe"
}

android {
    namespace = "org.ungoogled.samsungnotes"

    defaultConfig {
        // Samsung Notes 4.4 itself requires Android 10 (API 29).
        minSdk = 29
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
