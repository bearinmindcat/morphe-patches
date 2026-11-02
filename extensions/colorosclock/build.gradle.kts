extension {
    name = "extensions/colorosclock.mpe"
}

android {
    namespace = "org.ungoogled.colorosclock"

    defaultConfig {
        // OnePlus Clock 16.19 itself requires Android 9 (API 28).
        minSdk = 28
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
