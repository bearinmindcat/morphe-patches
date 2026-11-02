extension {
    name = "extensions/colorosgallery.mpe"
}

android {
    namespace = "org.ungoogled.colorosgallery"

    defaultConfig {
        // OnePlus Photos 16.45 itself requires Android 13 (API 33).
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
