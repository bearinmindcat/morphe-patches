extension {
    name = "extensions/honorhome.mpe"
}

android {
    namespace = "org.ungoogled.honorhome"

    defaultConfig {
        // Honor Home 14.0 itself requires Android 10 (API 29).
        minSdk = 29
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
