extension {
    name = "extensions/coloroscalculator.mpe"
}

android {
    namespace = "org.ungoogled.coloroscalculator"

    defaultConfig {
        // OnePlus Calculator 17.0 itself requires Android 11 (API 30).
        minSdk = 30
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
