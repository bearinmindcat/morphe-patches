extension {
    name = "extensions/colorosmyfiles.mpe"
}

android {
    namespace = "org.ungoogled.colorosmyfiles"

    defaultConfig {
        // OnePlus My Files 16.12 itself requires Android 10 (API 29).
        minSdk = 29
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
