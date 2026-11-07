extension {
    name = "extensions/colorosdocuments.mpe"
}

android {
    namespace = "org.ungoogled.colorosdocuments"

    defaultConfig {
        // OnePlus Documents 17.1 itself requires Android 15 (API 35).
        minSdk = 35
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
