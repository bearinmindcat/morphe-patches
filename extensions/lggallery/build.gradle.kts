extension {
    name = "extensions/lggallery.mpe"
}

android {
    namespace = "org.ungoogled.lggallery"

    defaultConfig {
        // LG Gallery 9.60 itself requires Android 11 (API 30).
        minSdk = 30
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
